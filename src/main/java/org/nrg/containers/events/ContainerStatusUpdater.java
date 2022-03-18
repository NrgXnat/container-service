package org.nrg.containers.events;

import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.mandas.docker.client.exceptions.ServiceNotFoundException;
import org.mandas.docker.client.exceptions.TaskNotFoundException;
import org.nrg.containers.api.ContainerControlApi;
import org.nrg.containers.events.model.DockerContainerEvent;
import org.nrg.containers.events.model.ServiceTaskEvent;
import org.nrg.containers.exceptions.ContainerException;
import org.nrg.containers.exceptions.DockerServerException;
import org.nrg.containers.exceptions.NoDockerServerException;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.auto.ServiceTask;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.services.ContainerService;
import org.nrg.containers.services.DockerServerService;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.framework.services.NrgEventService;
import org.nrg.xdat.security.helpers.Users;
import org.nrg.xdat.servlet.XDATServlet;
import org.nrg.xft.schema.XFTManager;
import org.nrg.xnat.services.XnatAppInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.mandas.docker.client.messages.swarm.TaskStatus.TASK_STATE_FAILED;

@Slf4j
@Component
public class ContainerStatusUpdater implements Runnable {

    private final ContainerControlApi containerControlApi;
    private final ContainerService containerService;
    private final DockerServerService dockerServerService;
    private final NrgEventService eventService;
    private final XnatAppInfo xnatAppInfo;

    private boolean haveLoggedDockerConnectFailure = false;
    private boolean haveLoggedNoServerInDb = false;
    private boolean haveLoggedXftInitFailure = false;

    @Autowired
    @SuppressWarnings("SpringJavaAutowiringInspection")
    public ContainerStatusUpdater(final ContainerControlApi containerControlApi,
                                  final ContainerService containerService,
                                  final DockerServerService dockerServerService,
                                  final NrgEventService eventService,
                                  final XnatAppInfo xnatAppInfo) {
        this.containerControlApi = containerControlApi;
        this.containerService = containerService;
        this.dockerServerService = dockerServerService;
        this.eventService = eventService;
        this.xnatAppInfo = xnatAppInfo;
    }

    @Override
    public void run() {

        final DockerServer server = initialize();

        if (server == null) {
            // We have already logged all the problems within initialize, so here we just exit silently.
            return;
        }

        // Now we should be able to check for updates
        final UpdateReport updateReport = checkForUpdatesAndThrowEvents(server);

        // Log update reports
        if (updateReport.successful == null) {
            // This means some, but not all, of the services didn't update properly. Which ones?
            for (final UpdateReportEntry entry : updateReport.updateReports) {
                if (!entry.successful) {
                    log.error("Could not update status for {}. Message: {}", entry.id, entry.message);
                } else {
                    log.debug("Updated successfully for {}.", entry.id);
                }
            }

            // Reset failure flags
            haveLoggedDockerConnectFailure = false;
            haveLoggedXftInitFailure = false;
            haveLoggedNoServerInDb = false;
        } else if (updateReport.successful) {
            if (updateReport.updateReports.size() > 0) {
                log.debug("Updated status successfully.");
            }
            // Reset failure flags
            haveLoggedDockerConnectFailure = false;
            haveLoggedXftInitFailure = false;
            haveLoggedNoServerInDb = false;
        } else {
            log.info("Did not update status successfully.");
        }
        log.trace("-----------------------------------------------------------------------------");
        log.trace("{}: RUN COMPLETE", this.getClass().getName().toUpperCase());
        log.trace("-----------------------------------------------------------------------------");
   
    }

    private DockerServer initialize() {
        if(!xnatAppInfo.isPrimaryNode()) {
            return null;
        }

        final String skipMessage = "Skipping attempt to update status.";

        if (!XFTManager.isInitialized() || !XDATServlet.isDatabasePopulateOrUpdateCompleted()) {
            if (!haveLoggedXftInitFailure) {
                log.info("XFT is not initialized. " + skipMessage);
                haveLoggedXftInitFailure = true;
            }
            return null;
        }

        // Since XFT is up, we should be able to connect to the database and read the docker server
        DockerServer dockerServer = null;
        try {
            dockerServer = dockerServerService.getServer();
        } catch (NotFoundException e) {
            // ignored
            log.error("Docker server not found");
        }
        if (dockerServer == null) {
            if (!haveLoggedNoServerInDb) {
                log.info("No docker server has been defined (or enabled) in the database. " + skipMessage);
                haveLoggedNoServerInDb = true;
                haveLoggedXftInitFailure = false;
            }
            return null;
        }

        if (!containerControlApi.canConnect()) {
            if (!haveLoggedDockerConnectFailure) {
                log.info("Cannot ping docker server " + dockerServer.name() + ". " + skipMessage);
                haveLoggedDockerConnectFailure = true;
                haveLoggedXftInitFailure = false;
                haveLoggedNoServerInDb = false;
            }
            return null;
        }

        return dockerServer;
    }

    @Nonnull
    private UpdateReport checkForUpdatesAndThrowEvents(final DockerServer server) {
        // Delegate to backend-specific update methods
        if (server.swarmMode()) {
            return checkForDockerSwarmServiceUpdatesAndThrowEvents(server);
        } else {
            return checkForDockerContainerUpdatesAndThrowEvents(server);
        }

    }

    @Nonnull
    private UpdateReport checkForDockerContainerUpdatesAndThrowEvents(final DockerServer server) {
        final Date lastEventCheckTime = server.lastEventCheckTime();
        final Date since = lastEventCheckTime == null ? new Date(0L) : lastEventCheckTime;

        final Date now = new Date();

        try {
            final List<DockerContainerEvent> events = containerControlApi.getContainerEvents(since, now);

            for (final DockerContainerEvent event : events) {
                if (event.isIgnoreStatus()) {
                    // This occurs on container cleanup, ignore it, we've already finalized at this point
                    log.debug("Skipping docker container event: {}", event);
                    continue;
                }
                log.debug("Throwing docker container event: {}", event);
                eventService.triggerEvent(event);
            }

            dockerServerService.update(server.updateEventCheckTime(now));

            return UpdateReport.singleton(UpdateReportEntry.success());
        } catch (NoDockerServerException e) {
            log.info("Cannot search for Docker container events. No Docker server defined.");
        } catch (DockerServerException e) {
            log.error("Cannot find Docker container events.", e);
        }
        return UpdateReport.singleton(UpdateReportEntry.failure());
    }

    @Nonnull
    private UpdateReport checkForDockerSwarmServiceUpdatesAndThrowEvents(final DockerServer dockerServer) {
        final UpdateReport report = UpdateReport.create();
        //TODO : Optimize this code so that waiting ones are handled first
        for (Container service : containerService.retrieveNonfinalizedServices()) {
            try {
                if (log.isTraceEnabled()) {
                    log.trace("Checking for updates for service {}", service);
                } else {
                    log.debug("Checking for updates for service {} \"{}\".", service.databaseId(), service.serviceId());
                }
                try {
                    // Refresh service status etc. bc it could change while we're processing this list
                    service = containerService.get(service.databaseId());
                    if (containerService.fixWorkflowContainerStatusMismatch(service, Users.getAdminUser())) {
                        log.debug("Service {} \"{}\" had workflow <> status mismatch", service.databaseId(), service.serviceId());
                    } else if (containerService.isFinalizing(service) ||
                            containerService.containerStatusIsTerminal(service)) {
                        log.debug("Service {} \"{}\" no longer unfinalized", service.databaseId(), service.serviceId());
                    } else if (containerService.isWaiting(service)) {
                        throwWaitingEventForService(service);
                    } else {
                        final ServiceTask task = containerControlApi.getTaskForService(dockerServer, service);
                        if (task != null) {
                            throwTaskEventForService(service, task);
                        } else {
                            log.debug("Appears that the task has not been assigned for service {} \"{}\".",
                                    service.databaseId(), service.serviceId());
                        }
                    }
                    report.add(UpdateReportEntry.success(service.serviceId()));
                } catch (ServiceNotFoundException e) {
                    // Service not found despite container being active: throw a restart event
                    log.debug("Cannot find service {} \"{}\".", service.databaseId(), service.serviceId());
                    throwRestartEventForService(service);
                    report.add(UpdateReportEntry.success(service.serviceId()));
                } catch (TaskNotFoundException e) {
                    log.error("Cannot get tasks for service {} \"{}\".", service.databaseId(), service.serviceId());
                    throwLostTaskEventForService(service);
                    report.add(UpdateReportEntry.failure(service.serviceId(), e.getMessage()));
                } catch (DockerServerException e) {
                    log.error("Cannot find server for service {} \"{}\".", service.databaseId(), service.serviceId(), e);
                    report.add(UpdateReportEntry.failure(service.serviceId(), e.getMessage()));
                }
            } catch (Exception e) {
                log.error("Unexpected exception trying to update service {} \"{}\".", service.databaseId(), service.serviceId(), e);
                report.add(UpdateReportEntry.failure(service.serviceId(), e.getMessage()));
            }
        }

        return report.finish();
    }

    private static class UpdateReport {
        private Boolean successful = null;
        private List<UpdateReportEntry> updateReports;

        private UpdateReport() {}

        public static UpdateReport create() {
            final UpdateReport report = new UpdateReport();
            report.updateReports = Lists.newArrayList();
            return report;
        }

        public static UpdateReport singleton(final UpdateReportEntry entry) {
            final UpdateReport report = new UpdateReport();
            report.updateReports = Collections.singletonList(entry);
            return report.finish();
        }

        public void add(final UpdateReportEntry entry) {
            updateReports.add(entry);
        }

        public UpdateReport finish() {
            successful = determineSuccess();
            return this;
        }

        private Boolean determineSuccess() {
            boolean allSucceeded = true;
            boolean allFailed = true;
            for (final UpdateReportEntry entry : updateReports) {
                allSucceeded = allSucceeded && entry.successful;
                allFailed = allFailed && !entry.successful;
            }

            // If either allSucceeded or allFailed is true,
            //   then allSucceeded will tell us if everything was successful or not.
            // If both allSucceeded and allFailed are false, that means some were successful and some not.
            //   So we set overall report success to null.
            return allSucceeded || allFailed ? allSucceeded : null;
        }

    }

    private static class UpdateReportEntry {
        private Boolean successful;
        private String id;
        private String message;

        public static UpdateReportEntry success() {
            final UpdateReportEntry updateReportEntry = new UpdateReportEntry();
            updateReportEntry.successful = true;
            return updateReportEntry;
        }

        public static UpdateReportEntry success(final String id) {
            final UpdateReportEntry updateReportEntry = success();
            updateReportEntry.id = id;
            return updateReportEntry;
        }

        public static UpdateReportEntry failure() {
            final UpdateReportEntry updateReportEntry = new UpdateReportEntry();
            updateReportEntry.successful = false;
            return updateReportEntry;
        }

        public static UpdateReportEntry failure(final String id,
                                                final String message) {
            final UpdateReportEntry updateReportEntry = failure();
            updateReportEntry.id = id;
            updateReportEntry.message = message;
            return updateReportEntry;
        }
    }

    private void throwLostTaskEventForService(@Nonnull final Container service) {
        final ServiceTask task = ServiceTask.builder()
                .serviceId(service.serviceId())
                .taskId(null)
                .nodeId(null)
                .status(TASK_STATE_FAILED)
                .swarmNodeError(true)
                .statusTime(null)
                .message(ServiceTask.swarmNodeErrMsg)
                .err(null)
                .exitCode(null)
                .containerId(service.containerId())
                .build();
        final ServiceTaskEvent serviceTaskEvent = ServiceTaskEvent.create(task, service);
        log.trace("Throwing service task event for service {}.", serviceTaskEvent.service().serviceId());
        eventService.triggerEvent(serviceTaskEvent);
    }

    private void throwRestartEventForService(final Container service) throws ContainerException {
        log.trace("Throwing restart event for service {} \"{}\".", service.databaseId(), service.serviceId());
        ServiceTask lastTask = service.makeTaskFromLastHistoryItem();
        ServiceTask restartTask = lastTask.toBuilder()
                .swarmNodeError(true)
                .message(ServiceTask.swarmNodeErrMsg) //Differentiate from when lastTask went through processEvent
                .build();
        final ServiceTaskEvent restartTaskEvent = ServiceTaskEvent.create(restartTask, service,
                ServiceTaskEvent.EventType.Restart);
        eventService.triggerEvent(restartTaskEvent);
    }

    private void throwTaskEventForService(final Container service, final ServiceTask task) throws DockerServerException, ServiceNotFoundException, TaskNotFoundException {
        final ServiceTaskEvent serviceTaskEvent = ServiceTaskEvent.create(task, service);
        log.trace("Throwing service task event for service {} \"{}\" task \"{}\".", service.databaseId(), service.serviceId(), task.taskId());
        eventService.triggerEvent(serviceTaskEvent);
    }

    private void throwWaitingEventForService(final Container service) throws ContainerException {
        log.trace("Throwing waiting event for service {} \"{}\".", service.databaseId(), service.serviceId());
        final ServiceTaskEvent waitingTaskEvent = ServiceTaskEvent.create(service.makeTaskFromLastHistoryItem(), service,
                ServiceTaskEvent.EventType.Waiting);
        eventService.triggerEvent(waitingTaskEvent);
    }
}
