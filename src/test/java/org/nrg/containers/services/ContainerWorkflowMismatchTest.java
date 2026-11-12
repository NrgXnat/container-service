package org.nrg.containers.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;
import org.nrg.containers.api.ContainerControlApi;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.entity.ContainerEntity;
import org.nrg.containers.model.container.entity.ContainerEntityHistory;
import org.nrg.containers.model.server.docker.Backend;
import org.nrg.containers.services.impl.ContainerServiceImpl;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.services.AliasTokenService;
import org.nrg.xft.event.persist.PersistentWorkflowI;
import org.nrg.xft.event.persist.PersistentWorkflowUtils;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.services.XnatAppInfo;
import org.nrg.xnat.services.archive.CatalogService;
import org.nrg.xnat.utils.WorkflowUtils;
import org.springframework.scheduling.concurrent.ThreadPoolExecutorFactoryBean;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code ContainerStatusUpdater} calls {@link ContainerService#fixWorkflowContainerStatusMismatch} with the partial
 * poll projection. When the workflow has gone terminal the method records a history item, which saves the container
 * it is given over the whole row, so it must reload the full container first rather than write the projection, and
 * re-check the reloaded row, which may have been deleted or finished since the poll.
 */
@RunWith(MockitoJUnitRunner.class)
public class ContainerWorkflowMismatchTest {
    private static final long DATABASE_ID = 42L;
    private static final String WORKFLOW_ID = "1234";

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
    private MockedStatic<WorkflowUtils> mockedWorkflowUtils;

    @Before
    public void setUp() {
        final PersistentWorkflowI workflow = mock(PersistentWorkflowI.class);
        when(workflow.getStatus()).thenReturn(PersistentWorkflowUtils.FAILED);
        mockedWorkflowUtils = mockStatic(WorkflowUtils.class);
        mockedWorkflowUtils.when(() -> WorkflowUtils.getUniqueWorkflow(user, WORKFLOW_ID)).thenReturn(workflow);

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
        mockedWorkflowUtils.closeOnDemand();
    }

    @Test
    public void mismatchFixDoesNotSaveThePollProjectionOverTheRow() throws Exception {
        final ContainerEntity row = ContainerEntity.fromPojo(fullService("running"));
        row.setId(DATABASE_ID);
        when(containerEntityService.retrieve(DATABASE_ID)).thenReturn(row);

        assertThat(containerService.fixWorkflowContainerStatusMismatch(pollProjection(), user), is(true));

        final ArgumentCaptor<ContainerEntity> saved = ArgumentCaptor.forClass(ContainerEntity.class);
        verify(containerEntityService).addContainerHistoryItem(saved.capture(), any(ContainerEntityHistory.class), eq(user));
        assertThat(saved.getValue().getDockerImage(), is("busybox:latest"));
        assertThat(saved.getValue().getCommandLine(), is("echo hello"));
        assertThat(saved.getValue().getNodeId(), is("swarm-node-1"));
        assertThat(saved.getValue().getMounts().size(), is(1));
        verify(containerControlApi).kill(any(Container.class));
    }

    @Test
    public void mismatchFixSkipsARowDeletedSinceThePoll() throws Exception {
        when(containerEntityService.retrieve(DATABASE_ID)).thenReturn(null);

        assertThat(containerService.fixWorkflowContainerStatusMismatch(pollProjection(), user), is(false));

        verify(containerEntityService, never()).addContainerHistoryItem(any(ContainerEntity.class), any(ContainerEntityHistory.class), any(UserI.class));
        verify(containerControlApi, never()).kill(any(Container.class));
    }

    @Test
    public void mismatchFixSkipsARowThatWentTerminalSinceThePoll() throws Exception {
        final ContainerEntity row = ContainerEntity.fromPojo(fullService(PersistentWorkflowUtils.COMPLETE));
        row.setId(DATABASE_ID);
        when(containerEntityService.retrieve(DATABASE_ID)).thenReturn(row);

        assertThat(containerService.fixWorkflowContainerStatusMismatch(pollProjection(), user), is(false));

        verify(containerEntityService, never()).addContainerHistoryItem(any(ContainerEntity.class), any(ContainerEntityHistory.class), any(UserI.class));
        verify(containerControlApi, never()).kill(any(Container.class));
    }

    private static Container fullService(final String status) {
        return Container.builder()
                .databaseId(DATABASE_ID)
                .commandId(7L)
                .wrapperId(8L)
                .userId("someuser")
                .backend(Backend.SWARM)
                .serviceId("swarm-service-abc123")
                .nodeId("swarm-node-1")
                .workflowId(WORKFLOW_ID)
                .status(status)
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
                .build();
    }

    /**
     * What ContainerStatusUpdater passes in: scalars only, placeholders for the required strings
     */
    private static Container pollProjection() {
        return Container.builder()
                .databaseId(DATABASE_ID)
                .commandId(0L)
                .wrapperId(0L)
                .userId("someuser")
                .backend(Backend.SWARM)
                .serviceId("swarm-service-abc123")
                .workflowId(WORKFLOW_ID)
                .status("running")
                .dockerImage("")
                .commandLine("")
                .build();
    }
}
