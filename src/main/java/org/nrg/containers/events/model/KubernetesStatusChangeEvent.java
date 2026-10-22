package org.nrg.containers.events.model;

import lombok.Builder;
import lombok.Value;
import org.nrg.containers.model.kubernetes.KubernetesPodPhase;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Map;

@Value
@Builder(toBuilder = true)
public class KubernetesStatusChangeEvent implements ContainerEvent {
    private static final long serialVersionUID = 1L;

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
    String namespace;
    /** Reason of a pod Warning Event (FailedMount, FailedAttachVolume, FailedScheduling); null for pod status changes. */
    String warningReason;
    /** When the warning first occurred (Event.firstTimestamp, falling back to eventTime then lastTimestamp); null otherwise. */
    OffsetDateTime firstTimestamp;

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
        if (warningReason != null) {
            return warningReason + ": " + podPhaseReason;
        }
        if (podPhaseReason != null && containerStateReason != null) {
            return "Pod: " + podPhaseReason + " Container: " + containerStateReason;
        }
        return podPhaseReason != null ? podPhaseReason : containerStateReason;
    }
}
