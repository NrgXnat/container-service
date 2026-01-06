package org.nrg.containers.events.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;
import org.nrg.containers.model.kubernetes.KubernetesPodPhase;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Map;

@Value
@Builder(toBuilder = true)
@AllArgsConstructor
public class KubernetesStatusChangeEvent implements ContainerEvent {
    String jobName;
    String podName;
    String containerId;
    KubernetesPodPhase podPhase;
    String podPhaseReason;
    KubernetesContainerState containerState;
    String containerStateReason;
    Integer exitCode;
    OffsetDateTime timestamp;
    String nodeId;

    @Override
    public String backendId() {
        return jobName;
    }

    @Override
    public String status() {
        return podPhase.toString();
    }

    @Override
    public String externalTimestamp() {
        return timestamp == null ? null : timestamp.toString();
    }

    @Override
    public Map<String, String> attributes() {
        // We don't get any attributes from kubernetes
        return Collections.emptyMap();
    }

    @Override
    public boolean isExitStatus() {
        return podPhase.isTerminal();
    }

    @Override
    public String exitCode() {
        return exitCode == null ? null : String.valueOf(exitCode);
    }

    @Override
    public String details() {
        final String details;
        if (podPhaseReason != null &&
containerStateReason != null) {
            details = "Pod: " + podPhaseReason + " Container: " + containerStateReason;
        } else if (podPhaseReason != null) {
            details = podPhaseReason;
        } else {
            details = containerStateReason;
        }
        return details;
    }
}
