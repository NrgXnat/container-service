package org.nrg.containers.events.listeners;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.config.ContainersConfig;
import org.nrg.containers.events.model.SessionMergeOrArchiveEvent;
import org.nrg.containers.jms.utils.QueueUtils;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.orchestration.auto.Orchestration;
import org.nrg.containers.services.ContainerService;
import org.nrg.containers.services.OrchestrationService;
import org.nrg.containers.utils.ContainerUtils;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xdat.om.XnatImagesessiondata;
import org.nrg.xdat.security.helpers.Users;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xdat.security.user.exceptions.UserInitException;
import org.nrg.xdat.security.user.exceptions.UserNotFoundException;
import org.nrg.xft.event.entities.WorkflowStatusEvent;
import org.nrg.xft.event.persist.PersistentWorkflowI;
import org.nrg.xft.event.persist.PersistentWorkflowUtils;
import org.nrg.xft.security.UserI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsTemplate;
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
public class ContainerServiceWorkflowStatusEventListener implements Consumer<Event<WorkflowStatusEvent>> {
    public static final String QUEUE = "containerServiceWorkflowStatusEventQueue";

    private final ContainerService       containerService;
    private final OrchestrationService   orchestrationService;
    private final UserManagementServiceI userManagementServiceI;
    private final JmsTemplate            template;

    @Autowired
    public ContainerServiceWorkflowStatusEventListener(final ContainerService containerService,
                                                       final OrchestrationService orchestrationService,
                                                       final UserManagementServiceI userManagementServiceI,
                                                       final EventBus eventBus,
                                                       final JmsTemplate template) {
        this.containerService       = containerService;
        this.orchestrationService   = orchestrationService;
        this.userManagementServiceI = userManagementServiceI;
        this.template               = template;
        eventBus.on(type(WorkflowStatusEvent.class), this);
    }

    @Override
    public void accept(final Event<WorkflowStatusEvent> event) {
        QueueUtils.sendJmsRequest(template, QUEUE, event.getData());
    }

    @JmsListener(containerFactory = ContainersConfig.EVENT_HANDLING_QUEUE_LISTENER_FACTORY,
                 destination = QUEUE)
    public void onRequest(final WorkflowStatusEvent event) {
        final PersistentWorkflowI workflow = event.getWorkflow();
        if (workflow.getNextStepId() != null) {
            boolean process = false;
            if (workflow.getStatus().equals(PersistentWorkflowUtils.COMPLETE)) {
                process = true;
            } else if (workflow.getStatus().startsWith(PersistentWorkflowUtils.FAILED)) {
                final String orchestrationIdStr = workflow.getNextStepId();
                try {
                    Orchestration orchestration = orchestrationService.retrieve(Long.parseLong(orchestrationIdStr));
                    if (null != orchestration && !orchestration.isHaltOnCommandFailure()) {
                        process = true;
                    }
                } catch(NotFoundException | NumberFormatException e) {
                    log.debug("Orchestration {} could not be found from workflow {}", orchestrationIdStr, workflow.getId(), e);
                }
            }
            if (process) {
                processNextStep(event, workflow);
            }
        }

        final String entityType = event.getEntityType();
        if (StringUtils.contains(entityType, "Session")) {
            processSessionWorkflowEvent(event);
        }
    }

    private void processSessionWorkflowEvent(final WorkflowStatusEvent event) {
        final String eventId = event.getEventId();
        if (SessionArchiveListenerAndCommandLauncher.WORKFLOW_TO_EVENT_ID.containsKey(eventId)) {
            try {
                final UserI user = Users.getUser(event.getUserId());
                QueueUtils.sendJmsRequest(template,
                                          SessionMergeOrArchiveEvent.QUEUE,
                                          SessionMergeOrArchiveEvent.create(XnatImagesessiondata.getXnatImagesessiondatasById(event.getEntityId(), user, true),
                                                                            user,
                                                                            SessionArchiveListenerAndCommandLauncher.WORKFLOW_TO_EVENT_ID.get(eventId)));
            } catch (UserNotFoundException e) {
                log.warn("The specified user was not found: {}", event.getUserId());
            } catch (UserInitException e) {
                log.error("An error occurred trying to retrieve the user for a workflow event: {}", event.getUserId(), e);
            }
        }
    }

    private void processNextStep(final WorkflowStatusEvent event, final PersistentWorkflowI workflow) {
        final long orchestrationId = Long.parseLong(workflow.getNextStepId());

        PersistentWorkflowI newWorkflow = null;
        try {
            final Integer   userId             = event.getUserId();
            final String    containerId        = workflow.getComments();
            final Container containerOrService = containerService.get(containerId);
            final long      wrapperId          = containerOrService.wrapperId();
            int             stepIdx            = Integer.parseInt(StringUtils.defaultIfBlank(workflow.getCurrentStepId(), "0"));
            final Command.CommandWrapper nextWrapper = orchestrationService.findNextWrapper(orchestrationId,
                                                                                            stepIdx, wrapperId);
            if (nextWrapper == null) {
                return;
            }

            // Make a new workflow to track any issues
            final String project = workflow.getExternalid();
            final UserI  user    = userManagementServiceI.getUser(userId);
            newWorkflow = containerService.createContainerWorkflow(workflow.getId(),
                                                                   workflow.getDataType(), nextWrapper.name(), project, user, workflow.getJobid(),
                                                                   orchestrationId, stepIdx + 1);

            // Determine inputValues (root element)
            final Map<String, String> inputValues;
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
