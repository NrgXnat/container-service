package org.nrg.containers.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.PingCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Task;
import com.github.dockerjava.api.model.TaskState;
import com.google.common.collect.Maps;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1NamespaceBuilder;
import io.kubernetes.client.openapi.models.V1Status;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.hamcrest.CustomTypeSafeMatcher;
import org.hamcrest.Matchers;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;
import org.nrg.containers.api.KubernetesClient;
import org.nrg.containers.api.KubernetesClientFactory;
import org.nrg.containers.api.KubernetesClientImpl;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.auto.ServiceTask;
import org.nrg.containers.model.server.docker.Backend;
import org.nrg.containers.model.xnat.FakeWorkflow;
import org.nrg.containers.model.xnat.Resource;
import org.nrg.containers.model.xnat.Session;
import org.nrg.containers.services.ContainerService;
import org.nrg.xft.XFTItem;
import org.nrg.xft.event.EventDetails;
import org.nrg.xft.event.persist.PersistentWorkflowI;
import org.nrg.xft.event.persist.PersistentWorkflowUtils;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.archive.ResourceData;
import org.nrg.xnat.helpers.uri.UriParserUtils;
import org.nrg.xnat.helpers.uri.URIManager;
import org.nrg.xnat.helpers.uri.archive.impl.ExptURI;
import org.nrg.xnat.services.archive.CatalogService;
import org.nrg.xnat.turbine.utils.ArchivableItem;
import org.nrg.xnat.utils.WorkflowUtils;
import org.springframework.test.context.transaction.TestTransaction;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isEmptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@Slf4j
public class TestingUtils {
    public static final String BUSYBOX = "busybox:latest";
    private static final String DEFAULT_DOCKER_SOCKET = "unix:///var/run/docker.sock";
    public static final boolean RUNNING_INTEGRATION_TESTS = Boolean.parseBoolean(System.getProperty("integration"));
    public static final boolean SKIP_KUBERNETES = Boolean.parseBoolean(System.getProperty("skipKubernetes"));

    public static void commitTransaction() {
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();
    }

    /**
     * Combine arrays into a big list of combined arrays. Useful for parameterizing tests.
     *
     * Example:
     * combinations({1, 2}, {true, false}) == [{1, true}, {2, true}, {1, false}, {2, false}]
     */
    public static Collection<Object[]> combinations(Object[]... paramArrays) {;
        int len = paramArrays.length;
        int[] divisors = new int[len];
        for (int i = 0; i < len; i++) {
            divisors[i] = i == 0 ? 1 : divisors[i-1] * paramArrays[i-1].length;
        }
        int numCombinations = divisors[len-1] * paramArrays[len-1].length;
        Collection<Object[]> combinations = new ArrayList<>(numCombinations);

        for (int combinationIdx = 0; combinationIdx < numCombinations; combinationIdx++) {
            Object[] combination = new Object[len];
            for (int placeIdx = 0; placeIdx < len; placeIdx++) {
                int paramIdx = (combinationIdx / divisors[placeIdx]) % paramArrays[placeIdx].length;
                combination[placeIdx] = paramArrays[placeIdx][paramIdx];
            }
            combinations.add(combination);
        }
        return combinations;
    }

    public static BackendConfig getBackendConfig() throws Exception {
        return getBackendConfig(null);
    }

    public static BackendConfig getBackendConfig(SystemDelegate systemDelegate) throws Exception {
        if (systemDelegate == null) {
            systemDelegate = new SysEnvSystemDelegate();
        }

        final String hostEnv = systemDelegate.getenv("DOCKER_HOST");
        final String certPathEnv = systemDelegate.getenv("DOCKER_CERT_PATH");
        final String tlsVerify = systemDelegate.getenv("DOCKER_TLS_VERIFY");

        final boolean useTls = tlsVerify != null && tlsVerify.equals("1");
        final String certPath;
        if (useTls) {
            if (StringUtils.isBlank(certPathEnv)) {
                throw new Exception("Must set DOCKER_CERT_PATH if DOCKER_TLS_VERIFY=1.");
            }
            certPath = certPathEnv;
        } else {
            certPath = "";
        }

        final String containerHost;
        if (StringUtils.isBlank(hostEnv)) {
            containerHost = DEFAULT_DOCKER_SOCKET;
        } else if (hostEnv.startsWith("tcp://")) {
            // Must switch out tcp:// for either http:// or https://
            containerHost = hostEnv.replace("tcp://", useTls ? "https://" : "http://");
        } else {
            containerHost = hostEnv;
        }

        return new BackendConfig(containerHost, certPath);
    }

    public interface SystemDelegate {
        String getenv(String key);
    }

    public static final class SysEnvSystemDelegate implements SystemDelegate {
        @Override
        public String getenv(String key) {
            return System.getenv(key);
        }
    }

    public static void skipIfCannotConnect(Backend backend, DockerClient dockerClient, ApiClient kubernetesClient) {
        switch (backend) {
            case DOCKER:
                skipIfCannotConnectToDocker(dockerClient);
                break;
            case SWARM:
                skipIfCannotConnectToSwarm(dockerClient);
                break;
            case KUBERNETES:
                skipIfCannotConnectToKubernetes(kubernetesClient);
                break;
        }
    }

    public static boolean canConnectToDocker(DockerClient client) {
        if (client == null) {
            return false;
        }

        try (final PingCmd cmd = client.pingCmd()) {
            cmd.exec();
            // No exception means we can connect
            return true;
        } catch (Exception ignored) {
            // Exceptions mean we cannot connect
            return false;
        }
    }

    public static void skipIfCannotConnectToDocker(DockerClient client) {
        // If we aren't running integration tests, we have already skipped this test
        if (RUNNING_INTEGRATION_TESTS) {
            assumeTrue("Cannot connect to docker", canConnectToDocker(client));
        }
    }

    public static void skipIfNotRunningIntegrationTests() {
        assumeTrue("Not running integration tests", RUNNING_INTEGRATION_TESTS);
    }

    public static boolean canConnectToSwarm(DockerClient client) {
        if (client == null) {
            return false;
        }

        try {
            client.inspectSwarmCmd().exec();

            // if we got here without an exception we're good
            return true;
        } catch (Exception ignored) {
            // Exceptions mean we cannot connect
            return false;
        }
    }

    public static void skipIfCannotConnectToSwarm(DockerClient client) {
        skipIfNotRunningIntegrationTests();
        assumeTrue("Cannot connect to swarm", canConnectToSwarm(client));
    }

    public static boolean canConnectToKubernetes(ApiClient client) {
        if (client == null) {
            return false;
        }
        try {
            CoreV1Api coreApi = new CoreV1Api(client);
            coreApi.listNamespace(null, null, null, null, null, null, null, null, null, null);

            // if we got here without an exception we're good
            return true;
        } catch (ApiException e) {
            // Exceptions mean we cannot connect
            return false;
        }
    }

    public static void skipIfCannotConnectToKubernetes(ApiClient client) {
        skipIfNotRunningIntegrationTests();
        assumeFalse("Kubernetes tests are switched off by -DskipKubernetes=true", SKIP_KUBERNETES);
        assumeTrue("Cannot connect to kubernetes", canConnectToKubernetes(client));
    }

    /**
     * The real Kubernetes client for the kubernetes backend, and a mock for the others, so that only the kubernetes
     * variants of a test depend on the local kubeconfig. Falls back to the mock, which the connection check then
     * skips, when no client can be built.
     */
    public static KubernetesClient kubernetesClientFor(final Backend backend, final KubernetesClientFactory kubernetesClientFactory) {
        if (backend != Backend.KUBERNETES || SKIP_KUBERNETES) {
            return mock(KubernetesClient.class);
        }
        try {
            return kubernetesClientFactory.getKubernetesClient();
        } catch (Exception | LinkageError e) {
            // LinkageError includes BouncyCastle missing from the test classpath, which a kubeconfig with certificates needs
            log.info("Cannot build a kubernetes client; kubernetes tests will be skipped", e);
            return mock(KubernetesClient.class);
        }
    }

    public static String createKubernetesNamespace(final KubernetesClient kubernetesClient) {
        if (kubernetesClient.getBackendClient() == null) {
            // A mock client: a CoreV1Api without one would fall back to building a default client from the kubeconfig
            return null;
        }
        String namespaceName = UUID.randomUUID().toString();
        CoreV1Api coreApi = new CoreV1Api(kubernetesClient.getBackendClient());
        try {
            log.debug("Creating namespace {}", namespaceName);
            V1Namespace namespace = new V1NamespaceBuilder()
                    .withNewMetadata()
                    .withName(namespaceName)
                    .endMetadata()
                    .build();
            coreApi.createNamespace(namespace, null, null, null, null);
            kubernetesClient.setNamespace(namespaceName);
            return namespaceName;
        } catch (Exception e) {
            log.debug("Failed to create namespace {}", namespaceName);
            return null;
        }
    }

    public static boolean cleanupKubernetesNamespace(final String namespaceName, final KubernetesClient kubernetesClient) {
        if (namespaceName == null || kubernetesClient.getBackendClient() == null) {
            return false;
        }
        CoreV1Api coreApi = new CoreV1Api(kubernetesClient.getBackendClient());
        try {
            log.debug("Deleting namespace {}", namespaceName);
            V1Status status = coreApi.deleteNamespace(namespaceName, null, null, null, null, KubernetesClientImpl.Propagation.Background.name(), null);
            return status != null && 200 == status.getCode();
        } catch (Exception e) {
            log.debug("Failed to delete namespace {}", namespaceName);
            return false;
        }
    }

    private static void dockerCleanup(DockerClient dockerClient, String containerId) {
        try {
            log.debug("Cleaning up container {}", containerId);
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
        } catch (NotFoundException ignored) {
            // ignored
        } catch (Exception e) {
            log.error("Could not clean up container {}", containerId, e);
        }
    }

    private static void dockerSwarmCleanup(DockerClient dockerClient, String serviceId) {
        try {
            log.debug("Cleaning up service {}", serviceId);
            dockerClient.removeServiceCmd(serviceId).exec();
        } catch (NotFoundException ignored) {
            // ignored
        } catch (Exception e) {
            log.error("Could not clean up service {}", serviceId, e);
        }
    }

    private static void kubernetesCleanupJob(BatchV1Api batchApi, String namespace, String jobName) {
        try {
            log.debug("Cleaning up job {}", jobName);
            batchApi.deleteNamespacedJob(jobName, namespace, null, null, null, null, KubernetesClientImpl.Propagation.Background.name(), null);
        } catch (ApiException e) {
            if (e.getCode() != 404) {
                log.error("Could not clean up job {}: code {} body {}", jobName, e.getCode(), e.getResponseBody());
            }
        } catch (Exception e) {
            log.error("Could not clean up job {}", jobName, e);
        }
    }

    public static Consumer<String> cleanupFunction(Backend backend, DockerClient dockerClient, ApiClient kubernetesClient, String kubernetesNamespace) throws Exception {
        switch (backend) {
            case DOCKER:
                return containerId -> dockerCleanup(dockerClient, containerId);
            case SWARM:
                return serviceId -> dockerSwarmCleanup(dockerClient, serviceId);
            case KUBERNETES:
                return jobName -> kubernetesCleanupJob(new BatchV1Api(kubernetesClient), kubernetesNamespace, jobName);
        }
        return null;
    }

    public static void cleanDockerImages(final DockerClient client, List<String> images) {
        images.forEach(image -> {
            try {
                client.removeImageCmd(image).withForce(true).withNoPrune(false).exec();
            } catch (NotFoundException ignored) {
                // ignored
            } catch (Exception e) {
                log.error("Could not clean up image {}", image, e);
            }
        });
    }

    /**
     * Adds the ID of every container and service recorded in the database, so teardown also removes those a test
     * created but never registered: the children of a parent it was still waiting on when it failed, or the
     * replacement service from a restart.
     */
    public static void addRecordedContainers(final ContainerService containerService, final Collection<String> toCleanUp) {
        try {
            for (final Container container : containerService.getAll()) {
                final String id = container.containerOrServiceId();
                if (id != null && !toCleanUp.contains(id)) {
                    toCleanUp.add(id);
                }
            }
        } catch (Exception e) {
            // Still clean up what the test registered
            log.error("Could not list recorded containers to clean up", e);
        }
    }

    public static void cleanDockerContainers(final DockerClient client, Collection<String> containers) {
        containers.forEach(container -> {
            try {
                client.removeContainerCmd(container).withForce(true).exec();
            } catch (NotFoundException ignored) {
                // ignored
            } catch (Exception e) {
                log.error("Could not clean up container {}", container, e);
            }
        });
    }

    public static void cleanSwarmServices(final DockerClient client, Collection<String> services) {
        services.forEach(service -> {
            try {
                client.removeServiceCmd(service).exec();
            } catch (NotFoundException ignored) {
                // ignored
            } catch (Exception e) {
                log.error("Could not clean up service {}", service, e);
            }
        });
    }

    @SuppressWarnings("unchecked")
    public static ArgumentMatcher<Map<String, String>> isMapWithEntry(final String key, final String value) {
        return new ArgumentMatcher<Map<String, String>>() {
            @Override
            public boolean matches(Map<String, String> argument) {
                if (argument == null) {
                    return false;
                }
                for (final Map.Entry<String, String> entry : argument.entrySet()) {
                    if (entry.getKey().equals(key) && entry.getValue().equals(value)) {
                        return true;
                    }
                }
                return false;
            }
        };
    }



    @SuppressWarnings("deprecation")
    public static String[] readFile(final String outputFilePath) throws IOException {
        return readFile(new File(outputFilePath));
    }

    @SuppressWarnings("deprecation")
    public static String[] readFile(final File file) throws IOException {
        if (!file.canRead()) {
            throw new IOException("Cannot read output file " + file.getAbsolutePath());
        }
        return FileUtils.readFileToString(file).split("\\n");
    }

    public static CustomTypeSafeMatcher<File> pathEndsWith(final String filePathEnd) {
        final String description = "Match a file path if it ends with " + filePathEnd;
        return new CustomTypeSafeMatcher<File>(description) {
            @Override
            protected boolean matchesSafely(final File item) {
                return item == null && filePathEnd == null ||
                        item != null && item.getAbsolutePath().endsWith(filePathEnd);
            }
        };
    }

    public static Callable<Boolean> containerIsRunning(final DockerClient client, final boolean swarmMode,
                                                       final Container container) {
        return () -> {
            try {
                if (swarmMode) {
                    final String serviceName = client.inspectServiceCmd(container.serviceId())
                            .exec()
                            .getSpec()
                            .getName();
                    return client.listTasksCmd()
                            .withServiceFilter(serviceName)
                            .exec()
                            .stream()
                            .noneMatch(task -> ServiceTask.isExitStatus(task.getStatus().getState().name()));
                } else {
                    return client.inspectContainerCmd(container.containerId()).exec().getState().getRunning();
                }
            } catch (NotFoundException ignored) {
                // Ignore exception. If container is not found, it is not running.
                return false;
            }
        };
    }

    public static Callable<Boolean> serviceIsRunning(final DockerClient client, final Container container) {
        return serviceIsRunning(client, container, false);
    }

    public static Callable<Boolean> serviceHasTaskId(final ContainerService containerService, final long containerDbId) {
        return () -> {
            try {
                if (StringUtils.isBlank(containerService.get(containerDbId).taskId())) {
                    return true;
                }
            } catch (Exception e) {
                // ignored
            }
            return false;
        };
    }

    public static Callable<Boolean> serviceIsRunning(final DockerClient client, final Container container,
                                                     boolean rtnForNoServiceId) {
        return () -> StringUtils.isBlank(container.serviceId()) ? rtnForNoServiceId : getServiceNode(client, container) != null;
    }

    public static String getServiceNode(final DockerClient client, final Container container) {
        try {
            final String serviceName = client.inspectServiceCmd(container.serviceId())
                    .exec()
                    .getSpec()
                    .getName();
            return client.listTasksCmd()
                    .withServiceFilter(serviceName)
                    .exec()
                    .stream()
                    .filter(task -> task.getStatus().getState().equals(TaskState.RUNNING))
                    .map(Task::getNodeId)
                    .findFirst()
                    .orElse(null);
        } catch (Exception ignored) {
            // Ignore exceptions
        }
        return null;
    }

    public static Container getContainerFromWorkflow(final ContainerService containerService,
                                                     final PersistentWorkflowI workflow) throws Exception {
        log.debug("Waiting until workflow {} has container id...", workflow.getWorkflowId());
        await().until(workflow::getComments, is(not(isEmptyOrNullString())));
        String containerId = workflow.getComments();
        log.debug("Got container id {} from workflow {}", containerId, workflow.getWorkflowId());

        log.debug("Reading container {} from db...", containerId);
        Container[] containerStorage = {null};  // Used to "return" value from closure
        await().pollInterval(10, TimeUnit.MILLISECONDS)
                .until(() -> {
                    try {
                        containerStorage[0] = containerService.get(containerId);
                        return containerStorage[0] != null;
                    } catch (org.nrg.framework.exceptions.NotFoundException ignored) {
                        return false;
                    }
                });
        Container container = containerStorage[0];
        log.debug("Got container {} {} from db", container.databaseId(), containerId);
        return containerStorage[0];
    }

    public static Callable<Boolean> containerIsFinalized(final ContainerService containerService,
                                                         final Container container) {
        return containerIsFinalized(containerService, container, null);
    }

    public static Callable<Boolean> containerIsFinalized(final ContainerService containerService,
                                                         final Container container,
                                                         final Container[] returnEnvelope) {
        return () -> {
            final Container containerFromDb = containerService.get(container.databaseId());
            if (returnEnvelope != null) {
                returnEnvelope[0] = containerFromDb;
            }
            final String status = containerFromDb.status();
            log.debug("Container {} status {}", container.databaseId(), status);
            return status != null &&
                    (status.equals(PersistentWorkflowUtils.COMPLETE) ||
                            status.startsWith(PersistentWorkflowUtils.FAILED));
        };
    }

    public static String setupSessionMock(TemporaryFolder folder, ObjectMapper mapper, Map<String, String> runtimeValues) throws Exception {
        final Path wrapupCommandDirPath = Paths.get(ClassLoader.getSystemResource("wrapupCommand").toURI());
        final String wrapupCommandDir = wrapupCommandDirPath.toString().replace("%20", " ");
        // Set up input object(s)
        final String sessionInputJsonPath = wrapupCommandDir + "/session.json";
        // I need to set the resource directory to a temp directory
        final String resourceDir = folder.newFolder("resource").getAbsolutePath();
        final Session sessionInput = mapper.readValue(new File(sessionInputJsonPath), Session.class);
        assertThat(sessionInput.getResources(), Matchers.hasSize(1));
        final Resource resource = sessionInput.getResources().get(0);
        resource.setDirectory(resourceDir);
        runtimeValues.put("session", mapper.writeValueAsString(sessionInput));
        return sessionInput.getUri();
    }

    /**
     * Stubs what launching a setup or wrapup container looks up. The static stubs are added through
     * {@code staticMocks} so that they also apply on the threads the launch runs on.
     */
    public static void setupMocksForSetupWrapupWorkflow(String uri, FakeWorkflow fakeWorkflow, CatalogService mockCatalogService,
                                                        UserI mockUser, StaticMocks.Registration staticMocks) throws Exception {
        setupMocksForSetupWrapupWorkflow(uri, ExptURI.class, fakeWorkflow, mockCatalogService, mockUser, staticMocks);
    }

    /** @param uriType the type UriParserUtils.parseURI returns for the input's URI */
    public static <T extends URIManager.ArchiveURI & URIManager.ArchiveItemURI> void setupMocksForSetupWrapupWorkflow(
            String uri, Class<T> uriType, FakeWorkflow fakeWorkflow, CatalogService mockCatalogService, UserI mockUser,
            StaticMocks.Registration staticMocks) throws Exception {
        final ArchivableItem mockItem = mock(ArchivableItem.class);
        String id = "id";
        String xsiType = "type";
        String project = "project";
        when(mockItem.getId()).thenReturn(id);
        when(mockItem.getXSIType()).thenReturn(xsiType);
        when(mockItem.getProject()).thenReturn(project);
        final T mockUriObject = mock(uriType);
        when(mockUriObject.getSecurityItem()).thenReturn(mockItem);
        fakeWorkflow.setId(uri);
        ResourceData mockRD = mock(ResourceData.class);
        when(mockRD.getItem()).thenReturn(mockItem);
        when(mockCatalogService.getResourceDataFromUri(uri)).thenReturn(mockRD);

        // A new workflow per setup or wrapup container, as in production. Sharing one would let the status updater's
        // mismatch check see a finished setup's Complete workflow on the still-running wrapup service and kill it
        final Map<String, FakeWorkflow> setupWrapupWorkflows = new ConcurrentHashMap<>();
        final AtomicInteger nextWfid = new AtomicInteger(111);
        staticMocks.add(mocks -> {
            mocks.mock(UriParserUtils.class).when(() -> UriParserUtils.parseURI(uri)).thenReturn(mockUriObject);
            mocks.mock(WorkflowUtils.class)
                    .when(() -> WorkflowUtils.buildOpenWorkflow(eq(mockUser), eq(xsiType), eq(id), eq(project), any(EventDetails.class)))
                    .thenAnswer(invocation -> {
                        final FakeWorkflow setupWrapupWorkflow = new FakeWorkflow();
                        setupWrapupWorkflow.setWfid(nextWfid.getAndIncrement());
                        setupWrapupWorkflow.setEventId(2);
                        setupWrapupWorkflows.put(setupWrapupWorkflow.getWorkflowId().toString(), setupWrapupWorkflow);
                        return setupWrapupWorkflow;
                    });
            mocks.mock(WorkflowUtils.class)
                    .when(() -> WorkflowUtils.getUniqueWorkflow(eq(mockUser), argThat(setupWrapupWorkflows::containsKey)))
                    .thenAnswer(invocation -> setupWrapupWorkflows.get(invocation.<String>getArgument(1)));
        });
    }
}

