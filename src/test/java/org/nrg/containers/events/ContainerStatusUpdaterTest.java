package org.nrg.containers.events;

import com.github.dockerjava.api.model.TaskState;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;
import org.nrg.containers.api.ContainerControlApi;
import org.nrg.containers.api.KubernetesClientFactory;
import org.nrg.containers.events.model.ServiceTaskEvent;
import org.nrg.containers.exceptions.TaskNotFoundException;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.auto.ServiceTask;
import org.nrg.containers.model.server.docker.Backend;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.services.ContainerService;
import org.nrg.containers.services.DockerServerService;
import org.nrg.framework.event.EventI;
import org.nrg.framework.services.NrgEventServiceI;
import org.nrg.xdat.security.helpers.Users;
import org.nrg.xdat.servlet.XDATServlet;
import org.nrg.xft.schema.XFTManager;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.services.XnatAppInfo;
import org.springframework.jms.core.JmsTemplate;

import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Swarm polling loop reads each service through a cheap partial projection
 * ({@link ContainerService#retrieveServiceForPoll}). Any service it hands on in an event is saved back to the
 * database by {@code ContainerServiceImpl.processEvent}, so it must be the full row: saving the projection would
 * blank the image and command line and orphan-delete the mounts, inputs, outputs and history.
 */
@RunWith(MockitoJUnitRunner.class)
public class ContainerStatusUpdaterTest {
    private static final long DATABASE_ID = 42L;
    private static final String SERVICE_ID = "swarm-service-abc123";

    @Mock private ContainerControlApi containerControlApi;
    @Mock private ContainerService containerService;
    @Mock private DockerServerService dockerServerService;
    @Mock private NrgEventServiceI eventService;
    @Mock private XnatAppInfo xnatAppInfo;
    @Mock private KubernetesClientFactory kubernetesClientFactory;
    @Mock private JmsTemplate jmsTemplate;
    @Mock private UserI adminUser;

    private MockedStatic<XFTManager> mockedXftManager;
    private MockedStatic<XDATServlet> mockedXdatServlet;
    private MockedStatic<Users> mockedUsers;

    private ContainerStatusUpdater updater;
    private DockerServer swarmServer;
    private Container pollProjection;
    private Container fullService;

    @Before
    public void setUp() throws Exception {
        mockedXftManager = mockStatic(XFTManager.class);
        mockedXftManager.when(XFTManager::isInitialized).thenReturn(true);
        mockedXdatServlet = mockStatic(XDATServlet.class);
        mockedXdatServlet.when(XDATServlet::isDatabasePopulateOrUpdateCompleted).thenReturn(true);
        mockedUsers = mockStatic(Users.class);
        mockedUsers.when(Users::getAdminUser).thenReturn(adminUser);

        swarmServer = DockerServer.builder()
                .name("Test server")
                .host("unix:///var/run/docker.sock")
                .backend(Backend.SWARM)
                .build();
        when(xnatAppInfo.isPrimaryNode()).thenReturn(true);
        when(dockerServerService.getServer()).thenReturn(swarmServer);
        when(containerControlApi.canConnect()).thenReturn(true);

        // What retrieveServiceForPoll returns: scalars only, placeholders for the required strings
        pollProjection = Container.builder()
                .databaseId(DATABASE_ID)
                .commandId(0L)
                .wrapperId(0L)
                .userId("someuser")
                .backend(Backend.SWARM)
                .serviceId(SERVICE_ID)
                .status("running")
                .dockerImage("")
                .commandLine("")
                .build();
        fullService = pollProjection.toBuilder()
                .commandId(7L)
                .wrapperId(8L)
                .dockerImage("busybox:latest")
                .commandLine("echo hello")
                .build();

        when(containerService.retrieveNonfinalizedServiceIds()).thenReturn(Collections.singletonList(DATABASE_ID));
        when(containerService.retrieveServiceForPoll(DATABASE_ID)).thenReturn(pollProjection);
        when(containerService.get(DATABASE_ID)).thenReturn(fullService);

        updater = new ContainerStatusUpdater(containerControlApi, containerService, dockerServerService,
                eventService, xnatAppInfo, kubernetesClientFactory, jmsTemplate);
    }

    @After
    public void tearDown() {
        mockedUsers.closeOnDemand();
        mockedXdatServlet.closeOnDemand();
        mockedXftManager.closeOnDemand();
    }

    @Test
    public void taskEventCarriesTheFullServiceNotThePollProjection() throws Exception {
        final ServiceTask task = ServiceTask.builder()
                .serviceId(SERVICE_ID)
                .taskId("task-1")
                .nodeId("swarm-node-1")
                .status(TaskState.RUNNING.getValue())
                .swarmNodeError(false)
                .build();
        when(containerControlApi.getTaskForService(swarmServer, pollProjection)).thenReturn(task);

        updater.run();

        final ServiceTaskEvent event = capturedEvent();
        assertThat(event.service(), is(sameInstance(fullService)));
        assertThat(event.task(), is(sameInstance(task)));
    }

    @Test
    public void lostTaskEventCarriesTheFullServiceNotThePollProjection() throws Exception {
        when(containerControlApi.getTaskForService(swarmServer, pollProjection))
                .thenThrow(new TaskNotFoundException(new RuntimeException("no task")));

        updater.run();

        final ServiceTaskEvent event = capturedEvent();
        assertThat(event.service(), is(sameInstance(fullService)));
        assertThat(event.task().swarmNodeError(), is(true));
    }

    private ServiceTaskEvent capturedEvent() {
        final ArgumentCaptor<EventI> captor = ArgumentCaptor.forClass(EventI.class);
        verify(eventService).triggerEvent(captor.capture());
        assertThat(captor.getValue(), is(instanceOf(ServiceTaskEvent.class)));
        return (ServiceTaskEvent) captor.getValue();
    }
}
