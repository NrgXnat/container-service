package org.nrg.containers.events.listeners;

import lombok.extern.slf4j.Slf4j;
import org.nrg.containers.config.ContainersConfig;
import org.nrg.containers.events.model.ContainerEvent;
import org.nrg.containers.jms.utils.QueueUtils;
import org.nrg.containers.services.ContainerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;
import reactor.bus.Event;
import reactor.bus.EventBus;
import reactor.fn.Consumer;

import static reactor.bus.selector.Selectors.type;

@Slf4j
@Component
public class ContainerEventListener implements Consumer<Event<ContainerEvent>> {
    private final ContainerService containerService;
    private final JmsTemplate      template;

    /**
     * Constructor for the ContainerEventListener.
     *
     * @param eventBus         The event bus
     * @param containerService The container service
     *
     * @deprecated This should be used only for unit testing.
     */
    @SuppressWarnings("DeprecatedIsStillUsed")
    public ContainerEventListener(final EventBus eventBus,
                                  final ContainerService containerService) {
        this(eventBus, containerService, null);
    }

    @Autowired
    public ContainerEventListener(final EventBus eventBus,
                                  final ContainerService containerService,
                                  final JmsTemplate template) {
        eventBus.on(type(ContainerEvent.class), this);
        this.containerService = containerService;
        this.template         = template;
    }

    @Override
    public void accept(final Event<ContainerEvent> containerEventEvent) {
        if (template != null) {
            QueueUtils.sendJmsRequest(template, ContainerEvent.QUEUE, containerEventEvent.getData());
        }
    }

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
