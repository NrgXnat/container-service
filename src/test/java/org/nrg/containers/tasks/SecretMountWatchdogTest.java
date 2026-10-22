package org.nrg.containers.tasks;

import org.junit.Before;
import org.junit.Test;
import org.nrg.containers.model.server.docker.Backend;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.services.DockerServerService;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Timing rules of design doc section 4.1; what happens on timeout is the container service's business and is tested there. */
public class SecretMountWatchdogTest {
    private static final Instant T0 = Instant.parse("2026-10-13T08:00:00Z");

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {
        private Instant now = T0;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(final ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(final Duration by) { now = now.plus(by); }
    }

    private final MutableClock clock = new MutableClock();
    private final DockerServerService dockerServerService = mock(DockerServerService.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final List<String> fired = new ArrayList<>();
    private SecretMountWatchdog watchdog;

    @Before
    public void setUp() {
        configureTimeout(600);
        watchdog = new SecretMountWatchdog(dockerServerService, scheduler, clock);
        watchdog.registerTimeoutHandler((containerDbId, timeoutSeconds, reason) -> fired.add(containerDbId + ":" + timeoutSeconds + ":" + reason));
    }

    private void configureTimeout(final int seconds) {
        when(dockerServerService.retrieveServer()).thenReturn(
                DockerServer.builder().name("k8s").host("").backend(Backend.KUBERNETES).secretMountTimeoutSeconds(seconds).build());
    }

    @Test
    public void afterPropertiesSetSchedulesTheTickEveryMinute() {
        watchdog.afterPropertiesSet();
        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class), eq(SecretMountWatchdog.TICK_INTERVAL));
    }

    @Test
    public void firstFailureTimeIsKeptAcrossRepeatedReports() {
        watchdog.recordFailedMount(42L, OffsetDateTime.ofInstant(T0, ZoneOffset.UTC), "first reason");
        clock.advance(Duration.ofSeconds(500));
        watchdog.recordFailedMount(42L, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), "later reason");
        clock.advance(Duration.ofSeconds(101));   // 601 s after the first report, 101 s after the second

        watchdog.tick();

        assertThat(fired, is(Collections.singletonList("42:600:first reason")));
    }

    @Test
    public void nothingFiresBeforeTheTimeoutOrWhenTheTimeoutIsZero() {
        watchdog.recordFailedMount(42L, OffsetDateTime.ofInstant(T0, ZoneOffset.UTC), "r");
        clock.advance(Duration.ofSeconds(600));
        watchdog.tick();
        assertThat("exactly the timeout is not yet over", fired.isEmpty(), is(true));

        configureTimeout(0);
        clock.advance(Duration.ofHours(1));
        watchdog.tick();
        assertThat("0 disables the watchdog but keeps the record", fired.isEmpty(), is(true));
        assertThat(watchdog.isTracking(42L), is(true));
    }

    @Test
    public void aMissingFirstTimestampUsesNow() {
        watchdog.recordFailedMount(42L, null, "r");
        clock.advance(Duration.ofSeconds(601));
        watchdog.tick();
        assertThat(fired.size(), is(1));
    }

    @Test
    public void clearForgetsTheContainerAndTheHandlerIsRetriedUntilCleared() {
        watchdog.recordFailedMount(42L, OffsetDateTime.ofInstant(T0, ZoneOffset.UTC), "r");
        clock.advance(Duration.ofSeconds(601));
        watchdog.tick();
        watchdog.tick();
        assertThat("the handler decides when to stop; the watchdog keeps asking", fired.size(), is(2));

        watchdog.clear(42L);
        watchdog.tick();
        assertThat(fired.size(), is(2));
        assertThat(watchdog.isTracking(42L), is(false));
    }

    @Test
    public void aFailingHandlerDoesNotStopTheTick() {
        watchdog.registerTimeoutHandler((id, timeout, reason) -> {
            if (id == 1L) {
                throw new IllegalStateException("boom");
            }
            fired.add(id + ":" + timeout + ":" + reason);
        });
        watchdog.recordFailedMount(1L, OffsetDateTime.ofInstant(T0, ZoneOffset.UTC), "r1");
        watchdog.recordFailedMount(2L, OffsetDateTime.ofInstant(T0, ZoneOffset.UTC), "r2");
        clock.advance(Duration.ofSeconds(601));

        watchdog.tick();   // must not throw

        assertThat("the other container is still handled whatever the iteration order",
                fired, is(Collections.singletonList("2:600:r2")));
    }
}
