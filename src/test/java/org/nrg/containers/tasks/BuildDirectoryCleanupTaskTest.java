package org.nrg.containers.tasks;

import org.junit.Before;
import org.junit.Test;
import org.nrg.containers.daos.BuildDirCleanupClaimDao;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.services.BuildDirectoryCleanupService;
import org.nrg.containers.services.DockerServerService;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The scheduling decision and the guards around it. Which node runs is decided by the claim in the database,
 * tested for real in BuildDirCleanupClaimTest; here the claim is mocked so this class stays a fast unit test.
 * */
public class BuildDirectoryCleanupTaskTest {

    private static final ZoneId    UTC     = ZoneOffset.UTC;
    private static final LocalTime AT_0200 = LocalTime.of(2, 0);

    private BuildDirectoryCleanupService cleanupService;
    private DockerServerService          dockerServerService;
    private BuildDirCleanupClaimDao      claimDao;
    private TaskScheduler                scheduler;
    private BuildDirectoryCleanupTask    task;

    @Before
    public void setUp() {
        cleanupService      = mock(BuildDirectoryCleanupService.class);
        dockerServerService = mock(DockerServerService.class);
        claimDao            = mock(BuildDirCleanupClaimDao.class);
        scheduler           = mock(TaskScheduler.class);
        when(claimDao.claim(any(Instant.class))).thenReturn(true);
        when(dockerServerService.retrieveServer()).thenReturn(null);   // no server: the default 02:00 applies
        task = new BuildDirectoryCleanupTask(cleanupService, dockerServerService, claimDao, scheduler) {
            @Override
            protected boolean xnatIsReady() {
                return true;
            }
        };
    }

    private static Instant utc(final String isoLocal) {
        return Instant.parse(isoLocal + "Z");
    }

    // ---------- the two pure time functions ----------

    @Test
    public void theMostRecentOccurrenceIsYesterdaysUntilTheConfiguredTimePasses() {
        assertThat("before 02:00, the most recent slot is yesterday's",
                BuildDirectoryCleanupTask.mostRecentOccurrence(utc("2026-03-10T01:30:00"), AT_0200, UTC),
                is(utc("2026-03-09T02:00:00")));
        assertThat("after 02:00, it is today's",
                BuildDirectoryCleanupTask.mostRecentOccurrence(utc("2026-03-10T02:00:30"), AT_0200, UTC),
                is(utc("2026-03-10T02:00:00")));
        assertThat("exactly at 02:00 counts as today's",
                BuildDirectoryCleanupTask.mostRecentOccurrence(utc("2026-03-10T02:00:00"), AT_0200, UTC),
                is(utc("2026-03-10T02:00:00")));
    }

    /** A run's deadline. Anchored to the claimed slot, this is the next time the slot becomes claimable again. */
    @Test
    public void theNextOccurrenceAfterASlotIsTheFollowingDay() {
        assertThat(BuildDirectoryCleanupTask.nextOccurrenceAfter(utc("2026-03-10T02:00:00"), AT_0200, UTC),
                is(utc("2026-03-11T02:00:00")));
        assertThat("a slot still ahead today is used as-is",
                BuildDirectoryCleanupTask.nextOccurrenceAfter(utc("2026-03-10T02:00:00"), LocalTime.of(5, 0), UTC),
                is(utc("2026-03-10T05:00:00")));
        assertThat("a slot already past today moves to tomorrow",
                BuildDirectoryCleanupTask.nextOccurrenceAfter(utc("2026-03-10T02:00:00"), LocalTime.of(1, 0), UTC),
                is(utc("2026-03-11T01:00:00")));
    }

    // ---------- the tick ----------

    /** Put the last attempt on a different slot so the next tick has a slot to claim. */
    private void makeARunDue() {
        task.lastAttemptedOccurrence = utc("2020-01-01T02:00:00");
    }

    /** The positive control: a due tick claims the slot and hands the sweep to the service. */
    @Test
    public void aDueTickClaimsTheSlotAndRunsTheCleanup() {
        makeARunDue();
        when(cleanupService.isEnabled()).thenReturn(true);

        task.tick();

        verify(claimDao).claim(any(Instant.class));
        verify(cleanupService, times(2)).isEnabled();   // once as the pre-check, once inside the run
    }

    /** Losing the claim means another node has this slot; this node must not sweep. */
    @Test
    public void aTickThatLosesTheClaimDoesNotRun() {
        makeARunDue();
        when(cleanupService.isEnabled()).thenReturn(true);
        when(claimDao.claim(any(Instant.class))).thenReturn(false);

        task.tick();

        verify(claimDao).claim(any(Instant.class));
        verify(cleanupService, times(1)).isEnabled();   // the pre-check only; the run never started
    }

    /** One slot, one attempt, however many ticks land in it. */
    @Test
    public void aSlotIsOnlyAttemptedOnce() {
        makeARunDue();
        when(cleanupService.isEnabled()).thenReturn(true);

        task.tick();
        task.tick();
        task.tick();

        verify(claimDao, times(1)).claim(any(Instant.class));
    }

    /** A disabled site leaves the slot unclaimed, so enabling it later does not find the day already taken. */
    @Test
    public void aDisabledSiteDoesNotClaimTheSlot() {
        makeARunDue();
        when(cleanupService.isEnabled()).thenReturn(false);

        task.tick();

        verify(claimDao, never()).claim(any(Instant.class));
    }

    /**
     * XNAT can tick before its database migration finishes. The slot must be left unattempted so the run happens
     * once XNAT is ready, rather than the day being silently consumed.
     */
    @Test
    public void aTickBeforeXnatIsReadyDoesNotConsumeTheSlot() {
        final BuildDirectoryCleanupTask notReady =
                new BuildDirectoryCleanupTask(cleanupService, dockerServerService, claimDao, scheduler) {
                    @Override
                    protected boolean xnatIsReady() {
                        return false;
                    }
                };
        notReady.lastAttemptedOccurrence = utc("2020-01-01T02:00:00");

        notReady.tick();

        verify(claimDao, never()).claim(any(Instant.class));
        assertThat("the slot is still unattempted", notReady.lastAttemptedOccurrence, is(utc("2020-01-01T02:00:00")));
    }

    // ---------- on demand ----------

    @Test
    public void triggerNowHandsOffToTheSchedulerAndReportsDisabledWithoutIt() {
        when(cleanupService.isEnabled()).thenReturn(false);
        assertThat(task.triggerNow(), is(BuildDirectoryCleanupTask.TriggerResult.DISABLED));
        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));

        when(cleanupService.isEnabled()).thenReturn(true);
        assertThat(task.triggerNow(), is(BuildDirectoryCleanupTask.TriggerResult.STARTED));
        verify(scheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    /** The guard is claimed at request time, not when the scheduler thread picks the run up. */
    @Test
    public void aSecondTriggerIsRefusedWhileTheFirstIsStillQueued() {
        when(cleanupService.isEnabled()).thenReturn(true);

        assertThat(task.triggerNow(), is(BuildDirectoryCleanupTask.TriggerResult.STARTED));
        assertThat(task.triggerNow(), is(BuildDirectoryCleanupTask.TriggerResult.ALREADY_RUNNING));
        verify(scheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));
    }

    /** A scheduler shutting down refuses work; the guard must come back or cleanup is dead until a restart. */
    @Test
    public void aRejectedHandOffReleasesTheGuard() {
        when(cleanupService.isEnabled()).thenReturn(true);
        when(scheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenThrow(new TaskRejectedException("scheduler is shutting down"))
                .thenReturn(null);

        assertThat(task.triggerNow(), is(BuildDirectoryCleanupTask.TriggerResult.FAILED_TO_START));
        assertThat("a later run must still be able to start",
                task.triggerNow(), is(BuildDirectoryCleanupTask.TriggerResult.STARTED));
    }

    /**
     * Read every tick, so it must never throw and must fall back rather than leave the schedule undefined. A bad
     * value reaching this from the database would otherwise stop cleanup running at all.
     */
    @Test
    public void theConfiguredTimeFallsBackToTheDefaultWhenUnusable() {
        assertThat("no container server configured", task.configuredTime(), is(AT_0200));

        when(dockerServerService.retrieveServer()).thenReturn(serverWithCleanupTime("nonsense"));
        assertThat("malformed value", task.configuredTime(), is(AT_0200));

        when(dockerServerService.retrieveServer()).thenThrow(new IllegalStateException("database not ready"));
        assertThat("unreadable", task.configuredTime(), is(AT_0200));

        reset(dockerServerService);
        when(dockerServerService.retrieveServer()).thenReturn(serverWithCleanupTime("04:30"));
        assertThat("a valid value is used", task.configuredTime(), is(LocalTime.of(4, 30)));
    }

    private static DockerServer serverWithCleanupTime(final String time) {
        return DockerServer.builder()
                .name("test").host("unix:///var/run/docker.sock")
                .buildDirCleanupTime(time)
                .build();
    }
}
