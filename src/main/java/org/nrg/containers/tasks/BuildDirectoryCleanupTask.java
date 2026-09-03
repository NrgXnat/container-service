package org.nrg.containers.tasks;

import lombok.extern.slf4j.Slf4j;
import org.nrg.containers.services.BuildDirectoryCleanupService;
import org.nrg.xdat.security.helpers.Users;
import org.nrg.xft.event.EventUtils;
import org.nrg.xft.event.persist.PersistentWorkflowI;
import org.nrg.xft.event.persist.PersistentWorkflowUtils;
import org.nrg.xft.schema.XFTManager;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.services.XnatAppInfo;
import org.nrg.xnat.utils.WorkflowUtils;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.Nullable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scheduled entry point for build directory cleanup, paired with a {@link BuildDirCleanupTrigger} in
 * ContainersConfig. Records each run as a site-wide ADMIN workflow entry.
 *
 * Neither entry point runs the sweep on the calling thread. A run is uncapped and can take hours on a site with a
 * large backlog, so executing it inline would hold an XNAT scheduler thread for that whole time - a pool that is
 * small and shared, and that services the container status updater every ten seconds. Both paths therefore hand
 * off to a dedicated single-threaded executor owned by this bean.
 *
 * The executor is deliberately private rather than a Spring bean. The plugin context holds exactly one
 * ExecutorService, injected by type in several places, and publishing a second one would make every one of those
 * injection points ambiguous. It is also deliberately not the shared container pool: a multi-hour sweep must not
 * occupy one of the five threads that serve bulk launches and the Kubernetes informers.
 */
@Slf4j
@Component
public class BuildDirectoryCleanupTask implements Runnable, DisposableBean {

    private static final String WORKFLOW_ACTION = "Build directory cleanup";
    private static final String SITE_XSI_TYPE   = "site";

    private final BuildDirectoryCleanupService cleanupService;
    private final XnatAppInfo                  xnatAppInfo;
    private final ExecutorService              executorService;

    /** Guards against a second run starting while one is still in flight in another thread. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    private boolean haveLoggedXftInitFailure = false;

    @Autowired
    public BuildDirectoryCleanupTask(final BuildDirectoryCleanupService cleanupService,
                                     final XnatAppInfo appInfo) {
        this(cleanupService, appInfo, newCleanupExecutor());
    }

    /** Visible for testing, so a test can hold the guard open without ever running the submitted task. */
    BuildDirectoryCleanupTask(final BuildDirectoryCleanupService cleanupService,
                              final XnatAppInfo appInfo,
                              final ExecutorService executorService) {
        this.cleanupService  = cleanupService;
        this.xnatAppInfo     = appInfo;
        this.executorService = executorService;
    }

    private static ExecutorService newCleanupExecutor() {
        final AtomicInteger counter = new AtomicInteger();
        return Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "build-dir-cleanup-" + counter.incrementAndGet());
            // Daemon so a sweep in progress can never delay JVM shutdown; a partial delete is retried next run.
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Outcome of an on-demand trigger, so the REST layer can pick a status code. */
    public enum TriggerResult { STARTED, ALREADY_RUNNING, DISABLED, FAILED_TO_START }

    /**
     * Start a cleanup run now, outside the schedule, without waiting for it to finish.
     *
     * Shares the scheduled run's guard, so an on-demand run cannot overlap a nightly one in either direction, and
     * produces the same ADMIN workflow entry. Deliberately does not check {@code isPrimaryNode()}: this is an
     * explicit operator action, and the build path is shared across nodes anyway.
     */
    public TriggerResult triggerNow() {
        // Guard first: a run already in flight is the more specific answer, whatever the enabled state.
        if (!running.compareAndSet(false, true)) {
            return TriggerResult.ALREADY_RUNNING;
        }
        if (!cleanupService.isEnabled()) {
            running.set(false);
            return TriggerResult.DISABLED;
        }
        return submitGuardedCleanup() ? TriggerResult.STARTED : TriggerResult.FAILED_TO_START;
    }

    @Override
    public void run() {
        if (!xnatAppInfo.isPrimaryNode()) {
            return;
        }

        if (!xftIsInitialized()) {
            if (!haveLoggedXftInitFailure) {
                log.info("XFT is not initialized, skipping build directory cleanup task");
                haveLoggedXftInitFailure = true;
            }
            return;
        }
        haveLoggedXftInitFailure = false;

        if (!running.compareAndSet(false, true)) {
            log.warn("A build directory cleanup is already in progress; skipping this run.");
            return;
        }
        // Returns as soon as the sweep is handed off, so the scheduler thread is free and the trigger is asked for
        // the next execution time immediately rather than after the sweep finishes.
        if (!submitGuardedCleanup()) {
            log.error("The scheduled build directory cleanup could not be started.");
        }
    }

    /**
     * Hand a guarded run to the cleanup thread. The caller must already hold the guard; this releases it if the
     * hand-off fails, since a guard left set would block every later run until the next restart.
     *
     * The guard admits at most one outstanding run, so this executor's queue never holds more than one task.
     */
    private boolean submitGuardedCleanup() {
        try {
            executorService.submit(this::guardedCleanup);
            return true;
        } catch (RejectedExecutionException e) {
            running.set(false);
            log.error("Could not submit build directory cleanup for execution.", e);
            return false;
        }
    }

    /** Runs cleanup and always releases the guard. The caller must already have acquired it. */
    private void guardedCleanup() {
        try {
            doCleanup();
        } catch (Throwable t) {
            // Never let an exception escape and kill the scheduled task.
            log.error("Unexpected error during build directory cleanup", t);
        } finally {
            running.set(false);
        }
    }

    /** Seam for tests: XFTManager is a static that cannot be initialized in a plain unit test. */
    protected boolean xftIsInitialized() {
        return XFTManager.isInitialized();
    }

    @Override
    public void destroy() {
        // Interrupts rather than waits: a sweep can run for hours and must not hold up a shutdown or redeploy.
        // A half-finished directory is left on disk and reclaimed by the next run, which is the same handling as
        // any other partial delete.
        executorService.shutdownNow();
    }

    private void doCleanup() {
        // Before creating a workflow, so a disabled site gets no daily audit noise.
        if (!cleanupService.isEnabled()) {
            log.debug("Build directory cleanup is disabled.");
            return;
        }

        final UserI adminUser = Users.getAdminUser();
        if (adminUser == null) {
            log.error("Could not resolve the site administrator user; skipping build directory cleanup.");
            return;
        }

        final PersistentWorkflowI workflow = openWorkflow(adminUser);
        if (workflow == null) {
            return;
        }

        // Set details on the workflow directly and let complete()/fail() do the single save. Routing through
        // ContainerUtils.updateWorkflowStatus would both save twice and NPE here, because a freshly built workflow
        // already has status "In Progress" but null details, and that method compares details without a null check.
        try {
            final String summary = cleanupService.cleanup();
            workflow.setDetails(summary);
            WorkflowUtils.complete(workflow, workflow.buildEvent());
            log.info("Build directory cleanup complete. {}", summary);
        } catch (Exception e) {
            log.error("Build directory cleanup failed", e);
            try {
                workflow.setDetails(e.getClass().getSimpleName() + ": " + e.getMessage());
                WorkflowUtils.fail(workflow, workflow.buildEvent());
            } catch (Exception inner) {
                log.error("Could not mark the build directory cleanup workflow as failed", inner);
            }
        }
    }

    @Nullable
    private PersistentWorkflowI openWorkflow(final UserI adminUser) {
        try {
            // buildAdminWorkflow lives on PersistentWorkflowUtils; WorkflowUtils only re-exports a subset.
            return PersistentWorkflowUtils.buildAdminWorkflow(adminUser, SITE_XSI_TYPE,
                    PersistentWorkflowUtils.ADMIN_EXTERNAL_ID,
                    EventUtils.newEventInstance(EventUtils.CATEGORY.SIDE_ADMIN, EventUtils.TYPE.PROCESS,
                            WORKFLOW_ACTION, "Cleanup", null));
        } catch (Exception e) {
            log.error("Unable to create an ADMIN workflow for build directory cleanup; skipping run.", e);
            return null;
        }
    }

}
