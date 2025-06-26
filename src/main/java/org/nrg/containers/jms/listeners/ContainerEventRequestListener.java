package org.nrg.containers.jms.listeners;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nrg.containers.config.ContainersConfig;
import org.nrg.containers.events.model.ContainerEvent;
import org.nrg.containers.exceptions.ContainerException;
import org.nrg.containers.jms.requests.ContainerFinalizingRequest;
import org.nrg.containers.jms.utils.QueueUtils;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.services.ContainerService;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xdat.security.user.exceptions.UserInitException;
import org.nrg.xdat.security.user.exceptions.UserNotFoundException;
import org.nrg.xft.security.UserI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ContainerEventRequestListener {
    private final ContainerService containerService;

    @JmsListener(containerFactory = ContainersConfig.EVENT_HANDLING_QUEUE_LISTENER_FACTORY,
                 destination = ContainerEvent.QUEUE)
    public void onRequest(final ContainerEvent event) {
        try {
            containerService.processEvent(event);
        } catch (Throwable e) {
            log.error("There was a problem handling the docker event.", e);
        }
    }
}
