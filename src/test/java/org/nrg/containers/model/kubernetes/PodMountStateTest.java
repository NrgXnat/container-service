package org.nrg.containers.model.kubernetes;

import io.kubernetes.client.openapi.models.V1ContainerState;
import io.kubernetes.client.openapi.models.V1ContainerStateWaiting;
import io.kubernetes.client.openapi.models.V1ContainerStatus;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodCondition;
import io.kubernetes.client.openapi.models.V1PodStatus;
import org.junit.Test;

import java.util.Collections;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

/** FU-3: whether a pod is still held at the volume mount stage, read from its current status rather than from old events. */
public class PodMountStateTest {

    private static V1Pod pod(final String phase) {
        return new V1Pod().status(new V1PodStatus().phase(phase));
    }

    private static V1Pod withReadyToStart(final V1Pod pod, final String status) {
        pod.getStatus().addConditionsItem(new V1PodCondition().type("PodReadyToStartContainers").status(status));
        return pod;
    }

    private static V1Pod withWaitingReason(final V1Pod pod, final String reason) {
        pod.getStatus().containerStatuses(Collections.singletonList(new V1ContainerStatus().name("main")
                .state(new V1ContainerState().waiting(new V1ContainerStateWaiting().reason(reason)))));
        return pod;
    }

    @Test
    public void noPodOrNoStatusIsUnknown() {
        assertThat(PodMountState.of(null), is(PodMountState.UNKNOWN));
        assertThat(PodMountState.of(new V1Pod()), is(PodMountState.UNKNOWN));
    }

    @Test
    public void aPodThatLeftPendingIsPastTheMount() {
        assertThat(PodMountState.of(pod("Running")), is(PodMountState.PAST_VOLUME_MOUNT));
        assertThat(PodMountState.of(pod("Failed")), is(PodMountState.PAST_VOLUME_MOUNT));
    }

    /** The case behind FU-3: mounts succeeded long ago and the pod is now stuck pulling its image. */
    @Test
    public void readyToStartContainersMeansTheVolumesAreMounted() {
        final V1Pod pod = withWaitingReason(withReadyToStart(pod("Pending"), "True"), "ImagePullBackOff");
        assertThat(PodMountState.of(pod), is(PodMountState.PAST_VOLUME_MOUNT));
    }

    @Test
    public void notReadyToStartContainersIsStillWaitingOnVolumes() {
        final V1Pod pod = withWaitingReason(withReadyToStart(pod("Pending"), "False"), "ContainerCreating");
        assertThat(PodMountState.of(pod), is(PodMountState.WAITING_ON_VOLUMES));
    }

    /** Clusters without the PodReadyToStartContainers condition: fall back to the container's waiting reason. */
    @Test
    public void withoutTheConditionAWaitingReasonOtherThanContainerCreatingIsPastTheMount() {
        assertThat(PodMountState.of(withWaitingReason(pod("Pending"), "ImagePullBackOff")), is(PodMountState.PAST_VOLUME_MOUNT));
        assertThat(PodMountState.of(withWaitingReason(pod("Pending"), "ErrImagePull")), is(PodMountState.PAST_VOLUME_MOUNT));
    }

    @Test
    public void withoutTheConditionContainerCreatingIsStillWaitingOnVolumes() {
        assertThat(PodMountState.of(withWaitingReason(pod("Pending"), "ContainerCreating")), is(PodMountState.WAITING_ON_VOLUMES));
        assertThat(PodMountState.of(pod("Pending")), is(PodMountState.WAITING_ON_VOLUMES));
    }
}
