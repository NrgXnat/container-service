package org.nrg.containers.events.listeners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Maps;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.events.model.ScanArchiveEventToLaunchCommands;
import org.nrg.containers.events.model.SessionArchiveEvent;
import org.nrg.containers.exceptions.*;
import org.nrg.containers.model.CommandEventMapping;
import org.nrg.containers.model.xnat.Scan;
import org.nrg.containers.model.xnat.Session;
import org.nrg.containers.services.CommandEventMappingService;
import org.nrg.containers.services.ContainerService;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.framework.services.NrgEventService;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xdat.security.user.exceptions.UserInitException;
import org.nrg.xdat.security.user.exceptions.UserNotFoundException;
import org.nrg.xft.security.UserI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.bus.Event;
import reactor.bus.EventBus;
import reactor.fn.Consumer;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@SuppressWarnings("unused")
public class SessionArchiveListenerAndCommandLauncher implements Consumer<Event<SessionArchiveEvent>> {
    private static final String EVENT_ID = "SessionArchived";

    private ObjectMapper mapper;
    private ContainerService containerService;
    private CommandEventMappingService commandEventMappingService;
    private NrgEventService eventService;
    private UserManagementServiceI userManagementService;

    @Autowired
    public SessionArchiveListenerAndCommandLauncher(final EventBus eventBus,
                                                    final ObjectMapper mapper,
                                                    final ContainerService containerService,
                                                    final CommandEventMappingService commandEventMappingService,
                                                    final NrgEventService eventService,
                                                    final UserManagementServiceI userManagementService) {
 //       eventBus.on(type(SessionArchiveEvent.class), this);
        this.mapper = mapper;
        this.containerService = containerService;
        this.commandEventMappingService = commandEventMappingService;
        this.eventService = eventService;
        this.userManagementService = userManagementService;
    }

    @Override
    public void accept(Event<SessionArchiveEvent> event) {
        final SessionArchiveEvent sessionArchivedEvent = event.getData();
        final Session session = new Session(sessionArchivedEvent.session());

        // Fire ScanArchiveEvent for each contained scan
        for (final Scan scan : session.getScans()) {
            eventService.triggerEvent(ScanArchiveEventToLaunchCommands.create(scan, sessionArchivedEvent.session().getProject(), sessionArchivedEvent.user()));
        }

        // Find commands defined for this event type
        List<CommandEventMapping> commandEventMappings = commandEventMappingService.findByEventType(EVENT_ID);

        if (commandEventMappings != null && !commandEventMappings.isEmpty()) {
            for (CommandEventMapping commandEventMapping : commandEventMappings) {
                final Long commandId = commandEventMapping.getCommandId();
                final String wrapperName = commandEventMapping.getXnatCommandWrapperName();
                final String subscriptionProjectId = commandEventMapping.getProjectId();

                final String sessionProjectId = sessionArchivedEvent.session().getProject();
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
                                    sessionArchivedEvent.user().getLogin(),
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
                        if (subscriptionProjectId != null && !subscriptionProjectId.isEmpty()) {
                            containerService.resolveCommandAndLaunchContainer(subscriptionProjectId, commandId, wrapperName, inputValues, subscriptionUser);
                        } else {
                            containerService.resolveCommandAndLaunchContainer(commandId, wrapperName, inputValues, subscriptionUser);
                        }
                    } catch (UserNotFoundException | UserInitException e) {
                        log.error(String.format("Error launching command %d. Could not find or Init subscription owner: %s", commandId, commandEventMapping.getSubscriptionUserName()), e);
                    } catch (NotFoundException | CommandResolutionException | NoDockerServerException | DockerServerException | ContainerException | UnauthorizedException e) {
                        log.error("Error launching command " + commandId, e);
                    }
                }
            }
        }
    }


}
