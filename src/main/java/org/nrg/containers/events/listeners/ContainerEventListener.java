package org.nrg.containers.events.listeners;

import lombok.extern.slf4j.Slf4j;
import org.nrg.containers.events.model.ContainerEvent;
import org.nrg.containers.services.ContainerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.bus.Event;
import reactor.bus.EventBus;
import reactor.fn.Consumer;

import java.util.concurrent.ExecutorService;

import static reactor.bus.selector.Selectors.type;

@Slf4j
@Component
public class ContainerEventListener implements Consumer<Event<ContainerEvent>> {
    private final ContainerService containerService;
    private final ExecutorService executorService;

    @Autowired
    public ContainerEventListener(final EventBus eventBus,
                                  final ContainerService containerService,
                                  final ExecutorService executorService) {
        eventBus.on(type(ContainerEvent.class), this);
        this.containerService = containerService;
        this.executorService = executorService;
    }

    @Override
    public void accept(final Event<ContainerEvent> containerEventEvent) {
        executorService.submit(() -> processEvent(containerEventEvent.getData()));
    }

    private void processEvent(final ContainerEvent event) {
        try {
            containerService.processEvent(event);
        } catch (Throwable e) {
            log.error("There was a problem handling the docker event.", e);
        }
    }
}
