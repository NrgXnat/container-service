package org.nrg.containers.events.listeners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.events.model.ScanArchiveEventToLaunchCommands;
import org.nrg.containers.events.model.SessionMergeOrArchiveEvent;
import org.nrg.containers.exceptions.CommandResolutionException;
import org.nrg.containers.exceptions.ContainerException;
import org.nrg.containers.exceptions.DockerServerException;
import org.nrg.containers.exceptions.NoDockerServerException;
import org.nrg.containers.exceptions.UnauthorizedException;
import org.nrg.containers.model.CommandEventMapping;
import org.nrg.containers.model.xnat.Scan;
import org.nrg.containers.model.xnat.Session;
import org.nrg.containers.services.CommandEventMappingService;
import org.nrg.containers.services.ContainerService;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.framework.services.NrgEventServiceI;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xdat.security.user.exceptions.UserInitException;
import org.nrg.xdat.security.user.exceptions.UserNotFoundException;
import org.nrg.xft.event.persist.PersistentWorkflowI;
import org.nrg.xft.security.UserI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.bus.Event;
import reactor.bus.EventBus;
import reactor.fn.Consumer;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static reactor.bus.selector.Selectors.type;

@Slf4j
@Service
@SuppressWarnings("unused")
public class SessionArchiveListenerAndCommandLauncher implements Consumer<Event<SessionMergeOrArchiveEvent>> {
    public static final String SESSION_ARCHIVED_EVENT = "SessionArchived";
    public static final String SESSION_MERGED_EVENT = "Merged";
    public static final Map<String, String> WORKFLOW_TO_EVENT_ID = Maps.newHashMap(ImmutableMap.of(
            "Transferred", SESSION_ARCHIVED_EVENT, SESSION_MERGED_EVENT, SESSION_MERGED_EVENT));
    private final ObjectMapper mapper;
    private final ContainerService containerService;
    private final CommandEventMappingService commandEventMappingService;
    private final NrgEventServiceI eventService;
    private final UserManagementServiceI userManagementService;
    private final ExecutorService executorService;

    @Autowired
    public SessionArchiveListenerAndCommandLauncher(final EventBus eventBus,
                                                    final ObjectMapper mapper,
                                                    final ContainerService containerService,
                                                    final CommandEventMappingService commandEventMappingService,
                                                    final NrgEventServiceI eventService,
                                                    final UserManagementServiceI userManagementService,
                                                    final ExecutorService executorService) {
        eventBus.on(type(SessionMergeOrArchiveEvent.class), this);
        this.mapper = mapper;
        this.containerService = containerService;
        this.commandEventMappingService = commandEventMappingService;
        this.eventService = eventService;
        this.userManagementService = userManagementService;
        this.executorService = executorService;
    }

    @Override
    public void accept(Event<SessionMergeOrArchiveEvent> event) {
        executorService.execute(() -> processEvent(event.getData()));
    }

    private void processEvent(final SessionMergeOrArchiveEvent sessionArchivedOrMergedEvent) {

        // Skip everything if no entries are found in the Command Automation table
        final List<CommandEventMapping> allCommandEventMappings = commandEventMappingService.getAll();
        if (allCommandEventMappings == null || allCommandEventMappings.isEmpty()){
            return;
        }

        final Session session = new Session(sessionArchivedOrMergedEvent.session(), true, Collections.emptySet());
        final String eventId = sessionArchivedOrMergedEvent.eventId();

        if (eventId.equals(SESSION_ARCHIVED_EVENT)) {
            // Fire ScanArchiveEvent for each contained scan
            for (final Scan scan : session.getScans()) {
                eventService.triggerEvent(ScanArchiveEventToLaunchCommands.create(scan, sessionArchivedOrMergedEvent.session().getProject(), sessionArchivedOrMergedEvent.user()));
            }
        }

        // Find commands defined for this event type
        List<CommandEventMapping> commandEventMappings = commandEventMappingService.findByEventType(eventId);

        if (commandEventMappings != null && !commandEventMappings.isEmpty()) {
            for (CommandEventMapping commandEventMapping : commandEventMappings) {
                final Long commandId = commandEventMapping.getCommandId();
                final String wrapperName = commandEventMapping.getXnatCommandWrapperName();
                final String subscriptionProjectId = commandEventMapping.getProjectId();

                final String sessionProjectId = sessionArchivedOrMergedEvent.session().getProject();
                // Allow action to run if subscriptionProjectId is null, empty, or matches sessionProjectId
                if (subscriptionProjectId == null || subscriptionProjectId.isEmpty() || subscriptionProjectId.equals(sessionProjectId)) {
                    final Map<String, String> inputValues = Maps.newHashMap();
                    String sessionString = session.getUri();
                    try {
                        sessionString = mapper.writeValueAsString(session);
                    } catch (JsonProcessingException e) {
                        log.error(String.format("Could not serialize Session %s to json.", session), e);
                    }
                    inputValues.put("session", sessionString);
                    try {
                        final UserI subscriptionUser = userManagementService.getUser(commandEventMapping.getSubscriptionUserName());
                        if (log.isInfoEnabled()) {
                            final String wrapperMessage = StringUtils.isNotBlank(wrapperName) ?
                                    String.format("wrapper \"%s\"", wrapperName) :
                                    "identity wrapper";
                            final String message = String.format(
                                    "Launching command %s, %s, for user \"%s\" as \"%s\"",
                                    commandId,
                                    wrapperMessage,
                                    sessionArchivedOrMergedEvent.user().getLogin(),
                                    subscriptionUser.getLogin()
                            );
                            log.info(message);
                        }
                        if (log.isDebugEnabled()) {
                            log.debug("Runtime parameter values:");
                            for (final Map.Entry<String, String> paramEntry : inputValues.entrySet()) {
                                log.debug(paramEntry.getKey() + ": " + paramEntry.getValue());
                            }
                        }
                        PersistentWorkflowI workflow = containerService.createContainerWorkflow(session.getUri(),
                                session.getXsiType(), wrapperName, subscriptionProjectId, subscriptionUser);
                        containerService.queueResolveCommandAndLaunchContainer(subscriptionProjectId, 0L,
                                commandId, wrapperName, inputValues, subscriptionUser, workflow);
                    } catch (UserNotFoundException | UserInitException e) {
                        log.error("Error launching command {}. Could not find or Init subscription owner: {}", commandId, commandEventMapping.getSubscriptionUserName(), e);
                    } catch (NotFoundException | CommandResolutionException | NoDockerServerException | DockerServerException | ContainerException | UnauthorizedException e) {
                        log.error("Error launching command {}", commandId, e);
                    } catch (Exception e) {
                        log.error("Error queueing launching command {}", commandId, e);
                    }
                }
            }
        }
    }


}
