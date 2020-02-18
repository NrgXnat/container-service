/*
 * xnatx-clara: org.nrg.xnatx.plugins.clara.services.impl.TrainingSessionLaunchListener
 * XNAT http://www.xnat.org
 * Copyright (c) 2019, Washington University School of Medicine
 * All Rights Reserved
 *
 * Released under the Simplified BSD.
 */

package org.nrg.containers.services.impl;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.auto.CommandSummaryForContext;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.services.CommandService;
import org.nrg.containers.services.ContainerService;
import org.nrg.xdat.security.user.XnatUserProvider;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.helpers.processing.handlers.AbstractProcessingOperationHandler;
import org.nrg.xnat.helpers.processing.handlers.Processes;
import org.nrg.xnat.services.messaging.processing.ProcessingOperationRequestData;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@Processes(ProcessingOperationRequestData.class)
@Getter
@Accessors(prefix = "_")
@Slf4j
public class LaunchContainerRequestHandler extends AbstractProcessingOperationHandler {
    public LaunchContainerRequestHandler(final ContainerService _containerService, final CommandService _commandService, final XnatUserProvider provider) {
        super(provider);
        this._containerService = _containerService;
        this._commandService = _commandService;
    }

    @Override
    public void execute(final ProcessingOperationRequestData data) {
        final ProcessingOperationRequestData containerProcessingRequest = data;
        try {
            // Processing request params
            final UserI user = getUser(data.getUsername());
            Class<? extends ProcessingOperationRequestData> aClass = containerProcessingRequest.getClass();
            String processingId = containerProcessingRequest.getProcessingId();
            Map<String, String> parameters = containerProcessingRequest.getParameters();

            final Long wrapperId = Long.parseLong(processingId);

            // Validate container and input
            Container container = null;
            String project = parameters.get("project");
            List<CommandSummaryForContext> available = _commandService.available(project,"clara:trainSession", user);
            if(available != null &&
                    available.size() > 0 &&
                    wrapperId != null &&
                    available.stream().filter(cm -> wrapperId.equals(cm.wrapperId())).findFirst().isPresent()) {

                Command.CommandWrapper wrapper = _commandService.getWrapper(wrapperId);
                ImmutableList<Command.CommandWrapperExternalInput> commandWrapperExternalInputs = wrapper.externalInputs();

                // resolve wrapper inputs
                final Map<String, String> inputValues = Maps.<String, String>newHashMap();
                for (Command.CommandWrapperExternalInput wrapperInput : commandWrapperExternalInputs) {
                    if (parameters.keySet().contains(wrapperInput.name())) {
                        String inputValue = parameters.get(wrapperInput.name());
                        inputValues.put(wrapperInput.name(), inputValue);
                    } else {
                        log.error("Missing external input {} value for container launch.", wrapperInput.name());
                    }
                }

                try {
                    // Launch container processing
                    container = _containerService.resolveCommandAndLaunchContainer(wrapperId, inputValues, user);
                    if (log.isDebugEnabled() && container != null) {
                        log.debug("Launched container in response to processing request");
                        log.debug(container.toString());
                    } else if (container == null) {
                        log.error("Failed to create container in response to processing request.");
                    }
                } catch (Throwable e) {
                    log.error("Failed to launch container in response to processing request.");
                    log.error(e.getMessage());
                    if (container != null) {
                        log.error(container.toString());
                    }
                }
            } else {
                log.debug("No container wrapper ID {} found to process clara:trainSession request.", processingId);
            }

        } catch (Throwable e) {
            log.error("Unable to process the request: " + containerProcessingRequest.getProcessingId());
        }
    }

    @Override
    public boolean handles(Class<? extends ProcessingOperationRequestData> requestType) {
        return super.handles(requestType);
    }

    private final ContainerService _containerService;
    private final CommandService _commandService;
}
