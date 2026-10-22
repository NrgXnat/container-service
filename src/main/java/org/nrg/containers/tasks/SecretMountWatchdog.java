package org.nrg.containers.tasks;

import lombok.extern.slf4j.Slf4j;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.services.DockerServerService;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import javax.annotation.Nullable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks containers whose secret volume cannot be mounted and reports the ones that have been stuck longer than
 * the container server's secret-mount-timeout-seconds (design doc section 4.1).
 *
 * <p>State lives here, keyed by container database id, because the informer only emits JMS events, knows no
 * containers, and is rebuilt (losing its state) whenever the Kubernetes client is shut down. What to do on
 * timeout is decided by the registered handler (the container service), so this class needs no reference to
 * it and no construction-time cycle arises.
 *
 * <p>Only FailedMount events on secret volumes are timed; optional secrets in secret mode never produce one: a
 * missing optional Secret mounts as an empty directory and the pod runs, so such a container is never tracked.
 *
 * <p>Own scheduler, like BuildDirectoryCleanupTask: there is no taskScheduler bean in this plugin, and a second
 * ThreadPoolTaskScheduler bean would make core's by-type injections ambiguous.
 */
@Slf4j
@Component
public class SecretMountWatchdog implements InitializingBean, DisposableBean {
    static final Duration TICK_INTERVAL = Duration.ofSeconds(60);

    public interface TimeoutHandler {
        void onSecretMountTimeout(long containerDbId, int timeoutSeconds, String translatedReason);
    }

    private static final class Entry {
        private final Instant firstFailure;
        private final String translatedReason;

        private Entry(final Instant firstFailure, final String translatedReason) {
            this.firstFailure = firstFailure;
            this.translatedReason = translatedReason;
        }
    }

    private final DockerServerService dockerServerService;
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();
    private volatile TimeoutHandler handler;

    @Autowired
    public SecretMountWatchdog(final DockerServerService dockerServerService) {
        this(dockerServerService, newScheduler(), Clock.systemUTC());
    }

    /** Public only for tests (SecretMountTimeoutTest lives in the services package): a controllable scheduler and clock. */
    public SecretMountWatchdog(final DockerServerService dockerServerService, final TaskScheduler scheduler, final Clock clock) {
        this.dockerServerService = dockerServerService;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    private static ThreadPoolTaskScheduler newScheduler() {
        final ThreadPoolTaskScheduler created = new ThreadPoolTaskScheduler();
        created.setPoolSize(1);
        created.setThreadNamePrefix("secret-mount-watchdog-");
        created.setDaemon(true);
        created.setWaitForTasksToCompleteOnShutdown(false);
        created.afterPropertiesSet();   // not a Spring bean, so it has to be initialized here
        return created;
    }

    @Override
    public void afterPropertiesSet() {
        scheduler.scheduleWithFixedDelay(this::tick, TICK_INTERVAL);
    }

    @Override
    public void destroy() {
        if (scheduler instanceof ThreadPoolTaskScheduler) {
            ((ThreadPoolTaskScheduler) scheduler).shutdown();
        }
    }

    public void registerTimeoutHandler(final TimeoutHandler handler) {
        this.handler = handler;
    }

    /**
     * Idempotent: the first report wins, so replayed events after an informer restart and repeated FailedMount
     * events for the same pod never move the start time.
     */
    public void recordFailedMount(final long containerDbId, @Nullable final OffsetDateTime firstTimestamp, final String translatedReason) {
        final Instant first = firstTimestamp == null ? clock.instant() : firstTimestamp.toInstant();
        if (entries.putIfAbsent(containerDbId, new Entry(first, translatedReason)) == null) {
            log.info("Container {} cannot mount a secret volume since {}: {}", containerDbId, first, translatedReason);
        }
    }

    public void clear(final long containerDbId) {
        if (entries.remove(containerDbId) != null) {
            log.debug("Container {} no longer waiting on a secret mount", containerDbId);
        }
    }

    public boolean isTracking(final long containerDbId) {
        return entries.containsKey(containerDbId);
    }

    /** Public so the container service test can drive it; scheduled every TICK_INTERVAL in production. */
    public void tick() {
        try {
            if (entries.isEmpty()) {
                return;   // nothing to time out: do not read the server row every minute for nothing
            }
            final int timeoutSeconds = configuredTimeoutSeconds();
            final TimeoutHandler currentHandler = handler;
            if (timeoutSeconds <= 0 || currentHandler == null) {
                return;
            }
            final Instant now = clock.instant();
            for (final Map.Entry<Long, Entry> entry : entries.entrySet()) {
                final long stuckFor = Duration.between(entry.getValue().firstFailure, now).getSeconds();
                if (stuckFor <= timeoutSeconds) {
                    continue;
                }
                try {
                    // The handler clears the entry once the job is gone; until then it is asked again next tick.
                    currentHandler.onSecretMountTimeout(entry.getKey(), timeoutSeconds, entry.getValue().translatedReason);
                } catch (RuntimeException e) {
                    log.error("Secret mount timeout handling failed for container {}", entry.getKey(), e);
                }
            }
        } catch (Throwable t) {
            // An escaping error would end the repeating tick for good.
            log.error("Unexpected error in the secret mount watchdog tick", t);
        }
    }

    /** 0 when there is no server, no Kubernetes backend, or the setting cannot be read. */
    int configuredTimeoutSeconds() {
        try {
            final DockerServer server = dockerServerService.retrieveServer();
            return server == null ? 0 : server.secretMountTimeoutSeconds();
        } catch (RuntimeException e) {
            log.debug("Could not read the secret mount timeout; watchdog idle this tick", e);
            return 0;
        }
    }
}
