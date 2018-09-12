package org.nrg.containers.events.listeners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Maps;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.events.model.ScanArchiveEventToLaunchCommands;
import org.nrg.containers.exceptions.*;
import org.nrg.containers.model.CommandEventMapping;
import org.nrg.containers.model.xnat.Scan;
import org.nrg.containers.services.CommandEventMappingService;
import org.nrg.containers.services.ContainerService;
import org.nrg.framework.exceptions.NotFoundException;
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
public class ScanArchiveListenerAndCommandLauncher implements Consumer<Event<ScanArchiveEventToLaunchCommands>> {
    private static final String EVENT_ID = "ScanArchived";

    private ObjectMapper mapper;
    private ContainerService containerService;
    private CommandEventMappingService commandEventMappingService;
    private UserManagementServiceI userManagementService;

    @Autowired
    public ScanArchiveListenerAndCommandLauncher(final EventBus eventBus,
                                                 final ObjectMapper mapper,
                                                 final ContainerService containerService,
                                                 final CommandEventMappingService commandEventMappingService,
                                                 final UserManagementServiceI userManagementService) {
 //       eventBus.on(type(ScanArchiveEventToLaunchCommands.class), this);
        this.mapper = mapper;
        this.containerService = containerService;
        this.commandEventMappingService = commandEventMappingService;
        this.userManagementService = userManagementService;
    }


    @Override
    public void accept(Event<ScanArchiveEventToLaunchCommands> event) {
        final ScanArchiveEventToLaunchCommands scanArchiveEventToLaunchCommands = event.getData();

        // Find commands defined for this event type
        final List<CommandEventMapping> commandEventMappings = commandEventMappingService.findByEventType(EVENT_ID);

        if (commandEventMappings != null && !commandEventMappings.isEmpty()) {
            final Scan scan = scanArchiveEventToLaunchCommands.scan();
            final String eventProjectId = scanArchiveEventToLaunchCommands.project();

            for (CommandEventMapping commandEventMapping: commandEventMappings) {
                final Long commandId = commandEventMapping.getCommandId();
                final String wrapperName = commandEventMapping.getXnatCommandWrapperName();
                final String subscriptionProjectId = commandEventMapping.getProjectId();

                if (subscriptionProjectId == null || subscriptionProjectId.isEmpty() || subscriptionProjectId.equals(eventProjectId)) {

                    final Map<String, String> inputValues = Maps.newHashMap();

                    String scanString = scan.getUri();
                    try {
                        scanString = mapper.writeValueAsString(scan);
                    } catch (JsonProcessingException e) {
                        log.error(String.format("Could not serialize Scan %s to json.", scan), e);
                    }
                    inputValues.put("scan", scanString);
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
                                    scanArchiveEventToLaunchCommands.user().getLogin(),
                                    subscriptionUser.getLogin()
                            );
                            log.info(message);
                            if (log.isDebugEnabled()) {
                                log.debug("Runtime parameter values:");
                                for (final Map.Entry<String, String> paramEntry : inputValues.entrySet()) {
                                    log.debug(paramEntry.getKey() + ": " + paramEntry.getValue());
                                }
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
