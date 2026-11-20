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
import org.nrg.containers.events.model.DockerContainerEvent;
import org.nrg.containers.model.container.auto.Container;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Docker keeps only its most recent events, so a container's "die" can be gone before the status updater asks
 * for it. The updater then reports the exit itself, with the time Docker says the container finished. That
 * event must finalize a container whose "die" never arrived, and must not finalize one a second time when the
 * real "die" was recorded after all.
 */
@RunWith(MockitoJUnitRunner.class)
public class DockerMissedExitEventTest {
    private static final String CONTAINER_ID = "e15c8d624765288484a25fb8843ae9f4cf6314993c8c3eee068ef14640216f9d";
    private static final String USER_LOGIN = "someuser";

    // Docker stamps the "die" event a little after the finish time it reports for the container, so the event
    // the updater builds never equals the real one.
    private static final long FINISHED_AT_NANOS = 1795137025778357966L;
    private static final long DIE_EVENT_NANOS = FINISHED_AT_NANOS + 190_000_000L;

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
    public void exitReportedByTheUpdaterFinalizesAContainerWhoseDieEventWasLost() {
        final ContainerEntity containerEntity = startedContainer();
        stubContainerEntityService(containerEntity);

        containerService.processEvent(exitReportedByTheUpdater());

        assertThat(finalizeRequests.size(), is(1));
    }

    @Test
    public void exitReportedByTheUpdaterIsSkippedWhenTheDieEventWasRecorded() {
        final ContainerEntity containerEntity = startedContainer();
        stubContainerEntityService(containerEntity);

        containerService.processEvent(dieEvent());
        containerService.processEvent(exitReportedByTheUpdater());

        assertThat(finalizeRequests.size(), is(1));
        assertThat(exitsInHistory(containerEntity), is(1L));
    }

    @Test
    public void dieEventIsSkippedWhenTheUpdaterAlreadyReportedTheExit() {
        final ContainerEntity containerEntity = startedContainer();
        stubContainerEntityService(containerEntity);

        containerService.processEvent(exitReportedByTheUpdater());
        containerService.processEvent(dieEvent());

        assertThat(finalizeRequests.size(), is(1));
        assertThat(exitsInHistory(containerEntity), is(1L));
    }

    @Test
    public void exitReportedOnTwoTicksFinalizesOnce() {
        final ContainerEntity containerEntity = startedContainer();
        stubContainerEntityService(containerEntity);

        containerService.processEvent(exitReportedByTheUpdater());
        containerService.processEvent(exitReportedByTheUpdater());

        assertThat(finalizeRequests.size(), is(1));
    }

    private static DockerContainerEvent dieEvent() {
        return DockerContainerEvent.create(DockerContainerEvent.EXIT_STATUS, CONTAINER_ID,
                new Date(DIE_EVENT_NANOS / 1_000_000L), DIE_EVENT_NANOS, Collections.singletonMap("exitCode", "0"));
    }

    private static DockerContainerEvent exitReportedByTheUpdater() {
        return DockerContainerEvent.create(DockerContainerEvent.EXIT_STATUS, CONTAINER_ID,
                new Date(FINISHED_AT_NANOS / 1_000_000L), FINISHED_AT_NANOS, Collections.singletonMap("exitCode", "0"));
    }

    /**
     * A plain Docker container as it looks when its "die" has not been recorded: created and started.
     */
    private static ContainerEntity startedContainer() {
        final ContainerEntity containerEntity = ContainerEntity.fromPojo(Container.builder()
                .databaseId(42L)
                .commandId(0L)
                .wrapperId(0L)
                .userId(USER_LOGIN)
                .backend(Backend.DOCKER)
                .containerId(CONTAINER_ID)
                .dockerImage("busybox:latest")
                .commandLine("cp /etc/hostname /output/out.txt")
                .status("Running")
                .build());
        final DockerContainerEvent start = DockerContainerEvent.create("start", CONTAINER_ID,
                new Date((FINISHED_AT_NANOS - 280_000_000L) / 1_000_000L), FINISHED_AT_NANOS - 280_000_000L, null);
        containerEntity.addToHistory(ContainerEntityHistory.fromContainerEvent(start, containerEntity));
        return containerEntity;
    }

    private static long exitsInHistory(final ContainerEntity containerEntity) {
        return containerEntity.getHistory().stream()
                .filter(history -> DockerContainerEvent.EXIT_STATUS.equals(history.getStatus()))
                .count();
    }

    /**
     * Stands in for the persistence layer while keeping the real duplicate detection, as
     * {@code ContainerEventReplayTest} does, and records each new event the way
     * {@code HibernateContainerEntityService.addContainerEventToHistory} does.
     */
    private void stubContainerEntityService(final ContainerEntity containerEntity) {
        when(containerEntityService.retrieve(CONTAINER_ID)).thenReturn(containerEntity);
        when(containerEntityService.addContainerEventToHistory(any(ContainerEvent.class), any(UserI.class)))
                .thenAnswer(invocation -> {
                    final ContainerEvent event = (ContainerEvent) invocation.getArguments()[0];
                    final ContainerEntityHistory historyItem =
                            ContainerEntityHistory.fromContainerEvent(event, containerEntity);
                    if (containerEntity.isItemInHistory(historyItem)) {
                        return null;
                    }
                    containerEntity.addToHistory(historyItem);
                    containerEntity.setStatus(historyItem.getStatus());
                    return containerEntity;
                });
    }
}
