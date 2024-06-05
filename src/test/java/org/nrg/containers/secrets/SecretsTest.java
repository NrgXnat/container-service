package org.nrg.containers.secrets;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.CreateServiceCmd;
import com.github.dockerjava.api.command.CreateServiceResponse;
import com.github.dockerjava.api.model.ServiceSpec;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobSpec;
import io.kubernetes.client.openapi.models.V1PodSpec;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.hamcrest.collection.IsMapContaining;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.internal.util.reflection.Whitebox;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.nrg.containers.api.DockerControlApi;
import org.nrg.containers.api.KubernetesClientImpl;
import org.nrg.containers.config.ObjectMapperConfig;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.auto.ResolvedCommand;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.server.docker.Backend;
import org.nrg.containers.model.server.docker.DockerServerBase;
import org.nrg.containers.services.CommandResolutionService;
import org.nrg.containers.services.CommandService;
import org.nrg.containers.services.ContainerSecretService;
import org.nrg.containers.services.DockerServerService;
import org.nrg.containers.services.DockerService;
import org.nrg.containers.services.impl.CommandResolutionServiceImpl;
import org.nrg.containers.services.impl.ContainerSecretServiceImpl;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.services.cache.UserDataCache;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.services.archive.CatalogService;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareOnlyThisForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.io.File;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

@Slf4j
@RunWith(PowerMockRunner.class)
@PrepareOnlyThisForTest(DockerControlApi.class)
public class SecretsTest {
    private final static Answer RETURN_SELF = InvocationOnMock::getMock;

    @Mock private SystemPropertySecretSource.ValueObtainer valueObtainer;
    @Mock private CommandService commandService;
    @Mock private DockerServerService dockerServerService;
    @Mock private SiteConfigPreferences siteConfigPreferences;
    @Mock private DockerService dockerService;
    @Mock private CatalogService catalogService;
    @Mock private UserDataCache userDataCache;
    @Mock private KubernetesClientImpl kubernetesClient;
    @Mock private DockerServerBase.DockerServer dockerServer;

    @Mock private UserI user;
    private Backend backend = null;
    private String userId;

    private static final ObjectMapper objectMapper = new ObjectMapperConfig().objectMapper();

    @Rule public TemporaryFolder folder = new TemporaryFolder(new File(System.getProperty("user.dir") + "/build"));

    @Rule
    public TestRule watcher = new TestWatcher() {
        protected void starting(Description description) {
            log.info("BEGINNING TEST " + description.getMethodName());
        }

        protected void finished(Description description) {
            log.info("ENDING TEST " + description.getMethodName());
        }
    };

    @Before
    public void setup() throws Exception {
        Mockito.when(dockerServerService.getServer()).thenReturn(dockerServer);

        userId = RandomStringUtils.randomAlphabetic(5);
        Mockito.when(user.getLogin()).thenReturn(userId);
    }

    @Test
    public void testResolveCommandWithEnvSecret() throws Exception {
        backend = Backend.DOCKER;
        Mockito.when(dockerServer.backend()).thenReturn(backend);

        // Create the secret value and objects
        final String secretValue = RandomStringUtils.randomAlphanumeric(32);

        final String mockSystemPropertyName = "sys.prop";
        final String secretEnvironmentVariableName = "SECRET_ENV_NAME";

        final SystemPropertySecretSource source = new SystemPropertySecretSource(mockSystemPropertyName);
        final EnvironmentVariableSecretDestination destination = new EnvironmentVariableSecretDestination(secretEnvironmentVariableName);
        final Secret secret = new Secret(source, destination);
        final ResolvedSecret expected = new EnvironmentVariableResolvedSecret(secret, secretValue);

        // Mock out obtaining the value
        Mockito.when(valueObtainer.handledType()).thenReturn(SystemPropertySecretSource.class);
        Mockito.when(valueObtainer.obtainValue(source)).thenReturn(Optional.of(secretValue));

        // Create a command that will use the secret value
        final String commandName = RandomStringUtils.randomAlphabetic(5);
        final String wrapperName = RandomStringUtils.randomAlphabetic(5);
        final Command.CommandWrapper wrapper = Command.CommandWrapper.builder()
                .name(wrapperName)
                .build();
        final Command commandWithEnvSecret = Command.builder()
                .name(commandName)
                .image("busybox:latest")
                .commandLine("echo $" + secretEnvironmentVariableName)
                .secrets(Collections.singletonList(secret))
                .addCommandWrapper(wrapper)
                .build();

        final Command.ConfiguredCommand configuredCommand = Command.ConfiguredCommand
                .initialize(commandWithEnvSecret)
                .wrapper(wrapper)
                .build();

        // Class under test
        final ContainerSecretService containerSecretService = new ContainerSecretServiceImpl(Collections.singletonList(valueObtainer));
        final CommandResolutionService commandResolutionService = new CommandResolutionServiceImpl(
                commandService,
                dockerServerService,
                siteConfigPreferences,
                objectMapper,
                dockerService,
                catalogService,
                userDataCache,
                containerSecretService
        );

        // Call method under test
        final ResolvedCommand resolvedCommand = commandResolutionService.resolve(configuredCommand, Collections.emptyMap(), user);

        // Assertions
        assertThat(resolvedCommand.secrets(), hasSize(1));
        final ResolvedSecret resolvedSecret = resolvedCommand.secrets().get(0);
        assertThat(resolvedSecret, is(expected));
    }

    @Test
    public void testContainerFromResolvedCommand() throws Exception {
        // Create the secret value and objects
        final String secretValue = RandomStringUtils.randomAlphanumeric(32);

        final String mockSystemPropertyName = "sys.prop";
        final String secretEnvironmentVariableName = "SECRET_ENV_NAME";

        final SystemPropertySecretSource source = new SystemPropertySecretSource(mockSystemPropertyName);
        final EnvironmentVariableSecretDestination destination = new EnvironmentVariableSecretDestination(secretEnvironmentVariableName);
        final Secret secret = new Secret(source, destination);
        final ResolvedSecret expected = new EnvironmentVariableResolvedSecret(secret, secretValue);
        assertThat(expected, is(notNullValue()));
        assertThat(expected.getClass(), is(equalTo(EnvironmentVariableResolvedSecret.class)));

        // Create resolved command with secret
        final List<ResolvedSecret> secrets = Collections.singletonList(expected);
        final ResolvedCommand resolvedCommand = ResolvedCommand.builder()
                .image("")
                .commandLine("")
                .commandId(0L)
                .commandName("")
                .wrapperId(0L)
                .wrapperName("")
                .secrets(secrets)
                .build();

        // Create container from resolved command
        final Container container = Container.builderFromResolvedCommand(resolvedCommand).userId(userId).build();

        // Container has secret list
        assertThat(container.secrets(), is(secrets));

        // Map of secrets by type has
        final Map<Class<? extends ResolvedSecret>, List<ResolvedSecret>> secretsByType = container.secretsByType();
        assertThat(secretsByType, IsMapContaining.hasEntry(is(equalTo(expected.getClass())), is(secrets)));
    }

    @Test
    public void testLaunchKubernetesJobWithEnvSecret() throws Exception {
        // Additional object mocking
        backend = Backend.KUBERNETES;

        final String namespace = RandomStringUtils.randomAlphabetic(5);
        Whitebox.setInternalState(kubernetesClient, "namespace", namespace);

        final BatchV1Api batchApi = Mockito.mock(BatchV1Api.class);
        Whitebox.setInternalState(kubernetesClient, "batchApi", batchApi);

        // Create secret value and objects
        final String secretValue = RandomStringUtils.randomAlphanumeric(32);

        final String mockSystemPropertyName = "sys.prop";
        final String secretEnvironmentVariableName = "SECRET_ENV_NAME";

        final SystemPropertySecretSource source = new SystemPropertySecretSource(mockSystemPropertyName);
        final EnvironmentVariableSecretDestination destination = new EnvironmentVariableSecretDestination(secretEnvironmentVariableName);
        final Secret secret = new Secret(source, destination);
        final ResolvedSecret resolvedSecret = new EnvironmentVariableResolvedSecret(secret, secretValue);

        // Create container that we will pass to the backend
        final Container toCreate = Container.builder()
                .wrapperId(0L)
                .commandId(0L)
                .dockerImage("busybox:latest")
                .commandLine("echo $" + secretEnvironmentVariableName)
                .secrets(Collections.singletonList(resolvedSecret))
                .backend(backend)
                .userId(userId)
                .build();

        // When the method under test calls the backend, just return whatever job was sent in.
        // In real life the backend would return the job spec we sent in + a bunch of info about what got created.
        // But we don't need any of that extra stuff, we only want to see the spec we made.
        // We return exactly the same job spec we sent in, as if the backend responded after having done nothing at all.
        Mockito.when(batchApi.createNamespacedJob(Mockito.eq(namespace), Mockito.any(V1Job.class), Mockito.isNull(String.class), Mockito.isNull(String.class), Mockito.isNull(String.class), Mockito.isNull(String.class)))
                .thenAnswer((Answer<V1Job>) invocation -> invocation.getArgumentAt(1, V1Job.class));

        // Call method under test
        Mockito.when(kubernetesClient.createJob(toCreate, DockerControlApi.NumReplicas.ZERO, null, null))
                .thenCallRealMethod();
        kubernetesClient.createJob(toCreate, DockerControlApi.NumReplicas.ZERO, null, null);

        // Capture call to backend api mock
        final ArgumentCaptor<V1Job> jobArgumentCaptor = ArgumentCaptor.forClass(V1Job.class);
        Mockito.verify(batchApi).createNamespacedJob(Mockito.eq(namespace), jobArgumentCaptor.capture(), Mockito.isNull(String.class), Mockito.isNull(String.class), Mockito.isNull(String.class), Mockito.isNull(String.class));
        final V1Job job = jobArgumentCaptor.getValue();

        // Extract container spec
        final V1JobSpec jobSpec = job.getSpec();
        assertThat(jobSpec, is(notNullValue()));
        final V1PodSpec podSpec = jobSpec.getTemplate().getSpec();
        assertThat(podSpec, is(notNullValue()));
        final List<V1Container> containers = podSpec.getContainers();
        assertThat(containers, hasSize(1));
        final V1Container container = containers.get(0);

        // Check for secret env value
        final List<V1EnvVar> containerEnv = container.getEnv();
        assertThat(containerEnv, is(not(empty())));
        assertThat(containerEnv, contains(
                        both(hasProperty("name", is(secretEnvironmentVariableName)))
                                .and(hasProperty("value", is(secretValue)))
        ));
    }

    @Test
    public void testLaunchSwarmJobWithEnvSecret() throws Exception {
        backend = Backend.SWARM;
        Mockito.when(dockerServer.backend()).thenReturn(backend);

        // Create secret value and objects
        final String secretValue = RandomStringUtils.randomAlphanumeric(32);

        final String mockSystemPropertyName = "sys.prop";
        final String secretEnvironmentVariableName = "SECRET_ENV_NAME";

        final SystemPropertySecretSource source = new SystemPropertySecretSource(mockSystemPropertyName);
        final EnvironmentVariableSecretDestination destination = new EnvironmentVariableSecretDestination(secretEnvironmentVariableName);
        final Secret secret = new Secret(source, destination);
        final ResolvedSecret resolvedSecret = new EnvironmentVariableResolvedSecret(secret, secretValue);

        // Create container that we will pass to the backend
        final String dockerImage = "busybox:latest";
        final Container toCreate = Container.builder()
                .wrapperId(0L)
                .commandId(0L)
                .dockerImage(dockerImage)
                .commandLine("echo $" + secretEnvironmentVariableName)
                .secrets(Collections.singletonList(resolvedSecret))
                .backend(backend)
                .userId(userId)
                .build();

        // Class under test
        final DockerControlApi dockerControlApi = PowerMockito.mock(DockerControlApi.class);

        // Mock out DockerControlApi#getDockerClient(dockerServer)
        final DockerClient dockerClient = Mockito.mock(DockerClient.class);
        PowerMockito.doReturn(dockerClient).when(dockerControlApi, "getDockerClient", dockerServer);

        PowerMockito.doReturn(dockerServer).when(dockerControlApi, "getServer");

        // Mock out backend call to create service
        final String containerId = RandomStringUtils.randomAlphanumeric(5);
        final CreateServiceResponse resp = Mockito.mock(CreateServiceResponse.class);
        Mockito.when(resp.getId()).thenReturn(containerId);

        final CreateServiceCmd cmd = Mockito.mock(CreateServiceCmd.class, RETURN_SELF);
        Mockito.doReturn(resp).when(cmd).exec();
        Mockito.when(dockerClient.createServiceCmd(Mockito.any(ServiceSpec.class))).thenReturn(cmd);

        // Call method under test
        PowerMockito.doCallRealMethod().when(dockerControlApi, "createDockerSwarmService", toCreate, dockerServer, DockerControlApi.NumReplicas.ZERO);
        PowerMockito.doCallRealMethod().when(dockerControlApi).create(toCreate, user);
        dockerControlApi.create(toCreate, user);

        // Capture call to backend api mock
        final ArgumentCaptor<ServiceSpec> serviceSpecArgumentCaptor = ArgumentCaptor.forClass(ServiceSpec.class);
        Mockito.verify(dockerClient).createServiceCmd(serviceSpecArgumentCaptor.capture());
        final ServiceSpec serviceSpec = serviceSpecArgumentCaptor.getValue();

        // Check for secret env value
        assertThat(serviceSpec.getTaskTemplate().getContainerSpec().getEnv(),
                contains(secretEnvironmentVariableName + "=" + secretValue));
    }

    @Test
    public void testLaunchDockerContainerWithEnvSecret() throws Exception {
        backend = Backend.DOCKER;
        Mockito.when(dockerServer.backend()).thenReturn(backend);

        // Create secret value and objects
        final String secretValue = RandomStringUtils.randomAlphanumeric(32);

        final String mockSystemPropertyName = "sys.prop";
        final String secretEnvironmentVariableName = "SECRET_ENV_NAME";

        final SystemPropertySecretSource source = new SystemPropertySecretSource(mockSystemPropertyName);
        final EnvironmentVariableSecretDestination destination = new EnvironmentVariableSecretDestination(secretEnvironmentVariableName);
        final Secret secret = new Secret(source, destination);
        final ResolvedSecret resolvedSecret = new EnvironmentVariableResolvedSecret(secret, secretValue);

        // Create container that we will pass to the backend
        final String dockerImage = "busybox:latest";
        final Container toCreate = Container.builder()
                .wrapperId(0L)
                .commandId(0L)
                .dockerImage(dockerImage)
                .commandLine("echo $" + secretEnvironmentVariableName)
                .secrets(Collections.singletonList(resolvedSecret))
                .backend(backend)
                .userId(userId)
                .build();

        // Class under test
        final DockerControlApi dockerControlApi = PowerMockito.mock(DockerControlApi.class);

        // Mock out DockerControlApi#getDockerClient(dockerServer)
        final DockerClient dockerClient = Mockito.mock(DockerClient.class);
        PowerMockito.doReturn(dockerClient).when(dockerControlApi, "getDockerClient", dockerServer);

        PowerMockito.doReturn(dockerServer).when(dockerControlApi, "getServer");

        // Mock out backend call to create service
        final String containerId = RandomStringUtils.randomAlphanumeric(5);
        final CreateContainerResponse resp = new CreateContainerResponse();
        resp.setId(containerId);
        resp.setWarnings(new String[0]);

        // This default answer lets us stub out all the method chaining .withFoo().withBar() without having to stub each one
        // Have to make it explicitly because Mockito.RETURNS_SELF is not available in our version
        @SuppressWarnings("rawtypes")
        final CreateContainerCmd cmd = Mockito.mock(CreateContainerCmd.class, (Answer) InvocationOnMock::getMock);
        Mockito.doReturn(resp).when(cmd).exec();
        Mockito.when(dockerClient.createContainerCmd(dockerImage)).thenReturn(cmd);

        // Call method under test
        PowerMockito.doCallRealMethod().when(dockerControlApi, "createDockerContainer", toCreate, dockerServer);
        PowerMockito.doCallRealMethod().when(dockerControlApi).create(toCreate, user);
        dockerControlApi.create(toCreate, user);

        // Capture call to backend api mock
        @SuppressWarnings({"unchecked", "rawtypes"})
        final ArgumentCaptor<List<String>> withEnvArgumentCaptor = ArgumentCaptor.forClass((Class) List.class);
        Mockito.verify(cmd).withEnv(withEnvArgumentCaptor.capture());
        final List<String> env = withEnvArgumentCaptor.getValue();

        // Check for secret env value
        assertThat(env, contains(secretEnvironmentVariableName + "=" + secretValue));
    }

    @Test
    public void testSystemPropertySecretSourceJson() throws Exception {
        final String identifier = RandomStringUtils.randomAlphabetic(10);
        final String json = "{" +
                "\"type\": \"" + SystemPropertySecretSource.JSON_TYPE_NAME + "\", " +
                "\"identifier\": \"" + identifier + "\"" +
                "}";
        final SecretSource secretSource = objectMapper.readValue(json, SecretSource.class);

        assertThat(secretSource, instanceOf(SystemPropertySecretSource.class));
        assertThat(secretSource.identifier(), is(identifier));
    }

    @Test
    public void testEnvSecretDestJson() throws Exception {
        final String identifier = RandomStringUtils.randomAlphabetic(10);
        final String json = "{" +
                "\"type\": \"" + EnvironmentVariableSecretDestination.JSON_TYPE_NAME + "\", " +
                "\"identifier\": \"" + identifier + "\"" +
                "}";
        final SecretDestination secretDestination = objectMapper.readValue(json, SecretDestination.class);

        assertThat(secretDestination, instanceOf(EnvironmentVariableSecretDestination.class));
        assertThat(secretDestination.identifier(), is(identifier));
    }


    @Test(expected = InvalidTypeIdException.class)
    public void testSecretSourceJsonInvalidType() throws Exception {
        final String type = RandomStringUtils.randomAlphabetic(10);
        final String identifier = RandomStringUtils.randomAlphabetic(10);
        final String json = "{" +
                "\"type\": \"" + type + "\", " +
                "\"identifier\": \"" + identifier + "\"" +
                "}";

        final SecretSource secretSource = objectMapper.readValue(json, SecretSource.class);
    }

    @Test(expected = InvalidTypeIdException.class)
    public void testSecretDestJsonInvalidType() throws Exception {
        final String type = RandomStringUtils.randomAlphabetic(10);
        final String identifier = RandomStringUtils.randomAlphabetic(10);
        final String json = "{" +
                "\"type\": \"" + type + "\", " +
                "\"identifier\": \"" + identifier + "\"" +
                "}";


        final SecretDestination secretDestination = objectMapper.readValue(json, SecretDestination.class);
    }

    @Test(expected = InvalidTypeIdException.class)
    public void testSecretSourceJsonNoType() throws Exception {
        final String identifier = RandomStringUtils.randomAlphabetic(10);
        final String json = "{\"identifier\": \"" + identifier + "\"}";

        final SecretSource secretSource = objectMapper.readValue(json, SecretSource.class);
    }

    @Test(expected = InvalidTypeIdException.class)
    public void testSecretDestJsonNoType() throws Exception {
        final String identifier = RandomStringUtils.randomAlphabetic(10);
        final String json = "{\"identifier\": \"" + identifier + "\"}";

        final SecretDestination secretDestination = objectMapper.readValue(json, SecretDestination.class);
    }

    @Test
    public void testSecretJson() throws Exception {
        final String sourceIdentifier = RandomStringUtils.randomAlphabetic(10);
        final String sourceJson = makeSystemPropertySourceJson(sourceIdentifier);
        final String destIdentifier = RandomStringUtils.randomAlphabetic(10);
        final String destJson = makeEnvironmentVariableDestJson(destIdentifier);

        final String json = "{" +
                "\"source\": " + sourceJson + ", " +
                "\"destination\": " + destJson +
                "}";

        final Secret secret = objectMapper.readValue(json, Secret.class);
        assertThat(secret.source(), instanceOf(SystemPropertySecretSource.class));
        assertThat(secret.source().identifier(), is(sourceIdentifier));
        assertThat(secret.destination(), instanceOf(EnvironmentVariableSecretDestination.class));
        assertThat(secret.destination().identifier(), is(destIdentifier));
    }

    @Test(expected = JsonParseException.class)
    public void testSecretJson_noSource() throws Exception {
        final String destIdentifier = RandomStringUtils.randomAlphabetic(10);
        final String destJson = makeEnvironmentVariableDestJson(destIdentifier);

        final String json = "{" +
                "\"destination\": " + destJson + ", " +
                "}";

        final Secret secret = objectMapper.readValue(json, Secret.class);
    }

    @Test(expected = JsonParseException.class)
    public void testSecretJson_noDestination() throws Exception {
        final String sourceIdentifier = RandomStringUtils.randomAlphabetic(10);
        final String sourceJson = makeSystemPropertySourceJson(sourceIdentifier);

        final String json = "{" +
                "\"source\": " + sourceJson + ", " +
                "}";

        final Secret secret = objectMapper.readValue(json, Secret.class);
    }

    private static String makeSourceDestJson(final String identifier, final String type) throws Exception {
        return makeSourceDestJson(identifier, type, Collections.emptyMap());
    }

    private static String makeSourceDestJson(final String identifier, final String type, final Map<String, String> otherProperties) throws Exception {
        final Map<String, String> map = Stream.concat(
                Stream.of(new AbstractMap.SimpleEntry<>("type", type),
                        new AbstractMap.SimpleEntry<>("identifier", identifier)),
                otherProperties.entrySet().stream()
        ).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return objectMapper.writeValueAsString(map);
    }

    private static String makeSystemPropertySourceJson(final String identifier) throws Exception {
        return makeSourceDestJson(identifier, SystemPropertySecretSource.JSON_TYPE_NAME);
    }

    private static String makeEnvironmentVariableDestJson(final String identifier) throws Exception {
        return makeSourceDestJson(identifier, EnvironmentVariableSecretDestination.JSON_TYPE_NAME);
    }
}
