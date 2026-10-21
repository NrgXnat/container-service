package org.nrg.containers.api;

import io.kubernetes.client.openapi.models.V1ObjectMeta;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.containsString;
import java.time.OffsetDateTime;
import io.kubernetes.client.openapi.models.V1ObjectReference;
import io.kubernetes.client.openapi.models.CoreV1Event;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodStatus;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.nrg.containers.events.model.ContainerEvent;
import org.nrg.containers.events.model.KubernetesStatusChangeEvent;
import org.nrg.containers.jms.utils.QueueUtils;
import org.nrg.containers.model.kubernetes.KubernetesPodPhase;
import org.springframework.jms.core.JmsTemplate;

import java.util.ArrayList;
import java.util.Collections;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

public class KubernetesInformerTest {
    private static final String JOB_NAME = "container-service-job-abc123";
    private static final String POD_NAME = JOB_NAME + "-x7k2p";

    /**
     * A pod that has been scheduled but whose kubelet has not yet reported on its container can arrive with
     * an empty containerStatuses list rather than none at all. Reading element zero of that list throws out
     * of the informer's event handler.
     */
    @Test
    public void emptyContainerStatusListIsTreatedAsNoContainerStatus() {
        final JmsTemplate template = mock(JmsTemplate.class);

        final V1Pod pod = new V1Pod()
                .metadata(new V1ObjectMeta()
                        .name(POD_NAME)
                        .namespace("xnat-proj01")
                        .labels(Collections.singletonMap("job-name", JOB_NAME)))
                .status(new V1PodStatus()
                        .phase(KubernetesPodPhase.PENDING.toString())
                        .containerStatuses(new ArrayList<>()));

        final ArgumentCaptor<KubernetesStatusChangeEvent> event =
                ArgumentCaptor.forClass(KubernetesStatusChangeEvent.class);

        try (final MockedStatic<QueueUtils> mockedQueueUtils = mockStatic(QueueUtils.class)) {
            new KubernetesInformerImpl.PodEventHandler(template).onAdd(pod);

            mockedQueueUtils.verify(() ->
                    QueueUtils.sendJmsRequest(eq(template), eq(ContainerEvent.QUEUE), event.capture()));
        }

        assertThat(event.getValue().getPodPhase(), is(KubernetesPodPhase.PENDING));
        assertThat(event.getValue().getContainerId(), is(nullValue()));
        assertThat(event.getValue().getContainerState(), is(nullValue()));
        assertThat(event.getValue().getNamespace(), is("xnat-proj01"));
    }

    // ---- Pod warning events (e.g. FailedMount when a secret is missing) ----

    private static V1Pod trackedPod() {
        return new V1Pod().metadata(new V1ObjectMeta()
                .name(POD_NAME)
                .namespace("xnat-proj01")
                .labels(Collections.singletonMap("job-name", JOB_NAME)));
    }

    private static CoreV1Event podEvent(final String type, final String reason, final String message) {
        return new CoreV1Event()
                .metadata(new V1ObjectMeta().name(POD_NAME + ".18a1b2c3").namespace("xnat-proj01"))
                .type(type)
                .reason(reason)
                .message(message)
                .lastTimestamp(OffsetDateTime.now())
                .involvedObject(new V1ObjectReference().kind("Pod").name(POD_NAME));
    }

    @Test
    public void failedMountWarningBecomesPendingStatusWithDetails() {
        final JmsTemplate template = mock(JmsTemplate.class);
        final String message = "MountVolume.SetUp failed for volume \"secret-redcap\" : secret \"xnat-proj01-redcap\" not found";
        final ArgumentCaptor<KubernetesStatusChangeEvent> event = ArgumentCaptor.forClass(KubernetesStatusChangeEvent.class);

        try (final MockedStatic<QueueUtils> mockedQueueUtils = mockStatic(QueueUtils.class)) {
            new KubernetesInformerImpl.PodWarningEventHandler(template, name -> POD_NAME.equals(name) ? trackedPod() : null)
                    .onAdd(podEvent("Warning", "FailedMount", message));
            mockedQueueUtils.verify(() ->
                    QueueUtils.sendJmsRequest(eq(template), eq(ContainerEvent.QUEUE), event.capture()));
        }

        final KubernetesStatusChangeEvent sent = event.getValue();
        assertThat(sent.getJobName(), is(JOB_NAME));
        assertThat(sent.getPodName(), is(POD_NAME));
        assertThat(sent.getPodPhase(), is(KubernetesPodPhase.PENDING));
        assertThat(sent.isExitStatus(), is(false));
        assertThat(sent.details(), containsString("FailedMount"));
        assertThat(sent.details(), containsString("xnat-proj01-redcap\" not found"));
        assertThat(sent.externalTimestamp(), is(notNullValue()));
        assertThat(sent.getNamespace(), is("xnat-proj01"));
    }

    @Test
    public void normalEventsAreIgnored() {
        final JmsTemplate template = mock(JmsTemplate.class);
        try (final MockedStatic<QueueUtils> mockedQueueUtils = mockStatic(QueueUtils.class)) {
            new KubernetesInformerImpl.PodWarningEventHandler(template, name -> trackedPod())
                    .onAdd(podEvent("Normal", "Scheduled", "Successfully assigned xnat-proj01/" + POD_NAME + " to node-1"));
            mockedQueueUtils.verifyNoInteractions();
        }
    }

    @Test
    public void warningsForPodsWeDoNotTrackAreIgnored() {
        final JmsTemplate template = mock(JmsTemplate.class);
        try (final MockedStatic<QueueUtils> mockedQueueUtils = mockStatic(QueueUtils.class)) {
            new KubernetesInformerImpl.PodWarningEventHandler(template, name -> null)
                    .onAdd(podEvent("Warning", "FailedMount", "secret \"x\" not found"));
            mockedQueueUtils.verifyNoInteractions();
        }
    }

    @Test
    public void repeatedWarningWithSameMessageIsNotResent() {
        final JmsTemplate template = mock(JmsTemplate.class);
        final CoreV1Event first = podEvent("Warning", "FailedMount", "secret \"x\" not found").count(1);
        final CoreV1Event again = podEvent("Warning", "FailedMount", "secret \"x\" not found").count(2);
        try (final MockedStatic<QueueUtils> mockedQueueUtils = mockStatic(QueueUtils.class)) {
            new KubernetesInformerImpl.PodWarningEventHandler(template, name -> trackedPod())
                    .onUpdate(first, again);
            mockedQueueUtils.verifyNoInteractions();
        }
    }
}
