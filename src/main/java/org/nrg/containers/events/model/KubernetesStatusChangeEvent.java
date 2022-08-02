package org.nrg.containers.events.model;

import org.nrg.containers.model.kubernetes.KubernetesPodPhase;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

public class KubernetesStatusChangeEvent implements ContainerEvent {
    private final String jobName;
    private final String podName;
    private final String containerId;
    private final KubernetesPodPhase podPhase;
    private final String podPhaseReason;
    private final KubernetesContainerState containerState;
    private final String containerStateReason;
    private final Integer exitCode;
    private final OffsetDateTime timestamp;

    public KubernetesStatusChangeEvent(String jobName,
                                       String podName,
                                       String containerId,
                                       KubernetesPodPhase podPhase,
                                       String podPhaseReason,
                                       KubernetesContainerState containerState,
                                       String containerStateReason,
                                       Integer exitCode,
                                       OffsetDateTime timestamp) {
        this.jobName = jobName;
        this.podName = podName;
        this.containerId = containerId;
        this.podPhase = podPhase;
        this.podPhaseReason = podPhaseReason;
        this.containerState = containerState;
        this.containerStateReason = containerStateReason;
        this.exitCode = exitCode;
        this.timestamp = timestamp;
    }

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

    public String podName() {
        return podName;
    }

    public String containerId() {
        return containerId;
    }

    @Override
    public String toString() {
        return "KubernetesStatusChangeEvent{" +
                "jobName=\"" + jobName + "\"" +
                ", podName=\"" + podName + "\"" +
                ", containerId=\"" + containerId + "\"" +
                ", podPhase=\"" + podPhase + "\"" +
                ", podPhaseReason=\"" + podPhaseReason + "\"" +
                ", containerState=\"" + containerState + "\"" +
                ", containerStateReason=\"" + containerStateReason + "\"" +
                ", exitCode=\"" + exitCode + "\"" +
                ", timestamp=\"" + timestamp + "\"" +
                "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        KubernetesStatusChangeEvent that = (KubernetesStatusChangeEvent) o;
        return Objects.equals(jobName, that.jobName) &&
                Objects.equals(podName, that.podName) &&
                Objects.equals(containerId, that.containerId) &&
                podPhase == that.podPhase &&
                Objects.equals(podPhaseReason, that.podPhaseReason) &&
                containerState == that.containerState &&
                Objects.equals(containerStateReason, that.containerStateReason) &&
                Objects.equals(exitCode, that.exitCode) &&
                Objects.equals(timestamp, that.timestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(jobName, podName, containerId, podPhase, podPhaseReason, containerState, containerStateReason, exitCode, timestamp);
    }
}
