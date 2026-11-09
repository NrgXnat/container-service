package org.nrg.containers.api;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectServiceCmd;
import com.github.dockerjava.api.model.ContainerSpec;
import com.github.dockerjava.api.model.Service;
import com.github.dockerjava.api.model.ServiceSpec;
import com.github.dockerjava.api.model.TaskSpec;
import org.apache.log4j.Level;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Answers;
import org.mockito.Mockito;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.server.docker.Backend;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.services.DockerHubService;
import org.nrg.containers.services.DockerServerService;
import org.nrg.containers.utils.LogCapture;

import java.util.Collections;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;

/** The swarm service inspected to find its task carries the container env, where env-type secrets are plain values. */
public class DockerControlApiServiceLoggingTest {
    private static final String SECRET_VALUE = "s3cr3t-redcap-token";
    private static final String SERVICE_ID = "svc-123";

    private final DockerClient dockerClient = Mockito.mock(DockerClient.class, Answers.RETURNS_MOCKS);
    private final InspectServiceCmd inspectServiceCmd = Mockito.mock(InspectServiceCmd.class);
    @Rule
    public final LogCapture logCapture = new LogCapture(DockerControlApi.class);
    private DockerControlApi dockerControlApi;

    @Before
    public void setUp() {
        dockerControlApi = Mockito.spy(new DockerControlApi(Mockito.mock(DockerServerService.class),
                Mockito.mock(DockerHubService.class), Mockito.mock(KubernetesClientFactory.class)));
        Mockito.doReturn(dockerClient).when(dockerControlApi).getDockerClient(any(DockerServer.class));

        final Service inspected = new Service();
        inspected.withSpec(new ServiceSpec().withName("secret-probe-svc")
                .withTaskTemplate(new TaskSpec().withContainerSpec(new ContainerSpec()
                        .withImage("busybox:latest")
                        .withEnv(Collections.singletonList("REDCAP_TOKEN=" + SECRET_VALUE)))));
        Mockito.when(dockerClient.inspectServiceCmd(SERVICE_ID)).thenReturn(inspectServiceCmd);
        Mockito.when(inspectServiceCmd.exec()).thenReturn(inspected);

        logCapture.setLevel(Level.TRACE);
    }


    @Test
    public void traceLogOfTheInspectedServiceDoesNotContainEnvironmentValues() throws Exception {
        final Container service = Container.builder().databaseId(7L).wrapperId(0L).commandId(0L)
                .dockerImage("busybox:latest").commandLine("true").userId("u")
                .backend(Backend.SWARM).serviceId(SERVICE_ID).build();
        final DockerServer server = DockerServer.builder().name("swarm").host("unix:///var/run/docker.sock").backend(Backend.SWARM).build();

        dockerControlApi.getTaskForService(server, service);

        final String logged = logCapture.messages();
        assertThat(logged, containsString("secret-probe-svc"));
        assertThat(logged, not(containsString(SECRET_VALUE)));
    }
}
