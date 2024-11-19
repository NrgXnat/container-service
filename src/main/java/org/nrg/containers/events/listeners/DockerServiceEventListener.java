package org.nrg.containers.events.listeners;

import lombok.extern.slf4j.Slf4j;
import org.nrg.containers.config.ContainersConfig;
import org.nrg.containers.events.model.ServiceTaskEvent;
import org.nrg.containers.jms.utils.QueueUtils;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.auto.ServiceTask;
import org.nrg.containers.services.ContainerService;
import org.nrg.xdat.XDAT;
import org.nrg.xdat.security.helpers.Users;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;
import reactor.bus.Event;
import reactor.bus.EventBus;
import reactor.fn.Consumer;

import java.util.HashSet;

import static reactor.bus.selector.Selectors.type;

@Slf4j
@Component
public class DockerServiceEventListener implements Consumer<Event<ServiceTaskEvent>> {
    private final HashSet<Long>    currentlyProcessing = new HashSet<>();

    private final ContainerService containerService;
    private final JmsTemplate      template;

    /**
     * Constructor for the DockerServiceEventListener.
     *
     * @param eventBus         The event bus
     * @param containerService The container service
     *
     * @deprecated This should be used only for unit testing.
     */
    @SuppressWarnings("DeprecatedIsStillUsed")
    public DockerServiceEventListener(final EventBus eventBus,
                                      final ContainerService containerService) {
        this(eventBus, containerService, null);
    }

    @Autowired
    public DockerServiceEventListener(final EventBus eventBus,
                                      final ContainerService containerService,
                                      final JmsTemplate template) {
        eventBus.on(type(ServiceTaskEvent.class), this);
        this.containerService = containerService;
        this.template = template;
    }

    @Override
    public void accept(final Event<ServiceTaskEvent> serviceTaskEvent) {
        QueueUtils.sendJmsRequest(template, ServiceTaskEvent.QUEUE, serviceTaskEvent.getData());
    }

    @JmsListener(containerFactory = ContainersConfig.EVENT_HANDLING_QUEUE_LISTENER_FACTORY,
                 destination = ServiceTaskEvent.QUEUE)
    public void onRequest(final ServiceTaskEvent event) {
        long                             serviceDbId = event.service().databaseId();
        final ServiceTaskEvent.EventType eventType   = event.eventType();
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
            switch (eventType) {
                case Waiting:
                    log.debug("Finalizing service");
                    Container service = event.service();
                    ServiceTask task = event.task();
                    String status;
                    // If we don't have a task or status, consider it a failure
                    boolean statusIsSuccessful = task != null && (status = task.status()) != null &&
                                                 ServiceTask.isSuccessfulStatus(status);
                    containerService.queueFinalize(service.exitCode(), statusIsSuccessful, service, Users.getAdminUser());
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

    private synchronized boolean addToQueue(Long serviceDbId) {
        return currentlyProcessing.add(serviceDbId);
    }

    private synchronized void removeFromQueue(Long serviceDbId) {
        currentlyProcessing.remove(serviceDbId);
    }
}
