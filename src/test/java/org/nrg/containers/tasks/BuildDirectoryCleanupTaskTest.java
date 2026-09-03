package org.nrg.containers.tasks;

import org.junit.Before;
import org.junit.Test;
import org.nrg.containers.services.BuildDirectoryCleanupService;
import org.nrg.xnat.services.XnatAppInfo;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The guards that manual testing cannot reach: multi-node behaviour, the hand-off that keeps a long sweep off the
 * scheduler thread, and two ways the task could permanently disable itself. Everything else about this task -
 * disabled, started, already running, guard release after a normal run - is directly observable against a running
 * XNAT through POST /xapi/docker/server/build-dir-cleanup, which returns 400, 202 and 409 respectively.
 *
 * The mocked executor never runs what it is given, which is what holds the guard open for these tests and keeps
 * them clear of the workflow path, which needs a live XFT.
 */
public class BuildDirectoryCleanupTaskTest {

    private BuildDirectoryCleanupService cleanupService;
    private XnatAppInfo                  appInfo;
    private ExecutorService              executorService;
    private BuildDirectoryCleanupTask    task;

    @Before
    public void setUp() {
        cleanupService  = mock(BuildDirectoryCleanupService.class);
        appInfo         = mock(XnatAppInfo.class);
        executorService = mock(ExecutorService.class);
        when(appInfo.isPrimaryNode()).thenReturn(true);
        // XFTManager is a static that cannot be initialized here, so stand in for the readiness check.
        task = new BuildDirectoryCleanupTask(cleanupService, appInfo, executorService) {
            @Override
            protected boolean xftIsInitialized() {
                return true;
            }
        };
    }

    /** Not observable on a single-node dev instance: only the primary node runs the schedule. */
    @Test
    public void doesNothingOnANonPrimaryNode() {
        when(appInfo.isPrimaryNode()).thenReturn(false);

        task.run();

        verify(executorService, never()).submit(any(Runnable.class));
        verify(cleanupService, never()).isEnabled();
    }

    /**
     * A sweep is uncapped and can run for hours, so the scheduled run must hand off rather than execute inline.
     * The mocked executor never runs the submitted task, so a run that had executed inline would have reached the
     * service; returning without touching it is the property that leaves the scheduler thread free.
     */
    @Test
    public void aScheduledRunHandsOffInsteadOfBlockingTheSchedulerThread() {
        task.run();

        verify(executorService).submit(any(Runnable.class));
        verify(cleanupService, never()).isEnabled();
    }

    /**
     * The design requirement that another cleanup must not already be running, in both directions. The guard is
     * held from the moment of hand-off until the submitted run finishes, so neither a second scheduled run nor an
     * on-demand trigger may queue a second sweep behind the first.
     */
    @Test
    public void aRunInFlightBlocksBothTheScheduleAndTheEndpoint() {
        when(cleanupService.isEnabled()).thenReturn(true);

        task.run();
        verify(executorService).submit(any(Runnable.class));

        task.run();
        assertThat("an on-demand trigger must be refused, not queued",
                task.triggerNow(), is(BuildDirectoryCleanupTask.TriggerResult.ALREADY_RUNNING));
        verify(executorService, times(1)).submit(any(Runnable.class));
    }

    /**
     * The guard is taken before the hand-off, so a rejected hand-off has to give it back. Leaving it set would
     * block every later run, scheduled and on-demand alike, until the next restart.
     */
    @Test
    public void aRejectedHandOffReleasesTheGuard() {
        when(cleanupService.isEnabled()).thenReturn(true);
        when(executorService.submit(any(Runnable.class)))
                .thenThrow(new RejectedExecutionException("pool is shut down"))
                .thenReturn(null);

        assertThat(task.triggerNow(), is(BuildDirectoryCleanupTask.TriggerResult.FAILED_TO_START));
        assertThat("a later run must still be able to start",
                task.triggerNow(), is(BuildDirectoryCleanupTask.TriggerResult.STARTED));
    }
}
