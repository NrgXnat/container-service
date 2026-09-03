package org.nrg.containers.tasks;

import lombok.extern.slf4j.Slf4j;
import org.nrg.containers.model.server.docker.DockerServerBase;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.services.DockerServerService;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.support.CronTrigger;

import java.util.Date;
import java.util.TimeZone;

/**
 * Fires build directory cleanup once a day at the UTC time configured on the container server.
 *
 * Cron rather than PeriodicTrigger, whose zero initial delay would run a full sweep on every application startup.
 * The time is re-read each cycle so a change needs no restart, but takes effect on the following cycle.
 *
 * UTC is used explicitly rather than the JVM default zone, so the configured value means the same thing regardless
 * of how the server or its container is configured.
 */
@Slf4j
public class BuildDirCleanupTrigger implements Trigger {

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private final DockerServerService dockerServerService;

    public BuildDirCleanupTrigger(final DockerServerService dockerServerService) {
        this.dockerServerService = dockerServerService;
    }

    @Override
    public Date nextExecutionTime(final TriggerContext triggerContext) {
        final String cron = cronExpression();
        final Date next = new CronTrigger(cron, UTC).nextExecutionTime(triggerContext);
        // Logged so an operator can confirm when cleanup will actually run.
        log.info("Next build directory cleanup scheduled for {} (cron \"{}\" UTC); last completion {}",
                next, cron, triggerContext.lastCompletionTime());
        return next;
    }

    private String cronExpression() {
        final String time = configuredTime();
        return "0 " + time.substring(3, 5) + " " + time.substring(0, 2) + " * * *";
    }

    /** The configured "HH:mm", or the default if unset, unreadable or malformed. */
    private String configuredTime() {
        try {
            // Null before the database is ready, and on sites with no container server configured.
            final DockerServer server = dockerServerService.retrieveServer();
            if (server != null) {
                final String time = server.buildDirCleanupTime();
                if (DockerServerBase.isValidCleanupTime(time)) {
                    return time.trim();
                }
                log.warn("Configured build directory cleanup time \"{}\" is not HH:mm; using {}.",
                        time, DockerServerBase.DEFAULT_BUILD_DIR_CLEANUP_TIME);
            }
        } catch (Exception e) {
            log.debug("Could not read the configured build directory cleanup time; using {}.",
                    DockerServerBase.DEFAULT_BUILD_DIR_CLEANUP_TIME, e);
        }
        return DockerServerBase.DEFAULT_BUILD_DIR_CLEANUP_TIME;
    }
}
