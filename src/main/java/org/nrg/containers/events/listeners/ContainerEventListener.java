package org.nrg.containers.events.listeners;

import lombok.extern.slf4j.Slf4j;
import org.nrg.containers.config.ContainersConfig;
import org.nrg.containers.events.model.ContainerEvent;
import org.nrg.containers.services.ContainerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ContainerEventListener {
    private final ContainerService containerService;

    /**
     * Constructor for the ContainerEventListener.
     *
     * @param containerService The container service
     */
    @Autowired
    public ContainerEventListener(final ContainerService containerService) {
        this.containerService = containerService;
    }

    @JmsListener(containerFactory = ContainersConfig.EVENT_HANDLING_QUEUE_LISTENER_FACTORY,
                 destination = ContainerEvent.QUEUE)
    public void onRequest(final ContainerEvent event) {
        try {
            log.info("Got container event: {}", event);
            containerService.processEvent(event);
        } catch (Throwable e) {
            log.error("There was a problem handling the docker event.", e);
        }
    }
}
