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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

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
    public void failedMountWarningCarriesReasonAndFirstTimestamp() {
        final JmsTemplate template = mock(JmsTemplate.class);
        final OffsetDateTime first = OffsetDateTime.parse("2026-10-13T08:00:00Z");
        final OffsetDateTime last = OffsetDateTime.parse("2026-10-13T08:09:00Z");
        final CoreV1Event event = podEvent("Warning", "FailedMount", "MountVolume.SetUp failed for volume \"secret-redcap\" : secret \"xnat-proj01-redcap\" not found")
                .firstTimestamp(first).lastTimestamp(last);
        final ArgumentCaptor<KubernetesStatusChangeEvent> sent = ArgumentCaptor.forClass(KubernetesStatusChangeEvent.class);

        try (final MockedStatic<QueueUtils> mockedQueueUtils = mockStatic(QueueUtils.class)) {
            new KubernetesInformerImpl.PodWarningEventHandler(template, name -> trackedPod()).onAdd(event);
            mockedQueueUtils.verify(() -> QueueUtils.sendJmsRequest(eq(template), eq(ContainerEvent.QUEUE), sent.capture()));
        }

        assertThat(sent.getValue().getWarningReason(), is("FailedMount"));
        assertThat(sent.getValue().getFirstTimestamp(), is(first));
        assertThat("the raw message is the pod phase reason, without the reason prefix",
                sent.getValue().getPodPhaseReason(), is("MountVolume.SetUp failed for volume \"secret-redcap\" : secret \"xnat-proj01-redcap\" not found"));
        assertThat("history text still starts with the reason", sent.getValue().details(), is("FailedMount: MountVolume.SetUp failed for volume \"secret-redcap\" : secret \"xnat-proj01-redcap\" not found"));
    }

    @Test
    public void firstTimestampFallsBackToEventTimeThenLastTimestamp() {
        final JmsTemplate template = mock(JmsTemplate.class);
        final OffsetDateTime eventTime = OffsetDateTime.parse("2026-10-13T08:01:00Z");
        final OffsetDateTime last = OffsetDateTime.parse("2026-10-13T08:09:00Z");
        final ArgumentCaptor<KubernetesStatusChangeEvent> sent = ArgumentCaptor.forClass(KubernetesStatusChangeEvent.class);

        try (final MockedStatic<QueueUtils> mockedQueueUtils = mockStatic(QueueUtils.class)) {
            final KubernetesInformerImpl.PodWarningEventHandler handler = new KubernetesInformerImpl.PodWarningEventHandler(template, name -> trackedPod());
            handler.onAdd(podEvent("Warning", "FailedMount", "m1").firstTimestamp(null).eventTime(eventTime).lastTimestamp(last));
            handler.onAdd(podEvent("Warning", "FailedMount", "m2").firstTimestamp(null).eventTime(null).lastTimestamp(last));
            mockedQueueUtils.verify(() -> QueueUtils.sendJmsRequest(eq(template), eq(ContainerEvent.QUEUE), sent.capture()), times(2));
        }

        assertThat(sent.getAllValues().get(0).getFirstTimestamp(), is(eventTime));
        assertThat(sent.getAllValues().get(1).getFirstTimestamp(), is(last));
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

    private static String awsNotFound(final String requestId) {
        return "MountVolume.SetUp failed for volume \"secret-redcap\" : rpc error: code = Unknown desc = failed to mount secrets store objects for pod xnat-proj01/" + POD_NAME
                + ", err: rpc error: code = Unknown desc = us-east-1: Failed fetching secret xnat/projects/PROJ01/does-not-exist: operation error Secrets Manager: GetSecretValue,"
                + " https response error StatusCode: 400, RequestID: " + requestId + ", ResourceNotFoundException: Secrets Manager can't find the specified secret.";
    }

    /** The AWS provider puts a fresh RequestID into every kubelet retry; without ignoring it each retry adds a history row. */
    @Test
    public void repeatedWarningDifferingOnlyInRequestIdIsNotResent() {
        final JmsTemplate template = mock(JmsTemplate.class);
        final CoreV1Event first = podEvent("Warning", "FailedMount", awsNotFound("7bfdc195-45b0-47a0-bbb3-688fb8c67355")).count(1);
        final CoreV1Event again = podEvent("Warning", "FailedMount", awsNotFound("0a1b2c3d-4e5f-6789-abcd-ef0123456789")).count(2);
        try (final MockedStatic<QueueUtils> mockedQueueUtils = mockStatic(QueueUtils.class)) {
            new KubernetesInformerImpl.PodWarningEventHandler(template, name -> trackedPod())
                    .onUpdate(first, again);
            mockedQueueUtils.verifyNoInteractions();
        }
    }

    @Test
    public void repeatedWarningDifferingElsewhereIsResent() {
        final JmsTemplate template = mock(JmsTemplate.class);
        final CoreV1Event first = podEvent("Warning", "FailedMount", awsNotFound("7bfdc195-45b0-47a0-bbb3-688fb8c67355")).count(1);
        final CoreV1Event again = podEvent("Warning", "FailedMount",
                awsNotFound("0a1b2c3d-4e5f-6789-abcd-ef0123456789").replace("does-not-exist", "redcap")).count(2);
        final ArgumentCaptor<KubernetesStatusChangeEvent> sent = ArgumentCaptor.forClass(KubernetesStatusChangeEvent.class);
        try (final MockedStatic<QueueUtils> mockedQueueUtils = mockStatic(QueueUtils.class)) {
            new KubernetesInformerImpl.PodWarningEventHandler(template, name -> trackedPod())
                    .onUpdate(first, again);
            mockedQueueUtils.verify(() -> QueueUtils.sendJmsRequest(eq(template), eq(ContainerEvent.QUEUE), sent.capture()));
        }
        assertThat("the recorded message keeps its request id", sent.getValue().getPodPhaseReason(),
                is(awsNotFound("0a1b2c3d-4e5f-6789-abcd-ef0123456789").replace("does-not-exist", "redcap")));
    }

    @Test
    public void firstWarningIsRecordedWithItsRequestId() {
        final JmsTemplate template = mock(JmsTemplate.class);
        final String message = awsNotFound("7bfdc195-45b0-47a0-bbb3-688fb8c67355");
        final ArgumentCaptor<KubernetesStatusChangeEvent> sent = ArgumentCaptor.forClass(KubernetesStatusChangeEvent.class);
        try (final MockedStatic<QueueUtils> mockedQueueUtils = mockStatic(QueueUtils.class)) {
            new KubernetesInformerImpl.PodWarningEventHandler(template, name -> trackedPod())
                    .onAdd(podEvent("Warning", "FailedMount", message));
            mockedQueueUtils.verify(() -> QueueUtils.sendJmsRequest(eq(template), eq(ContainerEvent.QUEUE), sent.capture()));
        }
        assertThat(sent.getValue().getPodPhaseReason(), is(message));
    }

    @Test
    public void normalizeWarningMessageStripsOnlyTheRequestId() {
        assertThat(KubernetesInformerImpl.PodWarningEventHandler.normalizeWarningMessage(
                        "StatusCode: 400, RequestID: 7bfdc195-45b0-47a0-bbb3-688fb8c67355, ResourceNotFoundException: x"),
                is("StatusCode: 400, ResourceNotFoundException: x"));
        assertThat(KubernetesInformerImpl.PodWarningEventHandler.normalizeWarningMessage("secret \"x\" not found"), is("secret \"x\" not found"));
        assertThat(KubernetesInformerImpl.PodWarningEventHandler.normalizeWarningMessage(null), is(nullValue()));
        assertThat("a non-UUID request id is stripped too",
                KubernetesInformerImpl.PodWarningEventHandler.normalizeWarningMessage("StatusCode: 400, RequestID: req_ABC.123/x, AccessDenied"),
                is("StatusCode: 400, AccessDenied"));
        assertThat("the volume name is never collapsed",
                KubernetesInformerImpl.PodWarningEventHandler.normalizeWarningMessage(awsNotFound("7bfdc195-45b0-47a0-bbb3-688fb8c67355")),
                not(KubernetesInformerImpl.PodWarningEventHandler.normalizeWarningMessage(
                        awsNotFound("7bfdc195-45b0-47a0-bbb3-688fb8c67355").replace("secret-redcap", "secret-other"))));
    }

    // ---- repeats arriving as new Event objects ----

    /** The kubelet's recorder creates a new Event object when the message changes, so retries arrive via onAdd. */
    private static CoreV1Event newPodEvent(final String podName, final String eventName, final String message) {
        return new CoreV1Event()
                .metadata(new V1ObjectMeta().name(podName + "." + eventName).uid(eventName).namespace("xnat-proj01"))
                .type("Warning")
                .reason("FailedMount")
                .message(message)
                .count(1)
                .lastTimestamp(OffsetDateTime.now())
                .involvedObject(new V1ObjectReference().kind("Pod").name(podName));
    }

    private static V1Pod finishedPod(final String podName) {
        return new V1Pod()
                .metadata(new V1ObjectMeta().name(podName).namespace("xnat-proj01").resourceVersion("9")
                        .labels(Collections.singletonMap("job-name", JOB_NAME)))
                .status(new V1PodStatus().phase("Failed"));
    }

    @Test
    public void newEventObjectsDifferingOnlyInRequestIdAreSentOnce() {
        final JmsTemplate template = mock(JmsTemplate.class);
        final ArgumentCaptor<KubernetesStatusChangeEvent> sent = ArgumentCaptor.forClass(KubernetesStatusChangeEvent.class);
        final String otherObject = awsNotFound("11111111-2222-3333-4444-555555555555").replace("does-not-exist", "redcap");
        try (final MockedStatic<QueueUtils> mockedQueueUtils = mockStatic(QueueUtils.class)) {
            final KubernetesInformerImpl.PodWarningEventHandler handler =
                    new KubernetesInformerImpl.PodWarningEventHandler(template, name -> trackedPod(), new PodWarningDeduplicator());
            handler.onAdd(newPodEvent(POD_NAME, "18a1b2c3", awsNotFound("7bfdc195-45b0-47a0-bbb3-688fb8c67355")));
            handler.onAdd(newPodEvent(POD_NAME, "18a1b2c4", awsNotFound("0a1b2c3d-4e5f-6789-abcd-ef0123456789")));
            handler.onAdd(newPodEvent(POD_NAME, "18a1b2c5", otherObject));
            mockedQueueUtils.verify(() -> QueueUtils.sendJmsRequest(eq(template), eq(ContainerEvent.QUEUE), sent.capture()), times(2));
        }
        assertThat("the first message is recorded as received", sent.getAllValues().get(0).getPodPhaseReason(),
                is(awsNotFound("7bfdc195-45b0-47a0-bbb3-688fb8c67355")));
        assertThat(sent.getAllValues().get(1).getPodPhaseReason(), is(otherObject));
    }

    @Test
    public void deletingThePodForgetsItsLastWarning() {
        final JmsTemplate template = mock(JmsTemplate.class);
        final PodWarningDeduplicator deduplicator = new PodWarningDeduplicator();
        try (final MockedStatic<QueueUtils> mockedQueueUtils = mockStatic(QueueUtils.class)) {
            final KubernetesInformerImpl.PodWarningEventHandler warnings =
                    new KubernetesInformerImpl.PodWarningEventHandler(template, name -> trackedPod(), deduplicator);
            warnings.onAdd(newPodEvent(POD_NAME, "18a1b2c3", awsNotFound("7bfdc195-45b0-47a0-bbb3-688fb8c67355")));
            new KubernetesInformerImpl.PodEventHandler(template, deduplicator).onDelete(finishedPod(POD_NAME), false);
            warnings.onAdd(newPodEvent(POD_NAME, "18a1b2c4", awsNotFound("0a1b2c3d-4e5f-6789-abcd-ef0123456789")));
            mockedQueueUtils.verify(() -> QueueUtils.sendJmsRequest(eq(template), eq(ContainerEvent.QUEUE), any()), times(2));
        }
    }

    @Test
    public void warningsOfDifferentPodsAreDeduplicatedIndependently() {
        final JmsTemplate template = mock(JmsTemplate.class);
        final ArgumentCaptor<KubernetesStatusChangeEvent> sent = ArgumentCaptor.forClass(KubernetesStatusChangeEvent.class);
        try (final MockedStatic<QueueUtils> mockedQueueUtils = mockStatic(QueueUtils.class)) {
            final KubernetesInformerImpl.PodWarningEventHandler handler =
                    new KubernetesInformerImpl.PodWarningEventHandler(template, name -> trackedPod(), new PodWarningDeduplicator());
            handler.onAdd(newPodEvent(POD_NAME, "18a1b2c3", awsNotFound("7bfdc195-45b0-47a0-bbb3-688fb8c67355")));
            handler.onAdd(newPodEvent(JOB_NAME + "-q9w8e", "18a1b2c4", awsNotFound("0a1b2c3d-4e5f-6789-abcd-ef0123456789")));
            mockedQueueUtils.verify(() -> QueueUtils.sendJmsRequest(eq(template), eq(ContainerEvent.QUEUE), sent.capture()), times(2));
        }
        assertThat(sent.getAllValues().get(1).getPodName(), is(JOB_NAME + "-q9w8e"));
    }

    @Test
    public void clearingTheDeduplicatorForgetsEveryPod() {
        final PodWarningDeduplicator deduplicator = new PodWarningDeduplicator();
        assertThat(deduplicator.isRepeat("xnat-proj01", POD_NAME, "FailedMount", "m"), is(false));
        assertThat(deduplicator.isRepeat("xnat-proj01", POD_NAME, "FailedMount", "m"), is(true));
        assertThat("another reason is tracked separately", deduplicator.isRepeat("xnat-proj01", POD_NAME, "FailedScheduling", "m"), is(false));
        deduplicator.clear();
        assertThat(deduplicator.isRepeat("xnat-proj01", POD_NAME, "FailedMount", "m"), is(false));
    }

    /** A warning that could not be queued must not count as seen, or it never reaches history and the watchdog never starts. */
    @Test
    public void warningThatFailedToQueueIsSentAgain() {
        final JmsTemplate template = mock(JmsTemplate.class);
        try (final MockedStatic<QueueUtils> mockedQueueUtils = mockStatic(QueueUtils.class)) {
            mockedQueueUtils.when(() -> QueueUtils.sendJmsRequest(any(), any(), any()))
                    .thenThrow(new IllegalStateException("broker down"))
                    .thenAnswer(invocation -> null);
            final KubernetesInformerImpl.PodWarningEventHandler handler =
                    new KubernetesInformerImpl.PodWarningEventHandler(template, name -> trackedPod(), new PodWarningDeduplicator());
            try {
                handler.onAdd(newPodEvent(POD_NAME, "18a1b2c3", awsNotFound("7bfdc195-45b0-47a0-bbb3-688fb8c67355")));
                fail("the send failure is rethrown");
            } catch (IllegalStateException expected) {
                assertThat(expected.getMessage(), is("broker down"));
            }
            handler.onAdd(newPodEvent(POD_NAME, "18a1b2c4", awsNotFound("0a1b2c3d-4e5f-6789-abcd-ef0123456789")));
            mockedQueueUtils.verify(() -> QueueUtils.sendJmsRequest(eq(template), eq(ContainerEvent.QUEUE), any()), times(2));
        }
    }

    /** Mirrors the history's clear on Running: a re-mount failure after a kubelet restart is recorded and re-arms the watchdog. */
    @Test
    public void podTurningRunningForgetsItsLastWarning() {
        final JmsTemplate template = mock(JmsTemplate.class);
        final PodWarningDeduplicator deduplicator = new PodWarningDeduplicator();
        final V1Pod pending = trackedPod().status(new V1PodStatus().phase("Pending"));
        pending.getMetadata().resourceVersion("1");
        final V1Pod running = trackedPod().status(new V1PodStatus().phase("Running"));
        running.getMetadata().resourceVersion("2");
        final ArgumentCaptor<KubernetesStatusChangeEvent> sent = ArgumentCaptor.forClass(KubernetesStatusChangeEvent.class);
        try (final MockedStatic<QueueUtils> mockedQueueUtils = mockStatic(QueueUtils.class)) {
            final KubernetesInformerImpl.PodWarningEventHandler warnings =
                    new KubernetesInformerImpl.PodWarningEventHandler(template, name -> trackedPod(), deduplicator);
            warnings.onAdd(newPodEvent(POD_NAME, "18a1b2c3", awsNotFound("7bfdc195-45b0-47a0-bbb3-688fb8c67355")));
            new KubernetesInformerImpl.PodEventHandler(template, deduplicator).onUpdate(pending, running);
            warnings.onAdd(newPodEvent(POD_NAME, "18a1b2c4", awsNotFound("0a1b2c3d-4e5f-6789-abcd-ef0123456789")));
            mockedQueueUtils.verify(() -> QueueUtils.sendJmsRequest(eq(template), eq(ContainerEvent.QUEUE), sent.capture()), times(3));
        }
        assertThat("both warnings and the Running status were queued", sent.getAllValues().get(2).getWarningReason(), is("FailedMount"));
        assertThat(sent.getAllValues().get(1).getPodPhase(), is(KubernetesPodPhase.RUNNING));
    }

    @Test
    public void forgettingAPodLeavesPodsWithALongerNameAlone() {
        final PodWarningDeduplicator deduplicator = new PodWarningDeduplicator();
        deduplicator.isRepeat("ns", "x", "FailedMount", "m");
        deduplicator.isRepeat("ns", "x-longer", "FailedMount", "m");
        deduplicator.forgetPod("ns", "x");
        assertThat(deduplicator.isRepeat("ns", "x", "FailedMount", "m"), is(false));
        assertThat(deduplicator.isRepeat("ns", "x-longer", "FailedMount", "m"), is(true));
    }

    @Test
    public void forgettingOneReasonLeavesTheOthers() {
        final PodWarningDeduplicator deduplicator = new PodWarningDeduplicator();
        deduplicator.isRepeat("ns", "x", "FailedMount", "m");
        deduplicator.isRepeat("ns", "x", "FailedScheduling", "m");
        deduplicator.forget("ns", "x", "FailedMount");
        assertThat(deduplicator.isRepeat("ns", "x", "FailedMount", "m"), is(false));
        assertThat(deduplicator.isRepeat("ns", "x", "FailedScheduling", "m"), is(true));
    }

    // ---- one informer group per namespace ----

    /** Replaces the real list/watch group so these tests never touch the network. */
    private static class RecordingInformer extends KubernetesInformerImpl {
        final List<String> started = new ArrayList<>();
        final List<String> stopped = new ArrayList<>();
        final List<String> created = new ArrayList<>();
        final Set<String> failOnStart = new HashSet<>();

        RecordingInformer() {
            super("ctx", new ApiClient(), mock(ExecutorService.class), mock(JmsTemplate.class));
        }

        @Override
        protected NamespaceWatch createWatch(final String namespace) {
            created.add(namespace);
            return new NamespaceWatch() {
                @Override public void start() {
                    if (failOnStart.contains(namespace)) {
                        throw new IllegalStateException("cannot start " + namespace);
                    }
                    started.add(namespace);
                }
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

    @Test
    public void aGroupThatFailsToStartIsForgottenSoTheNextCallRetries() {
        final RecordingInformer informer = new RecordingInformer();
        informer.start();
        informer.failOnStart.add("xnat-proj01");
        try {
            informer.ensureWatching("xnat-proj01");
            fail("Expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // the failure is rethrown to the caller
        }
        assertThat(informer.watchedNamespaces().contains("xnat-proj01"), is(false));

        informer.failOnStart.clear();
        informer.ensureWatching("xnat-proj01");
        assertThat(informer.created, is(Arrays.asList("ctx", "xnat-proj01", "xnat-proj01")));
        assertThat(informer.watchedNamespaces().contains("xnat-proj01"), is(true));
    }
}
