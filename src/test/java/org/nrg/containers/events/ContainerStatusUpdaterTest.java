package org.nrg.containers.events;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.nrg.containers.api.ContainerControlApi;
import org.nrg.containers.api.KubernetesClient;
import org.nrg.containers.api.KubernetesClientFactory;
import org.nrg.containers.events.model.ServiceTaskEvent;
import org.nrg.containers.exceptions.NoContainerServerException;
import org.nrg.containers.exceptions.TaskNotFoundException;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.auto.ServiceTask;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.model.server.docker.Backend;
import org.nrg.containers.services.ContainerService;
import org.nrg.containers.services.DockerServerService;
import org.nrg.framework.node.NodeLeader;
import org.nrg.framework.node.NodeLeaderListener;
import org.nrg.framework.node.NodeLockService;
import org.nrg.framework.services.NrgEventServiceI;
import org.nrg.framework.event.EventI;
import org.nrg.xdat.security.helpers.Users;
import org.nrg.xdat.servlet.XDATServlet;
import org.nrg.xft.schema.XFTManager;
import org.nrg.xft.security.UserI;
import org.springframework.jms.core.JmsTemplate;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ContainerStatusUpdater} against a mocked lock service: leadership gates the tick, demotion stops the
 * informer, and a leader that can't reach the backend steps down.
 *
 * <p>Also the Swarm polling loop (CS-1054): it reads each service through a cheap partial projection
 * ({@link ContainerService#retrieveServiceForPoll}). Task and lost-task events carry that projection without a
 * full load on the polling thread, because {@code ContainerServiceImpl.processEvent} reloads the full row before
 * saving (see {@code ServiceTaskEventReloadTest}).
 */
class ContainerStatusUpdaterTest {
    private static final long   DATABASE_ID = 42L;
    private static final String SERVICE_ID  = "swarm-service-abc123";

    private ContainerControlApi containerControlApi;
    private ContainerService containerService;
    private NrgEventServiceI eventService;
    private DockerServerService dockerServerService;
    private DockerServer server;
    private KubernetesClientFactory kubernetesClientFactory;
    private KubernetesClient kubernetesClient;
    private NodeLeader leader;
    private NodeLeaderListener listener;
    private ContainerStatusUpdater updater;

    private MockedStatic<XFTManager> xftManager;
    private MockedStatic<XDATServlet> xdatServlet;
    private MockedStatic<Users> users;

    @BeforeEach
    void setUp() throws Exception {
        xftManager = mockStatic(XFTManager.class);
        xftManager.when(XFTManager::isInitialized).thenReturn(true);
        xdatServlet = mockStatic(XDATServlet.class);
        xdatServlet.when(XDATServlet::isDatabasePopulateOrUpdateCompleted).thenReturn(true);
        users = mockStatic(Users.class);
        users.when(Users::getAdminUser).thenReturn(mock(UserI.class));

        containerControlApi = mock(ContainerControlApi.class);
        when(containerControlApi.canConnect()).thenReturn(true);
        containerService = mock(ContainerService.class);
        eventService = mock(NrgEventServiceI.class);

        server = mock(DockerServer.class);
        when(server.backend()).thenReturn(Backend.KUBERNETES);
        when(server.name()).thenReturn("kube");
        dockerServerService = mock(DockerServerService.class);
        when(dockerServerService.getServer()).thenReturn(server);

        kubernetesClient = mock(KubernetesClient.class);
        kubernetesClientFactory = mock(KubernetesClientFactory.class);
        when(kubernetesClientFactory.getKubernetesClient()).thenReturn(kubernetesClient);

        leader = mock(NodeLeader.class);
        when(leader.getName()).thenReturn(ContainerStatusUpdater.LEADER_LOCK);
        when(leader.isLeader()).thenReturn(true);

        final NodeLockService nodeLockService = mock(NodeLockService.class);
        when(nodeLockService.registerLeader(eq(ContainerStatusUpdater.LEADER_LOCK), any())).thenReturn(leader);

        updater = new ContainerStatusUpdater(containerControlApi, containerService, dockerServerService,
                eventService, kubernetesClientFactory, mock(JmsTemplate.class), nodeLockService);

        final ArgumentCaptor<NodeLeaderListener> captor = ArgumentCaptor.forClass(NodeLeaderListener.class);
        verify(nodeLockService).registerLeader(eq(ContainerStatusUpdater.LEADER_LOCK), captor.capture());
        listener = captor.getValue();
    }

    @AfterEach
    void tearDown() {
        users.close();
        xftManager.close();
        xdatServlet.close();
    }

    @Test
    void leaderTickStartsTheInformer() throws Exception {
        updater.run();
        verify(kubernetesClient).start();
        verify(leader).isLeader();
    }

    @Test
    void informerStartThatThrowsIsStillStoppedOnDemotion() throws Exception {
        doThrow(new RejectedExecutionException("executor full")).when(kubernetesClient).start();
        assertThrows(RejectedExecutionException.class, updater::run);

        listener.onDemoted(ContainerStatusUpdater.LEADER_LOCK, "test");
        verify(kubernetesClientFactory).shutdown();
    }

    @Test
    void swarmWalkStopsWhenDemotedPartway() throws Exception {
        final Container pollProjection = useSwarmBackend();
        when(containerService.retrieveNonfinalizedServiceIds()).thenReturn(Arrays.asList(DATABASE_ID, DATABASE_ID + 1));
        // Leader for the tick's start and the first service, demoted before the second.
        when(leader.isLeader()).thenReturn(true, true, false);

        updater.run();

        verify(containerService).retrieveServiceForPoll(DATABASE_ID);
        verify(containerControlApi).getTaskForService(server, pollProjection);
        verify(containerService, never()).retrieveServiceForPoll(DATABASE_ID + 1);
    }

    @Test
    void dockerCheckpointIsNotMovedAfterDemotion() throws Exception {
        when(server.backend()).thenReturn(Backend.DOCKER);
        when(containerControlApi.getContainerEvents(any(), any())).thenReturn(Collections.emptyList());

        // Demoted while the events were fetched: the checkpoint now belongs to the new leader.
        when(leader.isLeader()).thenReturn(true, false);
        updater.run();
        verify(dockerServerService, never()).update(any());

        // Still the leader at the end of the tick: the checkpoint moves.
        when(leader.isLeader()).thenReturn(true);
        updater.run();
        verify(dockerServerService).update(any());
    }

    @Test
    void followerTickDoesNothing() throws Exception {
        when(leader.isLeader()).thenReturn(false);
        updater.run();
        verify(kubernetesClientFactory, never()).getKubernetesClient();
        verify(containerControlApi, never()).canConnect();
    }

    @Test
    void demotionStopsTheInformerItStarted() throws Exception {
        updater.run();
        listener.onDemoted(ContainerStatusUpdater.LEADER_LOCK, "test");
        verify(kubernetesClientFactory).shutdown();

        // A second demotion has nothing to stop.
        listener.onDemoted(ContainerStatusUpdater.LEADER_LOCK, "test");
        verify(kubernetesClientFactory, times(1)).shutdown();
    }

    @Test
    void demotionBeforeAnyTickStopsNothing() throws Exception {
        listener.onDemoted(ContainerStatusUpdater.LEADER_LOCK, "test");
        verify(kubernetesClientFactory, never()).shutdown();
    }

    @Test
    void reElectionAfterDemotionStartsANewInformer() throws Exception {
        updater.run();
        listener.onDemoted(ContainerStatusUpdater.LEADER_LOCK, "test");
        listener.onElected(ContainerStatusUpdater.LEADER_LOCK);
        updater.run();
        verify(kubernetesClient, times(2)).start();
        verify(kubernetesClientFactory, times(1)).shutdown();
    }

    @Test
    void demotionWaitsForTheTickInFlightAndTheTickReChecksLeadership() throws Exception {
        // The tick blocks inside getKubernetesClient() while a demotion arrives on another thread. The demotion
        // must wait for the tick to finish, so the informer is shut down after it starts, never before.
        // (The tick runs on this thread because Mockito's static mocks are thread-local.)
        final CountDownLatch demotionRequested = new CountDownLatch(1);
        when(kubernetesClientFactory.getKubernetesClient()).thenAnswer(invocation -> {
            demotionRequested.countDown();
            // Give the demotion thread time to reach the monitor while we're still inside the tick.
            Thread.sleep(300);
            return kubernetesClient;
        });

        final Thread demotion = new Thread(() -> {
            try {
                demotionRequested.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            listener.onDemoted(ContainerStatusUpdater.LEADER_LOCK, "test");
        });
        demotion.start();

        updater.run();
        demotion.join(5000);

        final InOrder order = inOrder(kubernetesClient, kubernetesClientFactory);
        order.verify(kubernetesClient).start();
        order.verify(kubernetesClientFactory).shutdown();

        // Once demoted, the next tick doesn't restart anything.
        when(leader.isLeader()).thenReturn(false);
        updater.run();
        verify(kubernetesClient, times(1)).start();
    }

    @Test
    void leaderStepsDownAfterEighteenFailedPings() {
        when(containerControlApi.canConnect()).thenReturn(false);
        when(leader.stepDown(any())).thenReturn(true);

        for (int i = 0; i < ContainerStatusUpdater.BACKEND_FAILURES_BEFORE_STEP_DOWN - 1; i++) {
            updater.run();
        }
        verify(leader, never()).stepDown(any());

        updater.run();
        verify(leader).stepDown(ContainerStatusUpdater.STEP_DOWN_BACKOFF);
    }

    @Test
    void pingThatThrowsCountsAsFailedPing() {
        when(containerControlApi.canConnect()).thenThrow(new RuntimeException("no socket"));
        when(leader.stepDown(any())).thenReturn(true);

        for (int i = 0; i < ContainerStatusUpdater.BACKEND_FAILURES_BEFORE_STEP_DOWN; i++) {
            updater.run();
        }
        verify(leader).stepDown(ContainerStatusUpdater.STEP_DOWN_BACKOFF);
        verify(kubernetesClient, never()).start();
    }

    @Test
    void successfulPingResetsTheFailureCount() {
        when(containerControlApi.canConnect()).thenReturn(false);
        for (int i = 0; i < ContainerStatusUpdater.BACKEND_FAILURES_BEFORE_STEP_DOWN - 1; i++) {
            updater.run();
        }
        when(containerControlApi.canConnect()).thenReturn(true);
        updater.run();

        when(containerControlApi.canConnect()).thenReturn(false);
        for (int i = 0; i < ContainerStatusUpdater.BACKEND_FAILURES_BEFORE_STEP_DOWN - 1; i++) {
            updater.run();
        }
        verify(leader, never()).stepDown(any());
    }

    @Test
    void singleNodeKeepsLeadershipWhenStepDownIsRefusedAndTriesAgainLater() {
        when(containerControlApi.canConnect()).thenReturn(false);
        when(leader.stepDown(any())).thenReturn(false);

        for (int i = 0; i < ContainerStatusUpdater.BACKEND_FAILURES_BEFORE_STEP_DOWN * 2; i++) {
            updater.run();
        }
        verify(leader, times(2)).stepDown(any(Duration.class));

        // The backend is back: the leader resumes without any election.
        when(containerControlApi.canConnect()).thenReturn(true);
        updater.run();
        verify(kubernetesClient).start();
    }

    @Test
    void demotionResetsTheFailureCount() {
        when(containerControlApi.canConnect()).thenReturn(false);
        for (int i = 0; i < ContainerStatusUpdater.BACKEND_FAILURES_BEFORE_STEP_DOWN - 1; i++) {
            updater.run();
        }
        listener.onDemoted(ContainerStatusUpdater.LEADER_LOCK, "test");
        listener.onElected(ContainerStatusUpdater.LEADER_LOCK);
        updater.run();
        verify(leader, never()).stepDown(any());
    }

    @Test
    void stopFailureIsSwallowed() throws Exception {
        updater.run();
        doAnswer(invocation -> {
            throw new IllegalStateException("boom");
        }).when(kubernetesClientFactory).shutdown();
        listener.onDemoted(ContainerStatusUpdater.LEADER_LOCK, "test");
        verify(kubernetesClientFactory).shutdown();
    }

    @Test
    void repeatedUpdateFailureIsLoggedOncePerRunOfFailures() throws Exception {
        // The plugin's logging fragment now puts this class at INFO, and the tick fires every 10 seconds, so a
        // failure that persists must not print on every tick.
        final Logger            logger   = Logger.getLogger(ContainerStatusUpdater.class);
        final CollectingAppender appender = new CollectingAppender();
        final Level             previous = logger.getLevel();
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        try {
            when(kubernetesClientFactory.getKubernetesClient()).thenThrow(new NoContainerServerException("no backend"));
            updater.run();
            updater.run();
            updater.run();
            assertEquals(1, appender.failureLines().size(), "three failing ticks log the failure once");
            assertTrue(appender.failureLines().get(0).contains("no backend"), "the line carries the report's message");

            doReturn(kubernetesClient).when(kubernetesClientFactory).getKubernetesClient();
            updater.run();
            when(kubernetesClientFactory.getKubernetesClient()).thenThrow(new NoContainerServerException("no backend"));
            updater.run();
            updater.run();
            assertEquals(2, appender.failureLines().size(), "a success in between starts a new run of failures");
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(previous);
        }
    }

    @Test
    void swarmTaskEventCarriesThePollProjectionWithoutAFullLoad() throws Exception {
        final Container pollProjection = useSwarmBackend();
        final ServiceTask task = ServiceTask.builder()
                .serviceId(SERVICE_ID)
                .taskId("task-1")
                .nodeId("swarm-node-1")
                .status("running")
                .swarmNodeError(false)
                .build();
        when(containerControlApi.getTaskForService(server, pollProjection)).thenReturn(task);

        updater.run();

        final ServiceTaskEvent event = capturedServiceTaskEvent();
        assertSame(pollProjection, event.service());
        assertSame(task, event.task());
        verify(containerService, never()).get(DATABASE_ID);
    }

    @Test
    void swarmLostTaskEventCarriesThePollProjectionWithoutAFullLoad() throws Exception {
        final Container pollProjection = useSwarmBackend();
        when(containerControlApi.getTaskForService(server, pollProjection))
                .thenThrow(new TaskNotFoundException(new RuntimeException("no task")));

        updater.run();

        final ServiceTaskEvent event = capturedServiceTaskEvent();
        assertSame(pollProjection, event.service());
        assertTrue(event.task().swarmNodeError());
        verify(containerService, never()).get(DATABASE_ID);
    }

    /** Switches the mocked backend to Swarm with one unfinalized service, and returns what the poll reads for it. */
    private Container useSwarmBackend() {
        when(server.backend()).thenReturn(Backend.SWARM);
        // What retrieveServiceForPoll returns: scalars only, placeholders for the required strings
        final Container pollProjection = Container.builder()
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
        when(containerService.retrieveNonfinalizedServiceIds()).thenReturn(Collections.singletonList(DATABASE_ID));
        when(containerService.retrieveServiceForPoll(DATABASE_ID)).thenReturn(pollProjection);
        return pollProjection;
    }

    private ServiceTaskEvent capturedServiceTaskEvent() {
        final ArgumentCaptor<EventI> captor = ArgumentCaptor.forClass(EventI.class);
        verify(eventService).triggerEvent(captor.capture());
        return assertInstanceOf(ServiceTaskEvent.class, captor.getValue());
    }

    private static class CollectingAppender extends AppenderSkeleton {
        final List<LoggingEvent> events = new CopyOnWriteArrayList<>();

        @Override
        protected void append(final LoggingEvent event) {
            events.add(event);
        }

        List<String> failureLines() {
            final List<String> lines = new java.util.ArrayList<>();
            for (final LoggingEvent event : events) {
                final String message = String.valueOf(event.getMessage());
                if (event.getLevel() == Level.INFO && message.startsWith("Did not update status successfully")) {
                    lines.add(message);
                }
            }
            return lines;
        }

        @Override
        public void close() {
        }

        @Override
        public boolean requiresLayout() {
            return false;
        }
    }
}
