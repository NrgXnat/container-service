package org.nrg.containers.model.kubernetes;

import io.kubernetes.client.openapi.models.V1ContainerState;
import io.kubernetes.client.openapi.models.V1ContainerStateWaiting;
import io.kubernetes.client.openapi.models.V1ContainerStatus;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodCondition;
import io.kubernetes.client.openapi.models.V1PodStatus;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Whether a pod is still held at the volume mount stage, judged from its current status. A FailedMount event only
 * says the mount failed at some point; after a reconnect the informer replays old events, so the secret mount
 * watchdog checks this before it fails a container.
 */
public enum PodMountState {
    WAITING_ON_VOLUMES,
    PAST_VOLUME_MOUNT,
    UNKNOWN;

    /** Set by the kubelet once the sandbox exists, which happens only after all volumes are mounted. */
    static final String READY_TO_START_CONTAINERS = "PodReadyToStartContainers";
    static final String CONTAINER_CREATING = "ContainerCreating";

    public static PodMountState of(@Nullable final V1Pod pod) {
        final V1PodStatus status = pod == null ? null : pod.getStatus();
        if (status == null) {
            return UNKNOWN;
        }
        if (!KubernetesPodPhase.PENDING.toString().equals(status.getPhase())) {
            return PAST_VOLUME_MOUNT;
        }
        final Optional<String> readyToStart = nullToEmpty(status.getConditions()).stream()
                .filter(condition -> READY_TO_START_CONTAINERS.equals(condition.getType()))
                .map(V1PodCondition::getStatus)
                .findFirst();
        if (readyToStart.isPresent()) {
            return "True".equals(readyToStart.get()) ? PAST_VOLUME_MOUNT : WAITING_ON_VOLUMES;
        }
        // Clusters without the condition: any waiting reason other than ContainerCreating comes after the mounts.
        final boolean pastContainerCreating = nullToEmpty(status.getContainerStatuses()).stream()
                .map(V1ContainerStatus::getState)
                .filter(Objects::nonNull)
                .map(V1ContainerState::getWaiting)
                .filter(Objects::nonNull)
                .map(V1ContainerStateWaiting::getReason)
                .anyMatch(reason -> reason != null && !CONTAINER_CREATING.equals(reason));
        return pastContainerCreating ? PAST_VOLUME_MOUNT : WAITING_ON_VOLUMES;
    }

    private static <T> List<T> nullToEmpty(@Nullable final List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }
}
