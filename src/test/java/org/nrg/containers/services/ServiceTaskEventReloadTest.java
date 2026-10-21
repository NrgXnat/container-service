package org.nrg.containers.services;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.nrg.containers.events.model.ServiceTaskEvent;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.auto.ServiceTask;
import org.nrg.containers.model.container.entity.ContainerEntity;
import org.nrg.containers.model.container.entity.ContainerEntityHistory;
import org.nrg.containers.model.server.docker.Backend;
import org.nrg.containers.services.impl.ContainerServiceImpl;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.security.helpers.Users;
import org.nrg.xdat.services.AliasTokenService;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.services.XnatAppInfo;
import org.nrg.xnat.services.archive.CatalogService;
import org.springframework.scheduling.concurrent.ThreadPoolExecutorFactoryBean;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code ContainerStatusUpdater} sends service task events carrying the partial poll projection, and any copy of the
 * service in an event goes stale on its way through the event bus and JMS. {@code processEvent} saves the service,
 * so it must reload the full row rather than save the event's copy over it.
 */
@RunWith(MockitoJUnitRunner.class)
public class ServiceTaskEventReloadTest {
    private static final long DATABASE_ID = 42L;
    private static final String SERVICE_ID = "swarm-service-abc123";
    private static final String USER_LOGIN = "someuser";

    @Mock private ContainerControlApi containerControlApi;
    @Mock private ContainerEntityService containerEntityService;
    @Mock private CommandResolutionService commandResolutionService;
    @Mock private CommandService commandService;
    @Mock private AliasTokenService aliasTokenService;
    @Mock private SiteConfigPreferences siteConfigPreferences;
    @Mock private ContainerFinalizeService containerFinalizeService;
    @Mock private XnatAppInfo xnatAppInfo;
    @Mock private CatalogService catalogService;
    @Mock private OrchestrationService orchestrationService;
    @Mock private ThreadPoolExecutorFactoryBean executorFactoryBean;
    @Mock private UserI user;

    private ContainerService containerService;
    private MockedStatic<Users> mockedUsers;

    @Before
    public void setUp() {
        mockedUsers = mockStatic(Users.class);

        containerService = new ContainerServiceImpl(containerControlApi,
                containerEntityService,
                commandResolutionService,
                commandService,
                aliasTokenService,
                siteConfigPreferences,
                containerFinalizeService,
                xnatAppInfo,
                catalogService,
                orchestrationService,
                new ObjectMapper(),
                executorFactoryBean);
    }

    @After
    public void tearDown() {
        mockedUsers.closeOnDemand();
    }

    @Test
    public void processEventSavesTheReloadedRowNotTheEventsCopy() {
        final ContainerEntity row = ContainerEntity.fromPojo(Container.builder()
                .databaseId(DATABASE_ID)
                .commandId(7L)
                .wrapperId(8L)
                .userId(USER_LOGIN)
                .backend(Backend.SWARM)
                .serviceId(SERVICE_ID)
                .taskId("task-1")
                .nodeId("swarm-node-1")
                .containerId("container-1")
                .status("running")
                .dockerImage("busybox:latest")
                .commandLine("echo hello")
                .addMount(Container.ContainerMount.builder()
                        .databaseId(0L)
                        .name("output")
                        .writable(true)
                        .xnatHostPath("/data/build/0b8e1f2c")
                        .containerHostPath("/data/build/0b8e1f2c")
                        .containerPath("/output")
                        .build())
                .build());
        row.setId(DATABASE_ID);
        when(containerEntityService.retrieve(DATABASE_ID)).thenReturn(row);
        mockedUsers.when(() -> Users.getUser(USER_LOGIN)).thenReturn(user);

        containerService.processEvent(ServiceTaskEvent.create(runningTask(), pollProjection()));

        final ArgumentCaptor<ContainerEntity> saved = ArgumentCaptor.forClass(ContainerEntity.class);
        verify(containerEntityService).addContainerHistoryItem(saved.capture(), any(ContainerEntityHistory.class), eq(user));
        assertThat(saved.getValue().getDockerImage(), is("busybox:latest"));
        assertThat(saved.getValue().getCommandLine(), is("echo hello"));
        assertThat(saved.getValue().getNodeId(), is("swarm-node-1"));
        assertThat(saved.getValue().getMounts().size(), is(1));
    }

    @Test
    public void processEventSkipsARowDeletedSinceTheEventWasSent() {
        when(containerEntityService.retrieve(DATABASE_ID)).thenReturn(null);

        containerService.processEvent(ServiceTaskEvent.create(runningTask(), pollProjection()));

        verify(containerEntityService, never()).update(any(ContainerEntity.class));
        verify(containerEntityService, never()).addContainerHistoryItem(any(ContainerEntity.class), any(ContainerEntityHistory.class), any(UserI.class));
    }

    @Test
    public void processEventSkipsAnEventForAServiceThatHasSinceBeenRestarted() throws Exception {
        // The restart replaced the service and blanked its IDs until the poll sees the new service's task
        final ContainerEntity row = ContainerEntity.fromPojo(Container.builder()
                .databaseId(DATABASE_ID)
                .commandId(7L)
                .wrapperId(8L)
                .userId(USER_LOGIN)
                .backend(Backend.SWARM)
                .serviceId("swarm-service-restarted")
                .status(Container.ContainerHistory.restartStatus)
                .dockerImage("busybox:latest")
                .commandLine("echo hello")
                .build());
        row.setId(DATABASE_ID);
        when(containerEntityService.retrieve(DATABASE_ID)).thenReturn(row);

        // Queued before the restart, so it still describes the old service and its lost task
        final ServiceTask lostTask = runningTask().toBuilder()
                .status(TaskState.FAILED.getValue())
                .swarmNodeError(true)
                .build();
        containerService.processEvent(ServiceTaskEvent.create(lostTask, pollProjection()));

        verify(containerEntityService, never()).update(any(ContainerEntity.class));
        verify(containerEntityService, never()).addContainerHistoryItem(any(ContainerEntity.class), any(ContainerEntityHistory.class), any(UserI.class));
        verify(containerControlApi, never()).remove(any(Container.class));
    }

    private static ServiceTask runningTask() {
        return ServiceTask.builder()
                .serviceId(SERVICE_ID)
                .taskId("task-1")
                .nodeId("swarm-node-1")
                .containerId("container-1")
                .status(TaskState.RUNNING.getValue())
                .swarmNodeError(false)
                .build();
    }

    /**
     * What ContainerStatusUpdater sends: scalars only, placeholders for the required strings
     */
    private static Container pollProjection() {
        return Container.builder()
                .databaseId(DATABASE_ID)
                .commandId(0L)
                .wrapperId(0L)
                .userId(USER_LOGIN)
                .backend(Backend.SWARM)
                .serviceId(SERVICE_ID)
                .taskId("task-1")
                .containerId("container-1")
                .status("running")
                .dockerImage("")
                .commandLine("")
                .build();
    }
}
