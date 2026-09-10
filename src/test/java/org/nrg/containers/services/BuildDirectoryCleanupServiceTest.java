package org.nrg.containers.services;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentMatchers;
import org.nrg.containers.exceptions.BuildDirectoryCleanupException;
import org.nrg.containers.model.container.ContainerBuildDirRow;
import org.nrg.containers.model.server.docker.DockerServerBase;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.services.impl.BuildDirectoryCleanupServiceImpl;
import org.nrg.xdat.preferences.SiteConfigPreferences;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Eligibility rules for build directory cleanup, from the angle of what must survive. Mocks the DB layer and uses
 * real temp directories, so it runs without Docker or Postgres.
 *
 * The launch group rule is the core of this class: a build directory is shared between a setup container and the
 * main container it feeds, so the unit of cleanup is the launch group and not the container row.
 */
public class BuildDirectoryCleanupServiceTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private ContainerEntityService entityService;
    private DockerServerService    dockerServerService;
    private SiteConfigPreferences  siteConfigPreferences;
    private BuildDirectoryCleanupServiceImpl service;

    private Path buildRoot;
    private long nextContainerId = 1L;

    @Before
    public void setUp() throws IOException {
        buildRoot = tmp.newFolder("build").toPath();

        entityService         = mock(ContainerEntityService.class);
        dockerServerService   = mock(DockerServerService.class);
        siteConfigPreferences = mock(SiteConfigPreferences.class);

        when(siteConfigPreferences.getBuildPath()).thenReturn(buildRoot.toString());
        server(true, 7, 14, 1);

        service = new BuildDirectoryCleanupServiceImpl(entityService, dockerServerService, siteConfigPreferences);
    }

    // ---------- fixture helpers ----------

    private void server(final boolean enabled, final int completed, final int failed, final int killed) {
        when(dockerServerService.retrieveServer()).thenReturn(DockerServer.builder()
                .name("test").host("unix:///var/run/docker.sock")
                .buildDirCleanupEnabled(enabled)
                .buildDirRetainDaysCompleted(completed)
                .buildDirRetainDaysFailed(failed)
                .buildDirRetainDaysKilled(killed)
                .build());
    }

    /** Create a populated build directory on disk and return its path string. */
    private String makeDirOnDisk() throws IOException {
        final Path dir = Files.createDirectory(buildRoot.resolve(UUID.randomUUID().toString()));
        Files.write(dir.resolve("payload.txt"), new byte[]{1, 2, 3});
        return dir.toString();
    }

    private ContainerBuildDirRow row(final long rootId, final String status, final long ageMillis,
                                     final String path) {
        return new ContainerBuildDirRow(nextContainerId++, rootId, status,
                new Date(System.currentTimeMillis() - ageMillis), path);
    }

    private static long days(final int n) {
        return TimeUnit.DAYS.toMillis(n);
    }

    /** Wire the mocked queries: candidates come from the rows that have paths, launchGroups from all rows. */
    private void stubQueries(final List<ContainerBuildDirRow> allRows) {
        final List<ContainerBuildDirRow> withPaths = new ArrayList<>();
        for (final ContainerBuildDirRow r : allRows) {
            if (r.hasBuildDirPath()) {
                withPaths.add(r);
            }
        }
        when(entityService.retrieveBuildDirCandidates(any(Date.class), any(Date.class), anyString()))
                .thenReturn(withPaths);
        when(entityService.retrieveBuildDirRowsForLaunchGroups(ArgumentMatchers.<Collection<Long>>any(), anyString()))
                .thenReturn(allRows);
    }

    private String run() throws BuildDirectoryCleanupException {
        return service.cleanup(() -> true);
    }

    // The service reports through its summary line rather than a model object, so these assert on that line.
    // Counts of zero are asserted as absence, because the summary only mentions a category when it is non-zero.

    private static void assertDeleted(final String summary, final int count) {
        assertDeleted("deleted count", summary, count);
    }

    private static void assertDeleted(final String reason, final String summary, final int count) {
        assertThat(reason, summary, containsString("deleted " + count + " build dirs"));
    }

    private static void assertExamined(final String summary, final int count) {
        assertThat(summary, containsString("Examined " + count + " launch groups"));
    }

    private static void assertDeferred(final String summary, final int count) {
        assertCategory(summary, count, "deferred");
    }

    private static void assertTooYoung(final String summary, final int count) {
        assertCategory(summary, count, "too young");
    }

    private static void assertCategory(final String summary, final int count, final String label) {
        if (count == 0) {
            assertThat("should not mention " + label, summary, not(containsString(label)));
        } else {
            assertThat(summary, containsString(count + " " + label));
        }
    }

    private static boolean exists(final String path) {
        return Files.exists(java.nio.file.Paths.get(path));
    }

    /**
     * These two constants only work together: 364 exists because the lookback is 365, leaving exactly one day in
     * which a group at the maximum retention is both eligible and still visible to the candidate query. They live
     * in different classes, so nothing but this assertion stops an edit to one silently closing that window and
     * leaking those directories permanently.
     */
    @Test
    public void theRetentionCapLeavesAHarvestWindowInsideTheLookback() {
        assertThat("a group at the maximum retention must still be visible to the candidate query",
                DockerServerBase.MAX_BUILD_DIR_RETAIN_DAYS < BuildDirectoryCleanupServiceImpl.LOOKBACK_DAYS,
                is(true));
    }

    /**
     * PostgreSQL reads backslash as LIKE's escape character, so a Windows build root — whose stored paths use
     * backslashes — must be escaped or the pattern matches nothing and the feature silently reclaims nothing.
     * An underscore in the path would otherwise act as a single-character wildcard, broadening the match.
     */
    @Test
    public void theLikePrefixEscapesEverythingPostgresWouldTreatAsAPattern() {
        assertThat("posix, nothing to escape",
                BuildDirectoryCleanupServiceImpl.buildPathPrefix(Paths.get("/data/xnat/build"), "/"),
                is("/data/xnat/build/%"));

        assertThat("an underscore must not become a wildcard",
                BuildDirectoryCleanupServiceImpl.buildPathPrefix(Paths.get("/data/xnat_prod/build"), "/"),
                is("/data/xnat\\_prod/build/%"));

        assertThat("windows separators must survive as literals, including the trailing one",
                BuildDirectoryCleanupServiceImpl.buildPathPrefix(Paths.get("/data/x/build"), "\\")
                        .endsWith("\\\\%"), is(true));
    }

    // ---------- disabled by default ----------

    /** An upgraded site that never opts in must have its build path left completely untouched. */
    @Test
    public void disabledDoesNothingAtAll() throws Exception {
        server(false, 7, 14, 1);
        final String dir = makeDirOnDisk();

        final String summary = run();

        assertThat(summary, is("Build directory cleanup is disabled"));
        assertThat(exists(dir), is(true));
        verify(entityService, never()).retrieveBuildDirCandidates(any(), any(), anyString());
        verify(entityService, never()).retrieveBuildDirRowsForLaunchGroups(any(), anyString());
    }

    // ---------- retention windows ----------

    /**
     * Each terminal status is measured against its own setting, and the suffixed forms that ContainerUtils
     * recognises ("Failed (Setup)") must land in the same bucket as the bare status. Picking the wrong bucket
     * deletes early.
     */
    @Test
    public void eachTerminalStatusUsesItsOwnRetentionWindow() throws Exception {
        final String completedOld = makeDirOnDisk();
        stubQueries(Collections.singletonList(row(1L, "Complete", days(8), completedOld)));
        assertDeleted("8 days is past the 7 day completed window", run(), 1);
        assertThat(exists(completedOld), is(false));

        final String completedYoung = makeDirOnDisk();
        stubQueries(Collections.singletonList(row(2L, "Complete", days(6), completedYoung)));
        final String summary = run();
        assertDeleted(summary, 0);
        assertTooYoung(summary, 1);
        assertThat(exists(completedYoung), is(true));

        final String failedYoung = makeDirOnDisk();
        stubQueries(Collections.singletonList(row(3L, "Failed", days(10), failedYoung)));
        assertDeleted("10 days is inside the 14 day failed window", run(), 0);
        assertThat(exists(failedYoung), is(true));

        final String suffixedFailed = makeDirOnDisk();
        stubQueries(Collections.singletonList(row(4L, "Failed (Setup)", days(10), suffixedFailed)));
        assertDeleted("a suffixed status still uses the failed window", run(), 0);
        assertThat(exists(suffixedFailed), is(true));

        final String killedOld = makeDirOnDisk();
        stubQueries(Collections.singletonList(row(5L, "Killed", days(2), killedOld)));
        assertDeleted("2 days is past the 1 day killed window", run(), 1);
        assertThat(exists(killedOld), is(false));
    }

    /** A Complete parent with a Failed child aged 10 days: the longer failed window governs the whole group. */
    @Test
    public void mixedLaunchGroupTakesTheMostConservativeThreshold() throws Exception {
        final String dir = makeDirOnDisk();
        stubQueries(Arrays.asList(
                row(1L, "Complete", days(10), null),
                row(1L, "Failed (Setup)", days(10), dir)));

        final String summary = run();

        assertDeleted(summary, 0);
        // Distinguishes "too young" from "deferred": both leave 0 deleted, but deferred would mean
        // statusIsTerminal stopped matching a suffixed status, which is a regression rather than the rule working.
        assertTooYoung(summary, 1);
        assertThat(exists(dir), is(true));
    }

    // ---------- the launch group rule ----------

    /**
     * The headline hazard. A setup child is terminal and its retention is zero, but its output directory is still
     * mounted into a parent that has not finished, so nothing may be deleted. Finalizing is included because
     * wrap-up containers run while the parent sits in that non-terminal status.
     */
    @Test
    public void launchGroupDefersWhileAnyMemberIsNotTerminal() throws Exception {
        server(true, 0, 0, 0);

        final String runningParentDir = makeDirOnDisk();
        stubQueries(Arrays.asList(
                row(1L, "Running", days(1), null),                  // parent, no build mount of its own
                row(1L, "Complete", days(1), runningParentDir)));   // setup child holding the directory
        String summary = run();
        assertDeferred(summary, 1);
        assertDeleted(summary, 0);
        assertThat("a running parent's mounted scratch space must survive", exists(runningParentDir), is(true));

        final String finalizingParentDir = makeDirOnDisk();
        stubQueries(Arrays.asList(
                row(2L, "Finalizing", days(1), null),
                row(2L, "Complete", days(1), finalizingParentDir)));
        summary = run();
        assertDeferred(summary, 1);
        assertThat("a finalizing parent may still have a wrap-up container running",
                exists(finalizingParentDir), is(true));
    }

    /**
     * The positive control for the rule above; without it a service that deferred everything would pass every
     * other test here. Zero days means "as soon as the group is terminal and past MINIMUM_AGE_MILLIS".
     */
    @Test
    public void wholeLaunchGroupTerminalIsDeleted() throws Exception {
        server(true, 0, 0, 0);
        final String dir = makeDirOnDisk();
        stubQueries(Arrays.asList(
                row(1L, "Complete", TimeUnit.HOURS.toMillis(3), null),
                row(1L, "Complete", TimeUnit.HOURS.toMillis(2), dir)));

        final String summary = run();

        assertDeleted(summary, 1);
        assertTooYoung(summary, 0);
        assertThat(exists(dir), is(false));
    }

    /**
     * Group age is max(statusTime), not min. Taking the oldest member would age a group out while its parent had
     * only just finished. Here the child finalized 30 days ago and the parent 2 days ago, so the 7 day completed
     * window is not yet satisfied.
     */
    @Test
    public void launchGroupAgeIsTheNewestStatusTimeNotTheOldest() throws Exception {
        final String dir = makeDirOnDisk();
        stubQueries(Arrays.asList(
                row(1L, "Complete", days(2), null),
                row(1L, "Complete", days(30), dir)));

        assertTooYoung(run(), 1);
        assertThat(exists(dir), is(true));
    }

    /** Unknowable age or status defers rather than guessing, in either direction. */
    @Test
    public void launchGroupWithAMissingStatusOrStatusTimeIsDeferred() throws Exception {
        final String noStatusTime = makeDirOnDisk();
        stubQueries(Collections.singletonList(
                new ContainerBuildDirRow(1L, 1L, "Complete", null, noStatusTime)));
        assertDeferred(run(), 1);
        assertThat(exists(noStatusTime), is(true));

        final String noStatus = makeDirOnDisk();
        stubQueries(Collections.singletonList(
                new ContainerBuildDirRow(2L, 2L, null, new Date(0), noStatus)));
        assertDeferred(run(), 1);
        assertThat(exists(noStatus), is(true));
    }

    /**
     * A kill is persisted by ContainerEntity.mapStatus as "Failed (Killed)", never as a bare "Killed", so a
     * leading-token test would silently route killed containers to the failed window and leave the killed
     * retention setting doing nothing at all.
     */
    @Test
    public void aKilledContainerUsesTheKilledWindowDespiteItsFailedPrefix() throws Exception {
        server(true, 7, 14, 1);
        final String dir = makeDirOnDisk();
        stubQueries(Collections.singletonList(row(1L, "Failed (Killed)", days(2), dir)));

        assertDeleted("2 days is past the 1 day killed window, but inside the 14 day failed one", run(), 1);
        assertThat(exists(dir), is(false));
    }

    /** A retention of zero must not mean "delete the moment it dies"; see MINIMUM_AGE_MILLIS. */
    @Test
    public void aFreshlyTerminalGroupIsHeldBackEvenAtZeroRetention() throws Exception {
        server(true, 0, 0, 0);
        final String dir = makeDirOnDisk();
        stubQueries(Collections.singletonList(
                row(1L, "Failed", TimeUnit.MINUTES.toMillis(2), dir)));

        final String summary = run();

        assertDeleted(summary, 0);
        assertTooYoung(summary, 1);
        assertThat("outputs may still be waiting in the finalizing queue", exists(dir), is(true));
    }

    /** A child left at "Created" was orphaned, and must not pin its group's directories for the whole window. */
    @Test
    public void anOrphanedNeverLaunchedChildDoesNotBlockAFinishedGroup() throws Exception {
        final String dir = makeDirOnDisk();
        stubQueries(Arrays.asList(
                new ContainerBuildDirRow(1L, 1L, "Complete", new Date(System.currentTimeMillis() - days(30)), dir),
                new ContainerBuildDirRow(2L, 1L, "Created", null, null)));   // wrap-up that never ran

        assertDeleted(run(), 1);
        assertThat(exists(dir), is(false));
    }

    /** The exemption is narrow: a child that actually started still blocks, however stale it looks. */
    @Test
    public void aChildThatStartedStillBlocksTheGroup() throws Exception {
        final String dir = makeDirOnDisk();
        stubQueries(Arrays.asList(
                new ContainerBuildDirRow(1L, 1L, "Complete", new Date(System.currentTimeMillis() - days(30)), dir),
                new ContainerBuildDirRow(2L, 1L, "Running", new Date(System.currentTimeMillis() - days(30)), null)));

        assertDeferred(run(), 1);
        assertThat(exists(dir), is(true));
    }

    /** Never begin a new group past the deadline; always finish the one already started. */
    @Test
    public void stoppingAtTheDeadlineFinishesTheCurrentGroupAndLeavesTheRest() throws Exception {
        final String first  = makeDirOnDisk();
        final String second = makeDirOnDisk();
        final String third  = makeDirOnDisk();
        stubQueries(Arrays.asList(
                row(1L, "Complete", days(30), first),
                row(2L, "Complete", days(30), second),
                row(3L, "Complete", days(30), third)));

        // Expressed by observation rather than by counting calls, so it does not depend on where the service
        // happens to check.
        final String summary = service.cleanup(() -> exists(first) && exists(second) && exists(third));

        assertDeleted("only the group that was allowed to start", summary, 1);
        assertThat(summary, containsString("stopped at the next scheduled time"));

        int survivors = 0;
        for (final String dir : Arrays.asList(first, second, third)) {
            if (exists(dir)) {
                survivors++;
            }
        }
        assertThat("the groups never started are untouched", survivors, is(2));
    }

    /** With no deadline pressure the same input deletes everything, so the test above is not passing vacuously. */
    @Test
    public void withoutDeadlinePressureEveryGroupIsProcessed() throws Exception {
        final String first  = makeDirOnDisk();
        final String second = makeDirOnDisk();
        stubQueries(Arrays.asList(
                row(1L, "Complete", days(30), first),
                row(2L, "Complete", days(30), second)));

        final String summary = run();

        assertDeleted(summary, 2);
        assertThat(summary, not(containsString("stopped at the next scheduled time")));
    }

    // ---------- what is out of scope stays out of scope ----------

    /**
     * Cleanup only removes directories it holds a container record for. A UUID-named directory with no record and
     * a project-named directory written by XNAT core are both left alone; v1 does not sweep orphans.
     */
    @Test
    public void directoriesWithNoContainerRecordAreLeftAlone() throws Exception {
        final String orphanUuidDir = makeDirOnDisk();
        final Path projectDir = Files.createDirectory(buildRoot.resolve("MY_PROJECT"));
        Files.write(projectDir.resolve("data.txt"), new byte[]{1});
        stubQueries(Collections.<ContainerBuildDirRow>emptyList());

        final String summary = run();

        assertDeleted(summary, 0);
        assertThat(exists(orphanUuidDir), is(true));
        assertThat(Files.exists(projectDir), is(true));
    }

    /**
     * A recorded mount outside the build root is dropped before the launch group query, not after.
     *
     * Scope: this asserts the composed behaviour, not any one guard. Containment is enforced three times over -
     * resolveBuildDirToDelete's root check, its UUID-name check, and again at deletion - so removing any single
     * one leaves this green. What it does catch is restructuring, such as a candidate filter that stops calling
     * resolveBuildDirToDelete. The build root needs a directory of its own or the run short-circuits on an empty
     * listing and the filter never runs at all.
     */
    @Test
    public void mountPathOutsideTheBuildRootIsRefused() throws Exception {
        final String unrelated = makeDirOnDisk();          // so the build root listing is not empty
        final Path archive = tmp.newFolder("archive").toPath();
        final Path precious = Files.createDirectory(archive.resolve("PROJ"));
        Files.write(precious.resolve("scan.dcm"), new byte[]{9});
        stubQueries(Collections.singletonList(row(1L, "Complete", days(30), precious.toString())));

        final String summary = run();

        assertExamined(summary, 0);
        verify(entityService, never()).retrieveBuildDirRowsForLaunchGroups(any(), anyString());
        assertThat("the archive directory must survive", Files.exists(precious), is(true));
        assertThat("and nothing in the build root is touched either", exists(unrelated), is(true));
    }

    /**
     * An unmounted volume must fail the run loudly. Reading an empty or absent build root as "everything is
     * already clean" would be the quiet failure that hides a broken deployment.
     */
    @Test
    public void blankOrMissingBuildRootFailsTheRun() throws Exception {
        when(siteConfigPreferences.getBuildPath()).thenReturn(
                tmp.getRoot().toPath().resolve("not-mounted").toString());
        try {
            run();
            fail("Expected BuildDirectoryCleanupException for a missing build root");
        } catch (BuildDirectoryCleanupException e) {
            assertThat(e.getMessage(), containsString("not a readable directory"));
        }

        when(siteConfigPreferences.getBuildPath()).thenReturn("  ");
        try {
            run();
            fail("Expected BuildDirectoryCleanupException for a blank build path");
        } catch (BuildDirectoryCleanupException e) {
            assertThat(e.getMessage(), containsString("not configured"));
        }
    }
}
