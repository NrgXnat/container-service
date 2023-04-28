package org.nrg.containers.api;

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
import org.mandas.docker.client.DockerClient;
import org.mandas.docker.client.LogStream;
import org.mandas.docker.client.messages.ContainerConfig;
import org.mandas.docker.client.messages.ContainerCreation;
import org.mandas.docker.client.messages.ServiceCreateResponse;
import org.mandas.docker.client.messages.swarm.ContainerSpec;
import org.mandas.docker.client.messages.swarm.ReplicatedService;
import org.mandas.docker.client.messages.swarm.Service;
import org.mandas.docker.client.messages.swarm.ServiceMode;
import org.mandas.docker.client.messages.swarm.ServiceSpec;
import org.mandas.docker.client.messages.swarm.TaskSpec;
import org.mandas.docker.client.messages.swarm.Version;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.nrg.containers.model.command.auto.ResolvedCommand;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.image.docker.DockerImage;
import org.nrg.containers.model.server.docker.Backend;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.services.CommandLabelService;
import org.nrg.containers.services.DockerHubService;
import org.nrg.containers.services.DockerServerService;
import org.nrg.framework.services.NrgEventServiceI;
import org.nrg.xft.security.UserI;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.modules.junit4.PowerMockRunnerDelegate;
import org.powermock.reflect.Whitebox;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assume.assumeThat;
import static org.mockito.AdditionalMatchers.or;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.anyVararg;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isNull;
import static org.mockito.Mockito.doNothing;
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
    final String LOG_CONTENTS = UUID.randomUUID().toString();
    final String USER_LOGIN = UUID.randomUUID().toString();

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
            log.info("BEGINNING TEST " + description.getMethodName());
        }

        protected void finished(Description description) {
            log.info("ENDING TEST " + description.getMethodName());
        }
    };

    @Mock private CommandLabelService commandLabelService;
    @Mock private DockerHubService dockerHubService;
    @Mock private NrgEventServiceI eventService;
    @Mock private DockerServerService dockerServerService;
    @Mock private KubernetesClientFactory kubernetesClientFactory;
    @Mock private KubernetesClient kubernetesClient;

    @Mock private DockerClient mockDockerClient;
    @Mock private DockerImage mockDockerImage;
    @Mock private LogStream logStream;

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
                dockerServerService, commandLabelService, dockerHubService, eventService, kubernetesClientFactory
        ));
        PowerMockito.doReturn(mockDockerImage).when(dockerControlApi, method(
                DockerControlApi.class, "pullImage", String.class))
                .withArguments(anyString());
        PowerMockito.doReturn(mockDockerClient).when(dockerControlApi, method(
                DockerControlApi.class, "getClient", DockerServer.class))
                .withArguments(dockerServer);
        PowerMockito.doReturn(mockDockerClient).when(dockerControlApi, method(
                DockerControlApi.class, "getClient", DockerServer.class, String.class))
                .withArguments(eq(dockerServer), or(any(String.class), isNull(String.class)));

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

        when(logStream.readFully()).thenReturn(LOG_CONTENTS);

        when(user.getLogin()).thenReturn(USER_LOGIN);
    }

    @Test
    public void testPing() throws Exception {
        final String ok = "OK";

        // Set up test-specific mocks
        switch (backend) {
            case DOCKER:
                when(mockDockerClient.ping()).thenReturn(ok);
                break;
            case SWARM:
                when(mockDockerClient.listServices()).thenReturn(Collections.emptyList());
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
        final DockerClient.LogsParam dockerClientLogType = DockerClient.LogsParam.stdout();

        // Set up test-specific mocks
        if (backend == Backend.SWARM) {
            when(mockDockerClient.logs(any(String.class), (DockerClient.LogsParam) anyVararg()))
                    .thenThrow(new RuntimeException("Should not be called"));
            when(mockDockerClient.serviceLogs(BACKEND_ID, dockerClientLogType)).thenReturn(logStream);
        } else if (backend == Backend.KUBERNETES) {
            when(container.podName()).thenReturn(BACKEND_ID);
            when(kubernetesClient.getLog(
                    BACKEND_ID, logType, null, null
            )).thenReturn(LOG_CONTENTS);
        } else {
            when(mockDockerClient.logs(BACKEND_ID, dockerClientLogType)).thenReturn(logStream);
            when(mockDockerClient.serviceLogs(any(String.class), (DockerClient.LogsParam) anyVararg()))
                    .thenThrow(new RuntimeException("Should not be called"));
        }

        // Run the test
        final String log = dockerControlApi.getLog(container, logType);

        // Check results
        assertThat(log, is(equalTo(LOG_CONTENTS)));
    }

    @Test
    public void testGetLog_stderr() throws Exception {
        assumeThat("Special stderr handling for kubernetes", backend, not(Backend.KUBERNETES));

        final LogType logType = LogType.STDERR;
        final DockerClient.LogsParam dockerClientLogType = DockerClient.LogsParam.stderr();

        // Set up test-specific mocks
        if (swarmMode) {
            when(mockDockerClient.logs(any(String.class), (DockerClient.LogsParam) anyVararg()))
                    .thenThrow(new RuntimeException("Should not be called"));
            when(mockDockerClient.serviceLogs(BACKEND_ID, dockerClientLogType)).thenReturn(logStream);
        } else {
            when(mockDockerClient.logs(BACKEND_ID, dockerClientLogType)).thenReturn(logStream);
            when(mockDockerClient.serviceLogs(any(String.class), (DockerClient.LogsParam) anyVararg()))
                    .thenThrow(new RuntimeException("Should not be called"));
        }

        // Run the test
        final String log = dockerControlApi.getLog(container, logType);

        // Check results
        assertThat(log, is(equalTo(LOG_CONTENTS)));
    }

    @Test
    public void testGetLog_stderr_kubernetes() throws Exception {
        assumeThat("Special stderr handling for kubernetes", backend, is(Backend.KUBERNETES));

        // No need to mock anything

        // Run the test
        final String log = dockerControlApi.getLog(container, LogType.STDERR);

        // Check results
        assertThat(log, is(nullValue()));
    }

    @Test
    public void testGetLog_stdout_params() throws Exception {

        final LogType logType = LogType.STDOUT;
        final DockerClient.LogsParam dockerClientLogType = DockerClient.LogsParam.stdout();

        final boolean withTimestamp = false;
        final DockerClient.LogsParam timestampParam = DockerClient.LogsParam.timestamps(withTimestamp);
        final Integer since = Math.toIntExact(System.currentTimeMillis() / 1000L);
        final DockerClient.LogsParam sinceParam = DockerClient.LogsParam.since(since);

        // Set up test-specific mocks
        if (backend == Backend.SWARM) {
            when(mockDockerClient.logs(any(String.class), (DockerClient.LogsParam) anyVararg()))
                    .thenThrow(new RuntimeException("Should not be called"));
            when(mockDockerClient.serviceLogs(BACKEND_ID, dockerClientLogType, timestampParam, sinceParam))
                    .thenReturn(logStream);
        } else if (backend == Backend.KUBERNETES) {
            when(container.podName()).thenReturn(BACKEND_ID);
            when(kubernetesClient.getLog(
                    eq(BACKEND_ID), eq(logType), eq(withTimestamp), any(Integer.class)
            )).thenReturn(LOG_CONTENTS);
        } else {
            when(mockDockerClient.logs(BACKEND_ID, dockerClientLogType, timestampParam, sinceParam))
                    .thenReturn(logStream);
            when(mockDockerClient.serviceLogs(any(String.class), (DockerClient.LogsParam) anyVararg()))
                    .thenThrow(new RuntimeException("Should not be called"));
        }

        // Run the test
        final String log = dockerControlApi.getLog(container, logType, withTimestamp, since);

        // Check results
        assertThat(log, is(equalTo(LOG_CONTENTS)));
    }

    @Test
    public void testGetLog_stderr_params() throws Exception {
        assumeThat("Special stderr handling for kubernetes", backend, not(Backend.KUBERNETES));

        final LogType logType = LogType.STDERR;
        final DockerClient.LogsParam dockerClientLogType = DockerClient.LogsParam.stderr();

        final boolean withTimestamp = false;
        final DockerClient.LogsParam timestampParam = DockerClient.LogsParam.timestamps(withTimestamp);
        final Integer since = Math.toIntExact(System.currentTimeMillis() / 1000L);
        final DockerClient.LogsParam sinceParam = DockerClient.LogsParam.since(since);

        // Set up test-specific mocks
        if (swarmMode) {
            when(mockDockerClient.logs(any(String.class), (DockerClient.LogsParam) anyVararg()))
                    .thenThrow(new RuntimeException("Should not be called"));
            when(mockDockerClient.serviceLogs(BACKEND_ID, dockerClientLogType, timestampParam, sinceParam))
                    .thenReturn(logStream);
        } else {
            when(mockDockerClient.logs(BACKEND_ID, dockerClientLogType, timestampParam, sinceParam))
                    .thenReturn(logStream);
            when(mockDockerClient.serviceLogs(any(String.class), (DockerClient.LogsParam) anyVararg()))
                    .thenThrow(new RuntimeException("Should not be called"));
        }

        // Run the test
        final String log = dockerControlApi.getLog(container, logType, withTimestamp, since);

        // Check results
        assertThat(log, is(equalTo(LOG_CONTENTS)));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testGetStdoutLog() throws Exception {
        // Test values
        final DockerClient.LogsParam dockerClientLogType = DockerClient.LogsParam.stdout();

        // Set up test-specific mocks
        if (backend == Backend.SWARM) {
            when(mockDockerClient.logs(any(String.class), (DockerClient.LogsParam) anyVararg()))
                    .thenThrow(new RuntimeException("Should not be called"));
            when(mockDockerClient.serviceLogs(BACKEND_ID, dockerClientLogType)).thenReturn(logStream);
        } else if (backend == Backend.KUBERNETES) {
            when(container.podName()).thenReturn(BACKEND_ID);
            when(kubernetesClient.getLog(
                    BACKEND_ID, LogType.STDOUT, null, null
            )).thenReturn(LOG_CONTENTS);
        } else {
            when(mockDockerClient.logs(BACKEND_ID, dockerClientLogType)).thenReturn(logStream);
            when(mockDockerClient.serviceLogs(any(String.class), (DockerClient.LogsParam) anyVararg()))
                    .thenThrow(new RuntimeException("Should not be called"));
        }

        // Run the test
        final String log = dockerControlApi.getStdoutLog(container);

        // Check results
        assertThat(log, is(equalTo(LOG_CONTENTS)));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testGetStderrLog() throws Exception {
        assumeThat("Special stderr handling for kubernetes", backend, not(Backend.KUBERNETES));

        // Test values
        final DockerClient.LogsParam dockerClientLogType = DockerClient.LogsParam.stderr();

        // Set up test-specific mocks
        if (swarmMode) {
            when(mockDockerClient.logs(any(String.class), (DockerClient.LogsParam) anyVararg()))
                    .thenThrow(new RuntimeException("Should not be called"));
            when(mockDockerClient.serviceLogs(BACKEND_ID, dockerClientLogType)).thenReturn(logStream);
        } else {
            when(mockDockerClient.logs(BACKEND_ID, dockerClientLogType)).thenReturn(logStream);
            when(mockDockerClient.serviceLogs(any(String.class), (DockerClient.LogsParam) anyVararg()))
                    .thenThrow(new RuntimeException("Should not be called"));
        }

        // Run the test
        final String log = dockerControlApi.getStderrLog(container);

        // Check results
        assertThat(log, is(equalTo(LOG_CONTENTS)));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testGetContainerStdoutLog() throws Exception {
        assumeThat("Method only called in local docker mode", backend, is(Backend.DOCKER));

        // Test values.
        // Ideally these values would be parameterized, but I don't know how to make
        //  different sets of parameters for different tests in junit.
        final boolean withTimestamp = false;
        final DockerClient.LogsParam timestampParam = DockerClient.LogsParam.timestamps(withTimestamp);
        final Integer since = Math.toIntExact(System.currentTimeMillis() / 1000L);
        final DockerClient.LogsParam sinceParam = DockerClient.LogsParam.since(since);
        final DockerClient.LogsParam logType = DockerClient.LogsParam.stdout();

        // Set up test-specific mocks
        when(mockDockerClient.logs(BACKEND_ID, logType, timestampParam, sinceParam)).thenReturn(logStream);
        when(mockDockerClient.serviceLogs(any(String.class), (DockerClient.LogsParam) anyVararg()))
                .thenThrow(new RuntimeException("Should not be called"));

        // Run the test
        final String log = dockerControlApi.getContainerStdoutLog(BACKEND_ID, timestampParam, sinceParam);

        // Check results
        assertThat(log, is(equalTo(LOG_CONTENTS)));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testGetContainerStderrLog() throws Exception {
        assumeThat("Method only called in local docker mode", backend, is(Backend.DOCKER));

        // Test values.
        // Ideally these values would be parameterized, but I don't know how to make
        //  different sets of parameters for different tests in junit.
        final boolean withTimestamp = false;
        final DockerClient.LogsParam timestampParam = DockerClient.LogsParam.timestamps(withTimestamp);
        final Integer since = Math.toIntExact(System.currentTimeMillis() / 1000L);
        final DockerClient.LogsParam sinceParam = DockerClient.LogsParam.since(since);
        final DockerClient.LogsParam logType = DockerClient.LogsParam.stderr();

        // Set up test-specific mocks
        when(mockDockerClient.logs(BACKEND_ID, logType, timestampParam, sinceParam)).thenReturn(logStream);
        when(mockDockerClient.serviceLogs(any(String.class), (DockerClient.LogsParam) anyVararg()))
                .thenThrow(new RuntimeException("Should not be called"));

        // Run the test
        final String log = dockerControlApi.getContainerStderrLog(BACKEND_ID, timestampParam, sinceParam);

        // Check results
        assertThat(log, is(equalTo(LOG_CONTENTS)));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testGetServiceStdoutLog() throws Exception {
        assumeThat("Method only called in swarm mode", backend, is(Backend.SWARM));

        // Test values.
        // Ideally these values would be parameterized, but I don't know how to make
        //  different sets of parameters for different tests in junit.
        final boolean withTimestamp = false;
        final DockerClient.LogsParam timestampParam = DockerClient.LogsParam.timestamps(withTimestamp);
        final Integer since = Math.toIntExact(System.currentTimeMillis() / 1000L);
        final DockerClient.LogsParam sinceParam = DockerClient.LogsParam.since(since);
        final DockerClient.LogsParam logType = DockerClient.LogsParam.stdout();

        // Set up test-specific mocks
        when(mockDockerClient.serviceLogs(BACKEND_ID, logType, timestampParam, sinceParam)).thenReturn(logStream);
        when(mockDockerClient.logs(any(String.class), (DockerClient.LogsParam) anyVararg()))
                .thenThrow(new RuntimeException("Should not be called"));

        // Run the test
        final String log = dockerControlApi.getServiceStdoutLog(BACKEND_ID, timestampParam, sinceParam);

        // Check results
        assertThat(log, is(equalTo(LOG_CONTENTS)));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testGetServiceStderrLog() throws Exception {
        assumeThat("Method only called in swarm mode", backend, is(Backend.SWARM));

        // Test values.
        // Ideally these values would be parameterized, but I don't know how to make
        //  different sets of parameters for different tests in junit.
        final boolean withTimestamp = false;
        final DockerClient.LogsParam timestampParam = DockerClient.LogsParam.timestamps(withTimestamp);
        final Integer since = Math.toIntExact(System.currentTimeMillis() / 1000L);
        final DockerClient.LogsParam sinceParam = DockerClient.LogsParam.since(since);
        final DockerClient.LogsParam logType = DockerClient.LogsParam.stderr();

        // Set up test-specific mocks
        when(mockDockerClient.serviceLogs(BACKEND_ID, logType, timestampParam, sinceParam)).thenReturn(logStream);
        when(mockDockerClient.logs(any(String.class), (DockerClient.LogsParam) anyVararg()))
                .thenThrow(new RuntimeException("Should not be called"));

        // Run the test
        final String log = dockerControlApi.getServiceStderrLog(BACKEND_ID, timestampParam, sinceParam);

        // Check results
        assertThat(log, is(equalTo(LOG_CONTENTS)));
    }

    @Test
    public void testCreate_container() throws Exception {

        // Make test objects
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        final Container.Builder toLaunchAndExpectedContainerBuilder = Container.builder()
                .databaseId(random.nextInt())
                .commandId(random.nextInt())
                .wrapperId(random.nextInt())
                .userId(USER_LOGIN)
                .dockerImage("image")
                .commandLine("echo foo")
                .backend(backend)
                .addEnvironmentVariable("key", "value")
                .mounts(Collections.emptyList())
                .containerLabels(Collections.emptyMap());
        final Container toLaunch = toLaunchAndExpectedContainerBuilder.build();

        if (backend == Backend.SWARM) {
            toLaunchAndExpectedContainerBuilder.serviceId(BACKEND_ID);

            // Have to mock out the response from docker-client.
            // I would just make a real ServiceCreateResponse object,
            // but it doesn't have a build() method to return a Builder, and the
            // implementation is package-private.
            final ServiceCreateResponse serviceCreateResponse = Mockito.mock(ServiceCreateResponse.class);
            when(serviceCreateResponse.id()).thenReturn(BACKEND_ID);
            when(mockDockerClient.createService(any(ServiceSpec.class)))
                    .thenReturn(serviceCreateResponse);
        } else if (backend == Backend.KUBERNETES) {
            toLaunchAndExpectedContainerBuilder.serviceId(BACKEND_ID);

            when(kubernetesClient.createJob(
                    toLaunch, DockerControlApi.NumReplicas.ZERO, null, null
            )).thenReturn(BACKEND_ID);
        } else {
            toLaunchAndExpectedContainerBuilder.containerId(BACKEND_ID);

            when(mockDockerClient.createContainer(any(ContainerConfig.class)))
                    .thenReturn(ContainerCreation.builder().id(BACKEND_ID).build());
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
        final ResolvedCommand resolvedCommandToLaunch = ResolvedCommand.builder()
                .commandId(random.nextLong())
                .commandName("a command name")
                .wrapperId(random.nextLong())
                .wrapperName("a wrapper name")
                .image("test image name")
                .commandLine("echo foo bar baz")
                .mounts(Collections.emptyList())
                .environmentVariables(Collections.emptyMap())
                .containerLabels(Collections.emptyMap())
                .build();

        final Container.Builder expectedCreatedBuilder = Container.builderFromResolvedCommand(resolvedCommandToLaunch)
                .userId(USER_LOGIN)
                .backend(backend);

        if (backend == Backend.SWARM) {
            // Have to mock out the response from docker-client.
            // I would just make a real ServiceCreateResponse object,
            // but it doesn't have a build() method to return a Builder, and the
            // implementation is package-private.
            final ServiceCreateResponse serviceCreateResponse = Mockito.mock(ServiceCreateResponse.class);
            when(serviceCreateResponse.id()).thenReturn(BACKEND_ID);
            when(mockDockerClient.createService(any(ServiceSpec.class)))
                    .thenReturn(serviceCreateResponse);

            expectedCreatedBuilder.serviceId(BACKEND_ID);
        } else if (backend == Backend.KUBERNETES) {
            when(kubernetesClient.createJob(
                    any(Container.class), eq(DockerControlApi.NumReplicas.ZERO), any(String.class), any(String.class)
            )).thenReturn(BACKEND_ID);

            expectedCreatedBuilder.serviceId(BACKEND_ID);
        } else {
            when(mockDockerClient.createContainer(any(ContainerConfig.class)))
                    .thenReturn(ContainerCreation.builder().id(BACKEND_ID).build());

            expectedCreatedBuilder.containerId(BACKEND_ID);
        }
        final Container expected = expectedCreatedBuilder.build();

        // Run the test
        final Container created = dockerControlApi.create(resolvedCommandToLaunch, user);

        // Check results
        assertThat(created, equalTo(expected));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testCreateContainerOrSwarmService_container() throws Exception {
        // Test objects
        final Container toCreate = Mockito.mock(Container.class);
        Mockito.doReturn(container).when(dockerControlApi).create(any(Container.class), any(UserI.class));

        // Run the test
        dockerControlApi.createContainerOrSwarmService(toCreate, user);

        // Check the results
        verify(dockerControlApi).create(toCreate, user);
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testCreateContainerOrSwarmService_resolvedCommand() throws Exception {
        // Test objects
        final ResolvedCommand resolvedCommand = Mockito.mock(ResolvedCommand.class);
        Mockito.doReturn(container).when(dockerControlApi).create(any(ResolvedCommand.class), any(UserI.class));

        // Run the test
        dockerControlApi.createContainerOrSwarmService(resolvedCommand, user);

        // Check the results
        verify(dockerControlApi).create(resolvedCommand, user);
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
        // Real implementation of ServiceCreateResponse is package-private
        final ServiceCreateResponse serviceCreateResponse = new ServiceCreateResponse() {
            @Override
            public String id() {
                return BACKEND_ID;
            }

            @Override
            public List<String> warnings() {
                return Collections.emptyList();
            }
        };
        when(mockDockerClient.createService(any(ServiceSpec.class)))
                .thenReturn(serviceCreateResponse);

        // Run the method
        Whitebox.invokeMethod(dockerControlApi, "createDockerSwarmService", toCreate, dockerServer, numReplicas);

        // Assert on results
        final ArgumentCaptor<ServiceSpec> serviceSpecCaptor = ArgumentCaptor.forClass(ServiceSpec.class);
        verify(mockDockerClient).createService(serviceSpecCaptor.capture());

        final ServiceSpec serviceSpec = serviceSpecCaptor.getValue();
        assertThat(serviceSpec.mode().replicated().replicas(), equalTo(numReplicas.value));
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

        // First some garbage test data
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        final Long serviceVersion = random.nextLong();
        final String specName = "whatever";
        final ContainerSpec containerSpec = ContainerSpec.builder()
                .image("im")
                .env(Collections.emptyList())
                .dir("/a/dir")
                .user(user.getLogin())
                .command("echo", "foo")
                .build();
        final TaskSpec taskSpec = TaskSpec.builder().containerSpec(containerSpec).build();
        final ServiceSpec.Builder commonSpec = ServiceSpec.builder()
                .taskTemplate(taskSpec)
                .name(specName);

        // This is the mock spec that will pass through a chain of mocks into our code under test
        final ServiceSpec createdSpec = commonSpec
                .mode(ServiceMode.builder()
                        .replicated(ReplicatedService.builder().replicas(0L).build())
                        .build())
                .build();
        final Version version = Mockito.mock(Version.class);
        when(version.index()).thenReturn(serviceVersion);
        final Service created = Mockito.mock(Service.class);
        when(created.spec()).thenReturn(createdSpec);
        when(created.version()).thenReturn(version);
        when(mockDockerClient.inspectService(BACKEND_ID)).thenReturn(created);

        // This is the spec that we expect the code under test will create and pass to the client to update
        final ServiceSpec expectedUpdateSpec = commonSpec
                .mode(ServiceMode.builder()
                        .replicated(ReplicatedService.builder().replicas(1L).build())
                        .build())
                .build();

        // Run the test
        dockerControlApi.start(container);

        // Verify that the client method was called as expected
        verify(mockDockerClient).updateService(BACKEND_ID, serviceVersion, expectedUpdateSpec);
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
        verify(mockDockerClient).startContainer(BACKEND_ID);
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testKillContainer() throws Exception {
        assumeThat("Method only called in local docker mode", backend, is(Backend.DOCKER));

        // Run the test
        dockerControlApi.killContainer(BACKEND_ID);

        // Verify the client method was called as expected
        verify(mockDockerClient).killContainer(BACKEND_ID);
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testKillService() throws Exception {
        assumeThat("Method only called in swarm mode", backend, is(Backend.SWARM));

        // Run the test
        dockerControlApi.killService(BACKEND_ID);

        // Verify the client method was called as expected
        verify(mockDockerClient).removeService(BACKEND_ID);

    }

    @Test
    @SuppressWarnings("deprecation")
    public void testRemoveContainerOrService() throws Exception {
        // Mock expected behavior
        doNothing().when(dockerControlApi).autoCleanup(any(Container.class));

        // Run the test
        dockerControlApi.removeContainerOrService(container);

        // Verify the expected behavior
        verify(dockerControlApi).autoCleanup(container);
    }

    @Test
    public void testKill() throws Exception {
        // Run the test
        dockerControlApi.kill(container);

        // Verify the client method was called as expected
        switch (backend) {
            case DOCKER:
                verify(mockDockerClient).killContainer(BACKEND_ID);
                break;
            case SWARM:
                verify(mockDockerClient).removeService(BACKEND_ID);
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
        verify(mockDockerClient, times(0)).removeService(BACKEND_ID);
        verify(mockDockerClient, times(0)).removeContainer(BACKEND_ID);
        verify(kubernetesClient, times(0))
                .removeJob(BACKEND_ID);
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
                verify(mockDockerClient).removeContainer(BACKEND_ID);
                break;
            case SWARM:
                verify(mockDockerClient).removeService(BACKEND_ID);
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
                verify(mockDockerClient).removeContainer(BACKEND_ID);
                break;
            case SWARM:
                verify(mockDockerClient).removeService(BACKEND_ID);
                break;
            case KUBERNETES:
                verify(kubernetesClient).removeJob(BACKEND_ID);
                break;
        }
    }
}