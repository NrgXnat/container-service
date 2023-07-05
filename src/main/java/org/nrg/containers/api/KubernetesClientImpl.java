package org.nrg.containers.api;

import com.google.common.annotations.VisibleForTesting;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Affinity;
import io.kubernetes.client.openapi.models.V1AffinityBuilder;
import io.kubernetes.client.openapi.models.V1ContainerPort;
import io.kubernetes.client.openapi.models.V1ContainerPortBuilder;
import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1EnvVarBuilder;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobBuilder;
import io.kubernetes.client.openapi.models.V1NodeSelectorRequirement;
import io.kubernetes.client.openapi.models.V1NodeSelectorRequirementBuilder;
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
import okhttp3.logging.HttpLoggingInterceptor;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.exceptions.ContainerBackendException;
import org.nrg.containers.exceptions.ContainerException;
import org.nrg.containers.exceptions.NoContainerServerException;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.secrets.ContainerPropertiesWithSecretValues;
import org.nrg.containers.utils.KubernetesConfiguration;
import org.nrg.containers.utils.ShellSplitter;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.framework.services.NrgEventServiceI;

import java.io.File;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static io.kubernetes.client.util.Config.SERVICEACCOUNT_CA_PATH;
import static org.apache.commons.httpclient.HttpStatus.SC_BAD_REQUEST;
import static org.nrg.containers.utils.ContainerUtils.SECONDS_PER_WEEK;

@Slf4j
public class KubernetesClientImpl implements KubernetesClient {
    // Format: (label)(comparator)(value)
    //  comparator can be != or ==
    private static final String LABEL_CAPTURE_GROUP = "label";
    private static final String COMPARATOR_CAPTURE_GROUP = "comparator";
    private static final String VALUE_CAPTURE_GROUP = "value";
    public static final Pattern SWARM_CONSTRAINT_PATTERN = Pattern.compile("(node\\.labels\\.)?(?<" + LABEL_CAPTURE_GROUP +">.+?)(?<" + COMPARATOR_CAPTURE_GROUP + ">!=|==)(?<" + VALUE_CAPTURE_GROUP +">.+?)");

    public static final int JOB_TIME_TO_LIVE_WEEKS = 1;  // Clean up lingering jobs + pods + logs after one week
    public static final int JOB_TIME_TO_LIVE_SECS = JOB_TIME_TO_LIVE_WEEKS * SECONDS_PER_WEEK;
    public static final int JOB_BACKOFF_LIMIT = 0;  // No retries
    public static final String POD_RESTART_POLICY = "Never";

    public static final String CONTAINER_CREATING = "ContainerCreating";

    private final ExecutorService executorService;
    private final NrgEventServiceI eventService;

    private ApiClient apiClient;
    private CoreV1Api coreApi;
    private BatchV1Api batchApi;
    private String namespace;
    private KubernetesInformer kubernetesInformer = null;


    public KubernetesClientImpl(
            final ExecutorService executorService,
            final NrgEventServiceI eventService
    ) throws IOException, NoContainerServerException {
        this.executorService = executorService;
        this.eventService = eventService;

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

        // Turn on debug logging within the HTTP client if we are debugging out here
        if (log.isDebugEnabled()) {
            // This creates a logging interceptor in okhttp's style that hands messages to our class's logger instance
            final HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor(log::debug);
            loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);
            apiClient.setHttpClient(
                    apiClient.getHttpClient()
                            .newBuilder()
                            .addInterceptor(loggingInterceptor)
                            .build()
            );
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
            log.trace("Ping failed: message \"{}\" code {} body \"{}\"", e.getMessage(), e.getCode(), e.getResponseBody());
            throw new ContainerBackendException(e);
        }
        return "OK";
    }

    @Override
    public String getLog(final String podName, final LogType logType, final Boolean withTimestamp, final OffsetDateTime since)
            throws ContainerBackendException {
        if (logType == LogType.STDERR) {
            // Kubernetes does not split stdout and stderr logs.
            log.debug("Skipping stderr log request for pod {}", podName);
            return null;
        }

        // Our since value is a timestamp, but the kubernetes API wants a relative "seconds before now"
        final OffsetDateTime now = OffsetDateTime.now();
        if (since != null && !since.isBefore(now)) {
            // This can happen when the UI is streaming logs.
            // It will get the logs and ask for more in less than a second.
            // The API doesn't allow us to get logs with more precision than one second,
            //  so we can treat this as if no logs were created in the interval.
            return null;
        }
        final Integer sinceRelative = since == null ? null : Math.toIntExact(ChronoUnit.SECONDS.between(now, since));

        try {
            return coreApi.readNamespacedPodLog(podName, namespace, null, null, null, null, null, null, sinceRelative, null, withTimestamp);
        } catch (ApiException e) {
            if (e.getCode() == SC_BAD_REQUEST && e.getResponseBody() != null && e.getResponseBody().contains(CONTAINER_CREATING)) {
                // Kubernetes returns an error when we try to read logs for a container that is being created,
                //  but we don't need to consider that an error
                log.info("Could not read log for pod \"{}\". Container is creating. message \"{}\" code {} body {}", podName, e.getMessage(), e.getCode(), e.getResponseBody());
                return null;
            }
            log.error("Could not read log for pod {}: message \"{}\" code {} body {}", podName, e.getMessage(), e.getCode(), e.getResponseBody(), e);
            throw new ContainerBackendException("Could not read log", e);
        }
    }

    @Override
    public String createJob(final Container toCreate, final DockerControlApi.NumReplicas numReplicas, final String serverContainerUser, final String gpuVendor)
            throws ContainerBackendException, ContainerException {
        log.debug("Creating kubernetes job");

        Long containerUserId = null;
        if (StringUtils.isNotBlank(serverContainerUser)) {
            try {
                containerUserId = Long.parseLong(serverContainerUser);
            } catch (NumberFormatException e) {
                // We would have caught this earlier when setting the DockerServer. But just in case...
                log.error("Container user ID \"{}\" is not an integer ID, which is incompatible with Kubernetes backend. Ignoring value.", serverContainerUser);
            }
        }

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
        final V1Affinity affinity = parseSwarmConstraints(toCreate.swarmConstraints());

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
            if (StringUtils.isNotBlank(ourMounts.get(0).mountPvcName())) {
                Set<V1Volume> volumesSet = new HashSet<>();
                for (final Container.ContainerMount mount : ourMounts) {
                    volumesSet.add(new V1VolumeBuilder()
                            .withName(mount.mountPvcName())
                            .withNewPersistentVolumeClaim()
                            .withClaimName(mount.mountPvcName())
                            .withReadOnly(!mount.writable())
                            .endPersistentVolumeClaim()
                            .build());
                    mounts.add(new V1VolumeMountBuilder()
                            .withName(mount.mountPvcName())
                            .withMountPath(mount.containerPath())
                            .withSubPath(mount.containerHostPath())
                            .withReadOnly(!mount.writable())
                            .build());
                }
                volumes.addAll(volumesSet);
            } else {
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
        }

        // Work-around to support configurable shm-size
        // See https://stackoverflow.com/a/46434614
        final Long shmSize = toCreate.shmSize();
        if (shmSize != null && shmSize > 0) {
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
        if (toCreate.genericResources()!=null) {
            final String gpuNumber = toCreate.genericResources().get("gpu");
            if (StringUtils.isNotEmpty(gpuNumber)) {
                if (StringUtils.isNotEmpty(gpuVendor)) {
                    resourceRequirementsBuilder.addToLimits(gpuVendor.toLowerCase() + ".com/gpu", new Quantity(String.valueOf(gpuNumber)));
                } else {
                    log.error("When the value of the GPU vendor in the Kubernetes cluster is empty or null, the GPU resource cannot be requested.");
                    throw new ContainerException("When the value of the GPU vendor in the Kubernetes cluster is empty or null, the GPU resource cannot be requested.");
                }
            }
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

        // Secrets
        final ContainerPropertiesWithSecretValues containerPropertiesWithSecretValues =
                ContainerPropertiesWithSecretValues.prepareSecretsForLaunch(toCreate);

        // Environment variables
        final List<V1EnvVar> envVars = containerPropertiesWithSecretValues.environmentVariables()
                .entrySet().stream()
                .map(entry -> new V1EnvVarBuilder().withName(entry.getKey()).withValue(entry.getValue()).build())
                .collect(Collectors.toList());

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
                .withNewTemplate()
                .withNewMetadata()
                .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                .withAffinity(affinity)
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
                            "\n\timage {}" +
                            "\n\tcommand \"{}\"" +
                            "\n\tworking directory \"{}\"" +
                            "\n\tcontainerUser \"{}\"" +
                            "\n\tvolumes [{}]" +
                            "\n\texposed ports: {}",
                    toCreate.dockerImage(),
                    toCreate.commandLine(),
                    toCreate.workingDirectory(),
                    containerUserId,
                    StringUtils.join(toCreate.bindMountStrings(), ", "),
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

    @VisibleForTesting
    public static ParsedConstraint parseSwarmConstraint(String constraint) throws ContainerBackendException {
        final Matcher m = SWARM_CONSTRAINT_PATTERN.matcher(constraint);
        if (m.matches()) {
            return ParsedConstraint.fromComparator(m.group(LABEL_CAPTURE_GROUP), m.group(COMPARATOR_CAPTURE_GROUP), m.group(VALUE_CAPTURE_GROUP));
        } else {
            throw new ContainerBackendException("Improperly formatted constraint: " + constraint);
        }
    }

    @VisibleForTesting
    public static V1Affinity parseSwarmConstraints(List<String> constraints) throws ContainerBackendException {
        log.info("Constraints {}", constraints);
        if (constraints == null || constraints.isEmpty()) {
            return null;
        }
        // The constraints in the Container have already been baked into strings with swarm's syntax.
        // We need to parse the string values.

        // Can't use a stream.map because parseSwarmConstraints can throw
        final List<ParsedConstraint> parsedConstraints = new ArrayList<>(constraints.size());
        for (final String constraint : constraints) {
            parsedConstraints.add(parseSwarmConstraint(constraint));
        }

        // Translate the constraints into the kubernetes object format.
        // https://kubernetes.io/docs/concepts/scheduling-eviction/assign-pod-node/#node-affinity
        // In the pod spec (yaml format) our constraints will translate to node affinity like so:
        //
        // spec:
        //  affinity:
        //    nodeAffinity:
        //      requiredDuringSchedulingIgnoredDuringExecution:
        //        nodeSelectorTerms:
        //        - matchExpressions:
        //          - key: <key>
        //            operator: <"In" if comparator is "==", "NotIn" if comparator is "!=">
        //            values:
        //            - <value>

        // Group the constraints by unique key, operator pairs and collect the values into a list.
        final Map<ConstraintKey, List<String>> constraintMap = parsedConstraints.stream()
                .collect(Collectors.groupingBy(ParsedConstraint::asKey,
                        Collectors.mapping(ParsedConstraint::value, Collectors.toList())));

        // Create a match expression for each grouped constraint
        final List<V1NodeSelectorRequirement> matchExpressions = constraintMap.entrySet().stream()
                .map(entry -> new V1NodeSelectorRequirementBuilder()
                        .withKey(entry.getKey().label)
                        .withOperator(entry.getKey().operator)
                        .withValues(entry.getValue())
                        .build()
                ).collect(Collectors.toList());

        // Build the rest of the affinity infrastructure around the match expressions
        return new V1AffinityBuilder()
                .withNewNodeAffinity()
                .withNewRequiredDuringSchedulingIgnoredDuringExecution()
                .addNewNodeSelectorTerm()
                .withMatchExpressions(matchExpressions)
                .endNodeSelectorTerm()
                .endRequiredDuringSchedulingIgnoredDuringExecution()
                .endNodeAffinity()
                .build();
    }

    @VisibleForTesting
    public static class ParsedConstraint {
        final String label;
        final String operator;
        final String value;

        ParsedConstraint(String label, String operator, String value) {
            this.label = label;
            this.operator = operator;
            this.value = value;
        }

        static ParsedConstraint fromComparator(String label, String comparator, String value) {
            return new ParsedConstraint(label, constraintComparatorToKubernetesOperator(comparator), value);
        }

        ConstraintKey asKey() {
            return new ConstraintKey(label, operator);
        }

        public String label() {
            return label;
        }

        public String operator() {
            return operator;
        }

        public String value() {
            return value;
        }
    }

    private static class ConstraintKey {
        final String label;
        final String operator;

        ConstraintKey(String label, String operator) {
            this.label = label;
            this.operator = operator;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ConstraintKey that = (ConstraintKey) o;
            return Objects.equals(label, that.label) && Objects.equals(operator, that.operator);
        }

        @Override
        public int hashCode() {
            return Objects.hash(label, operator);
        }
    }

    private static String constraintComparatorToKubernetesOperator(final String comparator) {
        if (StringUtils.equals(comparator, "==")) {
            return "In";
        } else if (StringUtils.equals(comparator, "!=")) {
            return "NotIn";
        }
        return null;
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
        } catch (ApiException e) {
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
