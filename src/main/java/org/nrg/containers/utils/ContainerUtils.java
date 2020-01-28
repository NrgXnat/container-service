package org.nrg.containers.utils;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nrg.xft.event.persist.PersistentWorkflowI;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.utils.WorkflowUtils;

@Slf4j
public class ContainerUtils {
    public static void updateWorkflowStatus(final String workflowId, final String status, final UserI userI) {
        if (StringUtils.isNotBlank(workflowId)) {
            log.debug("Updating status of workflow {}.", workflowId);
            final PersistentWorkflowI workflow = WorkflowUtils.getUniqueWorkflow(userI, workflowId);
            if (workflow != null) {
                log.debug("Found workflow {}.", workflow.getWorkflowId());

                if (workflow.getStatus() == null || !workflow.getStatus().equals(status)) {
                    log.debug("Updating workflow {} status to \"{}\".", workflow.getWorkflowId(), status);

                    workflow.setStatus(status);
                    try {
                        WorkflowUtils.save(workflow, workflow.buildEvent());
                    } catch (Exception e) {
                        log.error("Could not update workflow status.", e);
                        log.error(e.getStackTrace().toString());
                    }
                } else {
                    log.debug("Workflow {} status is already \"{}\"; not updating.", workflow.getWorkflowId(), status);
                }
            } else {
                log.debug("Could not find workflow.");
            }
        } else {
            log.debug("Container has no workflow ID. Not attempting to update workflow.");
        }
    }
}
