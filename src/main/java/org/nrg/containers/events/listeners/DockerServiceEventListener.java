package org.nrg.containers.events.listeners;

import lombok.extern.slf4j.Slf4j;
import org.nrg.containers.events.model.ServiceTaskEvent;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.auto.ServiceTask;
import org.nrg.containers.services.ContainerService;
import org.nrg.xdat.security.helpers.Users;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.bus.Event;
import reactor.bus.EventBus;
import reactor.fn.Consumer;

import java.util.HashSet;

import static reactor.bus.selector.Selectors.type;

@Slf4j
@Component
public class DockerServiceEventListener implements Consumer<Event<ServiceTaskEvent>> {
    private ContainerService containerService;
    private HashSet<Long> currentlyProcessing = new HashSet<>();

    @Autowired
    public DockerServiceEventListener(final EventBus eventBus) {
        eventBus.on(type(ServiceTaskEvent.class), this);
    }

    private synchronized boolean addToQueue(Long serviceDbId) {
        return currentlyProcessing.add(serviceDbId);
    }
    private synchronized void removeFromQueue(Long serviceDbId) {
        currentlyProcessing.remove(serviceDbId);
    }

    @Override
    public void accept(final Event<ServiceTaskEvent> serviceTaskEvent) {
        final ServiceTaskEvent event = serviceTaskEvent.getData();
        long serviceDbId = event.service().databaseId();
        final ServiceTaskEvent.EventType eventType = event.eventType();
        if (log.isTraceEnabled()) {
            log.trace("Processing service task event {}", event);
        } else {
            log.debug("Processing service task event type \"{}\" for service {} \"{}\"",
                    eventType, event.service().databaseId(), event.service().serviceId()
            );
        }
        if (eventType == null) {
            log.error("Skipping event with null type for service {}", serviceDbId);
            return;
        }
        if (!addToQueue(serviceDbId)) {
            log.debug("Skipping event because service {} still being processed from last event", serviceDbId);
            return;
        }
        try {
            switch(eventType) {
                case Waiting:
                    log.debug("Finalizing service");
                    Container service = event.service();
                    ServiceTask task = event.task();
                    String status;
                    // If we don't have a task or status, consider it a failure
                    boolean statusIsSucceses = task != null && (status = task.status()) != null &&
                            ServiceTask.isSuccessfulStatus(status);
                    containerService.queueFinalize(service.exitCode(), statusIsSucceses, service, Users.getAdminUser());
                    break;
                case Restart:
                case ProcessTask:
                    containerService.processEvent(event);
                    break;
                default:
                    throw new Exception("Unknown type of service task event: " + eventType);
            }
        } catch (Throwable e) {
            log.error("There was a problem handling the docker service task event.", e);
        }
        removeFromQueue(serviceDbId);
    }

    @Autowired
    public void setContainerService(final ContainerService containerService) {
        this.containerService = containerService;
    }
}
