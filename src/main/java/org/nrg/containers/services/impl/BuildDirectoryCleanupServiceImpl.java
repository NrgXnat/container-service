package org.nrg.containers.services.impl;

import lombok.extern.slf4j.Slf4j;
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
    static final int LOOKBACK_DAYS = 365;

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
    }

    @Override
    @Nonnull
    public String cleanup() throws BuildDirectoryCleanupException {
        final Counters counters = new Counters();

        final DockerServer server = dockerServerService.retrieveServer();
        if (server == null || !server.buildDirCleanupEnabled()) {
            return "Build directory cleanup is disabled";
        }

        final Path buildRoot = resolveBuildRoot();

        // One listing replaces a filesystem check per candidate: rows linger in the window for up to a year after
        // their directory is deleted, so per-path checks would re-examine a year of cleaned containers every run.
        final Set<String> survivingDirNames = listBuildDirNames(buildRoot);
        if (survivingDirNames.isEmpty()) {
            log.debug("No Container Service build directories present under {}; nothing to clean up.", buildRoot);
            return summarize(counters);
        }

        final long   runStart      = System.currentTimeMillis();
        final int    minRetainDays = Math.min(server.buildDirRetainDaysCompleted(),
                Math.min(server.buildDirRetainDaysFailed(), server.buildDirRetainDaysKilled()));
        final Date   windowStart   = new Date(runStart - TimeUnit.DAYS.toMillis(LOOKBACK_DAYS));
        final Date   windowEnd     = new Date(runStart - TimeUnit.DAYS.toMillis(minRetainDays));
        final String pathPrefix    = buildRoot + File.separator + "%";

        final List<ContainerBuildDirRow> candidates =
                containerEntityService.retrieveBuildDirCandidates(windowStart, windowEnd, pathPrefix);

        // Only groups whose directories are still on disk are worth expanding.
        final Set<Long> candidateRootIds = new LinkedHashSet<>();
        for (final ContainerBuildDirRow row : candidates) {
            final String dirName = buildDirNameOf(row.getBuildDirPath(), buildRoot);
            if (dirName != null && survivingDirNames.contains(dirName)) {
                candidateRootIds.add(row.getRootId());
            }
        }

        if (candidateRootIds.isEmpty()) {
            log.debug("No launch groups have build directories eligible for examination.");
            return summarize(counters);
        }

        final Set<Path> alreadyHandled = new LinkedHashSet<>();
        final List<Long> rootIdList = new ArrayList<>(candidateRootIds);
        for (int offset = 0; offset < rootIdList.size(); offset += ID_CHUNK_SIZE) {
            final List<Long> chunk = rootIdList.subList(offset,
                    Math.min(offset + ID_CHUNK_SIZE, rootIdList.size()));
            processChunk(chunk, pathPrefix, buildRoot, runStart, server, alreadyHandled, counters);
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
                              final Counters counters) {

        // This query commits before any deletion below, so no transaction spans filesystem work.
        final Map<Long, List<ContainerBuildDirRow>> launchGroups =
                containerEntityService.retrieveBuildDirRowsForLaunchGroups(rootIds, pathPrefix).stream()
                        .collect(Collectors.groupingBy(ContainerBuildDirRow::getRootId));

        for (final Map.Entry<Long, List<ContainerBuildDirRow>> entry : launchGroups.entrySet()) {
            counters.launchGroupsExamined++;
            final List<ContainerBuildDirRow> members = entry.getValue();

            if (!launchGroupIsTerminal(members)) {
                counters.launchGroupsDeferred++;
                log.debug("Launch group {} has a member that is not finalized; deferring.", entry.getKey());
                continue;
            }

            final Long effectiveTime = newestStatusTime(members);
            if (effectiveTime == null) {
                counters.launchGroupsDeferred++;
                log.warn("Launch group {} has no usable statusTime; deferring. Its build directories will not be " +
                        "reclaimed until this is corrected.", entry.getKey());
                continue;
            }

            // No minimum age floor. Terminal status is set only after finalization uploads outputs, container logs
            // live under the archive, and post-terminal work touches no files, so 0 days is safe. The launch group
            // rule above, not a time delay, protects a running main container's mounted scratch space.
            final long requiredAge = TimeUnit.DAYS.toMillis(retainDaysFor(members, server));
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
     * Every member must be terminal. A setup child goes terminal while its parent may still be running with the
     * child's output mounted; a wrap-up child runs while its parent is still finalizing.
     */
    private static boolean launchGroupIsTerminal(final Collection<ContainerBuildDirRow> members) {
        return members.stream()
                .map(ContainerBuildDirRow::getStatus)
                .distinct()
                .allMatch(ContainerUtils::statusIsTerminal);
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
            if (status.startsWith(ContainerUtils.TerminalState.KILLED.value)) {
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
                log.warn("Partially removed build directory {}: {} entries could not be deleted", target,
                        result.getFailures());
            }
        } catch (IOException e) {
            // A per-directory problem must not abandon the rest of the run.
            counters.dirsPartiallyDeleted++;
            log.warn("Could not remove build directory {}", target, e);
        }
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
    private Set<String> listBuildDirNames(final Path buildRoot) throws BuildDirectoryCleanupException {
        final Set<String> names = new HashSet<>();
        // A directory stream rather than a listing: entries are not stat'd individually and a very large root is
        // never materialized into an array.
        try (final DirectoryStream<Path> stream = Files.newDirectoryStream(buildRoot)) {
            for (final Path entry : stream) {
                final Path fileName = entry.getFileName();
                if (fileName != null && BuildDirectoryDeleter.isBuildDirName(fileName.toString())) {
                    names.add(fileName.toString());
                }
            }
        } catch (IOException e) {
            throw new BuildDirectoryCleanupException("Could not list the build path " + buildRoot, e);
        }
        log.debug("Found {} Container Service build directories under {}", names.size(), buildRoot);
        return names;
    }

    /** The UUID directory name a persisted mount path falls under, or null if it is not under the build root. */
    @Nullable
    private static String buildDirNameOf(final @Nullable String xnatHostPath, final Path buildRoot) {
        if (StringUtils.isBlank(xnatHostPath)) {
            return null;
        }
        final Path path;
        try {
            path = Paths.get(xnatHostPath).normalize();
        } catch (InvalidPathException e) {
            return null;
        }
        if (!path.startsWith(buildRoot) || path.equals(buildRoot)) {
            return null;
        }
        final String first = buildRoot.relativize(path).getName(0).toString();
        return BuildDirectoryDeleter.isBuildDirName(first) ? first : null;
    }
}
