package org.nrg.containers.utils;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.model.container.auto.ServiceTask;
import org.nrg.containers.model.kubernetes.KubernetesPodPhase;
import org.nrg.containers.model.server.docker.Backend;
import org.nrg.xft.event.persist.PersistentWorkflowI;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.utils.WorkflowUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.EnumSet;

@Slf4j
public class ContainerUtils {
    public static final int KIB_TO_BYTES = 1024;
    public static final int MIB_TO_BYTES = KIB_TO_BYTES * KIB_TO_BYTES;
    public static final double NANO = 1e9;

    public static final int SECONDS_PER_MINUTE = 60;
    public static final int MINUTES_PER_HOUR = 60;
    public static final int HOURS_PER_DAY = 24;
    public static final int DAYS_PER_WEEK = 7;
    public static final int SECONDS_PER_WEEK = SECONDS_PER_MINUTE * MINUTES_PER_HOUR * HOURS_PER_DAY * DAYS_PER_WEEK;

    public static final String KUBERNETES_FAILED_STATUS = "Failed";
    public static final String CS_SHARED_PROJECT_STRING = "container-service";

    public enum TerminalState {
        COMPLETE("Complete"),
        FAILED("Failed"),
        KILLED("Killed");

        public final String value;

        TerminalState(String value) {
            this.value = value;
        }
    }

    public static boolean statusIsTerminal(String status) {
        return status != null && EnumSet.allOf(TerminalState.class).stream().anyMatch(s -> status.startsWith(s.value));
    }

    public static boolean statusIsSuccessful(final String status, final Backend backend) {
        switch (backend) {
            case DOCKER:
                // No way to determine success or failure based on status.
                // Containers always exit with status "die", have to use exit code.
                return true;
            case SWARM:
                return ServiceTask.isSuccessfulStatus(status);
            case KUBERNETES:
                return KubernetesPodPhase.isSuccessful(status);
        }
        return false;
    }

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
        updateWorkflowStatus(workflow, status, details);
    }

    public static void updateWorkflowStatus(final PersistentWorkflowI workflow, final String status, @Nullable String details) {
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

    public static <T> T instanceOrDefault(@Nullable final T instance, @Nonnull final T other) {
        return instance != null ? instance : other;
    }
}
