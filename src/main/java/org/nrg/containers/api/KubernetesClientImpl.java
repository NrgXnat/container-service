package org.nrg.containers.api;

import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ContainerPort;
import io.kubernetes.client.openapi.models.V1ContainerPortBuilder;
import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1EnvVarBuilder;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobBuilder;
import io.kubernetes.client.openapi.models.V1LabelSelector;
import io.kubernetes.client.openapi.models.V1LabelSelectorBuilder;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
import io.kubernetes.client.openapi.models.V1ResourceRequirementsBuilder;
import io.kubernetes.client.openapi.models.V1SecurityContext;
import io.kubernetes.client.openapi.models.V1SecurityContextBuilder;
import io.kubernetes.client.openapi.models.V1Volume;
import io.kubernetes.client.openapi.models.V1VolumeBuilder;
import io.kubernetes.client.openapi.models.V1VolumeMount;
import io.kubernetes.client.openapi.models.V1VolumeMountBuilder;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.KubeConfig;
import io.kubernetes.client.util.Namespaces;
import io.kubernetes.client.util.PatchUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.exceptions.ContainerBackendException;
import org.nrg.containers.exceptions.ContainerException;
import org.nrg.containers.exceptions.NoContainerServerException;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.utils.KubernetesConfiguration;
import org.nrg.containers.utils.ShellSplitter;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.framework.services.NrgEventServiceI;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static io.kubernetes.client.util.Config.SERVICEACCOUNT_CA_PATH;
import static org.nrg.containers.utils.ContainerUtils.SECONDS_PER_WEEK;

@Slf4j
public class KubernetesClientImpl implements KubernetesClient {
    // Format: (label)(comparator)(value)
    //  comparator can be != or ==
    public static final Pattern SWARM_CONSTRAINT_PATTERN = Pattern.compile("(?<label>.+)(?<comparator>!=|==)(?<value>.+)");

    public static final int JOB_TIME_TO_LIVE_WEEKS = 1;  // Clean up lingering jobs + pods + logs after one week
    public static final int JOB_TIME_TO_LIVE_SECS = JOB_TIME_TO_LIVE_WEEKS * SECONDS_PER_WEEK;
    public static final int JOB_BACKOFF_LIMIT = 0;  // No retries
    public static final String POD_RESTART_POLICY = "Never";

    private final ExecutorService executorService;
    private final NrgEventServiceI eventService;

    private final DockerServer dockerServer;
    private final Long containerUserId;
    private ApiClient apiClient;
    private CoreV1Api coreApi;
    private BatchV1Api batchApi;
    private String namespace;
    private KubernetesInformer kubernetesInformer = null;


    public KubernetesClientImpl(
            ExecutorService executorService,
            NrgEventServiceI eventService,
            DockerServer dockerServer
    ) throws IOException, NoContainerServerException {
        this.executorService = executorService;
        this.eventService = eventService;
        this.dockerServer = dockerServer;

        final String serverContainerUser = dockerServer.containerUser();
        Long containerUserId = null;
        if (StringUtils.isNotBlank(serverContainerUser)) {
            try {
                containerUserId = Long.parseLong(serverContainerUser);
            } catch (NumberFormatException e) {
                // We would have caught this earlier when setting the DockerServer. But just in case...
                log.error("Container user ID \"{}\" is not an integer ID, which is incompatible with Kubernetes backend. Ignoring value.", serverContainerUser);
            }
        }
        this.containerUserId = containerUserId;

        ApiClient apiClient = null;
        String namespace = null;
        final KubeConfig kubeConfig = KubernetesConfiguration.loadStandard(true);
        if (kubeConfig != null) {
            apiClient = ClientBuilder.kubeconfig(kubeConfig).build();

            namespace = kubeConfig.getNamespace();
            if (StringUtils.isBlank(namespace)) {
                namespace = Namespaces.NAMESPACE_DEFAULT;
            }
        } else {
            final File clusterCa = new File(SERVICEACCOUNT_CA_PATH);
            if (clusterCa.exists()) {
                // We are running within a pod in the cluster
                apiClient = ClientBuilder.cluster().build();
                namespace = Namespaces.getPodNamespace();
            }
        }

        if (apiClient == null) {
            throw new NoContainerServerException("Could not read kubernetes configuration");
        }

        setBackendClient(apiClient);
        setNamespace(namespace);
    }


    @Override
    public ApiClient getBackendClient() {
        return apiClient;
    }

    @Override
    public void setBackendClient(ApiClient apiClient) {
        this.apiClient = apiClient;
        coreApi = new CoreV1Api(apiClient);
        batchApi = new BatchV1Api(apiClient);

        restart();
    }

    @Override
    public String getNamespace() {
        return namespace;
    }

    @Override
    public void setNamespace(String namespace) {
        this.namespace = namespace;
        restart();
    }

    @Override
    public synchronized void start() {
        if (kubernetesInformer == null) {
            kubernetesInformer = new KubernetesInformerImpl(namespace, apiClient, executorService, eventService);
        }
        kubernetesInformer.start();
    }

    @Override
    public synchronized void stop() {
        if (kubernetesInformer != null) {
            kubernetesInformer.stop();
            kubernetesInformer = null;
        }
    }

    private synchronized void restart() {
        if (kubernetesInformer != null) {
            stop();
            start();
        }
    }

    @Override
    public String ping() throws ContainerBackendException {
        try {
            // TODO
            //  I don't know that "list pods" is the best API call to make to see if we can connect.
            //  Kubernetes has health checks for the API itself https://kubernetes.io/docs/reference/using-api/health-checks/
            //  but currently the API client cannot get at them.
            //  See https://github.com/kubernetes-client/java/issues/2189
            coreApi.listNamespacedPod(namespace, null, null, null, null, null, null, null, null, null, null);
            // if we get here everything is good
        } catch (ApiException e) {
            log.error("Ping failed: message \"{}\" code {} body \"{}\"", e.getMessage(), e.getCode(), e.getResponseBody());
            throw new ContainerBackendException(e);
        }
        return "OK";
    }

    @Override
    public String getLog(final String podName, final ContainerControlApi.LogType logType, final Boolean withTimestamp, final Integer since)
            throws ContainerBackendException {
        if (logType == ContainerControlApi.LogType.STDERR) {
            // Kubernetes does not split stdout and stderr logs.
            log.debug("Skipping stderr log request for pod {}", podName);
            return null;
        }

        // Our since value is a unix timestamp, but the kubernetes API wants a relative "seconds before now"
        final long currentUnixTimestamp = Instant.now().getEpochSecond();
        final Integer sinceRelative = since == null ? null : Math.toIntExact(currentUnixTimestamp) - since;

        try {
            return coreApi.readNamespacedPodLog(podName, namespace, null, null, null, null, null, null, sinceRelative, null, withTimestamp);
        } catch (ApiException e) {
            log.error("Could not read log for pod {}: message \"{}\" code {} body {}", podName, e.getMessage(), e.getCode(), e.getResponseBody(), e);
            throw new ContainerBackendException("Could not read log", e);
        }
    }

    @Override
    public String createJob(final Container toCreate, final DockerControlApi.NumReplicas numReplicas)
            throws ContainerBackendException, ContainerException {
        log.debug("Creating kubernetes job");

        // Ulimits
        final Map<String, String> ulimits = toCreate.ulimits();
        if (!(ulimits == null || ulimits.isEmpty())) {
            // It is possible to set ulimits using kubernetes. See https://stackoverflow.com/a/62136351.
            // But you have to add an init container to your pod with a privileged security context,
            //  which I can't guarantee we can do.
            // Plus it changes the ulimits setting on the entire node (I think...) which sounds iffy.
            log.debug("Ulimits command configuration ignored in kubernetes mode. ulimits={}", ulimits);
        }

        // Constraints
        final List<String> constraints = toCreate.swarmConstraints();
        final V1LabelSelectorBuilder labelSelectorBuilder = new V1LabelSelectorBuilder();
        if (!(constraints == null || constraints.isEmpty())) {
            // Unfortunately these constraints have already been baked into strings with swarm's syntax.
            // We need to parse the string values.

            // We will store the constraints in separate maps based on comparator
            final Map<String, List<String>> equalConstraints = new HashMap<>();
            final Map<String, List<String>> unequalConstraints = new HashMap<>();
            for (final String constraint : constraints) {
                final Matcher m = SWARM_CONSTRAINT_PATTERN.matcher(constraint);
                try {
                    final String label = m.group("label");
                    final String comparator = m.group("comparator");
                    final String value = m.group("value");

                    // Add to the appropriate map
                    (comparator.equals("==") ? equalConstraints : unequalConstraints)
                            .computeIfAbsent(label, k -> new ArrayList<>()).add(value);
                } catch (Exception e) {
                    throw new ContainerException("Improperly formatted constraint", e);
                }
            }

            // Now we translate the constraints into the kubernetes object format:
            // https://kubernetes.io/docs/reference/kubernetes-api/common-definitions/label-selector/#LabelSelector
            for (final Map.Entry<String, List<String>> entry : equalConstraints.entrySet()) {
                labelSelectorBuilder.addNewMatchExpression()
                        .withKey(entry.getKey())
                        .withOperator("In")
                        .addAllToValues(entry.getValue())
                        .endMatchExpression();
            }
            for (final Map.Entry<String, List<String>> entry : unequalConstraints.entrySet()) {
                labelSelectorBuilder.addNewMatchExpression()
                        .withKey(entry.getKey())
                        .withOperator("NotIn")
                        .addAllToValues(entry.getValue())
                        .endMatchExpression();
            }
        }
        final V1LabelSelector selector = labelSelectorBuilder.build();

        // Environment variables
        final List<V1EnvVar> envVars = toCreate.environmentVariables().entrySet().stream().map(
                entry -> new V1EnvVarBuilder().withName(entry.getKey()).withValue(entry.getValue()).build()
        ).collect(Collectors.toList());

        // Labels
        final Map<String, String> labels = toCreate.containerLabels() == null ?
                new HashMap<>() :
                new HashMap<>(toCreate.containerLabels());
        // TODO add helpful labels. Things like command and wrapper IDs, maybe workflow id if we have one, etc.
        //  Or maybe those could be annotations?

        // Ports
        final Map<String, String> ourPorts = toCreate.ports() == null ? Collections.emptyMap() : toCreate.ports();
        final List<V1ContainerPort> ports = ourPorts.entrySet().stream().map(e -> new V1ContainerPortBuilder()
                .withContainerPort(Integer.parseInt(e.getKey()))
                .withHostPort(Integer.parseInt(e.getValue()))
                .build()).collect(Collectors.toList());

        // Mounts
        final List<Container.ContainerMount> ourMounts = toCreate.mounts();
        final List<V1VolumeMount> mounts = new ArrayList<>();
        final List<V1Volume> volumes = new ArrayList<>();
        if (ourMounts != null && !ourMounts.isEmpty()) {
            for (final Container.ContainerMount mount : ourMounts) {
                // For each mount we create a Volume and a VolumeMount with the same name.
                // Volumes are set on the Pod, VolumeMounts are set on the Container.
                // We assume that all mounts we make are already available on all worker nodes,
                //  which implies we can mount the files we want at a host path.
                volumes.add(new V1VolumeBuilder()
                        .withName(mount.name())
                        .withNewHostPath()
                        .withPath(mount.xnatHostPath())
                        .endHostPath()
                        .build());
                mounts.add(new V1VolumeMountBuilder()
                        .withName(mount.name())
                        .withMountPath(mount.containerPath())
                        .withReadOnly(!mount.writable())
                        .build());
            }
        }

        // Work-around to support configurable shm-size
        // See https://stackoverflow.com/a/46434614
        final Long shmSize = toCreate.shmSize();
        if (shmSize != null && shmSize > 0){
            final String shmVolName = "shm";
            volumes.add(new V1VolumeBuilder()
                    .withName(shmVolName)
                    .withNewEmptyDir()
                    .withMedium("Memory")
                    .withNewSizeLimit(String.valueOf(shmSize))
                    .endEmptyDir()
                    .build()
            );
            mounts.add(new V1VolumeMountBuilder()
                    .withMountPath("/dev/shm")
                    .withName(shmVolName)
                    .build()
            );
        }

        // Command line and args
        final List<String> command;
        final List<String> args;
        if (toCreate.overrideEntrypointNonnull()) {
            // Run their command line as a script through a shell
            command = Arrays.asList("/bin/sh", "-c");
            args = Collections.singletonList(toCreate.commandLine());
        } else {
            // Run their command line as args (tokenized and split) through the image's entrypoint
            args = ShellSplitter.shellSplit(toCreate.commandLine());
            command = null;
        }

        // User ID
        final V1SecurityContext securityContext = new V1SecurityContextBuilder()
                .withRunAsUser(containerUserId)
                .build();

        // Resource requests and limits
        final V1ResourceRequirementsBuilder resourceRequirementsBuilder = new V1ResourceRequirementsBuilder();
        if (toCreate.reserveMemory() != null) {
            resourceRequirementsBuilder.addToRequests("memory", new Quantity(String.valueOf(toCreate.reserveMemoryBytes())));
        }
        // TODO Seems like we are missing a reserve CPU option
        if (toCreate.limitMemory() != null) {
            resourceRequirementsBuilder.addToLimits("memory", new Quantity(String.valueOf(toCreate.limitMemoryBytes())));
        }
        if (toCreate.limitCpu() != null) {
            resourceRequirementsBuilder.addToLimits("cpu", new Quantity(String.valueOf(toCreate.limitCpu())));
        }
        final V1ResourceRequirements resources = resourceRequirementsBuilder.build();

        // Network
        // TODO I don't know how this works so for now I'm ignoring it
        if (toCreate.network() != null) {
            log.debug("Ignoring container network setting \"{}\"", toCreate.network());
        }

        // Replicas
        // Set to zero to mimic separate "create"/"start" or set to 1 to start immediately
        final boolean suspend = numReplicas == DockerControlApi.NumReplicas.ZERO;

        // Container name (also used to generate a job name)
        final String name = toCreate.containerNameOrRandom();

        // Build job
        V1Job job = new V1JobBuilder()
                .withNewMetadata()
                .withGenerateName(name)  // Container name used as job name prefix
                .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                .withTtlSecondsAfterFinished(JOB_TIME_TO_LIVE_SECS)
                .withBackoffLimit(JOB_BACKOFF_LIMIT)
                .withSuspend(suspend)
                .withSelector(selector)
                .withNewTemplate()
                .withNewMetadata()
                .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                .withRestartPolicy(POD_RESTART_POLICY)
                .withVolumes(volumes)
                .addNewContainer()
                .withName(name)
                .withImage(toCreate.dockerImage())
                .withCommand(command)
                .withArgs(args)
                .withWorkingDir(toCreate.workingDirectory())
                .withEnv(envVars)
                .withVolumeMounts(mounts)
                .withPorts(ports)
                .withResources(resources)
                .withSecurityContext(securityContext)
                .endContainer()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();

        if (log.isTraceEnabled()) {
            log.trace("Creating kubernetes job: {}", job);
        } else if (log.isDebugEnabled()) {
            log.debug("Creating kubernetes job:" +
                            "\n\tserver {} {}" +
                            "\n\timage {}" +
                            "\n\tcommand \"{}\"" +
                            "\n\tworking directory \"{}\"" +
                            "\n\tcontainerUser \"{}\"" +
                            "\n\tvolumes [{}]" +
                            "\n\tenvironment variables [{}]" +
                            "\n\texposed ports: {}",
                    dockerServer.name(), dockerServer.host(),
                    toCreate.dockerImage(),
                    toCreate.commandLine(),
                    toCreate.workingDirectory(),
                    dockerServer.containerUser(),
                    StringUtils.join(toCreate.bindMountStrings(), ", "),
                    StringUtils.join(toCreate.environmentVariableStrings(), ", "),
                    StringUtils.join(toCreate.portStrings(), ", "));
        }

        try {
            job = batchApi.createNamespacedJob(namespace, job, null, null, null, null);
            final V1ObjectMeta meta = job.getMetadata();
            final String jobName = meta == null ? null : meta.getName();
            log.debug("Created job {}", jobName);
            return jobName;
        } catch (ApiException e) {
            log.error("Could not create job: message \"{}\" code {} body {}", e.getMessage(), e.getCode(), e.getResponseBody(), e);
            throw new ContainerBackendException("Could not create job", e);
        }
    }

    @Override
    public void unsuspendJob(final String jobName)
            throws ContainerBackendException {
        // Patch the job spec's suspend property
        final V1Patch patch = new V1Patch("{\"spec\": {\"suspend\": false}}");
        try {

            // Write updated job definition
            log.debug("Unsuspending kubernetes job {}", jobName);

            // We use PatchUtils.patch here rather than directly calling
            // batchApi.patchNamespacedJob because the latter sets a header saying
            // that the patch is formatted as a json patch, but we have formatted ours as
            // a strategic merge patch.
            // PatchUtils.patch lets us set that header.
            PatchUtils.patch(
                    V1Job.class,
                    () -> batchApi.patchNamespacedJobCall(jobName, namespace, patch, null, null, null, null, null, null),
                    V1Patch.PATCH_FORMAT_STRATEGIC_MERGE_PATCH,
                    batchApi.getApiClient()
            );
        }  catch (ApiException e) {
            log.error("Could not start job {}: message \"{}\" code {} body {}", jobName, e.getMessage(), e.getCode(), e.getResponseBody(), e);
            throw new ContainerBackendException("Could not start job " + jobName, e);
        }
    }

    @Override
    public void removeJob(final String jobName) throws NotFoundException, ContainerBackendException {
        try {
            log.debug("Removing job {}", jobName);
            batchApi.deleteNamespacedJob(jobName, namespace, null, null, null, null, Propagation.Foreground.name(), null);
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                log.error("Could not remove job {}: not found", jobName);
                throw new NotFoundException(e);
            } else {
                log.error("Could not remove job {}: message \"{}\" code {} body {}", jobName, e.getMessage(), e.getCode(), e.getResponseBody(), e);
                throw new ContainerBackendException("Could not remove job " + jobName, e);
            }
        }
    }

    /**
     * Parameter for deleting objects in Kubernetes API
     */
    public enum Propagation {
        Orphan,
        Background,
        Foreground;
    }
}
