package org.nrg.containers.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;
import org.nrg.containers.api.ContainerControlApi;
import org.nrg.containers.events.model.ContainerEvent;
import org.nrg.containers.events.model.KubernetesContainerState;
import org.nrg.containers.events.model.KubernetesStatusChangeEvent;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.entity.ContainerEntity;
import org.nrg.containers.model.container.entity.ContainerEntityHistory;
import org.nrg.containers.model.kubernetes.KubernetesPodPhase;
import org.nrg.containers.model.server.docker.Backend;
import org.nrg.containers.services.impl.ContainerServiceImpl;
import org.nrg.containers.utils.ContainerUtils;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.security.helpers.Users;
import org.nrg.xdat.services.AliasTokenService;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.services.XnatAppInfo;
import org.nrg.xnat.services.archive.CatalogService;
import org.springframework.scheduling.concurrent.ThreadPoolExecutorFactoryBean;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The kubernetes informer replays every pod it sees on its initial LIST, so an XNAT restart re-delivers
 * the terminal event of every finished pod still in the namespace (which is all of them when the docker
 * server is configured with auto-cleanup off). Those replayed events must not finalize a container a
 * second time, but a terminal event we have never seen must still finalize — that replay is how we
 * recover a container that finished while XNAT was down.
 */
@RunWith(MockitoJUnitRunner.class)
public class ContainerEventReplayTest {
    private static final String JOB_NAME = "container-service-job-abc123";
    private static final String POD_NAME = JOB_NAME + "-x7k2p";
    private static final String CONTAINER_ID = "docker://f00ba7";
    private static final String NODE_ID = "kube-node-01";
    private static final String USER_LOGIN = "someuser";

    // Kubernetes reports terminated.finishedAt, so a replayed pod carries the same timestamp it did the
    // first time we saw it. That is what makes the replayed event recognizable as one we have recorded.
    private static final OffsetDateTime FINISHED_AT = OffsetDateTime.parse("2026-08-18T14:03:11Z");
    private static final OffsetDateTime STARTED_AT = OffsetDateTime.parse("2026-08-18T14:01:02Z");

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

    private final List<Container> finalizeRequests = new ArrayList<>();

    private ContainerService containerService;
    private MockedStatic<Users> mockedUsers;

    @Before
    public void setUp() {
        mockedUsers = mockStatic(Users.class);
        mockedUsers.when(() -> Users.getUser(USER_LOGIN)).thenReturn(user);

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
                executorFactoryBean) {
            @Override
            public void queueFinalize(final String exitCodeString, final boolean isSuccessfulStatus,
                                      final Container containerOrService, final UserI userI) {
                finalizeRequests.add(containerOrService);
            }
        };
    }

    @After
    public void tearDown() {
        mockedUsers.closeOnDemand();
    }

    @Test
    public void terminalEventIsNotFinalizedAgainWhenItIsReplayed() {
        // A container we have already seen succeed, finalize, and complete
        final ContainerEntity containerEntity = trackedContainer(ContainerUtils.TerminalState.COMPLETE.value);
        recordEvent(containerEntity, succeededEvent());
        stubContainerEntityService(containerEntity);

        // The informer's initial LIST re-delivers the same pod as a distinct but equal event object
        containerService.processEvent(succeededEvent());

        assertThat(finalizeRequests.size(), is(0));
    }

    @Test
    public void terminalEventIsFinalizedWhenTheContainerFinishedWhileXnatWasDown() {
        // A container that was still running the last time we heard about it
        final ContainerEntity containerEntity = trackedContainer(KubernetesPodPhase.RUNNING.toString());
        recordEvent(containerEntity, runningEvent());
        stubContainerEntityService(containerEntity);

        // Its pod terminated while we were down; the initial LIST is the first we hear of it
        containerService.processEvent(succeededEvent());

        assertThat(finalizeRequests.size(), is(1));
    }

    /**
     * A kubernetes container as we track it: {@link Container#jobName()} reads serviceId and
     * {@link Container#podName()} reads taskId. Every backend id is already set, so a replayed event
     * has nothing new to tell us about the container other than its status.
     */
    private ContainerEntity trackedContainer(final String status) {
        return ContainerEntity.fromPojo(Container.builder()
                .databaseId(42L)
                .commandId(0L)
                .wrapperId(0L)
                .userId(USER_LOGIN)
                .backend(Backend.KUBERNETES)
                .serviceId(JOB_NAME)
                .taskId(POD_NAME)
                .containerId(CONTAINER_ID)
                .nodeId(NODE_ID)
                .dockerImage("busybox:latest")
                .commandLine("echo hello")
                .status(status)
                .build());
    }

    private static void recordEvent(final ContainerEntity containerEntity, final ContainerEvent event) {
        containerEntity.addToHistory(ContainerEntityHistory.fromContainerEvent(event, containerEntity));
    }

    /**
     * Stands in for the persistence layer while keeping the real duplicate detection:
     * {@link ContainerEntity#isItemInHistory} and {@link ContainerEntityHistory#equals} decide whether an
     * event is new, and a null return means "we have already recorded this event", exactly as
     * {@code HibernateContainerEntityService.addContainerEventToHistory} does.
     */
    private void stubContainerEntityService(final ContainerEntity containerEntity) {
        when(containerEntityService.retrieve(JOB_NAME)).thenReturn(containerEntity);
        when(containerEntityService.addContainerEventToHistory(any(ContainerEvent.class), any(UserI.class)))
                .thenAnswer(invocation -> {
                    final ContainerEvent event = (ContainerEvent) invocation.getArguments()[0];
                    final ContainerEntityHistory historyItem =
                            ContainerEntityHistory.fromContainerEvent(event, containerEntity);
                    if (containerEntity.isItemInHistory(historyItem)) {
                        return null;
                    }
                    containerEntity.addToHistory(historyItem);
                    return containerEntity;
                });
    }

    private static KubernetesStatusChangeEvent runningEvent() {
        return KubernetesStatusChangeEvent.builder()
                .jobName(JOB_NAME)
                .podName(POD_NAME)
                .containerId(CONTAINER_ID)
                .podPhase(KubernetesPodPhase.RUNNING)
                .containerState(KubernetesContainerState.RUNNING)
                .timestamp(STARTED_AT)
                .nodeId(NODE_ID)
                .build();
    }

    private static KubernetesStatusChangeEvent succeededEvent() {
        return KubernetesStatusChangeEvent.builder()
                .jobName(JOB_NAME)
                .podName(POD_NAME)
                .containerId(CONTAINER_ID)
                .podPhase(KubernetesPodPhase.SUCCEEDED)
                .containerState(KubernetesContainerState.TERMINATED)
                .containerStateReason("Completed")
                .exitCode(0)
                .timestamp(FINISHED_AT)
                .nodeId(NODE_ID)
                .build();
    }
}
