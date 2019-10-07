package org.nrg.containers.utils;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nrg.xft.event.persist.PersistentWorkflowI;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.utils.WorkflowUtils;

import javax.annotation.Nullable;

@Slf4j
public class ContainerUtils {

    public static void updateWorkflowStatus(final String workflowId, final String status, final UserI userI,
                                            @Nullable String details) {
        if (StringUtils.isBlank(workflowId)) {
            log.debug("Container has no workflow ID. Not attempting to update workflow.");
            return;
        }
        log.debug("Updating status of workflow {}.", workflowId);
        final PersistentWorkflowI workflow = WorkflowUtils.getUniqueWorkflow(userI, workflowId);
        if (workflow == null) {
            log.debug("Could not find workflow.");
            return;
        }
        log.debug("Found workflow {}.", workflow.getWorkflowId());

        if (StringUtils.isBlank(details)) {
            details = "";
        }

        if (workflow.getStatus() != null && workflow.getStatus().equals(status) && workflow.getDetails().equals(details)) {
            log.debug("Workflow {} status is already \"{}\"; not updating.", workflow.getWorkflowId(), status);
            return;
        }

        log.info("Updating workflow {} pipeline \"{}\" from \"{}\" to \"{}\" (details: {}).", workflow.getWorkflowId(),
                workflow.getPipelineName(), workflow.getStatus(), status, details);
        workflow.setStatus(status);
        workflow.setDetails(details);
        try {
            WorkflowUtils.save(workflow, workflow.buildEvent());
        } catch (Exception e) {
            log.error("Could not update workflow status.", e);
        }
    }
 
}
