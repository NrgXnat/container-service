package org.nrg.containers.api;

import io.kubernetes.client.openapi.ApiClient;
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
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
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

    // ---- one informer group per namespace ----

    /** Replaces the real list/watch group so these tests never touch the network. */
    private static class RecordingInformer extends KubernetesInformerImpl {
        final List<String> started = new ArrayList<>();
        final List<String> stopped = new ArrayList<>();

        RecordingInformer() {
            super("ctx", new ApiClient(), mock(ExecutorService.class), mock(JmsTemplate.class));
        }

        @Override
        protected NamespaceWatch createWatch(final String namespace) {
            return new NamespaceWatch() {
                @Override public void start() { started.add(namespace); }
                @Override public void stop() { stopped.add(namespace); }
            };
        }
    }

    @Test
    public void startWatchesTheContextNamespaceOnly() {
        final RecordingInformer informer = new RecordingInformer();
        informer.start();
        assertThat(informer.getNamespace(), is("ctx"));
        assertThat(informer.started, is(Collections.singletonList("ctx")));
        assertThat(informer.watchedNamespaces(), is(Collections.singleton("ctx")));
    }

    @Test
    public void ensureWatchingStartsANewGroupOnceAndIsIdempotent() {
        final RecordingInformer informer = new RecordingInformer();
        informer.start();
        informer.ensureWatching("xnat-proj01");
        informer.ensureWatching("xnat-proj01");
        informer.ensureWatching("ctx");
        assertThat(informer.started, is(java.util.Arrays.asList("ctx", "xnat-proj01")));
    }

    @Test
    public void ensureWatchingBeforeStartIsDeferredUntilStart() {
        final RecordingInformer informer = new RecordingInformer();
        informer.ensureWatching("xnat-proj01");
        assertThat(informer.started.isEmpty(), is(true));
        informer.start();
        assertThat(informer.started, containsInAnyOrder("ctx", "xnat-proj01"));
    }

    /**
     * A stopped SharedInformerFactory cannot be restarted (its informers keep their started flag), so stop() discards
     * every group. start() afterwards only recreates the context group; per-project groups come back through the
     * status updater's ensureWatching on its next cycle.
     */
    @Test
    public void stopDiscardsEveryGroupAndStartRecreatesOnlyTheContextGroup() {
        final RecordingInformer informer = new RecordingInformer();
        informer.start();
        informer.ensureWatching("xnat-proj01");
        informer.stop();
        assertThat(informer.stopped, containsInAnyOrder("ctx", "xnat-proj01"));
        assertThat(informer.watchedNamespaces().isEmpty(), is(true));
        informer.start();
        assertThat(informer.started, is(java.util.Arrays.asList("ctx", "xnat-proj01", "ctx")));
        informer.ensureWatching("xnat-proj01");
        assertThat(informer.started.size(), is(4));
    }
}
