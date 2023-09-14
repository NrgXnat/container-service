package org.nrg.containers.events.listeners;

import lombok.extern.slf4j.Slf4j;
import org.nrg.containers.events.model.SessionMergeOrArchiveEvent;
import org.nrg.framework.services.NrgEventServiceI;
import org.nrg.xdat.om.XnatImagesessiondata;
import org.nrg.xdat.security.helpers.Users;
import org.nrg.xdat.security.user.exceptions.UserInitException;
import org.nrg.xdat.security.user.exceptions.UserNotFoundException;
import org.nrg.xft.event.entities.WorkflowStatusEvent;
import org.nrg.xft.security.UserI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.bus.Event;
import reactor.bus.EventBus;
import reactor.fn.Consumer;

import java.util.concurrent.ExecutorService;

import static reactor.bus.selector.Selectors.type;

@Slf4j
@Service
public class SessionTransferredListener implements Consumer<Event<WorkflowStatusEvent>> {

    private final NrgEventServiceI eventService;
    private final ExecutorService executorService;

    @Autowired
    public SessionTransferredListener(final EventBus eventBus,
                                      final NrgEventServiceI eventService,
                                      final ExecutorService executorService) {
        eventBus.on(type(WorkflowStatusEvent.class), this);
        this.eventService = eventService;
        this.executorService = executorService;
    }

    @Override
    public void accept(Event<WorkflowStatusEvent> event) {
        executorService.execute(() -> processEvent(event.getData()));
    }

    //*
    // Translate "Transferred" or "Merged" workflow event into SessionArchivedOrMergedEvent for workflow events containing Session type
    //*
    private void processEvent(final WorkflowStatusEvent wfsEvent) {
        String entityType = wfsEvent.getEntityType();
        if (entityType != null && entityType.contains("Session")) {
            final String eventId = wfsEvent.getEventId();
            if (SessionArchiveListenerAndCommandLauncher.WORKFLOW_TO_EVENT_ID.containsKey(eventId)) {
                try {
                    final UserI user = Users.getUser(wfsEvent.getUserId());
                    final XnatImagesessiondata session = XnatImagesessiondata.getXnatImagesessiondatasById(wfsEvent.getEntityId(), user, true);
                    eventService.triggerEvent(SessionMergeOrArchiveEvent.create(session, user,
                            SessionArchiveListenerAndCommandLauncher.WORKFLOW_TO_EVENT_ID.get(eventId)));
                } catch (UserNotFoundException e) {
                    log.warn("The specified user was not found: {}", wfsEvent.getUserId());
                } catch (UserInitException e) {
                    log.error("An error occurred trying to retrieve the user for a workflow event: " + wfsEvent.getUserId(), e);
                }
            }
        }
    }
}
