package org.nrg.containers.events.model;

/**
 * See kubernetes docs
 * <a href="https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/#container-states">Pod Lifecycle: Container States</a>
 */
public enum KubernetesContainerState {
    WAITING,
    RUNNING,
    TERMINATED
}
