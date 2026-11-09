package org.nrg.containers.model.kubernetes;

/** Reasons of Kubernetes Warning Events about pods that the Container Service records in container history. */
public final class PodWarningReason {
    public static final String FAILED_MOUNT = "FailedMount";
    public static final String FAILED_ATTACH_VOLUME = "FailedAttachVolume";
    public static final String FAILED_SCHEDULING = "FailedScheduling";

    private PodWarningReason() {
    }
}
