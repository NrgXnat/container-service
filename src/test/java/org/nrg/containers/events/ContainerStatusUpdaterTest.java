package org.nrg.containers.events;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.nrg.containers.api.ContainerControlApi;
import org.nrg.containers.api.KubernetesClient;
import org.nrg.containers.api.KubernetesClientFactory;
import org.nrg.containers.exceptions.NoContainerServerException;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.model.server.docker.Backend;
import org.nrg.containers.services.ContainerService;
import org.nrg.containers.services.DockerServerService;
import org.nrg.framework.node.NodeLeader;
import org.nrg.framework.node.NodeLeaderListener;
import org.nrg.framework.node.NodeLockService;
import org.nrg.framework.services.NrgEventServiceI;
import org.nrg.xdat.servlet.XDATServlet;
import org.nrg.xft.schema.XFTManager;
import org.springframework.jms.core.JmsTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ContainerStatusUpdater} against a mocked lock service: leadership gates the tick, demotion stops the
 * informer, and a leader that can't reach the backend steps down.
 */
class ContainerStatusUpdaterTest {
    private ContainerControlApi containerControlApi;
    private DockerServerService dockerServerService;
    private KubernetesClientFactory kubernetesClientFactory;
    private KubernetesClient kubernetesClient;
    private NodeLeader leader;
    private NodeLeaderListener listener;
    private ContainerStatusUpdater updater;

    private MockedStatic<XFTManager> xftManager;
    private MockedStatic<XDATServlet> xdatServlet;

    @BeforeEach
    void setUp() throws Exception {
        xftManager = mockStatic(XFTManager.class);
        xftManager.when(XFTManager::isInitialized).thenReturn(true);
        xdatServlet = mockStatic(XDATServlet.class);
        xdatServlet.when(XDATServlet::isDatabasePopulateOrUpdateCompleted).thenReturn(true);

        containerControlApi = mock(ContainerControlApi.class);
        when(containerControlApi.canConnect()).thenReturn(true);

        final DockerServer server = mock(DockerServer.class);
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

        updater = new ContainerStatusUpdater(containerControlApi, mock(ContainerService.class), dockerServerService,
                mock(NrgEventServiceI.class), kubernetesClientFactory, mock(JmsTemplate.class), nodeLockService);

        final ArgumentCaptor<NodeLeaderListener> captor = ArgumentCaptor.forClass(NodeLeaderListener.class);
        verify(nodeLockService).registerLeader(eq(ContainerStatusUpdater.LEADER_LOCK), captor.capture());
        listener = captor.getValue();
    }

    @AfterEach
    void tearDown() {
        xftManager.close();
        xdatServlet.close();
    }

    @Test
    void leaderTickStartsTheInformer() throws Exception {
        updater.run();
        verify(kubernetesClient).start();
        assertTrue(updater.isLeader());
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
        verify(kubernetesClient).stop();

        // A second demotion has nothing to stop.
        listener.onDemoted(ContainerStatusUpdater.LEADER_LOCK, "test");
        verify(kubernetesClient, times(1)).stop();
    }

    @Test
    void demotionBeforeAnyTickStopsNothing() throws Exception {
        listener.onDemoted(ContainerStatusUpdater.LEADER_LOCK, "test");
        verify(kubernetesClientFactory, never()).getKubernetesClient();
    }

    @Test
    void reElectionAfterDemotionStartsANewInformer() throws Exception {
        updater.run();
        listener.onDemoted(ContainerStatusUpdater.LEADER_LOCK, "test");
        listener.onElected(ContainerStatusUpdater.LEADER_LOCK);
        updater.run();
        verify(kubernetesClient, times(2)).start();
        verify(kubernetesClient, times(1)).stop();
    }

    @Test
    void demotionWaitsForTheTickInFlightAndTheTickReChecksLeadership() throws Exception {
        // The tick blocks inside getKubernetesClient() while a demotion arrives on another thread. The demotion
        // must wait for the tick to finish, and must then find the informer started so it can stop it.
        // (The tick runs on this thread because Mockito's static mocks are thread-local.)
        final CountDownLatch demotionRequested = new CountDownLatch(1);
        final AtomicBoolean tickFinished = new AtomicBoolean();
        final AtomicBoolean tickHadFinishedWhenDemotionReturned = new AtomicBoolean();
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
            tickHadFinishedWhenDemotionReturned.set(tickFinished.get());
        });
        demotion.start();

        updater.run();
        tickFinished.set(true);
        demotion.join(5000);

        assertTrue(tickHadFinishedWhenDemotionReturned.get());
        verify(kubernetesClient).start();
        verify(kubernetesClient).stop();

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
        }).when(kubernetesClient).stop();
        listener.onDemoted(ContainerStatusUpdater.LEADER_LOCK, "test");
        verify(kubernetesClient).stop();
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
