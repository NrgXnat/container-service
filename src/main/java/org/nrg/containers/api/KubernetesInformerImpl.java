package org.nrg.containers.api;

import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.informer.ResourceEventHandler;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.informer.cache.Lister;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ContainerState;
import io.kubernetes.client.openapi.models.V1ContainerStateRunning;
import io.kubernetes.client.openapi.models.V1ContainerStateTerminated;
import io.kubernetes.client.openapi.models.V1ContainerStateWaiting;
import io.kubernetes.client.openapi.models.V1ContainerStatus;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobList;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import java.util.function.Function;
import java.util.Set;
import java.util.Objects;
import java.util.HashSet;
import java.util.Arrays;
import io.kubernetes.client.openapi.models.V1ObjectReference;
import io.kubernetes.client.openapi.models.CoreV1EventList;
import io.kubernetes.client.openapi.models.CoreV1Event;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1PodStatus;
import io.kubernetes.client.util.CallGeneratorParams;
import lombok.extern.slf4j.Slf4j;
import org.nrg.containers.events.model.ContainerEvent;
import org.nrg.containers.events.model.KubernetesContainerState;
import org.nrg.containers.events.model.KubernetesStatusChangeEvent;
import org.nrg.containers.jms.utils.QueueUtils;
import org.nrg.containers.model.kubernetes.KubernetesPodPhase;
import org.springframework.jms.core.JmsTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

@Slf4j
public class KubernetesInformerImpl implements KubernetesInformer {
    private static final int RESYNC_DISABLED = 0;

    private final SharedInformerFactory sharedInformerFactory;
    private volatile boolean isStarted;

    private final Lister<V1Job> jobLister;
    private final Lister<V1Pod> podLister;

    public KubernetesInformerImpl(final String namespace,
                                  final ApiClient apiClient,
                                  final ExecutorService executorService,
                                  final JmsTemplate template) {

        // Required to set read timeout to zero (disabling timeout) so long-lived watches work
        apiClient.setReadTimeout(0);

        CoreV1Api coreApi = new CoreV1Api(apiClient);
        BatchV1Api batchApi = new BatchV1Api(apiClient);

        sharedInformerFactory = new SharedInformerFactory(apiClient, executorService);
        SharedIndexInformer<V1Job> jobInformer = sharedInformerFactory.sharedIndexInformerFor(
                (CallGeneratorParams params) ->
                        batchApi.listNamespacedJobCall(
                                namespace,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                params.resourceVersion,
                                null,
                                params.timeoutSeconds,
                                params.watch,
                                null
                        ),
                V1Job.class, V1JobList.class, RESYNC_DISABLED);
        jobLister = new Lister<>(jobInformer.getIndexer(), namespace);

        SharedIndexInformer<V1Pod> podInformer = sharedInformerFactory.sharedIndexInformerFor(
                (CallGeneratorParams params) ->
                        coreApi.listNamespacedPodCall(
                                namespace,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                params.resourceVersion,
                                null,
                                params.timeoutSeconds,
                                params.watch,
                                null
                        ),
                V1Pod.class, V1PodList.class, RESYNC_DISABLED);
        podInformer.addEventHandler(new PodEventHandler(template));
        podLister = new Lister<>(podInformer.getIndexer(), namespace);

        // Pod warning events (FailedMount, FailedAttachVolume, ...) never show up in pod status,
        // only as Event objects. Watch them so a missing secret is visible in container history.
        SharedIndexInformer<CoreV1Event> eventInformer = sharedInformerFactory.sharedIndexInformerFor(
                (CallGeneratorParams params) ->
                        coreApi.listNamespacedEventCall(
                                namespace,
                                null,
                                null,
                                null,
                                "involvedObject.kind=Pod",
                                null,
                                null,
                                params.resourceVersion,
                                null,
                                params.timeoutSeconds,
                                params.watch,
                                null
                        ),
                CoreV1Event.class, CoreV1EventList.class, RESYNC_DISABLED);
        eventInformer.addEventHandler(new PodWarningEventHandler(template, podLister::get));
        isStarted = false;
    }

    private static String readLabels(KubernetesObject obj, String key) {
        final V1ObjectMeta meta = obj.getMetadata();
        final Map<String, String> labels = meta == null ? null : meta.getLabels();
        return labels == null ? null : labels.get(key);
    }

    private static String jobNameFromPodLabels(V1Pod pod) {
        return readLabels(pod, "job-name");
    }

    private static String objName(KubernetesObject obj) {
        final V1ObjectMeta meta = obj.getMetadata();
        return meta == null ? null : meta.getName();
    }

    @Override
    public void start() {
        if (!isStarted) {
            log.debug("Starting kubernetes informer");
            sharedInformerFactory.startAllRegisteredInformers();
            isStarted = true;
        }
    }

    @Override
    public void stop() {
        if (isStarted) {
            log.debug("Stopping kubernetes informer");
            sharedInformerFactory.stopAllRegisteredInformers(false);
            isStarted = false;
        }
    }

    @Override
    public V1Job getJob(final String jobName) {
        return jobLister.get(jobName);
    }

    @Override
    public V1Pod getPod(final String name) {
        return podLister.get(name);
    }

    static class PodEventHandler implements ResourceEventHandler<V1Pod> {
        private final JmsTemplate template;

        PodEventHandler(final JmsTemplate template) {
            this.template = template;
        }

        private static KubernetesStatusChangeEvent createEvent(V1Pod pod) {
            final V1PodStatus podStatus = pod.getStatus();

            // Status phase
            final String podPhaseString = podStatus == null ? null : podStatus.getPhase();
            final KubernetesPodPhase podPhase = KubernetesPodPhase.fromString(podPhaseString);
            final String podPhaseReason = podStatus == null ? null : podStatus.getReason();

            // Pull out container statuses
            // We only ever launch jobs with a single template, so we only expect one container
            final List<V1ContainerStatus> containerStatuses = podStatus == null ? null : podStatus.getContainerStatuses();
            final V1ContainerStatus containerStatus = containerStatuses == null || containerStatuses.isEmpty() ?
                    null : containerStatuses.get(0);

            // Docker container id
            final String containerId = containerStatus == null ? null : containerStatus.getContainerID();

            // Container state
            final V1ContainerState containerStateObj = containerStatus == null ? null : containerStatus.getState();
            final V1ContainerStateTerminated terminated = containerStateObj == null ? null : containerStateObj.getTerminated();
            final V1ContainerStateRunning running = containerStateObj == null ? null : containerStateObj.getRunning();
            final V1ContainerStateWaiting waiting = containerStateObj == null ? null : containerStateObj.getWaiting();

            final String containerStateReason;
            final KubernetesContainerState containerState;
            if (terminated != null) {
                containerState = KubernetesContainerState.TERMINATED;
                containerStateReason = terminated.getReason();
            } else if (waiting != null) {
                containerState = KubernetesContainerState.WAITING;
                containerStateReason = waiting.getReason();
            } else if (running != null) {
                containerState = KubernetesContainerState.RUNNING;
                containerStateReason = null;
            } else {
                containerState = null;
                containerStateReason = null;
            }

            // exit code
            final Integer exitCode = terminated == null ? null : terminated.getExitCode();

            // timestamp
            final OffsetDateTime timestamp;
            if (terminated != null) {
                timestamp = terminated.getFinishedAt();
            } else if (running != null) {
                timestamp = running.getStartedAt();
            } else {
                timestamp = null;
            }

            // Node
            final V1PodSpec podSpec = pod.getSpec();
            final String nodeName = podSpec == null ? null : podSpec.getNodeName();

            return new KubernetesStatusChangeEvent(
                    jobNameFromPodLabels(pod),
                    objName(pod),
                    containerId,
                    podPhase,
                    podPhaseReason,
                    containerState,
                    containerStateReason,
                    exitCode,
                    timestamp,
                    nodeName
            );
        }

        private void triggerEvent(final KubernetesStatusChangeEvent event) {
            log.debug("Triggering event {}", event);
            QueueUtils.sendJmsRequest(template, ContainerEvent.QUEUE, event);
        }

        @Override
        public void onAdd(V1Pod obj) {
            final String podName = objName(obj);
            final String jobName = jobNameFromPodLabels(obj);
            if (podName == null || jobName == null) {
                return;
            }

            log.debug("Added pod {} produced by job {}", podName, jobName);
            triggerEvent(createEvent(obj));
        }

        @Override
        public void onUpdate(V1Pod oldObj, V1Pod newObj) {
            final V1ObjectMeta oldMeta = oldObj.getMetadata();
            final V1ObjectMeta newMeta = newObj.getMetadata();
            if (oldMeta == null || newMeta == null) {
                log.debug("Pod {} onUpdate skipped due to missing metadata. oldMeta={}, newMeta={}",
                        objName(newObj), oldObj.getMetadata(), newObj.getMetadata());
                return;
            }

            final String newPhase = newObj.getStatus() != null && newObj.getStatus().getPhase() != null
                    ? newObj.getStatus().getPhase() : "<unknown>";

            final String oldPhase = oldObj.getStatus() != null && oldObj.getStatus().getPhase() != null
                    ? oldObj.getStatus().getPhase() : "<unknown>";

            final String oldVersion = oldMeta.getResourceVersion();
            final String newVersion = newMeta.getResourceVersion();

            if (newVersion == null) {
                log.debug("Exiting onUpdate because Pod {} resourceVersion is null", objName(newObj));
                return;
            } else if (newVersion.equals(oldVersion)) {
                log.debug("Exiting onUpdate because Pod {} resourceVersion unchanged.  newObject(phase={}, resourceVersion={}); oldObject(phase={}, resourceVersion={}). Skipping onUpdate",
                        objName(newObj), newPhase, newVersion, oldPhase, oldVersion);
                return;
            }

            final KubernetesStatusChangeEvent oldEvent = createEvent(oldObj);
            final KubernetesStatusChangeEvent newEvent = createEvent(newObj);
            if (newEvent.equals(oldEvent)) {
                log.debug("Pod {} onUpdate, but derived event has not changed. Not triggering additional XNAT event. newObject(phase={}, resourceVersion={}); oldObject(phase={}, resourceVersion={})",
                        objName(newObj), newPhase, newVersion, oldPhase, oldVersion);
                return;
            }

            log.debug("Pod {} onUpdate (phase={}, resourceVersion={})", objName(newObj), newPhase, newVersion);
            triggerEvent(newEvent);
        }

        @Override
        public void onDelete(V1Pod obj, boolean deletedFinalStateUnknown) {
            final V1ObjectMeta meta = obj.getMetadata();
            final String resourceVersion = meta != null && meta.getResourceVersion() != null
                    ? meta.getResourceVersion() : "<unknown>";
            final String podStatus = obj.getStatus() != null && obj.getStatus().getPhase() != null
                    ? obj.getStatus().getPhase() : "<unknown>";

            log.debug("Pod {} onDelete (phase={}, resourceVersion={}, deletedFinalStateUnknown={})",
                    objName(obj), podStatus, resourceVersion, deletedFinalStateUnknown);

            final KubernetesPodPhase podPhase = KubernetesPodPhase.fromString(podStatus);
            if (podPhase != null && podPhase.isTerminal()) {
                return; // podPhase is terminal so deletion was expected
            }

            log.error("Pod was deleted unexpectedly. Last known pod phase was: {}", podStatus);
            triggerEvent(
                    createEvent(obj).toBuilder()
                            .podPhase(KubernetesPodPhase.FAILED)
                            .exitCode(1)
                            .podPhaseReason("Pod was deleted unexpectedly. Last known pod phase: " + podStatus)
                            .containerStateReason(null)
                            .build());
        }
    }

    /**
     * Turns Kubernetes warning Events about pods we launched (FailedMount, FailedAttachVolume, FailedScheduling)
     * into status-change events so their message lands in the container history. Pod status alone never
     * carries these reasons; the pod just sits in ContainerCreating.
     */
    static class PodWarningEventHandler implements ResourceEventHandler<CoreV1Event> {
        static final Set<String> REASONS_OF_INTEREST = new HashSet<>(Arrays.asList(
                "FailedMount", "FailedAttachVolume", "FailedScheduling"));
        private static final String WARNING = "Warning";

        private final JmsTemplate template;
        private final Function<String, V1Pod> podLookup;

        PodWarningEventHandler(final JmsTemplate template, final Function<String, V1Pod> podLookup) {
            this.template = template;
            this.podLookup = podLookup;
        }

        private static boolean isInteresting(final CoreV1Event event) {
            final V1ObjectReference involved = event.getInvolvedObject();
            return WARNING.equals(event.getType())
                    && event.getReason() != null && REASONS_OF_INTEREST.contains(event.getReason())
                    && involved != null && "Pod".equals(involved.getKind()) && involved.getName() != null;
        }

        private void handle(final CoreV1Event event) {
            if (!isInteresting(event)) {
                return;
            }
            final V1Pod pod = podLookup.apply(event.getInvolvedObject().getName());
            final String jobName = pod == null ? null : jobNameFromPodLabels(pod);
            if (jobName == null) {
                return;
            }
            final OffsetDateTime timestamp = event.getLastTimestamp() != null ? event.getLastTimestamp() : event.getEventTime();
            final KubernetesStatusChangeEvent statusEvent = new KubernetesStatusChangeEvent(
                    jobName,
                    event.getInvolvedObject().getName(),
                    null,
                    KubernetesPodPhase.PENDING,
                    event.getReason() + ": " + event.getMessage(),
                    null,
                    null,
                    null,
                    timestamp,
                    null
            );
            log.debug("Triggering event {}", statusEvent);
            QueueUtils.sendJmsRequest(template, ContainerEvent.QUEUE, statusEvent);
        }

        @Override
        public void onAdd(final CoreV1Event obj) {
            handle(obj);
        }

        @Override
        public void onUpdate(final CoreV1Event oldObj, final CoreV1Event newObj) {
            // Kubernetes bumps count/lastTimestamp on repeats; only a changed message is new information
            if (oldObj != null && Objects.equals(oldObj.getMessage(), newObj.getMessage())
                    && Objects.equals(oldObj.getReason(), newObj.getReason())) {
                return;
            }
            handle(newObj);
        }

        @Override
        public void onDelete(final CoreV1Event obj, final boolean deletedFinalStateUnknown) {
            // nothing to do
        }
    }
}
