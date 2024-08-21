package org.nrg.containers.api;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.CreateServiceCmd;
import com.github.dockerjava.api.command.CreateServiceResponse;
import com.github.dockerjava.api.command.InspectServiceCmd;
import com.github.dockerjava.api.command.InspectSwarmCmd;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.command.LogSwarmObjectCmd;
import com.github.dockerjava.api.command.PingCmd;
import com.github.dockerjava.api.command.UpdateServiceCmd;
import com.github.dockerjava.api.model.Service;
import com.github.dockerjava.api.model.ServiceModeConfig;
import com.github.dockerjava.api.model.ServiceSpec;
import com.google.common.collect.ImmutableList;
import io.kubernetes.client.util.PatchUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.nrg.containers.model.command.auto.ResolvedCommand;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.image.docker.DockerImage;
import org.nrg.containers.model.server.docker.Backend;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.services.DockerHubService;
import org.nrg.containers.services.DockerServerService;
import org.nrg.xft.security.UserI;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.modules.junit4.PowerMockRunnerDelegate;
import org.powermock.reflect.Whitebox;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assume.assumeThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.powermock.api.support.membermodification.MemberMatcher.method;

@Slf4j
@RunWith(PowerMockRunner.class)
@PowerMockRunnerDelegate(Parameterized.class)
@PrepareForTest({DockerControlApi.class, PatchUtils.class})
public class DockerControlApiTest {
    final String BACKEND_ID = UUID.randomUUID().toString();
    final String USER_LOGIN = UUID.randomUUID().toString();

    // This default answer lets us stub out all the method chaining .withFoo().withBar() without having to stub each one
    // Have to make it explicitly because Mockito.RETURNS_SELF is not available in our version
    @SuppressWarnings("rawtypes")
    private final static Answer RETURN_SELF = InvocationOnMock::getMock;

    @Parameterized.Parameters(name="backend={0}")
    public static Collection<Backend> backend() {
        return EnumSet.allOf(Backend.class);
    }

    @Parameterized.Parameter
    public Backend backend;
    public boolean swarmMode;

    @Rule
    public TestRule watcher = new TestWatcher() {
        protected void starting(Description description) {
            log.info("BEGINNING TEST {}", description.getMethodName());
        }

        protected void finished(Description description) {
            log.info("ENDING TEST {}", description.getMethodName());
        }
    };

    @Mock private DockerHubService dockerHubService;
    @Mock private DockerServerService dockerServerService;
    @Mock private KubernetesClientFactory kubernetesClientFactory;
    @Mock private KubernetesClient kubernetesClient;

    @Mock(answer = Answers.RETURNS_MOCKS) private com.github.dockerjava.api.DockerClient mockDockerJavaClient;
    @Mock private DockerImage mockDockerImage;

    @Mock private DockerServer dockerServer;
    @Mock private Container container;
    @Mock private UserI user;

    private DockerControlApi dockerControlApi;

    @Before
    public void setup() throws Exception {
        // We want to test the real DockerControlApi methods, but we want a fake DockerClient.
        // Since the DockerClient is constructed in a private method in DockerControlApi, we need
        // to use PowerMock to mock it out.
        // The fact that we have to do this is a code smell!
        // Should probably inject this client instance into DockerControlApi as a bean.
        dockerControlApi = PowerMockito.spy(new DockerControlApi(
                dockerServerService, dockerHubService, kubernetesClientFactory
        ));
        PowerMockito.doReturn(mockDockerImage)
                .when(dockerControlApi, method(DockerControlApi.class, "pullImage", String.class))
                .withArguments(anyString());
        PowerMockito.doReturn(mockDockerJavaClient)
                .when(dockerControlApi, method(DockerControlApi.class, "getDockerClient", DockerServer.class))
                .withArguments(dockerServer);

        // Mock simple return values
        when(dockerServer.backend()).thenReturn(backend);
        when(dockerServerService.getServer()).thenReturn(dockerServer);
        when(kubernetesClientFactory.getKubernetesClient()).thenReturn(kubernetesClient);

        switch (backend) {
            case SWARM:
                when(container.containerId()).thenThrow(new RuntimeException("Should not be called"));
                when(container.serviceId()).thenReturn(BACKEND_ID);
                break;
            case KUBERNETES:
                when(container.jobName()).thenReturn(BACKEND_ID);
                break;
            case DOCKER:
                when(container.containerId()).thenReturn(BACKEND_ID);
                when(container.serviceId()).thenThrow(new RuntimeException("Should not be called"));
                break;
        }
        swarmMode = backend == Backend.SWARM;  // backwards compatibility

        when(user.getLogin()).thenReturn(USER_LOGIN);
    }

    @Test
    public void testPing() throws Exception {
        final String ok = "OK";

        // Set up test-specific mocks
        switch (backend) {
            case DOCKER:
                final PingCmd pingCmd = Mockito.mock(PingCmd.class);
                when(mockDockerJavaClient.pingCmd()).thenReturn(pingCmd);
                when(pingCmd.exec()).thenReturn(null);
                break;
            case SWARM:
                final InspectSwarmCmd inspectSwarmCmd = Mockito.mock(InspectSwarmCmd.class);
                when(mockDockerJavaClient.inspectSwarmCmd()).thenReturn(inspectSwarmCmd);
                when(inspectSwarmCmd.exec()).thenReturn(null);
                break;
            case KUBERNETES:
                when(kubernetesClient.ping()).thenReturn(ok);
                break;
        }

        // Run the test
        final String ping = dockerControlApi.ping();

        // Check the results
        assumeThat(ping, is(ok));
    }

    @Test
    public void testGetLog_stdout() throws Exception {
        final LogType logType = LogType.STDOUT;
        final String logContents = UUID.randomUUID().toString();

        // Set up test-specific mocks
        if (backend == Backend.SWARM) {
            setUpForSwarmLogTest(logContents);
        } else if (backend == Backend.KUBERNETES) {
            when(container.podName()).thenReturn(BACKEND_ID);
            when(kubernetesClient.getLog(
                    BACKEND_ID, logType, null, null
            )).thenReturn(logContents);
        } else {
            setUpForContainerLogTest(logContents);
        }

        // Run the test
        final String log = dockerControlApi.getLog(container, logType);

        // Check results
        assertThat(log, is(equalTo(logContents)));
    }

    @Test
    public void testGetLog_stderr() throws Exception {
        final LogType logType = LogType.STDERR;
        final String logContents = UUID.randomUUID().toString();

        // Set up test-specific mocks
        if (backend == Backend.SWARM) {
            setUpForSwarmLogTest(logContents);
        } else if (backend == Backend.DOCKER) {
            setUpForContainerLogTest(logContents);
        } else {
            // No need to mock anything for Kubernetes
        }

        // Run the test
        final String log = dockerControlApi.getLog(container, logType);

        // Check results
        if (backend == Backend.KUBERNETES) {
            assertThat(log, is(nullValue()));
        } else {
            assertThat(log, is(equalTo(logContents)));
        }
    }

    private void setUpForSwarmLogTest(final String logContents) {
        final LogSwarmObjectCmd cmd = Mockito.mock(LogSwarmObjectCmd.class, RETURN_SELF);
        when(mockDockerJavaClient.logServiceCmd(BACKEND_ID)).thenReturn(cmd);
        final DockerControlApi.GetLogCallback callback = Mockito.mock(DockerControlApi.GetLogCallback.class);
        Mockito.doReturn(callback).when(cmd).exec(any(DockerControlApi.GetLogCallback.class));

        when(callback.getLog()).thenReturn(logContents);

        when(mockDockerJavaClient.logContainerCmd(any(String.class)))
                .thenThrow(new RuntimeException("Should not be called"));
    }

    private void setUpForContainerLogTest(final String logContents) {
        final LogContainerCmd cmd = Mockito.mock(LogContainerCmd.class, RETURN_SELF);
        when(mockDockerJavaClient.logContainerCmd(BACKEND_ID)).thenReturn(cmd);
        final DockerControlApi.GetLogCallback callback = Mockito.mock(DockerControlApi.GetLogCallback.class);
        Mockito.doReturn(callback).when(cmd).exec(any(DockerControlApi.GetLogCallback.class));

        when(callback.getLog()).thenReturn(logContents);

        when(mockDockerJavaClient.logServiceCmd(any(String.class)))
                .thenThrow(new RuntimeException("Should not be called"));
    }

    @Test
    public void testCreate_container() throws Exception {

        // Make test objects
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        final String dockerImage = "test image name";
        final Container.Builder toLaunchAndExpectedContainerBuilder = Container.builder()
                .databaseId(random.nextInt())
                .commandId(random.nextInt())
                .wrapperId(random.nextInt())
                .userId(USER_LOGIN)
                .dockerImage(dockerImage)
                .commandLine("echo foo")
                .backend(backend)
                .addEnvironmentVariable("key", "value")
                .mounts(Collections.emptyList())
                .containerLabels(Collections.emptyMap());
        final Container toLaunch = toLaunchAndExpectedContainerBuilder.build();

        if (backend == Backend.SWARM) {
            toLaunchAndExpectedContainerBuilder.serviceId(BACKEND_ID);

            final CreateServiceResponse resp = Mockito.mock(CreateServiceResponse.class);
            Mockito.when(resp.getId()).thenReturn(BACKEND_ID);

            final CreateServiceCmd cmd = Mockito.mock(CreateServiceCmd.class, RETURN_SELF);
            Mockito.doReturn(resp).when(cmd).exec();
            Mockito.when(mockDockerJavaClient.createServiceCmd(Mockito.any(ServiceSpec.class))).thenReturn(cmd);
        } else if (backend == Backend.KUBERNETES) {
            toLaunchAndExpectedContainerBuilder.serviceId(BACKEND_ID);

            when(kubernetesClient.createJob(
                    toLaunch, DockerControlApi.NumReplicas.ZERO, null, null
            )).thenReturn(BACKEND_ID);
        } else {
            toLaunchAndExpectedContainerBuilder.containerId(BACKEND_ID);

            final CreateContainerResponse resp = new CreateContainerResponse();
            resp.setId(BACKEND_ID);
            resp.setWarnings(new String[0]);

            final CreateContainerCmd cmd = Mockito.mock(CreateContainerCmd.class, RETURN_SELF);
            Mockito.doReturn(resp).when(cmd).exec();
            Mockito.when(mockDockerJavaClient.createContainerCmd(dockerImage)).thenReturn(cmd);

            // We also try to pull the image
            Mockito.when(mockDockerImage.tags()).thenReturn(ImmutableList.of(dockerImage));
            PowerMockito.doReturn(Collections.singletonList(mockDockerImage))
                    .when(dockerControlApi, method(DockerControlApi.class, "getAllImages", DockerServer.class))
                    .withArguments(dockerServer);
        }
        final Container expected = toLaunchAndExpectedContainerBuilder.build();

        // Run the test
        final Container created = dockerControlApi.create(toLaunch, user);

        // Check results
        assertThat(created, equalTo(expected));
    }

    @Test
    public void testCreate_resolvedCommand() throws Exception {

        // Make test objects
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        final String dockerImage = "test image name";
        final ResolvedCommand resolvedCommandToLaunch = ResolvedCommand.builder()
                .commandId(random.nextLong())
                .commandName("a command name")
                .wrapperId(random.nextLong())
                .wrapperName("a wrapper name")
                .image(dockerImage)
                .commandLine("echo foo bar baz")
                .mounts(Collections.emptyList())
                .environmentVariables(Collections.emptyMap())
                .containerLabels(Collections.emptyMap())
                .build();

        final Container.Builder expectedCreatedBuilder = Container.builderFromResolvedCommand(resolvedCommandToLaunch)
                .userId(USER_LOGIN)
                .backend(backend);

        if (backend == Backend.SWARM) {
            final CreateServiceResponse resp = Mockito.mock(CreateServiceResponse.class);
            Mockito.when(resp.getId()).thenReturn(BACKEND_ID);

            final CreateServiceCmd cmd = Mockito.mock(CreateServiceCmd.class, RETURN_SELF);
            Mockito.doReturn(resp).when(cmd).exec();
            Mockito.when(mockDockerJavaClient.createServiceCmd(Mockito.any(ServiceSpec.class))).thenReturn(cmd);

            expectedCreatedBuilder.serviceId(BACKEND_ID);
        } else if (backend == Backend.KUBERNETES) {
            when(kubernetesClient.createJob(
                    any(Container.class), eq(DockerControlApi.NumReplicas.ZERO), any(String.class), any(String.class)
            )).thenReturn(BACKEND_ID);

            expectedCreatedBuilder.serviceId(BACKEND_ID);
        } else {
            final CreateContainerResponse resp = new CreateContainerResponse();
            resp.setId(BACKEND_ID);
            resp.setWarnings(new String[0]);

            final CreateContainerCmd cmd = Mockito.mock(CreateContainerCmd.class, RETURN_SELF);
            Mockito.doReturn(resp).when(cmd).exec();
            Mockito.when(mockDockerJavaClient.createContainerCmd(dockerImage)).thenReturn(cmd);

            // We also try to pull the image
            Mockito.when(mockDockerImage.tags()).thenReturn(ImmutableList.of(dockerImage));
            PowerMockito.doReturn(Collections.singletonList(mockDockerImage))
                    .when(dockerControlApi, method(DockerControlApi.class, "getAllImages", DockerServer.class))
                    .withArguments(dockerServer);

            expectedCreatedBuilder.containerId(BACKEND_ID);
        }
        final Container expected = expectedCreatedBuilder.build();

        // Run the test
        final Container created = dockerControlApi.create(resolvedCommandToLaunch, user);

        // Check results
        assertThat(created, equalTo(expected));
    }

    @Test
    public void testCreateDockerSwarmService_zeroReplicas() throws Exception {
        assumeThat(backend, is(Backend.SWARM));
        invokeCreateDockerSwarmServicePrivateMethod(DockerControlApi.NumReplicas.ZERO);
    }

    @Test
    public void testCreateDockerSwarmService_oneReplica() throws Exception {
        assumeThat(backend, is(Backend.SWARM));
        invokeCreateDockerSwarmServicePrivateMethod(DockerControlApi.NumReplicas.ONE);
    }

    public void invokeCreateDockerSwarmServicePrivateMethod(final DockerControlApi.NumReplicas numReplicas) throws Exception {
        // Test data
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        final Container toCreate = Container.builder()
                .databaseId(random.nextInt())
                .commandId(random.nextInt())
                .wrapperId(random.nextInt())
                .userId(USER_LOGIN)
                .dockerImage("image")
                .commandLine("echo foo")
                .addEnvironmentVariable("key", "value")
                .mounts(Collections.emptyList())
                .containerLabels(Collections.emptyMap())
                .build();

        // Mocks
        final CreateServiceResponse resp = Mockito.mock(CreateServiceResponse.class);
        Mockito.when(resp.getId()).thenReturn(BACKEND_ID);

        final CreateServiceCmd cmd = Mockito.mock(CreateServiceCmd.class, RETURN_SELF);
        Mockito.doReturn(resp).when(cmd).exec();
        Mockito.when(mockDockerJavaClient.createServiceCmd(Mockito.any(ServiceSpec.class))).thenReturn(cmd);

        // Run the method
        Whitebox.invokeMethod(dockerControlApi, "createDockerSwarmService", toCreate, dockerServer, numReplicas);

        // Assert on results
        final ArgumentCaptor<ServiceSpec> serviceSpecCaptor = ArgumentCaptor.forClass(ServiceSpec.class);
        Mockito.verify(mockDockerJavaClient).createServiceCmd(serviceSpecCaptor.capture());

        final ServiceSpec serviceSpec = serviceSpecCaptor.getValue();
        assertThat(serviceSpec.getMode().getReplicated().getReplicas(), equalTo(new Integer(numReplicas.value).longValue()));
    }

    @Test
    public void testStart() throws Exception {
        switch (backend) {
            case SWARM:
                testStart_swarmMode();
                break;
            case DOCKER:
                testStart_localDocker();
                break;
            case KUBERNETES:
                testStart_kubernetes();
                break;
        }
    }

    private void testStart_swarmMode() throws Exception {

        // When we "create"d the swarm service we set its replicas to 0.
        // We "start" a swarm service by setting its replicas to 1.
        // The code under test will get the spec from the client, change it, and send
        //  it back to the client to update.
        // We need to make a mock spec that the mock client can return.
        // We can also change the replicas and verify that the code under test made
        //  that same change.

        final ServiceSpec serviceSpec = Mockito.mock(ServiceSpec.class);
        final ServiceSpec updatedSpec = Mockito.mock(ServiceSpec.class);
        final Service service = Mockito.mock(Service.class);
        final InspectServiceCmd inspectServiceCmd = Mockito.mock(InspectServiceCmd.class, Mockito.RETURNS_DEEP_STUBS);
        final UpdateServiceCmd updateServiceCmd = Mockito.mock(UpdateServiceCmd.class, Mockito.RETURNS_DEEP_STUBS);
        when(mockDockerJavaClient.inspectServiceCmd(BACKEND_ID)).thenReturn(inspectServiceCmd);
        when(mockDockerJavaClient.updateServiceCmd(BACKEND_ID, updatedSpec)).thenReturn(updateServiceCmd);
        when(inspectServiceCmd.exec()).thenReturn(service);
        when(service.getSpec()).thenReturn(serviceSpec);
        when(serviceSpec.withMode(any(ServiceModeConfig.class))).thenReturn(updatedSpec);

        // Run the test
        dockerControlApi.start(container);

        // Verify that the service inspect API was called
        verify(mockDockerJavaClient).inspectServiceCmd(BACKEND_ID);

        // Verify that the service was updated to have 1 replica
        final ArgumentCaptor<ServiceModeConfig> modeCaptor = ArgumentCaptor.forClass(ServiceModeConfig.class);
        verify(serviceSpec).withMode(modeCaptor.capture());
        final ServiceModeConfig mode = modeCaptor.getValue();
        assertThat(mode.getReplicated(), notNullValue());
        assertThat(mode.getReplicated().getReplicas(), equalTo(1L));

        // Verify that the service update API was called
        verify(mockDockerJavaClient).updateServiceCmd(BACKEND_ID, updatedSpec);
        verify(updateServiceCmd).withVersion(any(Long.class));
    }

    private void testStart_kubernetes() throws Exception {

        // Run the test
        dockerControlApi.start(container);

        verify(kubernetesClient).unsuspendJob(BACKEND_ID);
    }

    private void testStart_localDocker() throws Exception {

        // Run the test
        dockerControlApi.start(container);

        // Verify that the client method was called as expected
        verify(mockDockerJavaClient).startContainerCmd(BACKEND_ID);
    }

    @Test
    public void testKill() throws Exception {
        // Run the test
        dockerControlApi.kill(container);

        // Verify the client method was called as expected
        switch (backend) {
            case DOCKER:
                verify(mockDockerJavaClient).killContainerCmd(BACKEND_ID);
                break;
            case SWARM:
                verify(mockDockerJavaClient).removeServiceCmd(BACKEND_ID);
                break;
            case KUBERNETES:
                verify(kubernetesClient).removeJob(BACKEND_ID);
                break;
        }
    }

    @Test
    public void testAutoCleanup_autoCleanupFalse() throws Exception {
        // mock server settings to not autocleanup
        when(dockerServer.autoCleanup()).thenReturn(false);

        // Run the test
        dockerControlApi.autoCleanup(container);

        // Verify the client method was called as expected
        // In this case we expect nothing will happen
        verify(mockDockerJavaClient, times(0)).removeServiceCmd(BACKEND_ID);
        verify(mockDockerJavaClient, times(0)).removeContainerCmd(BACKEND_ID);
        verify(kubernetesClient, times(0)).removeJob(BACKEND_ID);
    }

    @Test
    public void testAutoCleanup_autoCleanupTrue() throws Exception {
        // mock server settings to autocleanup
        when(dockerServer.autoCleanup()).thenReturn(true);

        // Run the test
        dockerControlApi.autoCleanup(container);

        // Verify the client method was called as expected
        switch (backend) {
            case DOCKER:
                verify(mockDockerJavaClient).removeContainerCmd(BACKEND_ID);
                break;
            case SWARM:
                verify(mockDockerJavaClient).removeServiceCmd(BACKEND_ID);
                break;
            case KUBERNETES:
                verify(kubernetesClient).removeJob(BACKEND_ID);
                break;
        }
    }

    @Test
    public void testRemove() throws Exception {
        // Run the test
        dockerControlApi.remove(container);

        // Verify the client method was called as expected
        switch (backend) {
            case DOCKER:
                verify(mockDockerJavaClient).removeContainerCmd(BACKEND_ID);
                break;
            case SWARM:
                verify(mockDockerJavaClient).removeServiceCmd(BACKEND_ID);
                break;
            case KUBERNETES:
                verify(kubernetesClient).removeJob(BACKEND_ID);
                break;
        }
    }
}