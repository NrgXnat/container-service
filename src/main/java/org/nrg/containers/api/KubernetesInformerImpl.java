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
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1PodStatus;
import io.kubernetes.client.util.CallGeneratorParams;
import lombok.extern.slf4j.Slf4j;
import org.nrg.containers.events.model.KubernetesContainerState;
import org.nrg.containers.events.model.KubernetesStatusChangeEvent;
import org.nrg.containers.model.kubernetes.KubernetesPodPhase;
import org.nrg.framework.services.NrgEventServiceI;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

@Slf4j
public class KubernetesInformerImpl implements KubernetesInformer {
    private static final int RESYNC_PERIOD_MILLISECONDS = 10000;

    private final SharedInformerFactory sharedInformerFactory;
    private volatile boolean isStarted;

    private final Lister<V1Job> jobLister;
    private final Lister<V1Pod> podLister;

    public KubernetesInformerImpl(final String namespace,
                                  final ApiClient apiClient,
                                  final ExecutorService executorService,
                                  final NrgEventServiceI eventService) {

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
                V1Job.class, V1JobList.class, RESYNC_PERIOD_MILLISECONDS);
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
                V1Pod.class, V1PodList.class, RESYNC_PERIOD_MILLISECONDS);
        podInformer.addEventHandler(new PodEventHandler(eventService));
        podLister = new Lister<>(podInformer.getIndexer(), namespace);

        if (log.isTraceEnabled()) {
            podInformer.addEventHandler(new LoggingResourceEventHandler<>());
            jobInformer.addEventHandler(new LoggingResourceEventHandler<>());
        }

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

    static class LoggingResourceEventHandler<T extends KubernetesObject> implements ResourceEventHandler<T> {
        @Override
        public void onAdd(T obj) {
            log.trace("Added {} {}\n{}", obj.getKind(), obj.getMetadata().getName(), obj);
        }

        @Override
        public void onUpdate(T oldObj, T newObj) {
            if (newObj.getMetadata().getResourceVersion().equals(oldObj.getMetadata().getResourceVersion())) {
                // Nothing changed. Informer just periodically rebuilds its internal cache.
                log.trace("Recached {} {}", newObj.getKind(), newObj.getMetadata().getName());
            } else {
                log.trace("Updated {} {}\n{}", newObj.getKind(), newObj.getMetadata().getName(), newObj);
            }
        }

        @Override
        public void onDelete(T obj, boolean deletedFinalStateUnknown) {
            log.trace("Deleted {} {}", obj.getKind(), obj.getMetadata().getName());
        }
    }

    static class PodEventHandler implements ResourceEventHandler<V1Pod> {
        private final NrgEventServiceI eventService;

        PodEventHandler(NrgEventServiceI eventService) {
            this.eventService = eventService;
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
            final V1ContainerStatus containerStatus = containerStatuses == null ? null : containerStatuses.get(0);

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

            return new KubernetesStatusChangeEvent(
                    jobNameFromPodLabels(pod),
                    objName(pod),
                    containerId,
                    podPhase,
                    podPhaseReason,
                    containerState,
                    containerStateReason,
                    exitCode,
                    timestamp
            );
        }

        private void triggerEvent(final KubernetesStatusChangeEvent event) {
            log.debug("Triggering event {}", event);
            eventService.triggerEvent(event);
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
                log.debug("Pod updated. No metadata?");
                return;
            }
            final String oldVersion = oldMeta.getResourceVersion();
            final String newVersion = newMeta.getResourceVersion();
            if (newVersion == null || newVersion.equals(oldVersion)) {
                // recache, nothing changed
                log.trace("Pod {} recached", objName(newObj));
                return;
            }

            final KubernetesStatusChangeEvent oldEvent = createEvent(oldObj);
            final KubernetesStatusChangeEvent newEvent = createEvent(newObj);
            if (newEvent.equals(oldEvent)) {
                log.debug("Pod {} updated, but derived event has not changed. Not throwing repeat event.", objName(newObj));
                return;
            }

            log.debug("Pod {} updated", newEvent.podName());
            triggerEvent(newEvent);
        }

        @Override
        public void onDelete(V1Pod obj, boolean deletedFinalStateUnknown) {
            // ignored
        }
    }
}
