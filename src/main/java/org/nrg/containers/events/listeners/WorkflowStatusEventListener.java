package org.nrg.containers.events.listeners;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.events.model.BulkLaunchEvent;
import org.nrg.framework.services.NrgEventService;
import org.nrg.xft.event.entities.WorkflowStatusEvent;
import org.nrg.xft.event.persist.PersistentWorkflowI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.bus.Event;
import reactor.bus.EventBus;
import reactor.fn.Consumer;

import static reactor.bus.selector.Selectors.type;

@Slf4j
@Component
public class WorkflowStatusEventListener implements Consumer<Event<WorkflowStatusEvent>> {
    private final NrgEventService eventService;

    @Autowired
    public WorkflowStatusEventListener(final NrgEventService eventService,
                                       final EventBus eventBus) {
        this.eventService = eventService;
        eventBus.on(type(WorkflowStatusEvent.class), this);
    }

    @Override
    public void accept(Event<WorkflowStatusEvent> busEvent) {
        final PersistentWorkflowI workflow = busEvent.getData().getWorkflow();
        final String bulkLaunchId = workflow.getJobid();
        if (StringUtils.isBlank(bulkLaunchId)) {
            return;
        }
        eventService.triggerEvent(new BulkLaunchEvent(bulkLaunchId, workflow.getWorkflowId(), workflow.getId(),
                workflow.getStatus(), workflow.getDetails(), workflow.getComments()));
    }
}
