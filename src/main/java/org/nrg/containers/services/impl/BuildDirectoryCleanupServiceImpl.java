package org.nrg.containers.services.impl;

import lombok.extern.slf4j.Slf4j;
import com.google.common.collect.Lists;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.exceptions.BuildDirectoryCleanupException;
import org.nrg.containers.model.container.ContainerBuildDirRow;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.services.BuildDirectoryCleanupService;
import org.nrg.containers.services.ContainerEntityService;
import org.nrg.containers.services.DockerServerService;
import org.nrg.containers.utils.BuildDirectoryDeleter;
import org.nrg.containers.utils.ContainerUtils;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * Removes build directories of finalized containers once they have aged past their retention threshold.
 *
 * The unit of work is a "launch group": one main container plus its setup and wrap-up containers. They share build
 * directories, and a setup container goes terminal long before the main container still reading its output, so a
 * group is only eligible once every member is terminal, aged from the newest statusTime in the group.
 *
 * No class-level transaction: each query commits before that chunk's filesystem work begins.
 */
@Slf4j
@Service
public class BuildDirectoryCleanupServiceImpl implements BuildDirectoryCleanupService {

    /** Only containers finalized within the past year are considered, per the feature specification. */
    public static final int LOOKBACK_DAYS = 365;

    /**
     * A container goes terminal when the backend reports its exit, *before* finalization reads its outputs:
     * ContainerServiceImpl records the exit, then queueFinalize only posts a JMS request and leaves the status
     * alone. So at a retention of zero a just-failed container's build directory is already eligible while its
     * outputs are still unread.
     *
     * An hour covers the queue wait at normal load and costs nothing at the default settings, which are days. A
     * mitigation, not a guarantee: a throttled finalizing queue can exceed it.
     */
    static final long MINIMUM_AGE_MILLIS = TimeUnit.HOURS.toMillis(1);

    /** What ContainerEntity.mapStatus produces for a container row that has been created but never launched. */
    private static final String CREATED_STATUS = "Created";

    /**
     * Root ids per family query. Both branches of its id disjunction bind the full list, so a chunk costs 2N+1
     * bind parameters, putting the real ceiling near 32,700 ids rather than Postgres's 65,535.
     */
    static final int ID_CHUNK_SIZE = 500;

    private final ContainerEntityService containerEntityService;
    private final DockerServerService    dockerServerService;
    private final SiteConfigPreferences  siteConfigPreferences;

    @Autowired
    public BuildDirectoryCleanupServiceImpl(final ContainerEntityService containerEntityService,
                                            final DockerServerService dockerServerService,
                                            final SiteConfigPreferences siteConfigPreferences) {
        this.containerEntityService = containerEntityService;
        this.dockerServerService    = dockerServerService;
        this.siteConfigPreferences  = siteConfigPreferences;
    }

    @Override
    public boolean isEnabled() {
        final DockerServer server = dockerServerService.retrieveServer();
        return server != null && server.buildDirCleanupEnabled();
    }

    /** Counters for one run. A private holder, not a model class: these only feed the summary line. */
    private static final class Counters {
        private int  launchGroupsExamined;
        private int  launchGroupsDeferred;
        private int  launchGroupsTooYoung;
        private int  dirsDeleted;
        private int  dirsPartiallyDeleted;
        private long bytesFreed;
        private int  permissionFailures;
        private boolean stoppedEarly;
    }

    @Override
    @Nonnull
    public String cleanup(final BooleanSupplier mayStartMoreWork) throws BuildDirectoryCleanupException {
        final Counters counters = new Counters();

        final DockerServer server = dockerServerService.retrieveServer();
        if (server == null || !server.buildDirCleanupEnabled()) {
            return "Build directory cleanup is disabled";
        }

        final Path buildRoot = resolveBuildRoot();

        // One listing replaces a filesystem check per candidate: rows linger in the window for up to a year after
        // their directory is deleted, so per-path checks would re-examine a year of cleaned containers every run.
        final Set<Path> survivingDirs = listBuildDirs(buildRoot);
        if (survivingDirs.isEmpty()) {
            log.debug("No Container Service build directories present under {}; nothing to clean up.", buildRoot);
            return summarize(counters);
        }

        final long   runStart      = System.currentTimeMillis();
        final int    minRetainDays = Math.min(server.buildDirRetainDaysCompleted(),
                Math.min(server.buildDirRetainDaysFailed(), server.buildDirRetainDaysKilled()));
        final Date   windowStart   = new Date(runStart - TimeUnit.DAYS.toMillis(LOOKBACK_DAYS));
        final Date   windowEnd     = new Date(runStart - TimeUnit.DAYS.toMillis(minRetainDays));
        final String pathPrefix    = buildPathPrefix(buildRoot, File.separator);

        final List<ContainerBuildDirRow> candidates =
                containerEntityService.retrieveBuildDirCandidates(windowStart, windowEnd, pathPrefix);

        // Only groups whose directories are still on disk are worth expanding.
        final Set<Long> candidateRootIds = new LinkedHashSet<>();
        for (final ContainerBuildDirRow row : candidates) {
            final Path dir = BuildDirectoryDeleter.resolveBuildDirToDelete(row.getBuildDirPath(), buildRoot);
            if (dir != null && survivingDirs.contains(dir)) {
                candidateRootIds.add(row.getRootId());
            }
        }

        if (candidateRootIds.isEmpty()) {
            log.debug("No launch groups have build directories eligible for examination.");
            return summarize(counters);
        }

        final Set<Path> alreadyHandled = new LinkedHashSet<>();
        final List<Long> rootIdList = new ArrayList<>(candidateRootIds);
        for (final List<Long> chunk : Lists.partition(rootIdList, ID_CHUNK_SIZE)) {
            if (counters.stoppedEarly) {
                break;      // set by processChunk, before issuing a query for work we would not start
            }
            processChunk(chunk, pathPrefix, buildRoot, runStart, server, alreadyHandled, counters,
                    mayStartMoreWork);
        }

        if (counters.permissionFailures > 0) {
            log.warn("{} build directories could not be fully removed due to filesystem permissions. The container " +
                            "processes likely ran as root; consider setting the Container User in the container " +
                            "server configuration.",
                    counters.permissionFailures);
        }

        return summarize(counters);
    }

    /** One line for the workflow details field and a single INFO log entry. */
    private static String summarize(final Counters counters) {
        final List<String> parts = new ArrayList<>();
        parts.add("Examined " + counters.launchGroupsExamined + " launch groups");
        parts.add("deleted " + counters.dirsDeleted + " build dirs ("
                + FileUtils.byteCountToDisplaySize(counters.bytesFreed) + ")");
        addIfPositive(parts, counters.dirsPartiallyDeleted, "partial");
        addIfPositive(parts, counters.launchGroupsDeferred, "deferred");
        addIfPositive(parts, counters.launchGroupsTooYoung, "too young");
        if (counters.stoppedEarly) {
            parts.add("stopped at the next scheduled time, work remains");
        }
        return String.join("; ", parts);
    }

    private static void addIfPositive(final List<String> parts, final int count, final String label) {
        if (count > 0) {
            parts.add(count + " " + label);
        }
    }

    private void processChunk(final Collection<Long> rootIds,
                              final String pathPrefix,
                              final Path buildRoot,
                              final long runStart,
                              final DockerServer server,
                              final Set<Path> alreadyHandled,
                              final Counters counters,
                              final BooleanSupplier mayStartMoreWork) {

        final Map<Long, List<ContainerBuildDirRow>> launchGroups =
                containerEntityService.retrieveBuildDirRowsForLaunchGroups(rootIds, pathPrefix).stream()
                        .collect(Collectors.groupingBy(ContainerBuildDirRow::getRootId));

        for (final Map.Entry<Long, List<ContainerBuildDirRow>> entry : launchGroups.entrySet()) {
            // Checked before a group, never inside one: whole groups stay intact and overlap with the next run
            // is bounded to one group.
            if (!mayStartMoreWork.getAsBoolean()) {
                counters.stoppedEarly = true;
                break;
            }
            counters.launchGroupsExamined++;
            final List<ContainerBuildDirRow> members = entry.getValue();

            final List<ContainerBuildDirRow> blocking = membersThatBlockCleanup(members, entry.getKey());

            if (!launchGroupIsTerminal(blocking)) {
                counters.launchGroupsDeferred++;
                log.debug("Launch group {} has a member that is not finalized; deferring.", entry.getKey());
                continue;
            }

            final Long effectiveTime = newestStatusTime(blocking);
            if (effectiveTime == null) {
                counters.launchGroupsDeferred++;
                log.warn("Launch group {} has no usable statusTime; deferring. Its build directories will not be " +
                        "reclaimed until this is corrected.", entry.getKey());
                continue;
            }

            final long requiredAge = Math.max(MINIMUM_AGE_MILLIS,
                    TimeUnit.DAYS.toMillis(retainDaysFor(blocking, server)));
            if (runStart - effectiveTime < requiredAge) {
                counters.launchGroupsTooYoung++;
                continue;
            }

            for (final Path target : resolveTargets(members, buildRoot)) {
                if (!alreadyHandled.add(target)) {
                    continue;   // the same directory is legitimately mounted by more than one container
                }
                deleteOne(target, counters);
            }
        }
    }

    /**
     * Drops children that were created but never launched, once the root itself has finished.
     *
     * Wrap-up containers are persisted at parent-launch time with status "Created" and no statusTime, and are only
     * launched or failed from inside the parent's finalize(). Finalization pauses and returns while they run, so
     * the parent cannot reach a terminal status until they are done: a terminal root therefore means no wrap-up of
     * that group can still be pending, and a child left at "Created" was orphaned rather than being about to run.
     * Without this, one orphaned launch — a restart mid-run, a lost backend event — would block its group's build
     * directories forever, and the deferral warning would ask an operator to correct something they cannot.
     *
     * Scoped deliberately: only non-root rows, only the exact "Created" status, and only with no statusTime.
     */
    private static List<ContainerBuildDirRow> membersThatBlockCleanup(final List<ContainerBuildDirRow> members,
                                                                    final Long rootId) {
        final boolean rootIsFinished = members.stream()
                .filter(row -> rootId.equals(row.getContainerId()))
                .allMatch(row -> ContainerUtils.statusIsTerminal(row.getStatus()));
        if (!rootIsFinished) {
            return members;
        }
        return members.stream()
                .filter(row -> !isNeverLaunchedChild(row, rootId))
                .collect(Collectors.toList());
    }

    private static boolean isNeverLaunchedChild(final ContainerBuildDirRow row, final Long rootId) {
        return !rootId.equals(row.getContainerId())
                && row.getStatusTime() == null
                && CREATED_STATUS.equals(row.getStatus());
    }

    /**
     * Every member must be terminal. A setup child goes terminal while its parent may still be running with the
     * child's output mounted; a wrap-up child runs while its parent is still finalizing.
     */
    private static boolean launchGroupIsTerminal(final Collection<ContainerBuildDirRow> members) {
        return members.stream()
                .allMatch(row -> ContainerUtils.statusIsTerminal(row.getStatus()));
    }

    @Nullable
    private static Long newestStatusTime(final Collection<ContainerBuildDirRow> members) {
        Long newest = null;
        for (final ContainerBuildDirRow row : members) {
            if (row.getStatusTime() == null) {
                return null;    // an unknown time could be arbitrarily recent, so treat the launchGroup as unsafe
            }
            final long time = row.getStatusTime().getTime();
            if (newest == null || time > newest) {
                newest = time;
            }
        }
        return newest;
    }

    /** The most conservative threshold in the group wins, so a Complete parent with a Failed child uses Failed. */
    private static int retainDaysFor(final Collection<ContainerBuildDirRow> members, final DockerServer server) {
        int retainDays = 0;
        for (final ContainerBuildDirRow row : members) {
            final String status = StringUtils.defaultString(row.getStatus());
            final int forThisMember;
            // ContainerEntity.mapStatus turns a kill into "Failed (Killed)" (and swarm wraps that again), so the
            // marker is never the leading token. Tested before the Failed branch, which would otherwise claim it.
            if (status.contains(ContainerUtils.TerminalState.KILLED.value)) {
                forThisMember = server.buildDirRetainDaysKilled();
            } else if (status.startsWith(ContainerUtils.TerminalState.FAILED.value)) {
                forThisMember = server.buildDirRetainDaysFailed();
            } else {
                forThisMember = server.buildDirRetainDaysCompleted();
            }
            retainDays = Math.max(retainDays, forThisMember);
        }
        return retainDays;
    }

    private static Set<Path> resolveTargets(final Collection<ContainerBuildDirRow> members,
                                            final Path buildRoot) {
        final Set<Path> targets = new LinkedHashSet<>();
        for (final ContainerBuildDirRow row : members) {
            if (!row.hasBuildDirPath()) {
                continue;
            }
            final Path target = BuildDirectoryDeleter.resolveBuildDirToDelete(
                    row.getBuildDirPath(), buildRoot);
            if (target == null) {
                log.warn("Refusing to delete mount path \"{}\" for container {}: not a Container Service build " +
                        "directory under {}", row.getBuildDirPath(), row.getContainerId(), buildRoot);
                continue;
            }
            targets.add(target);
        }
        return targets;
    }

    private static void deleteOne(final Path target, final Counters counters) {
        try {
            final BuildDirectoryDeleter.DeleteResult result = BuildDirectoryDeleter.deleteRecursively(target);
            if (result.isAlreadyGone()) {
                // Only reachable if something removed the directory between the listing and now.
                log.trace("Build directory {} was already gone.", target);
                return;
            }
            counters.bytesFreed += result.getBytesFreed();
            if (result.isComplete()) {
                counters.dirsDeleted++;
                log.debug("Removed build directory {} ({} files, {})", target, result.getFilesDeleted(),
                        FileUtils.byteCountToDisplaySize(result.getBytesFreed()));
            } else {
                counters.dirsPartiallyDeleted++;
                if (result.getAccessDenied() > 0) {
                    counters.permissionFailures++;
                }
                log.warn("Partially removed build directory {}: {} entries could not be deleted, {} were already gone",
                        target, result.getFailures(), result.getAlreadyDeleted());
            }
        } catch (IOException e) {
            // A per-directory problem must not abandon the rest of the run.
            counters.dirsPartiallyDeleted++;
            log.warn("Could not remove build directory {}", target, e);
        }
    }

    /**
     * The SQL LIKE pattern matching everything under the build root.
     *
     * PostgreSQL treats backslash as LIKE's escape character, so on Windows — where the stored paths use
     * backslashes, because CommandResolutionServiceImpl builds them with FilenameUtils.concat — an unescaped
     * prefix matches nothing at all and the feature becomes a silent no-op. `_` and `%` in a build path would
     * likewise be read as wildcards. Escaping backslashes first is required: doing it later would double-escape
     * the backslashes introduced by the other two replacements.
     *
     * The separator is a parameter so the Windows case can be tested from any platform.
     */
    public static String buildPathPrefix(final Path buildRoot, final String separator) {
        return escapeLikeLiteral(buildRoot.toString()) + escapeLikeLiteral(separator) + "%";
    }

    private static String escapeLikeLiteral(final String literal) {
        return literal.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @Nonnull
    private Path resolveBuildRoot() throws BuildDirectoryCleanupException {
        final String configured = siteConfigPreferences.getBuildPath();
        if (StringUtils.isBlank(configured)) {
            throw new BuildDirectoryCleanupException("The site build path is not configured");
        }
        final Path buildRoot;
        try {
            buildRoot = Paths.get(configured).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new BuildDirectoryCleanupException("The site build path is not a usable path: " + configured, e);
        }
        if (buildRoot.getNameCount() == 0) {
            throw new BuildDirectoryCleanupException("Refusing to operate on the filesystem root");
        }
        // Fail loudly rather than treating an unmounted volume as "everything is already clean".
        if (!Files.isDirectory(buildRoot)) {
            throw new BuildDirectoryCleanupException(
                    "Build path " + buildRoot + " is not a readable directory. Is the volume mounted?");
        }
        return buildRoot;
    }

    /**
     * UUID-named immediate children of the build root. The root is shared with XNAT core, which creates
     * project-named directories under it, so anything that is not a bare UUID is ignored.
     */
    @Nonnull
    private Set<Path> listBuildDirs(final Path buildRoot) throws BuildDirectoryCleanupException {
        final Set<Path> dirs = new HashSet<>();
        // A directory stream rather than a listing: entries are not stat'd individually and a very large root is
        // never materialized into an array.
        try (final DirectoryStream<Path> stream = Files.newDirectoryStream(buildRoot)) {
            for (final Path entry : stream) {
                final Path fileName = entry.getFileName();
                if (fileName != null && BuildDirectoryDeleter.isBuildDirName(fileName.toString())) {
                    dirs.add(entry);
                }
            }
        } catch (IOException e) {
            throw new BuildDirectoryCleanupException("Could not list the build path " + buildRoot, e);
        }
        log.debug("Found {} Container Service build directories under {}", dirs.size(), buildRoot);
        return dirs;
    }

}
