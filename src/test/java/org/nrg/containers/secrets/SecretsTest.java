package org.nrg.containers.secrets;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.CreateServiceCmd;
import com.github.dockerjava.api.command.CreateServiceResponse;
import com.github.dockerjava.api.model.ServiceSpec;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mockito;
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
import org.nrg.containers.model.server.docker.SecretVolumeType;
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

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.AbstractMap;
import java.util.Arrays;
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
import static org.hamcrest.Matchers.nullValue;
import io.kubernetes.client.openapi.models.V1VolumeMount;
import io.kubernetes.client.openapi.models.V1Volume;
import static org.hamcrest.Matchers.anyOf;
import org.nrg.containers.exceptions.ContainerException;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@Slf4j
//@PrepareOnlyThisForTest(DockerControlApi.class)
public class SecretsTest {
    private final static Answer RETURN_SELF = InvocationOnMock::getMock;

    private SystemPropertySecretSource.ValueObtainer valueObtainer = Mockito.mock(SystemPropertySecretSource.ValueObtainer.class);
    private CommandService commandService = Mockito.mock(CommandService.class);
    private DockerServerService dockerServerService = Mockito.mock(DockerServerService.class);
    private SiteConfigPreferences siteConfigPreferences = Mockito.mock(SiteConfigPreferences.class);
    private DockerService dockerService = Mockito.mock(DockerService.class);
    private CatalogService catalogService =Mockito.mock(CatalogService.class);
    private UserDataCache userDataCache = Mockito.mock(UserDataCache.class);
    private KubernetesClientImpl kubernetesClient = Mockito.mock(KubernetesClientImpl.class);
    private DockerServerBase.DockerServer dockerServer = Mockito.mock(DockerServerBase.DockerServer.class);
    private org.nrg.containers.services.DockerHubService dockerHubService = Mockito.mock(org.nrg.containers.services.DockerHubService.class);
    private org.nrg.containers.api.KubernetesClientFactory kubernetesClientFactory = Mockito.mock(org.nrg.containers.api.KubernetesClientFactory.class);

    private UserI user = Mockito.mock(UserI.class);
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
        // Whitebox.setInternalState(kubernetesClient, "contextNamespace", namespace);
        Field namespaceField = kubernetesClient.getClass().getDeclaredField("contextNamespace");
        namespaceField.setAccessible(true);  // Allow access to private fields
        namespaceField.set(kubernetesClient, namespace);

        final BatchV1Api batchApi = Mockito.mock(BatchV1Api.class);
        // Whitebox.setInternalState(kubernetesClient, "batchApi", batchApi);
        Field batchApiField = kubernetesClient.getClass().getDeclaredField("batchApi");
        batchApiField.setAccessible(true);  // Allow access to private fields
        batchApiField.set(kubernetesClient, batchApi);


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
        Mockito.when(batchApi.createNamespacedJob(Mockito.eq(namespace), Mockito.any(V1Job.class), Mockito.isNull(), Mockito.isNull(), Mockito.isNull(), Mockito.isNull()))
                .thenAnswer((Answer<V1Job>) invocation -> invocation.getArgument(1, V1Job.class));

        // Call method under test
        Mockito.when(kubernetesClient.createJob(toCreate, DockerControlApi.NumReplicas.ZERO, kubernetesServer()))
                .thenCallRealMethod();
        kubernetesClient.createJob(toCreate, DockerControlApi.NumReplicas.ZERO, kubernetesServer());

        // Capture call to backend api mock
        final ArgumentCaptor<V1Job> jobArgumentCaptor = ArgumentCaptor.forClass(V1Job.class);
        Mockito.verify(batchApi).createNamespacedJob(Mockito.eq(namespace), jobArgumentCaptor.capture(), Mockito.isNull(), Mockito.isNull(), Mockito.isNull(), Mockito.isNull());
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
        final DockerControlApi dockerControlApi = Mockito.spy(new DockerControlApi(dockerServerService, dockerHubService, kubernetesClientFactory));

        // Mock out DockerControlApi#getDockerClient(dockerServer)
        final DockerClient dockerClient = Mockito.mock(DockerClient.class);
//        PowerMockito.doReturn(dockerClient).when(dockerControlApi, "getDockerClient", dockerServer);
        Mockito.doReturn(dockerClient).when(dockerControlApi)
                .getDockerClient(any(DockerServerBase.DockerServer.class));

        //        PowerMockito.doReturn(dockerServer).when(dockerControlApi, "getServer");
        when(dockerServerService.getServer()).thenReturn(dockerServer);

        // Mock authConfig - return a valid DockerHub so authConfig doesn't need to access config
        final org.nrg.containers.model.dockerhub.DockerHubBase.DockerHub mockDockerHub = Mockito.mock(org.nrg.containers.model.dockerhub.DockerHubBase.DockerHub.class);
        when(mockDockerHub.url()).thenReturn("docker.io");
        when(mockDockerHub.username()).thenReturn("");
        when(mockDockerHub.password()).thenReturn("");
        when(mockDockerHub.token()).thenReturn("");
        when(mockDockerHub.email()).thenReturn("");
        when(dockerHubService.getByUrl(any())).thenReturn(mockDockerHub);

        // Mock out backend call to create service
        final String containerId = RandomStringUtils.randomAlphanumeric(5);
        final CreateServiceResponse resp = Mockito.mock(CreateServiceResponse.class);
        Mockito.when(resp.getId()).thenReturn(containerId);

        final CreateServiceCmd cmd = Mockito.mock(CreateServiceCmd.class, RETURN_SELF);
        Mockito.doReturn(resp).when(cmd).exec();
        Mockito.when(dockerClient.createServiceCmd(Mockito.any(ServiceSpec.class))).thenReturn(cmd);

        // Call method under test
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
        final DockerControlApi dockerControlApi = Mockito.spy(new DockerControlApi(dockerServerService, dockerHubService, kubernetesClientFactory));

        // Mock out DockerControlApi#getDockerClient(dockerServer)
        final DockerClient dockerClient = Mockito.mock(DockerClient.class);

//        PowerMockito.doReturn(dockerClient).when(dockerControlApi, "getDockerClient", dockerServer);
        Mockito.doReturn(dockerClient).when(dockerControlApi)
                .getDockerClient(any(DockerServerBase.DockerServer.class));

//        PowerMockito.doReturn(dockerServer).when(dockerControlApi, "getServer");
        when(dockerServerService.getServer()).thenReturn(dockerServer);

        // Mock authConfig - return a valid DockerHub so authConfig doesn't need to access config
        final org.nrg.containers.model.dockerhub.DockerHubBase.DockerHub mockDockerHub2 = Mockito.mock(org.nrg.containers.model.dockerhub.DockerHubBase.DockerHub.class);
        when(mockDockerHub2.url()).thenReturn("docker.io");
        when(mockDockerHub2.username()).thenReturn("");
        when(mockDockerHub2.password()).thenReturn("");
        when(mockDockerHub2.token()).thenReturn("");
        when(mockDockerHub2.email()).thenReturn("");
        when(dockerHubService.getByUrl(any())).thenReturn(mockDockerHub2);

        // Mock listImagesCmd for getAllImages call
        final com.github.dockerjava.api.command.ListImagesCmd listImagesCmd = Mockito.mock(com.github.dockerjava.api.command.ListImagesCmd.class);
        when(dockerClient.listImagesCmd()).thenReturn(listImagesCmd);
        when(listImagesCmd.exec()).thenReturn(java.util.Collections.emptyList());

        // Mock pullImageCmd for pullImage call
        final com.github.dockerjava.api.command.PullImageCmd pullImageCmd = Mockito.mock(com.github.dockerjava.api.command.PullImageCmd.class);
        final com.github.dockerjava.api.command.PullImageResultCallback pullCallback = Mockito.mock(com.github.dockerjava.api.command.PullImageResultCallback.class);
        when(dockerClient.pullImageCmd(any(String.class))).thenReturn(pullImageCmd);
        when(pullImageCmd.start()).thenReturn(pullCallback);
        when(pullCallback.awaitCompletion()).thenReturn(pullCallback);

        // Mock inspectImageCmd for getImageById call
        final com.github.dockerjava.api.command.InspectImageCmd inspectImageCmd = Mockito.mock(com.github.dockerjava.api.command.InspectImageCmd.class);
        final com.github.dockerjava.api.command.InspectImageResponse inspectImageResponse = Mockito.mock(com.github.dockerjava.api.command.InspectImageResponse.class);
        when(dockerClient.inspectImageCmd(any(String.class))).thenReturn(inspectImageCmd);
        when(inspectImageCmd.exec()).thenReturn(inspectImageResponse);

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

    // ---- k8s-secret source / file destination (XNAT-8703 Project Secrets) ----

    @Test
    public void testK8sSecretSourceJson() throws Exception {
        final String identifier = RandomStringUtils.randomAlphabetic(10);
        final String json = "{" +
                "\"type\": \"" + K8sSecretSource.JSON_TYPE_NAME + "\", " +
                "\"identifier\": \"" + identifier + "\"" +
                "}";
        final SecretSource secretSource = objectMapper.readValue(json, SecretSource.class);

        assertThat(secretSource, instanceOf(K8sSecretSource.class));
        assertThat(secretSource.identifier(), is(identifier));
        // The value is never obtained by XNAT; kubelet mounts it. So it must NOT be a value-obtaining source.
        assertThat(secretSource, not(instanceOf(SecretSource.ValueObtainingSecretSource.class)));
        assertThat(((K8sSecretSource) secretSource).secretName(), is(nullValue()));
    }

    @Test
    public void testK8sSecretSourceJsonWithExplicitName() throws Exception {
        final String identifier = RandomStringUtils.randomAlphabetic(10);
        final String name = "custom-" + RandomStringUtils.randomAlphabetic(6);
        final String json = "{" +
                "\"type\": \"" + K8sSecretSource.JSON_TYPE_NAME + "\", " +
                "\"identifier\": \"" + identifier + "\", " +
                "\"name\": \"" + name + "\"" +
                "}";
        final SecretSource secretSource = objectMapper.readValue(json, SecretSource.class);

        assertThat(secretSource, instanceOf(K8sSecretSource.class));
        assertThat(((K8sSecretSource) secretSource).secretName(), is(name));
        // Round-trip keeps the explicit name
        final String reserialized = objectMapper.writeValueAsString(secretSource);
        final SecretSource again = objectMapper.readValue(reserialized, SecretSource.class);
        assertThat(((K8sSecretSource) again).secretName(), is(name));
    }

    @Test
    public void testK8sSecretSourceRequiredDefaultsToTrue() throws Exception {
        final SecretSource withoutFlag = objectMapper.readValue(
                "{\"type\": \"k8s-secret\", \"identifier\": \"redcap\"}", SecretSource.class);
        assertThat(((K8sSecretSource) withoutFlag).required(), is(true));

        final SecretSource optional = objectMapper.readValue(
                "{\"type\": \"k8s-secret\", \"identifier\": \"redcap\", \"name\": \"redcap-#PROJECT#\", \"required\": false}", SecretSource.class);
        assertThat(((K8sSecretSource) optional).required(), is(false));
        assertThat("placeholders in name are stored verbatim; rendering happens at resolution",
                ((K8sSecretSource) optional).secretName(), is("redcap-#PROJECT#"));

        final SecretSource again = objectMapper.readValue(objectMapper.writeValueAsString(optional), SecretSource.class);
        assertThat(((K8sSecretSource) again).required(), is(false));
        assertThat(again, is(optional));
    }

    @Test
    public void testFileSecretDestJson() throws Exception {
        final String mountPath = "/run/secrets/" + RandomStringUtils.randomAlphabetic(6);
        final String json = "{" +
                "\"type\": \"" + FileSecretDestination.JSON_TYPE_NAME + "\", " +
                "\"identifier\": \"" + mountPath + "\"" +
                "}";
        final SecretDestination secretDestination = objectMapper.readValue(json, SecretDestination.class);

        assertThat(secretDestination, instanceOf(FileSecretDestination.class));
        assertThat(secretDestination.identifier(), is(mountPath));
        assertThat(((FileSecretDestination) secretDestination).mountPath(), is(mountPath));
    }

    @Test
    public void testResolveK8sFileSecretHasNoValue() throws Exception {
        final String logicalName = RandomStringUtils.randomAlphabetic(8).toLowerCase();
        final String mountPath = "/run/secrets";
        final Secret secret = new Secret(new K8sSecretSource(logicalName), new FileSecretDestination(mountPath));

        // No value obtainers at all: resolving must still succeed because nothing is obtained
        final ContainerSecretService service = new ContainerSecretServiceImpl(Collections.emptyList());
        final ResolvedSecret resolved = service.resolve(secret);

        assertThat(resolved, instanceOf(FileResolvedSecret.class));
        assertThat(resolved, not(instanceOf(ResolvedSecret.WithValue.class)));
        final FileResolvedSecret fileSecret = (FileResolvedSecret) resolved;
        assertThat(fileSecret.mountPath(), is(mountPath));
        assertThat(fileSecret.identifier(), is(logicalName));
        assertThat(fileSecret.secretName(), is(nullValue()));
        assertThat(fileSecret.required(), is(true));
    }

    @Test
    public void testPrepareSecretsForLaunchSeparatesFileSecrets() throws Exception {
        final Secret secret = new Secret(new K8sSecretSource("redcap"), new FileSecretDestination("/run/secrets"));
        final ResolvedSecret resolved = new FileResolvedSecret(secret, Optional.empty());
        final Container container = Container.builder()
                .wrapperId(0L).commandId(0L)
                .dockerImage("busybox:latest")
                .commandLine("true")
                .secrets(Collections.singletonList(resolved))
                .userId(userId)
                .build();

        final ContainerPropertiesWithSecretValues props = ContainerPropertiesWithSecretValues.prepareSecretsForLaunch(container);

        assertThat(props.environmentVariables().entrySet(), is(empty()));
        assertThat(props.fileSecrets(), hasSize(1));
        assertThat(props.fileSecrets().get(0).identifier(), is("redcap"));
    }

    /**
     * ContainerEntity.secrets is a jsonb column read back through ResolvedSecret.fromUnresolved. A file secret
     * must come back as a FileResolvedSecret with its rendered name and required flag, so the watchdog and the
     * error translator can work from the database object. Env secrets still degrade (their value is never stored).
     */
    @Test
    public void testFileResolvedSecretSurvivesJsonRoundTrip() throws Exception {
        final FileResolvedSecret file = new FileResolvedSecret(new Secret(
                new K8sSecretSource("redcap", "xnat-proj01-redcap", false), new FileSecretDestination("/run/secrets/redcap")));
        final ResolvedSecret env = new EnvironmentVariableResolvedSecret(new Secret(
                new SystemPropertySecretSource("sys.prop"), new EnvironmentVariableSecretDestination("ENV")), "value");

        final String json = objectMapper.writeValueAsString(Arrays.asList(file, env));
        assertThat("values are never serialised", json, not(containsString("value")));
        final List<ResolvedSecret> back = objectMapper.readValue(json, new TypeReference<List<ResolvedSecret>>() {});

        assertThat(back.get(0), instanceOf(FileResolvedSecret.class));
        final FileResolvedSecret fileBack = (FileResolvedSecret) back.get(0);
        assertThat(fileBack.identifier(), is("redcap"));
        assertThat(fileBack.secretName(), is("xnat-proj01-redcap"));
        assertThat(fileBack.mountPath(), is("/run/secrets/redcap"));
        assertThat(fileBack.required(), is(false));
        assertThat(fileBack.volumeName(), is("secret-redcap"));

        assertThat(back.get(1).getClass(), is(equalTo(ResolvedSecret.class)));
    }

    @Test
    public void testFileResolvedSecretVolumeNameIsSanitisedIdentifier() {
        final FileResolvedSecret secret = new FileResolvedSecret(new Secret(
                new K8sSecretSource("RedCap_API"), new FileSecretDestination("/run/secrets/redcap")));
        assertThat(secret.volumeName(), is("secret-redcap-api"));

        final FileResolvedSecret dotted = new FileResolvedSecret(new Secret(
                new K8sSecretSource("redcap.v2"), new FileSecretDestination("/run/secrets/redcap")));
        assertThat("volume names are DNS-1123 labels, so dots are not allowed", dotted.volumeName(), is("secret-redcap-v2"));

        final FileResolvedSecret rendered = secret.withSecretName("xnat-proj01-redcap-api");
        assertThat("the original is not mutated", secret.secretName(), is(nullValue()));
        assertThat(rendered.secretName(), is("xnat-proj01-redcap-api"));
        assertThat("the rendered name is written back into the serialisable source",
                ((K8sSecretSource) rendered.source()).secretName(), is("xnat-proj01-redcap-api"));
        assertThat(rendered.identifier(), is("RedCap_API"));
    }

    @Test
    public void testDockerBackendRejectsFileSecret() throws Exception {
        backend = Backend.DOCKER;
        Mockito.when(dockerServer.backend()).thenReturn(backend);

        final Secret secret = new Secret(new K8sSecretSource("redcap"), new FileSecretDestination("/run/secrets"));
        final ResolvedSecret resolved = new FileResolvedSecret(secret, Optional.empty());
        final Container toCreate = Container.builder()
                .wrapperId(0L).commandId(0L)
                .dockerImage("busybox:latest")
                .commandLine("true")
                .secrets(Collections.singletonList(resolved))
                .backend(backend)
                .userId(userId)
                .build();

        final DockerControlApi dockerControlApi = Mockito.spy(new DockerControlApi(dockerServerService, dockerHubService, kubernetesClientFactory));
        final DockerClient dockerClient = Mockito.mock(DockerClient.class);
        Mockito.doReturn(dockerClient).when(dockerControlApi).getDockerClient(any(DockerServerBase.DockerServer.class));

        try {
            dockerControlApi.create(toCreate, user);
            org.junit.Assert.fail("Expected ContainerException for file secret on Docker backend");
        } catch (ContainerException e) {
            assertThat(e.getMessage(), containsString("not supported"));
        }
        // Nothing may have been sent to Docker
        Mockito.verify(dockerClient, Mockito.never()).createContainerCmd(any(String.class));
    }

    // ---- Kubernetes backend: file secrets become volumes ----

    private static DockerServerBase.DockerServer kubernetesServer() {
        return DockerServerBase.DockerServer.builder().name("k8s").host("").backend(Backend.KUBERNETES).build();
    }

    private KubernetesClientImpl kubernetesClientWithMockedApi(final String namespace, final BatchV1Api batchApi) throws Exception {
        setField(kubernetesClient, "contextNamespace", namespace);
        setField(kubernetesClient, "batchApi", batchApi);
        return kubernetesClient;
    }

    private Container kubernetesContainer(final String namespace) {
        return Container.builder()
                .wrapperId(0L).commandId(0L)
                .dockerImage("busybox:latest").commandLine("true")
                .backend(Backend.KUBERNETES).userId(userId)
                .serviceId("job-1").taskId("job-1-pod")
                .namespace(namespace)
                .build();
    }

    @Test
    public void testKubernetesJobIsCreatedInTheContainerNamespace() throws Exception {
        final BatchV1Api batchApi = Mockito.mock(BatchV1Api.class);
        kubernetesClientWithMockedApi("ctx", batchApi);
        Mockito.when(batchApi.createNamespacedJob(Mockito.anyString(), Mockito.any(V1Job.class), Mockito.isNull(), Mockito.isNull(), Mockito.isNull(), Mockito.isNull()))
                .thenAnswer((Answer<V1Job>) invocation -> invocation.getArgument(1, V1Job.class));
        final DockerServerBase.DockerServer server = kubernetesServer();

        final Container perProject = kubernetesContainer("xnat-proj01");
        Mockito.when(kubernetesClient.createJob(perProject, DockerControlApi.NumReplicas.ZERO, server)).thenCallRealMethod();
        kubernetesClient.createJob(perProject, DockerControlApi.NumReplicas.ZERO, server);
        Mockito.verify(batchApi).createNamespacedJob(Mockito.eq("xnat-proj01"), Mockito.any(V1Job.class), Mockito.isNull(), Mockito.isNull(), Mockito.isNull(), Mockito.isNull());

        final Container single = kubernetesContainer(null);
        Mockito.when(kubernetesClient.createJob(single, DockerControlApi.NumReplicas.ZERO, server)).thenCallRealMethod();
        kubernetesClient.createJob(single, DockerControlApi.NumReplicas.ZERO, server);
        Mockito.verify(batchApi).createNamespacedJob(Mockito.eq("ctx"), Mockito.any(V1Job.class), Mockito.isNull(), Mockito.isNull(), Mockito.isNull(), Mockito.isNull());
    }

    @Test
    public void testKubernetesRemoveAndLogUseTheGivenNamespace() throws Exception {
        final BatchV1Api batchApi = Mockito.mock(BatchV1Api.class);
        final CoreV1Api coreApi = Mockito.mock(CoreV1Api.class);
        kubernetesClientWithMockedApi("ctx", batchApi);
        setField(kubernetesClient, "coreApi", coreApi);

        Mockito.doCallRealMethod().when(kubernetesClient).removeJob("job-1", "xnat-proj01");
        kubernetesClient.removeJob("job-1", "xnat-proj01");
        Mockito.verify(batchApi).deleteNamespacedJob(Mockito.eq("job-1"), Mockito.eq("xnat-proj01"), Mockito.isNull(), Mockito.isNull(), Mockito.isNull(), Mockito.isNull(), Mockito.eq("Foreground"), Mockito.isNull());

        Mockito.doCallRealMethod().when(kubernetesClient).removeJob("job-2", null);
        kubernetesClient.removeJob("job-2", null);
        Mockito.verify(batchApi).deleteNamespacedJob(Mockito.eq("job-2"), Mockito.eq("ctx"), Mockito.isNull(), Mockito.isNull(), Mockito.isNull(), Mockito.isNull(), Mockito.eq("Foreground"), Mockito.isNull());

        Mockito.when(kubernetesClient.getLog("job-1-pod", "xnat-proj01", org.nrg.containers.api.LogType.STDOUT, null, null)).thenCallRealMethod();
        kubernetesClient.getLog("job-1-pod", "xnat-proj01", org.nrg.containers.api.LogType.STDOUT, null, null);
        Mockito.verify(coreApi).readNamespacedPodLog(Mockito.eq("job-1-pod"), Mockito.eq("xnat-proj01"), Mockito.isNull(), Mockito.isNull(), Mockito.isNull(), Mockito.isNull(), Mockito.isNull(), Mockito.isNull(), Mockito.isNull(), Mockito.isNull(), Mockito.isNull());
    }

    private static void setField(final Object target, final String name, final Object value) throws Exception {
        final Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private V1Job captureJobForFileSecret(final String secretName, final String mountPath,
                                          final DockerServerBase.DockerServer server) throws Exception {
        backend = Backend.KUBERNETES;
        final String namespace = RandomStringUtils.randomAlphabetic(5).toLowerCase();
        final BatchV1Api batchApi = Mockito.mock(BatchV1Api.class);
        kubernetesClientWithMockedApi(namespace, batchApi);

        final Secret secret = new Secret(new K8sSecretSource("redcap", secretName), new FileSecretDestination(mountPath));
        final ResolvedSecret resolved = new FileResolvedSecret(secret, Optional.empty());
        final Container toCreate = Container.builder()
                .wrapperId(0L).commandId(0L)
                .dockerImage("busybox:latest")
                .commandLine("cat " + mountPath + "/client_id")
                .secrets(Collections.singletonList(resolved))
                .backend(backend)
                .userId(userId)
                .build();

        Mockito.when(batchApi.createNamespacedJob(Mockito.eq(namespace), Mockito.any(V1Job.class), Mockito.isNull(), Mockito.isNull(), Mockito.isNull(), Mockito.isNull()))
                .thenAnswer((Answer<V1Job>) invocation -> invocation.getArgument(1, V1Job.class));
        Mockito.when(kubernetesClient.createJob(toCreate, DockerControlApi.NumReplicas.ZERO, server))
                .thenCallRealMethod();
        kubernetesClient.createJob(toCreate, DockerControlApi.NumReplicas.ZERO, server);

        final ArgumentCaptor<V1Job> jobArgumentCaptor = ArgumentCaptor.forClass(V1Job.class);
        Mockito.verify(batchApi).createNamespacedJob(Mockito.eq(namespace), jobArgumentCaptor.capture(), Mockito.isNull(), Mockito.isNull(), Mockito.isNull(), Mockito.isNull());
        return jobArgumentCaptor.getValue();
    }

    @Test
    public void testKubernetesMountsFileSecretByResolvedName() throws Exception {
        final V1Job job = captureJobForFileSecret("xnat-proj01-redcap", "/run/secrets/redcap", kubernetesServer());
        final V1PodSpec podSpec = job.getSpec().getTemplate().getSpec();

        final List<V1Volume> volumes = podSpec.getVolumes();
        assertThat(volumes, hasSize(1));
        final V1Volume volume = volumes.get(0);
        assertThat(volume.getName(), is("secret-redcap"));
        assertThat(volume.getSecret(), is(notNullValue()));
        assertThat(volume.getSecret().getSecretName(), is("xnat-proj01-redcap"));
        assertThat(volume.getCsi(), is(nullValue()));

        final List<V1VolumeMount> mounts = podSpec.getContainers().get(0).getVolumeMounts();
        assertThat(mounts, hasSize(1));
        assertThat(mounts.get(0).getName(), is("secret-redcap"));
        assertThat(mounts.get(0).getMountPath(), is("/run/secrets/redcap"));
        assertThat(mounts.get(0).getReadOnly(), is(true));

        assertThat(podSpec.getContainers().get(0).getEnv(), anyOf(nullValue(), empty()));
        assertThat(podSpec.getServiceAccountName(), is(nullValue()));
    }

    @Test
    public void testKubernetesRefusesFileSecretWithoutResolvedName() throws Exception {
        try {
            captureJobForFileSecret(null, "/run/secrets/redcap", kubernetesServer());
            org.junit.Assert.fail("Expected ContainerException");
        } catch (ContainerException e) {
            assertThat(e.getMessage(), containsString("has no rendered Kubernetes object name"));
        }
    }

    @Test
    public void testKubernetesMountsFileSecretAsCsiVolume() throws Exception {
        final DockerServerBase.DockerServer server = kubernetesServer().toBuilder()
                .secretVolumeType(SecretVolumeType.CSI).build();
        final V1Job job = captureJobForFileSecret("xnat-proj01-redcap", "/run/secrets/redcap", server);
        final V1Volume volume = job.getSpec().getTemplate().getSpec().getVolumes().get(0);
        assertThat(volume.getSecret(), is(nullValue()));
        assertThat(volume.getCsi(), is(notNullValue()));
        assertThat(volume.getCsi().getDriver(), is("secrets-store.csi.k8s.io"));
        assertThat(volume.getCsi().getReadOnly(), is(true));
        assertThat(volume.getCsi().getVolumeAttributes(), IsMapContaining.hasEntry("secretProviderClass", "xnat-proj01-redcap"));
    }

    @Test
    public void testKubernetesSetsServiceAccountNameFromServerSettings() throws Exception {
        final DockerServerBase.DockerServer server = kubernetesServer().toBuilder().jobServiceAccount("xnat-jobs").build();
        final V1Job job = captureJobForFileSecret("xnat-proj01-redcap", "/run/secrets/redcap", server);
        assertThat(job.getSpec().getTemplate().getSpec().getServiceAccountName(), is("xnat-jobs"));
    }

    /**
     * Regression guard: the legacy xnat.cs.k8s.* system properties can no longer influence the volume type or the
     * service account.
     */
    @Test
    public void testLegacySystemPropertiesNoLongerConfigureFileSecrets() throws Exception {
        System.setProperty("xnat.cs.k8s.secretVolumeType", "csi");
        System.setProperty("xnat.cs.k8s.serviceAccountName", "legacy-sa");
        try {
            final V1Job job = captureJobForFileSecret("xnat-proj01-redcap", "/run/secrets/redcap", kubernetesServer());
            final V1PodSpec podSpec = job.getSpec().getTemplate().getSpec();
            assertThat(podSpec.getServiceAccountName(), is(nullValue()));
            assertThat(podSpec.getVolumes().get(0).getCsi(), is(nullValue()));
            assertThat(podSpec.getVolumes().get(0).getSecret().getSecretName(), is("xnat-proj01-redcap"));
        } finally {
            System.clearProperty("xnat.cs.k8s.secretVolumeType");
            System.clearProperty("xnat.cs.k8s.serviceAccountName");
        }
    }

    /** FR-15: both kinds in one command resolve independently and the Kubernetes job carries both. */
    @Test
    public void testKubernetesJobWithMixedEnvAndFileSecrets() throws Exception {
        backend = Backend.KUBERNETES;
        final DockerServerBase.DockerServer server = kubernetesServer();
        Mockito.when(dockerServerService.getServer()).thenReturn(server);

        final String secretValue = RandomStringUtils.randomAlphanumeric(16);
        final SystemPropertySecretSource envSource = new SystemPropertySecretSource("sys.prop");
        Mockito.when(valueObtainer.handledType()).thenReturn(SystemPropertySecretSource.class);
        Mockito.when(valueObtainer.obtainValue(envSource)).thenReturn(Optional.of(secretValue));
        final Secret envSecret = new Secret(envSource, new EnvironmentVariableSecretDestination("SECRET_ENV"));
        final Secret fileSecret = new Secret(new K8sSecretSource("redcap"), new FileSecretDestination("/run/secrets/redcap"));

        final Command.CommandWrapper wrapper = Command.CommandWrapper.builder().name("w").build();
        final Command command = Command.builder().name("mixed").image("busybox:latest").commandLine("true")
                .secrets(Arrays.asList(envSecret, fileSecret)).addCommandWrapper(wrapper).build();
        final Command.ConfiguredCommand configured = Command.ConfiguredCommand.initialize(command).wrapper(wrapper).build();
        final CommandResolutionService resolutionService = new CommandResolutionServiceImpl(commandService, dockerServerService,
                siteConfigPreferences, objectMapper, dockerService, catalogService, userDataCache,
                new ContainerSecretServiceImpl(Collections.singletonList(valueObtainer)));

        final ResolvedCommand resolved = resolutionService.resolve(configured, Collections.emptyMap(), "PROJ01", user, null);
        assertThat(resolved.secrets(), hasSize(2));
        assertThat(resolved.secrets().stream()
                .anyMatch(s -> s instanceof EnvironmentVariableResolvedSecret
                        && secretValue.equals(((EnvironmentVariableResolvedSecret) s).envValue())), is(true));
        assertThat(resolved.secrets().stream()
                .anyMatch(s -> s instanceof FileResolvedSecret && !(s instanceof ResolvedSecret.WithValue)), is(true));

        final String namespace = "ns";
        final BatchV1Api batchApi = Mockito.mock(BatchV1Api.class);
        kubernetesClientWithMockedApi(namespace, batchApi);
        Mockito.when(batchApi.createNamespacedJob(Mockito.eq(namespace), Mockito.any(V1Job.class), Mockito.isNull(), Mockito.isNull(), Mockito.isNull(), Mockito.isNull()))
                .thenAnswer((Answer<V1Job>) invocation -> invocation.getArgument(1, V1Job.class));
        final Container toCreate = Container.builderFromResolvedCommand(resolved).userId(userId).backend(backend).build();
        Mockito.when(kubernetesClient.createJob(toCreate, DockerControlApi.NumReplicas.ZERO, server)).thenCallRealMethod();
        kubernetesClient.createJob(toCreate, DockerControlApi.NumReplicas.ZERO, server);

        final ArgumentCaptor<V1Job> captor = ArgumentCaptor.forClass(V1Job.class);
        Mockito.verify(batchApi).createNamespacedJob(Mockito.eq(namespace), captor.capture(), Mockito.isNull(), Mockito.isNull(), Mockito.isNull(), Mockito.isNull());
        final V1PodSpec podSpec = captor.getValue().getSpec().getTemplate().getSpec();
        assertThat(podSpec.getContainers().get(0).getEnv(), contains(
                both(hasProperty("name", is("SECRET_ENV"))).and(hasProperty("value", is(secretValue)))));
        assertThat(podSpec.getVolumes(), hasSize(1));
        assertThat(podSpec.getVolumes().get(0).getSecret().getSecretName(), is("xnat-proj01-redcap"));
        final List<V1VolumeMount> mounts = podSpec.getContainers().get(0).getVolumeMounts();
        assertThat(mounts, hasSize(1));
        assertThat(mounts.get(0).getName(), is("secret-redcap"));
        assertThat(mounts.get(0).getMountPath(), is("/run/secrets/redcap"));
        assertThat(mounts.get(0).getReadOnly(), is(true));
    }
}
