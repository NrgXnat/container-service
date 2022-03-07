package org.nrg.containers.api;

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
import org.mockito.Mock;
import org.mockito.Mockito;
import org.nrg.containers.model.command.auto.ResolvedCommand;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.services.CommandLabelService;
import org.nrg.containers.services.DockerHubService;
import org.nrg.containers.services.DockerServerService;
import org.nrg.framework.services.NrgEventService;
import org.nrg.xft.security.UserI;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.modules.junit4.PowerMockRunnerDelegate;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.isEmptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assume.assumeThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyVararg;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;
import static org.powermock.api.support.membermodification.MemberMatcher.method;

@Slf4j
@RunWith(PowerMockRunner.class)
@PowerMockRunnerDelegate(Parameterized.class)
@PrepareForTest(DockerControlApi.class)
public class DockerControlApiTest {
    final String BACKEND_ID = UUID.randomUUID().toString();
    final String LOG_CONTENTS = UUID.randomUUID().toString();
    final String USER_LOGIN = UUID.randomUUID().toString();

    @Parameterized.Parameters(name="swarmMode={0}")
    public static Collection<Boolean> swarmModeValues() {
        return Arrays.asList(Boolean.TRUE, Boolean.FALSE);
    }

    @Parameterized.Parameter
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
    @Mock private NrgEventService eventService;
    @Mock private DockerServerService dockerServerService;

    @Mock private DockerClient mockDockerClient;
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
                dockerServerService, commandLabelService, dockerHubService, eventService
        ));
        PowerMockito.doReturn(mockDockerClient).when(dockerControlApi, method(
                DockerControlApi.class, "getClient", DockerServer.class))
                .withArguments(dockerServer);
        PowerMockito.doReturn(mockDockerClient).when(dockerControlApi, method(
                DockerControlApi.class, "getClient", DockerServer.class, String.class))
                .withArguments(eq(dockerServer), argThat(isEmptyOrNullString()));

        // Mock simple return values
        when(dockerServer.swarmMode()).thenReturn(swarmMode);
        when(dockerServerService.getServer()).thenReturn(dockerServer);
        when(container.isSwarmService()).thenReturn(swarmMode);

        if (swarmMode) {
            when(container.containerId()).thenThrow(new RuntimeException("Should not be called"));
            when(container.serviceId()).thenReturn(BACKEND_ID);
        } else {
            when(container.containerId()).thenReturn(BACKEND_ID);
            when(container.serviceId()).thenThrow(new RuntimeException("Should not be called"));
        }

        when(logStream.readFully()).thenReturn(LOG_CONTENTS);

        when(user.getLogin()).thenReturn(USER_LOGIN);
    }

    @Test
    public void testGetLog_stdout() throws Exception {
        final ContainerControlApi.LogType logType = ContainerControlApi.LogType.STDOUT;
        final DockerClient.LogsParam dockerClientLogType = DockerClient.LogsParam.stdout();

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
        final String log_withContainer = dockerControlApi.getLog(container, logType);
        final String log_withBackendId = dockerControlApi.getLog(BACKEND_ID, logType);

        // Check results
        assertThat(log_withContainer, is(equalTo(LOG_CONTENTS)));
        assertThat(log_withBackendId, is(equalTo(LOG_CONTENTS)));
    }

    @Test
    public void testGetLog_stderr() throws Exception {
        final ContainerControlApi.LogType logType = ContainerControlApi.LogType.STDERR;
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
        final String log_withContainer = dockerControlApi.getLog(container, logType);
        final String log_withBackendId = dockerControlApi.getLog(BACKEND_ID, logType);

        // Check results
        assertThat(log_withContainer, is(equalTo(LOG_CONTENTS)));
        assertThat(log_withBackendId, is(equalTo(LOG_CONTENTS)));
    }

    @Test
    public void testGetLog_stdout_params() throws Exception {
        final ContainerControlApi.LogType logType = ContainerControlApi.LogType.STDOUT;
        final DockerClient.LogsParam dockerClientLogType = DockerClient.LogsParam.stdout();

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
        final String log_withContainer = dockerControlApi.getLog(container, logType, withTimestamp, since);
        final String log_withBackendId = dockerControlApi.getLog(BACKEND_ID, logType, withTimestamp, since);

        // Check results
        assertThat(log_withContainer, is(equalTo(LOG_CONTENTS)));
        assertThat(log_withBackendId, is(equalTo(LOG_CONTENTS)));
    }

    @Test
    public void testGetLog_stderr_params() throws Exception {
        final ContainerControlApi.LogType logType = ContainerControlApi.LogType.STDERR;
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
        final String log_withContainer = dockerControlApi.getLog(container, logType, withTimestamp, since);
        final String log_withBackendId = dockerControlApi.getLog(BACKEND_ID, logType, withTimestamp, since);

        // Check results
        assertThat(log_withContainer, is(equalTo(LOG_CONTENTS)));
        assertThat(log_withBackendId, is(equalTo(LOG_CONTENTS)));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testGetStdoutLog() throws Exception {
        // Test values
        final DockerClient.LogsParam dockerClientLogType = DockerClient.LogsParam.stdout();

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
        final String log = dockerControlApi.getStdoutLog(container);

        // Check results
        assertThat(log, is(equalTo(LOG_CONTENTS)));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testGetStderrLog() throws Exception {
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
        // This method is only called in local docker mode
        assumeThat(swarmMode, is(false));

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
        // This method is only called in local docker mode
        assumeThat(swarmMode, is(false));

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
        // This method is only called in local docker mode
        assumeThat(swarmMode, is(false));

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
        // This method is only called in local docker mode
        assumeThat(swarmMode, is(false));

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
        final Container toLaunch = Container.builder()
                .databaseId(random.nextInt())
                .commandId(random.nextInt())
                .wrapperId(random.nextInt())
                .userId(USER_LOGIN)
                .dockerImage("image")
                .commandLine("echo foo")
                .swarm(swarmMode)
                .addEnvironmentVariable("key", "value")
                .mounts(Collections.emptyList())
                .containerLabels(Collections.emptyMap())
                .build();
        final Container expectedCreated;

        if (swarmMode) {
            expectedCreated = toLaunch.toBuilder().serviceId(BACKEND_ID).build();

            // Have to mock out the response from docker-client.
            // I would just make a real ServiceCreateResponse object,
            // but it doesn't have a build() method to return a Builder, and the
            // implementation is package-private.
            final ServiceCreateResponse serviceCreateResponse = Mockito.mock(ServiceCreateResponse.class);
            when(serviceCreateResponse.id()).thenReturn(BACKEND_ID);
            when(mockDockerClient.createService(any(ServiceSpec.class)))
                    .thenReturn(serviceCreateResponse);
        } else {
            expectedCreated = toLaunch.toBuilder().containerId(BACKEND_ID).build();

            when(mockDockerClient.createContainer(any(ContainerConfig.class)))
                    .thenReturn(ContainerCreation.builder().id(BACKEND_ID).build());
        }

        // Run the test
        final Container created = dockerControlApi.create(toLaunch, user);

        // Check results
        assertThat(created, equalTo(expectedCreated));
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

        final Container expectedCreated;
        if (swarmMode) {
            expectedCreated = Container.serviceFromResolvedCommand(
                    resolvedCommandToLaunch, BACKEND_ID, USER_LOGIN
            );

            // Have to mock out the response from docker-client.
            // I would just make a real ServiceCreateResponse object,
            // but it doesn't have a build() method to return a Builder, and the
            // implementation is package-private.
            final ServiceCreateResponse serviceCreateResponse = Mockito.mock(ServiceCreateResponse.class);
            when(serviceCreateResponse.id()).thenReturn(BACKEND_ID);
            when(mockDockerClient.createService(any(ServiceSpec.class)))
                    .thenReturn(serviceCreateResponse);
        } else {
            expectedCreated = Container.containerFromResolvedCommand(
                    resolvedCommandToLaunch, BACKEND_ID, USER_LOGIN
            );

            when(mockDockerClient.createContainer(any(ContainerConfig.class)))
                    .thenReturn(ContainerCreation.builder().id(BACKEND_ID).build());
        }

        // Run the test
        final Container created = dockerControlApi.create(resolvedCommandToLaunch, user);

        // Check results
        assertThat(created, equalTo(expectedCreated));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testCreateContainerOrSwarmService_container() throws Exception {
        // Make test objects
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        final Container toLaunch = Container.builder()
                .databaseId(random.nextInt())
                .commandId(random.nextInt())
                .wrapperId(random.nextInt())
                .userId(USER_LOGIN)
                .dockerImage("image")
                .commandLine("echo foo")
                .swarm(swarmMode)
                .addEnvironmentVariable("key", "value")
                .mounts(Collections.emptyList())
                .containerLabels(Collections.emptyMap())
                .build();
        final Container expectedCreated;

        if (swarmMode) {
            expectedCreated = toLaunch.toBuilder().serviceId(BACKEND_ID).build();

            // Have to mock out the response from docker-client.
            // I would just make a real ServiceCreateResponse object,
            // but it doesn't have a build() method to return a Builder, and the
            // implementation is package-private.
            final ServiceCreateResponse serviceCreateResponse = Mockito.mock(ServiceCreateResponse.class);
            when(serviceCreateResponse.id()).thenReturn(BACKEND_ID);
            when(mockDockerClient.createService(any(ServiceSpec.class)))
                    .thenReturn(serviceCreateResponse);
        } else {
            expectedCreated = toLaunch.toBuilder().containerId(BACKEND_ID).build();

            when(mockDockerClient.createContainer(any(ContainerConfig.class)))
                    .thenReturn(ContainerCreation.builder().id(BACKEND_ID).build());
        }

        // Run the test
        final Container created = dockerControlApi.createContainerOrSwarmService(toLaunch, user);

        // Check results
        assertThat(created, equalTo(expectedCreated));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testCreateContainerOrSwarmService_resolvedCommand() throws Exception {
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

        final Container expectedCreated;
        if (swarmMode) {
            expectedCreated = Container.serviceFromResolvedCommand(
                    resolvedCommandToLaunch, BACKEND_ID, USER_LOGIN
            );

            // Have to mock out the response from docker-client.
            // I would just make a real ServiceCreateResponse object,
            // but it doesn't have a build() method to return a Builder, and the
            // implementation is package-private.
            final ServiceCreateResponse serviceCreateResponse = Mockito.mock(ServiceCreateResponse.class);
            when(serviceCreateResponse.id()).thenReturn(BACKEND_ID);
            when(mockDockerClient.createService(any(ServiceSpec.class)))
                    .thenReturn(serviceCreateResponse);
        } else {
            expectedCreated = Container.containerFromResolvedCommand(
                    resolvedCommandToLaunch, BACKEND_ID, USER_LOGIN
            );

            when(mockDockerClient.createContainer(any(ContainerConfig.class)))
                    .thenReturn(ContainerCreation.builder().id(BACKEND_ID).build());
        }

        // Run the test
        final Container created = dockerControlApi.createContainerOrSwarmService(resolvedCommandToLaunch, user);

        // Check results
        assertThat(created, equalTo(expectedCreated));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testStartContainer_swarm() throws Exception {
        assumeThat(swarmMode, is(true));

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
        dockerControlApi.startContainer(container);

        // Verify that the client method was called as expected
        verify(mockDockerClient).updateService(BACKEND_ID, serviceVersion, expectedUpdateSpec);
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testStartContainer_localDocker() throws Exception {
        assumeThat(swarmMode, is(false));

        // Run the test
        dockerControlApi.startContainer(container);

        // Verify that the client method was called as expected
        verify(mockDockerClient).startContainer(BACKEND_ID);
    }

    @Test
    public void testStart_swarm() throws Exception {
        assumeThat(swarmMode, is(true));

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

    @Test
    public void testStart_localDocker() throws Exception {
        assumeThat(swarmMode, is(false));

        // Run the test
        dockerControlApi.start(container);

        // Verify that the client method was called as expected
        verify(mockDockerClient).startContainer(BACKEND_ID);
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testKillContainer() throws Exception {
        assumeThat(swarmMode, is(false));

        // Run the test
        dockerControlApi.killContainer(BACKEND_ID);

        // Verify the client method was called as expected
        verify(mockDockerClient).killContainer(BACKEND_ID);
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testKillService() throws Exception {
        assumeThat(swarmMode, is(false));

        // Run the test
        dockerControlApi.killService(BACKEND_ID);

        // Verify the client method was called as expected
        verify(mockDockerClient).removeService(BACKEND_ID);

    }

    @Test
    @SuppressWarnings("deprecation")
    public void testRemoveContainerOrService() throws Exception {
        // set server to autocleanup
        when(dockerServer.autoCleanup()).thenReturn(true);

        // Run the test
        dockerControlApi.removeContainerOrService(container);

        // Verify the client method was called as expected
        if (swarmMode) {
            verify(mockDockerClient).removeService(BACKEND_ID);
        } else {
            verify(mockDockerClient).removeContainer(BACKEND_ID);
        }
    }

    @Test
    public void testKill() throws Exception {
        // Run the test
        dockerControlApi.kill(container);

        // Verify the client method was called as expected
        if (swarmMode) {
            verify(mockDockerClient).removeService(BACKEND_ID);
        } else {
            verify(mockDockerClient).killContainer(BACKEND_ID);
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
    }

    @Test
    public void testAutoCleanup_autoCleanupTrue() throws Exception {
        // mock server settings to not autocleanup
        when(dockerServer.autoCleanup()).thenReturn(true);

        // Run the test
        dockerControlApi.autoCleanup(container);

        // Verify the client method was called as expected
        if (swarmMode) {
            verify(mockDockerClient).removeService(BACKEND_ID);
        } else {
            verify(mockDockerClient).removeContainer(BACKEND_ID);
        }
    }

    @Test
    public void testRemove() throws Exception {
        // Run the test
        dockerControlApi.remove(container);

        // Verify the client method was called as expected
        if (swarmMode) {
            verify(mockDockerClient).removeService(BACKEND_ID);
        } else {
            verify(mockDockerClient).removeContainer(BACKEND_ID);
        }
    }
}