package org.nrg.containers.events.listeners;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.services.ContainerService;
import org.nrg.containers.services.OrchestrationEntityService;
import org.nrg.containers.utils.ContainerUtils;
import org.nrg.xdat.security.helpers.Users;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xft.event.entities.WorkflowStatusEvent;
import org.nrg.xft.event.persist.PersistentWorkflowI;
import org.nrg.xft.event.persist.PersistentWorkflowUtils;
import org.nrg.xft.security.UserI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.bus.Event;
import reactor.bus.EventBus;
import reactor.fn.Consumer;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static reactor.bus.selector.Selectors.type;

@Slf4j
@Component
public class WorkflowStatusEventOrchestrationListener implements Consumer<Event<WorkflowStatusEvent>> {
    private final ContainerService containerService;
    private final OrchestrationEntityService orchestrationEntityService;
    private final UserManagementServiceI userManagementServiceI;

    @Autowired
    public WorkflowStatusEventOrchestrationListener(final ContainerService containerService,
                                                    final OrchestrationEntityService orchestrationEntityService,
                                                    final UserManagementServiceI userManagementServiceI,
                                                    final EventBus eventBus) {
        this.containerService = containerService;
        this.orchestrationEntityService = orchestrationEntityService;
        this.userManagementServiceI = userManagementServiceI;
        eventBus.on(type(WorkflowStatusEvent.class), this);
    }

    @Override
    public void accept(Event<WorkflowStatusEvent> busEvent) {
        final WorkflowStatusEvent workflowStatusEvent = busEvent.getData();
        final PersistentWorkflowI workflow = workflowStatusEvent.getWorkflow();
        final Long orchestrationId = workflow.getNextStepId() == null ? null : Long.parseLong(workflow.getNextStepId());
        if (!workflow.getStatus().equals(PersistentWorkflowUtils.COMPLETE) || orchestrationId == null) {
            return;
        }

        PersistentWorkflowI newWorkflow = null;
        try {
            final Integer userId = workflowStatusEvent.getUserId();
            final String containerId = workflow.getComments();
            final Container containerOrService = containerService.get(containerId);
            final long wrapperId = containerOrService.wrapperId();
            int stepIdx = Integer.parseInt(StringUtils.defaultIfBlank(workflow.getCurrentStepId(), "0"));
            final Command.CommandWrapper nextWrapper = orchestrationEntityService.findNextWrapper(orchestrationId,
                    stepIdx, wrapperId);
            if (nextWrapper == null) {
                return;
            }

            // Make a new workflow to track any issues
            int nextWrapperIdx = stepIdx + 1;
            final String project = workflow.getExternalid();
            UserI user = userManagementServiceI.getUser(userId);
            newWorkflow = containerService.createContainerWorkflow(workflow.getId(),
                    workflow.getDataType(), nextWrapper.name(), project, user, workflow.getJobid(),
                    orchestrationId, nextWrapperIdx);

            // Determine inputValues (root element)
            Map<String, String> inputValues;
            if (nextWrapper.externalInputs().size() == 1 && containerOrService.getExternalWrapperInputs().size() == 1) {
                // If both are size 1, just put the value from one into the name of the other
                inputValues = new HashMap<>();
                inputValues.put(nextWrapper.externalInputs().get(0).name(),
                        containerOrService.getExternalWrapperInputs().values().toArray()[0].toString());
            } else {
                // try to match them by name
                inputValues = nextWrapper.externalInputs().stream().collect(Collectors.toMap(Command.Input::name,
                        ei -> containerOrService.getExternalWrapperInputs().get(ei.name())));
            }

            containerService.queueResolveCommandAndLaunchContainer(project, nextWrapper.id(), 0L,
                    null, inputValues, user, newWorkflow);
        } catch (Exception e) {
            log.error("Unable to orchestrate containers", e);
            if (newWorkflow != null) {
                ContainerUtils.updateWorkflowStatus(newWorkflow, PersistentWorkflowUtils.FAILED + " (Orchestration)",
                        e.getClass().getName() + ": " + e.getMessage());
            }
        }
    }
}
