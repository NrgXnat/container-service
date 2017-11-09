package org.nrg.containers.events.listeners;

import org.nrg.framework.services.NrgEventService;
import org.nrg.xft.event.entities.WorkflowStatusEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.bus.Event;
import reactor.bus.EventBus;
import reactor.fn.Consumer;

@Service
public class SessionTransferredListener implements Consumer<Event<WorkflowStatusEvent>> {
    private static final Logger log = LoggerFactory.getLogger(SessionTransferredListener.class);

    private final NrgEventService eventService;

    @Autowired
    public SessionTransferredListener(final EventBus eventBus, final NrgEventService eventService) {
//        eventBus.on(type(WorkflowStatusEvent.class), this);
        this.eventService = eventService;
    }

    //*
    // Translate "Transferred" workflow event into SessionArchivedEvent for workflow events containing Session type
    //*
    @Override
    public void accept(Event<WorkflowStatusEvent> event) {
//        final WorkflowStatusEvent wfsEvent = event.getData();
//
//        if (StringUtils.equals(wfsEvent.getEventId(), "Transferred") && wfsEvent.getEntityType().contains("Session")) {
//            try {
//                final UserI user = Users.getUser(wfsEvent.getUserId());
//                final XnatImagesessiondata session = XnatImagesessiondata.getXnatImagesessiondatasById(wfsEvent.getEntityId(), user, true);
//                eventService.triggerEvent(SessionArchiveEvent.create(session, user));
//            } catch (UserNotFoundException e) {
//                log.warn("The specified user was not found: {}", wfsEvent.getUserId());
//            } catch (UserInitException e) {
//                log.error("An error occurred trying to retrieve the user for a workflow event: " + wfsEvent.getUserId(), e);
//            }
//        }
    }
}
