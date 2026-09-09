package org.nrg.containers.tasks;

import lombok.extern.slf4j.Slf4j;
import org.nrg.containers.model.server.docker.DockerServerBase;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.daos.BuildDirCleanupClaimDao;
import org.nrg.containers.services.BuildDirectoryCleanupService;
import org.nrg.containers.services.DockerServerService;
import org.nrg.xdat.security.helpers.Users;
import org.nrg.xft.event.EventUtils;
import org.nrg.xft.event.persist.PersistentWorkflowI;
import org.nrg.xft.event.persist.PersistentWorkflowUtils;
import org.nrg.xft.schema.XFTManager;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.utils.WorkflowUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import javax.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs build directory cleanup once a day at the UTC time configured on the container server.
 *
 * <p>Polls instead of using a Spring {@link org.springframework.scheduling.Trigger}: ReschedulingRunnable asks its
 * trigger for the next execution only once the previous run completes, so a run longer than a day yields a deadline
 * already in the past, and a changed cleanup time is not seen until the run after next.
 *
 * <p>Ticks and runs share one single-threaded scheduler, so a tick cannot start while a run is in flight; that
 * needs no lock.
 */
@Slf4j
@Component
public class BuildDirectoryCleanupTask implements InitializingBean, DisposableBean {

    /**
     * Bounds how late a run may start and how long a changed cleanup time takes to be noticed. A run is never
     * started *before* its configured time, only up to this long after.
     */
    static final Duration TICK_INTERVAL = Duration.ofMinutes(10);

    /**
     * How stale the run deadline may be. Re-deriving it reads the container server row, which is far too costly
     * once per launch group, and pointless more often than this: it exists only to notice a changed cleanup time,
     * and it is already an order of magnitude finer than {@link #TICK_INTERVAL}.
     */
    private static final Duration DEADLINE_TTL = Duration.ofMinutes(1);

    /** UTC, as the settings UI states; no DST rules apply. */
    private static final ZoneId UTC = ZoneOffset.UTC;

    private static final String WORKFLOW_ACTION = "Build directory cleanup";
    private static final String SITE_XSI_TYPE   = "site";

    private final BuildDirectoryCleanupService cleanupService;
    private final DockerServerService          dockerServerService;
    private final BuildDirCleanupClaimDao      claimDao;
    private final TaskScheduler                scheduler;

    /**
     * Slot this node already attempted, so the database is asked once per slot rather than once per tick. Purely
     * an optimization: the claim in the database, not this field, decides who runs. Package-private for tests.
     */
    volatile Instant lastAttemptedOccurrence = null;

    /**
     * Anchor for the current run's deadline: the occurrence it claimed, or the start instant for an on-demand run.
     * Anchoring rather than measuring from "now" is the point - the next occurrence after *now* is always in the
     * future, so a deadline recomputed from now would never arrive.
     */
    private volatile Instant runAnchor = Instant.EPOCH;

    /** Derived from {@link #runAnchor} and the configured time, re-derived at most once per {@link #DEADLINE_TTL}. */
    private volatile Instant cachedDeadline;
    private volatile long    deadlineDerivedAt;

    /** Claimed before a run is handed off, released when it ends, so a queued run also counts as running. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    private boolean haveLoggedXftInitFailure = false;

    @Autowired
    public BuildDirectoryCleanupTask(final BuildDirectoryCleanupService cleanupService,
                                     final DockerServerService dockerServerService,
                                     final BuildDirCleanupClaimDao claimDao) {
        this(cleanupService, dockerServerService, claimDao, newScheduler());
    }

    /** Visible for testing, so a test can supply a scheduler it controls. */
    BuildDirectoryCleanupTask(final BuildDirectoryCleanupService cleanupService,
                              final DockerServerService dockerServerService,
                              final BuildDirCleanupClaimDao claimDao,
                              final TaskScheduler scheduler) {
        this.cleanupService      = cleanupService;
        this.dockerServerService = dockerServerService;
        this.claimDao            = claimDao;
        this.scheduler           = scheduler;
    }

    /**
     * A dedicated thread, because the sweep runs on the tick thread and can last hours: on XNAT's shared scheduler
     * pool ({@code scheduling.thread.pool.size}, default 4, also serving the ten-second container status updater)
     * that would hold a quarter of the pool.
     *
     * <p>Not a {@code @Bean}: core injects ThreadPoolTaskScheduler by type with no qualifier in several places
     * (InitializingTasksExecutor, EventSchedulingServiceImpl, AbstractScheduledXnatPreferenceHandlerMethod), so a
     * second one makes those ambiguous and the context fails to refresh.
     */
    private static ThreadPoolTaskScheduler newScheduler() {
        final ThreadPoolTaskScheduler created = new ThreadPoolTaskScheduler();
        created.setPoolSize(1);
        created.setThreadNamePrefix("build-dir-cleanup-");
        // Daemon: a sweep must not hold up JVM shutdown; a partial delete is retried next run.
        created.setDaemon(true);
        created.setWaitForTasksToCompleteOnShutdown(false);
        created.afterPropertiesSet();   // not a Spring bean, so it has to be initialized here
        return created;
    }

    @Override
    public void destroy() {
        // TaskScheduler has no shutdown(), and a test-supplied one is not ours to close.
        if (scheduler instanceof ThreadPoolTaskScheduler) {
            ((ThreadPoolTaskScheduler) scheduler).shutdown();
        }
    }

    @Override
    public void afterPropertiesSet() {
        // Fixed delay, not fixed rate: a run lasting days means no ticks meanwhile, not a burst of catch-up firings.
        scheduler.scheduleWithFixedDelay(this::tick, TICK_INTERVAL);
        log.info("Build directory cleanup will check every {} minutes whether its configured UTC time has passed.",
                TICK_INTERVAL.toMinutes());
    }

    /**
     * The earliest occurrence of {@code timeOfDay} in {@code zone} that is strictly after {@code now}. This is a
     * run's deadline: no new launch group is started past it, so a run always finishes before the next occurrence
     * becomes claimable and two runs can never overlap by more than the group in flight.
     *
     * Computed in the zoned domain rather than by adding 24 hours, so it stays correct if the configured zone is
     * ever something other than UTC.
     */
    static Instant nextOccurrenceAfter(final Instant now, final LocalTime timeOfDay, final ZoneId zone) {
        final ZonedDateTime zoned = now.atZone(zone);
        final ZonedDateTime today = zoned.with(timeOfDay);
        return (today.isAfter(zoned) ? today : today.plusDays(1)).toInstant();
    }

    /** The latest occurrence of {@code timeOfDay} in {@code zone} that is at or before {@code now}. */
    static Instant mostRecentOccurrence(final Instant now, final LocalTime timeOfDay, final ZoneId zone) {
        final ZonedDateTime zoned = now.atZone(zone);
        final ZonedDateTime today = zoned.with(timeOfDay);
        return (today.isAfter(zoned) ? today.minusDays(1) : today).toInstant();
    }

    void tick() {
        try {
            final LocalTime timeOfDay  = configuredTime();
            final Instant   now        = Instant.now();
            final Instant   occurrence = mostRecentOccurrence(now, timeOfDay, UTC);

            if (occurrence.equals(lastAttemptedOccurrence)) {
                return;
            }
            if (!xftIsInitialized()) {
                // No attempt recorded: a node still starting up must retry next tick, not consume the day.
                if (!haveLoggedXftInitFailure) {
                    log.info("XFT is not initialized, skipping build directory cleanup task");
                    haveLoggedXftInitFailure = true;
                }
                return;
            }
            haveLoggedXftInitFailure = false;
            lastAttemptedOccurrence  = occurrence;

            if (!cleanupService.isEnabled()) {
                return;     // before claiming, so a disabled site leaves the slot free
            }
            // isPrimaryNode() is deliberately not consulted: it defaults to true, so on an unconfigured cluster
            // every node thinks it is primary.
            if (!claimDao.claim(occurrence)) {
                return;
            }
            if (!running.compareAndSet(false, true)) {
                log.warn("A build directory cleanup is already in progress; skipping this run.");
                return;
            }
            anchorRunAt(occurrence);
            guardedCleanup();
        } catch (Throwable t) {
            // Not redundant with Spring's error handler: an escaping error would end the repeating tick for good.
            log.error("Unexpected error in the build directory cleanup tick", t);
        }
    }

    /**
     * A run starts no new group past this, so overlap with the next run is bounded to one group. The configured
     * time is re-read rather than captured once, so moving the setting earlier shortens the run in progress
     * instead of letting it overrun into the next slot.
     */
    private boolean mayStartMoreWork() {
        final long now = System.nanoTime();
        if (cachedDeadline == null || now - deadlineDerivedAt > DEADLINE_TTL.toNanos()) {
            cachedDeadline    = nextOccurrenceAfter(runAnchor, configuredTime(), UTC);
            deadlineDerivedAt = now;
        }
        return Instant.now().isBefore(cachedDeadline);
    }

    private void anchorRunAt(final Instant anchor) {
        runAnchor      = anchor;
        cachedDeadline = null;
    }

    /** The configured "HH:mm" as UTC, or the default if unset, unreadable or malformed. Package-private for tests. */
    LocalTime configuredTime() {
        String configured = null;
        try {
            // Null before the database is ready, and on sites with no container server configured.
            final DockerServer server = dockerServerService.retrieveServer();
            if (server != null) {
                configured = server.buildDirCleanupTime();
                if (DockerServerBase.isValidCleanupTime(configured)) {
                    return LocalTime.parse(configured.trim());
                }
                log.warn("Configured build directory cleanup time \"{}\" is not HH:mm; using {}.",
                        configured, DockerServerBase.DEFAULT_BUILD_DIR_CLEANUP_TIME);
            }
        } catch (RuntimeException e) {   // includes DateTimeParseException
            log.debug("Could not read the configured build directory cleanup time; using {}.",
                    DockerServerBase.DEFAULT_BUILD_DIR_CLEANUP_TIME, e);
        }
        return LocalTime.parse(DockerServerBase.DEFAULT_BUILD_DIR_CLEANUP_TIME);
    }

    /** Outcome of an on-demand trigger, so the REST layer can pick a status code. */
    public enum TriggerResult { STARTED, ALREADY_RUNNING, DISABLED, FAILED_TO_START }

    /**
     * Start a cleanup run now, outside the schedule, without waiting for it to finish.
     *
     * Handed to the scheduler the tick uses, so it produces the same ADMIN workflow entry and cannot overlap a
     * scheduled run. Any node may serve this: an explicit operator action, and the build path is shared. It does
     * not consume a daily slot.
     */
    public TriggerResult triggerNow() {
        if (!running.compareAndSet(false, true)) {
            return TriggerResult.ALREADY_RUNNING;
        }
        if (!cleanupService.isEnabled()) {
            running.set(false);
            return TriggerResult.DISABLED;
        }
        // An on-demand run is bounded from now, so it too stops before the next scheduled slot.
        anchorRunAt(Instant.now());
        return handOff() ? TriggerResult.STARTED : TriggerResult.FAILED_TO_START;
    }

    /** A scheduler shutting down rejects new work, and a guard left set would block every later run. */
    private boolean handOff() {
        try {
            scheduler.schedule(this::guardedCleanup, Instant.now());
            return true;
        } catch (TaskRejectedException e) {
            running.set(false);
            log.error("Could not hand build directory cleanup to its scheduler.", e);
            return false;
        }
    }

    /** Seam for tests: XFTManager is a static that cannot be initialized in a plain unit test. */
    protected boolean xftIsInitialized() {
        return XFTManager.isInitialized();
    }

    /** The caller must already hold the guard; this releases it. */
    private void guardedCleanup() {
        try {
            doCleanup();
        } catch (Throwable t) {
            log.error("Unexpected error during build directory cleanup", t);
        } finally {
            running.set(false);
        }
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

        // Not ContainerUtils.updateWorkflowStatus: it saves twice, and NPEs on a freshly built workflow, which has
        // status "In Progress" but null details, because it compares details without a null check.
        try {
            final String summary = cleanupService.cleanup(this::mayStartMoreWork);
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
