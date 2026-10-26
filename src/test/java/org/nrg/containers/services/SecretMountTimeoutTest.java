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
import org.nrg.containers.events.model.ContainerEvent;
import org.nrg.containers.events.model.KubernetesContainerState;
import org.nrg.containers.events.model.KubernetesStatusChangeEvent;
import org.nrg.containers.exceptions.ContainerBackendException;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.auto.Container.ContainerHistory;
import org.nrg.containers.model.container.entity.ContainerEntity;
import org.nrg.containers.model.container.entity.ContainerEntityHistory;
import org.nrg.containers.model.kubernetes.KubernetesPodPhase;
import org.nrg.containers.model.server.docker.Backend;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.secrets.FileResolvedSecret;
import org.nrg.containers.secrets.FileSecretDestination;
import org.nrg.containers.secrets.K8sSecretSource;
import org.nrg.containers.secrets.Secret;
import org.nrg.containers.services.impl.ContainerServiceImpl;
import org.nrg.containers.tasks.SecretMountWatchdog;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.security.helpers.Users;
import org.nrg.xdat.security.user.exceptions.UserNotFoundException;
import org.nrg.xdat.services.AliasTokenService;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.services.XnatAppInfo;
import org.nrg.xnat.services.archive.CatalogService;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolExecutorFactoryBean;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** FR-10: only secret volumes are timed; the timeout path writes history, queues finalize, then kills only once queued. */
@RunWith(MockitoJUnitRunner.class)
public class SecretMountTimeoutTest {
    private static final long DB_ID = 42L;
    private static final String JOB_NAME = "secret-probe-7fd2a";
    private static final String USER_LOGIN = "someuser";
    private static final Instant T0 = Instant.parse("2026-10-13T08:00:00Z");
    private static final String SECRET_MISSING = "MountVolume.SetUp failed for volume \"secret-redcap\" : secret \"xnat-proj01-redcap\" not found";

    private static final class MutableClock extends Clock {
        private Instant now = T0;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(final ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

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
    @Mock private DockerServerService dockerServerService;
    @Mock private UserI user;

    private final MutableClock clock = new MutableClock();
    private final List<String> finalizeRequests = new ArrayList<>();
    private ContainerEntity entity;
    private SecretMountWatchdog watchdog;
    private ContainerServiceImpl containerService;
    private MockedStatic<Users> mockedUsers;
    /** Set by the test to simulate markAsQueuedForFinalizing (status "_Pending") or a throttled queue (unchanged). */
    private boolean queueAccepts = true;

    @Before
    public void setUp() {
        mockedUsers = mockStatic(Users.class);
        mockedUsers.when(() -> Users.getUser(USER_LOGIN)).thenReturn(user);
        when(dockerServerService.retrieveServer()).thenReturn(
                DockerServer.builder().name("k8s").host("").backend(Backend.KUBERNETES).secretMountTimeoutSeconds(600).build());
        watchdog = new SecretMountWatchdog(dockerServerService, mock(TaskScheduler.class), clock);

        entity = pendingContainer();
        when(containerEntityService.retrieveByJobNameAndNamespace(JOB_NAME, "xnat-proj01")).thenReturn(entity);
        // lenient: fromPojo() reads the row back on every history write, not only in the timeout cases (MockitoJUnitRunner is strict)
        lenient().when(containerEntityService.retrieve(DB_ID)).thenReturn(entity);
        // History writes land on the entity like the real service, so later assertions can count them
        lenient().when(containerEntityService.addContainerHistoryItem(any(ContainerEntity.class), any(ContainerEntityHistory.class), any(UserI.class)))
                .thenAnswer(invocation -> {
                    final ContainerEntityHistory item = (ContainerEntityHistory) invocation.getArguments()[1];
                    entity.addToHistory(item);
                    entity.setStatus(item.getStatus());
                    return item;
                });
        when(containerEntityService.addContainerEventToHistory(any(ContainerEntity.class), any(ContainerEvent.class), any(UserI.class)))
                .thenAnswer(invocation -> {
                    final ContainerEvent event = (ContainerEvent) invocation.getArguments()[1];
                    final ContainerEntityHistory item = ContainerEntityHistory.fromContainerEvent(event, entity);
                    if (entity.isItemInHistory(item)) {
                        return null;
                    }
                    entity.addToHistory(item);
                    // Like the real service: the returned entity carries the new status
                    entity.setStatus(item.getStatus());
                    return entity;
                });

        containerService = new ContainerServiceImpl(containerControlApi, containerEntityService, commandResolutionService,
                commandService, aliasTokenService, siteConfigPreferences, containerFinalizeService, xnatAppInfo,
                catalogService, orchestrationService, new ObjectMapper(), executorFactoryBean, dockerServerService, watchdog) {
            @Override
            public void queueFinalize(final String exitCodeString, final boolean isSuccessfulStatus,
                                      final Container containerOrService, final UserI userI) {
                finalizeRequests.add(exitCodeString);
                if (queueAccepts) {
                    entity.setStatus("_" + KubernetesPodPhase.PENDING);
                }
            }
        };
        containerService.afterPropertiesSet();
    }

    @After
    public void tearDown() {
        mockedUsers.closeOnDemand();
    }

    private static ContainerEntity pendingContainer() {
        final FileResolvedSecret redcap = new FileResolvedSecret(new Secret(
                new K8sSecretSource("redcap", null, "xnat-proj01-redcap", null), new FileSecretDestination("/run/secrets/redcap")));
        return ContainerEntity.fromPojo(Container.builder()
                .databaseId(DB_ID).commandId(0L).wrapperId(0L).userId(USER_LOGIN)
                .backend(Backend.KUBERNETES).serviceId(JOB_NAME).taskId(JOB_NAME + "-x7k2p").namespace("xnat-proj01")
                .dockerImage("busybox:latest").commandLine("true")
                .secrets(Collections.singletonList(redcap))
                .status(KubernetesPodPhase.PENDING.toString())
                .build());
    }

    private static KubernetesStatusChangeEvent failedMount(final String message, final Instant first) {
        return KubernetesStatusChangeEvent.builder()
                .jobName(JOB_NAME).podName(JOB_NAME + "-x7k2p").namespace("xnat-proj01")
                .podPhase(KubernetesPodPhase.PENDING).podPhaseReason(message).warningReason("FailedMount")
                .firstTimestamp(OffsetDateTime.ofInstant(first, ZoneOffset.UTC))
                .timestamp(OffsetDateTime.ofInstant(first.plusSeconds(30), ZoneOffset.UTC))
                .build();
    }

    @Test
    public void failedMountOnASecretVolumeIsRecordedTranslatedAndRecordedAgainWhenReplayed() {
        containerService.processEvent(failedMount(SECRET_MISSING, T0));
        assertThat(watchdog.isTracking(DB_ID), is(true));
        assertThat(entity.getHistory().size(), is(1));
        final String historyMessage = entity.getHistory().get(0).getMessage();
        assertThat(historyMessage, containsString("Secret \"xnat-proj01-redcap\" does not exist in namespace \"xnat-proj01\""));
        assertThat("the raw message is kept in the same record", historyMessage, containsString(SECRET_MISSING));

        watchdog.clear(DB_ID);
        containerService.processEvent(failedMount(SECRET_MISSING, T0));   // informer replay: same event, already in history
        assertThat("eventAlreadyRecorded still registers the start time", watchdog.isTracking(DB_ID), is(true));
        assertThat(entity.getHistory().size(), is(1));
    }

    @Test
    public void failedMountOnAnotherVolumeIsHistoryOnly() {
        containerService.processEvent(failedMount("MountVolume.SetUp failed for volume \"xnat-data\" : rpc error: timed out waiting for the condition", T0));
        assertThat(watchdog.isTracking(DB_ID), is(false));
        assertThat(entity.getHistory().size(), is(1));
        assertThat(entity.getHistory().get(0).getMessage(), containsString("FailedMount: MountVolume.SetUp failed for volume \"xnat-data\""));
    }

    @Test
    public void runningClearsTheEntry() {
        containerService.processEvent(failedMount(SECRET_MISSING, T0));
        containerService.processEvent(KubernetesStatusChangeEvent.builder()
                .jobName(JOB_NAME).podName(JOB_NAME + "-x7k2p").namespace("xnat-proj01")
                .podPhase(KubernetesPodPhase.RUNNING).containerState(KubernetesContainerState.RUNNING)
                .timestamp(OffsetDateTime.ofInstant(T0.plusSeconds(90), ZoneOffset.UTC)).build());
        assertThat(watchdog.isTracking(DB_ID), is(false));
    }

    @Test
    public void timeoutWritesHistoryQueuesFinalizeThenKillsOnceQueued() throws Exception {
        containerService.processEvent(failedMount(SECRET_MISSING, T0));
        clock.now = T0.plus(Duration.ofSeconds(601));

        watchdog.tick();

        final ArgumentCaptor<ContainerEntityHistory> history = ArgumentCaptor.forClass(ContainerEntityHistory.class);
        verify(containerEntityService).addContainerHistoryItem(any(ContainerEntity.class), history.capture(), any(UserI.class));
        assertThat("status stays non-terminal; the terminal entry is finalize's job", history.getValue().getStatus(), is("Pending"));
        assertThat(history.getValue().getMessage(), containsString("Secret mount timed out after 600 s: Secret \"xnat-proj01-redcap\" does not exist"));
        assertThat(finalizeRequests, is(Collections.singletonList("1")));
        verify(containerControlApi).kill(any(Container.class));
        assertThat(watchdog.isTracking(DB_ID), is(false));
    }

    @Test
    public void throttledFinalizeLeavesTheJobAloneUntilTheNextTick() throws Exception {
        queueAccepts = false;
        containerService.processEvent(failedMount(SECRET_MISSING, T0));
        clock.now = T0.plus(Duration.ofSeconds(601));

        watchdog.tick();
        verify(containerControlApi, never()).kill(any(Container.class));
        assertThat(watchdog.isTracking(DB_ID), is(true));

        queueAccepts = true;
        watchdog.tick();
        verify(containerControlApi).kill(any(Container.class));
        verify(containerEntityService, times(1)).addContainerHistoryItem(any(ContainerEntity.class),
                argThat(h -> h.getMessage() != null && h.getMessage().startsWith("Secret mount timed out")), any(UserI.class));
    }

    private static KubernetesStatusChangeEvent podDeletedEvent() {
        return KubernetesStatusChangeEvent.builder()
                .jobName(JOB_NAME).podName(JOB_NAME + "-x7k2p").namespace("xnat-proj01")
                .podPhase(KubernetesPodPhase.FAILED).exitCode(1)
                .podPhaseReason("Pod was deleted unexpectedly. Last known pod phase: Pending")
                .timestamp(OffsetDateTime.ofInstant(T0.plusSeconds(700), ZoneOffset.UTC))
                .build();
    }

    /**
     * Deleting the job makes the informer emit FAILED "Pod was deleted unexpectedly". After the watchdog queued
     * finalize that event must not add a Failed history entry or queue a second finalize (second mail).
     * The status is "_Pending", which isFinalizing() does not recognise, so processEvent needs its own guard.
     */
    @Test
    public void podDeletedAfterTimeoutIsNotFinalizedAgainWhileQueued() throws Exception {
        containerService.processEvent(failedMount(SECRET_MISSING, T0));
        clock.now = T0.plus(Duration.ofSeconds(601));
        watchdog.tick();
        final int historyBefore = entity.getHistory().size();

        containerService.processEvent(podDeletedEvent());

        assertThat(entity.getHistory().size(), is(historyBefore));
        assertThat(finalizeRequests, is(Collections.singletonList("1")));
    }

    /** Same guard when finalize consumed the queue before the informer delivered onDelete and left the container Failed. */
    @Test
    public void podDeletedAfterTimeoutIsNotFinalizedAgainWhenAlreadyFailed() throws Exception {
        containerService.processEvent(failedMount(SECRET_MISSING, T0));
        clock.now = T0.plus(Duration.ofSeconds(601));
        watchdog.tick();
        entity.setStatus("Failed");
        final int historyBefore = entity.getHistory().size();

        containerService.processEvent(podDeletedEvent());

        assertThat(entity.getHistory().size(), is(historyBefore));
        assertThat(finalizeRequests, is(Collections.singletonList("1")));
    }

    /**
     * The watchdog wrote its entry but finalize was throttled; then the user killed the container. kill() records a
     * user "kill" history item, which the real entity maps to the terminal status "Failed (Killed)" (swarm is false
     * for kubernetes rows). That kill is finalized only through the onDelete FAILED event, so the guard must let it through.
     */
    @Test
    public void userKillDuringThrottledFinalizeIsStillFinalized() throws Exception {
        queueAccepts = false;
        containerService.processEvent(failedMount(SECRET_MISSING, T0));
        clock.now = T0.plus(Duration.ofSeconds(601));
        watchdog.tick();
        entity.setSwarm(false);
        containerService.addContainerHistoryItem(containerService.retrieve(DB_ID),
                ContainerHistory.fromUserAction(ContainerEntity.KILL_STATUS, USER_LOGIN), user);
        assertThat(entity.getStatus(), is("Failed (Killed)"));
        final int historyBefore = entity.getHistory().size();

        containerService.processEvent(podDeletedEvent());

        assertThat(finalizeRequests, is(Arrays.asList("1", "1")));
        assertThat(entity.getHistory().size(), is(historyBefore + 1));
        assertThat(entity.getHistory().get(historyBefore).getStatus(), is("Failed"));
    }

    /** A terminal status alone is not a user kill: without a user "kill" history item the watchdog's own delete is still swallowed. */
    @Test
    public void podDeletedAfterTimeoutIsNotFinalizedAgainWhenTerminalWithoutUserKill() throws Exception {
        containerService.processEvent(failedMount(SECRET_MISSING, T0));
        clock.now = T0.plus(Duration.ofSeconds(601));
        watchdog.tick();
        entity.setSwarm(false);
        entity.setStatus(ContainerEntity.KILL_STATUS);
        assertThat(entity.getStatus(), is("Failed (Killed)"));
        final int historyBefore = entity.getHistory().size();

        containerService.processEvent(podDeletedEvent());

        assertThat(entity.getHistory().size(), is(historyBefore));
        assertThat(finalizeRequests, is(Collections.singletonList("1")));
    }

    private static KubernetesStatusChangeEvent podFailedContainerStatusUnknownEvent() {
        return KubernetesStatusChangeEvent.builder()
                .jobName(JOB_NAME).podName(JOB_NAME + "-x7k2p").namespace("xnat-proj01")
                .podPhase(KubernetesPodPhase.FAILED).exitCode(137)
                .podPhaseReason("ContainerStatusUnknown")
                .timestamp(OffsetDateTime.ofInstant(T0.plusSeconds(710), ZoneOffset.UTC))
                .build();
    }

    /**
     * After deleteJobAndClear the pod informer also delivers a genuine FAILED / ContainerStatusUnknown / 137 event,
     * not only the synthetic onDelete one. Once the watchdog's finalize has run it is the single source of truth,
     * so this event (and any repeated FailedMount warning) must add no history and queue no second finalize.
     */
    @Test
    public void podFailedEventAfterTimeoutIsIgnoredWhenAlreadyFailed() throws Exception {
        containerService.processEvent(failedMount(SECRET_MISSING, T0));
        clock.now = T0.plus(Duration.ofSeconds(601));
        watchdog.tick();
        entity.setStatus("Failed");
        final int historyBefore = entity.getHistory().size();

        containerService.processEvent(podFailedContainerStatusUnknownEvent());
        assertThat(entity.getHistory().size(), is(historyBefore));
        assertThat(finalizeRequests, is(Collections.singletonList("1")));

        containerService.processEvent(failedMount(SECRET_MISSING, T0.plusSeconds(60)));
        assertThat("a repeated FailedMount warning is noise too", entity.getHistory().size(), is(historyBefore));
    }

    /** Same event delivered while the watchdog's finalize is still queued ("_Pending"). */
    @Test
    public void podFailedEventAfterTimeoutIsIgnoredWhileQueued() throws Exception {
        containerService.processEvent(failedMount(SECRET_MISSING, T0));
        clock.now = T0.plus(Duration.ofSeconds(601));
        watchdog.tick();
        final int historyBefore = entity.getHistory().size();

        containerService.processEvent(podFailedContainerStatusUnknownEvent());

        assertThat(entity.getHistory().size(), is(historyBefore));
        assertThat(finalizeRequests, is(Collections.singletonList("1")));
    }

    /** Registers the failed mount and moves the clock past the timeout; the next tick hands the entry to the service. */
    private void timedOutEntry() {
        containerService.processEvent(failedMount(SECRET_MISSING, T0));
        clock.now = T0.plus(Duration.ofSeconds(601));
    }

    private void assertNothingWritten() {
        verify(containerEntityService, never()).addContainerHistoryItem(any(ContainerEntity.class), any(ContainerEntityHistory.class), any(UserI.class));
        assertThat(finalizeRequests.isEmpty(), is(true));
    }

    @Test
    public void containerGoneClearsTheEntry() {
        timedOutEntry();
        when(containerEntityService.retrieve(DB_ID)).thenReturn(null);
        watchdog.tick();
        assertThat(watchdog.isTracking(DB_ID), is(false));
        assertNothingWritten();
    }

    @Test
    public void terminalContainerIsClearedWithoutHistoryOrFinalize() {
        timedOutEntry();
        entity.setStatus("Failed (Exited)");
        watchdog.tick();
        assertThat(watchdog.isTracking(DB_ID), is(false));
        assertNothingWritten();
    }

    @Test
    public void runningContainerIsClearedWithoutHistoryOrFinalize() {
        timedOutEntry();
        entity.setStatus(KubernetesPodPhase.RUNNING.toString());
        watchdog.tick();
        assertThat(watchdog.isTracking(DB_ID), is(false));
        assertNothingWritten();
    }

    @Test
    public void containerQueuedOnAnEarlierTickOnlyRetriesTheDelete() throws Exception {
        timedOutEntry();
        entity.setStatus("_" + KubernetesPodPhase.PENDING);
        watchdog.tick();
        verify(containerControlApi).kill(any(Container.class));
        assertThat(watchdog.isTracking(DB_ID), is(false));
        assertNothingWritten();
    }

    @Test
    public void failedDeleteKeepsTheEntryForTheNextTick() throws Exception {
        timedOutEntry();
        doThrow(new ContainerBackendException("api down")).when(containerControlApi).kill(any(Container.class));
        watchdog.tick();
        assertThat(watchdog.isTracking(DB_ID), is(true));
    }

    @Test
    public void jobAlreadyGoneClearsTheEntry() throws Exception {
        timedOutEntry();
        doThrow(new NotFoundException("no such job")).when(containerControlApi).kill(any(Container.class));
        watchdog.tick();
        assertThat(watchdog.isTracking(DB_ID), is(false));
    }

    @Test
    public void unknownUserKeepsTheEntryAndWritesNothing() {
        timedOutEntry();
        mockedUsers.when(() -> Users.getUser(USER_LOGIN)).thenThrow(UserNotFoundException.class);
        watchdog.tick();
        assertThat(watchdog.isTracking(DB_ID), is(true));
        assertNothingWritten();
    }

    @Test
    public void deleteThatKeepsFailingIsGivenUpAfterTheRetryCap() throws Exception {
        timedOutEntry();
        doThrow(new ContainerBackendException("api down")).when(containerControlApi).kill(any(Container.class));
        for (int i = 1; i < 10; i++) {
            watchdog.tick();
        }
        assertThat(watchdog.isTracking(DB_ID), is(true));
        watchdog.tick();
        assertThat("the Job needs manual cleanup; the entry is not retried forever", watchdog.isTracking(DB_ID), is(false));
        verify(containerControlApi, times(10)).kill(any(Container.class));
    }
}
