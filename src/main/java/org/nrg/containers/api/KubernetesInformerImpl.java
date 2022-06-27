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
import org.nrg.containers.events.model.KubernetesStatusChangeEvent;
import org.nrg.containers.model.container.KubernetesJobInfo;
import org.nrg.framework.services.NrgEventServiceI;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;

@Slf4j
public class KubernetesInformerImpl implements KubernetesInformer {
    private static final int RESYNC_PERIOD_MILLISECONDS = 10000;

    private final SharedInformerFactory sharedInformerFactory;
    private volatile boolean isStarted;

    private final Lister<V1Job> jobLister;
    private final Lister<V1Pod> podLister;

    private final ConcurrentMap<String, V1Pod> jobNameToPodMap;

    public KubernetesInformerImpl(final String namespace,
                                  final ApiClient apiClient,
                                  final ExecutorService executorService,
                                  final NrgEventServiceI eventService) {
        jobNameToPodMap = new ConcurrentHashMap<>();

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
        podInformer.addEventHandler(new PodEventHandler(jobNameToPodMap, eventService));
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
            jobNameToPodMap.clear();
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

    @Override
    public V1Pod getPodForJob(final String jobName) {
        return jobNameToPodMap.get(jobName);
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
        private final Map<String, V1Pod> jobNameToPodMap;
        private final NrgEventServiceI eventService;

        PodEventHandler(Map<String, V1Pod> jobNameToPodMap,
                        NrgEventServiceI eventService) {
            this.jobNameToPodMap = jobNameToPodMap;
            this.eventService = eventService;
        }

        private void triggerEvent(V1Pod pod) {
            final V1PodStatus podStatus = pod.getStatus();

            // Status phase
            final String podPhase = podStatus == null ? null : podStatus.getPhase();

            // Pull out container statuses
            // We only ever launch jobs with a single template, so we only expect one container
            final List<V1ContainerStatus> containerStatuses = podStatus == null ? null : podStatus.getContainerStatuses();
            final V1ContainerStatus containerStatus = containerStatuses == null ? null : containerStatuses.get(0);

            // Docker container id
            final String containerId = containerStatus == null ? null : containerStatus.getContainerID();

            // Container state
            final V1ContainerState containerState = containerStatus == null ? null : containerStatus.getState();
            final V1ContainerStateTerminated terminated = containerState == null ? null : containerState.getTerminated();
            final V1ContainerStateRunning running = containerState == null ? null : containerState.getRunning();
            final V1ContainerStateWaiting waiting = containerState == null ? null : containerState.getWaiting();
            final String containerPhase = terminated != null ? terminated.getReason() :
                    waiting != null ? waiting.getReason() :
                            running != null ? "Running" : null;

            // exit code
            final Integer exitCode = terminated == null ? null : terminated.getExitCode();

            final KubernetesStatusChangeEvent event = new KubernetesStatusChangeEvent(
                    new KubernetesJobInfo(jobNameFromPodLabels(pod), objName(pod), containerId),
                    podPhase,
                    containerPhase,
                    exitCode
            );
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
            jobNameToPodMap.put(jobName, obj);

            triggerEvent(obj);
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
            String podName = newObj.getMetadata().getName();
            V1PodStatus oldStatus = oldObj.getStatus();
            V1PodStatus newStatus = newObj.getStatus();
            if (newStatus == null || newStatus.equals(oldStatus)) {
                log.debug("Pod {} updated, no status change", objName(newObj));
                return;
            }

            log.debug("Updated Pod {} Status phase \"{}\"", podName, newStatus.getPhase());

            triggerEvent(newObj);
        }

        @Override
        public void onDelete(V1Pod obj, boolean deletedFinalStateUnknown) {
            final String jobName = jobNameFromPodLabels(obj);
            if (jobName == null) {
                return;
            }
            jobNameToPodMap.remove(jobName);
        }
    }
}
