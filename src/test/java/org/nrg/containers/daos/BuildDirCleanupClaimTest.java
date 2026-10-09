package org.nrg.containers.daos;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nrg.containers.config.BuildDirCleanupClaimTestConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

/**
 * The single primitive that decides which node runs a day's cleanup. Requires Docker, because HibernateConfig
 * starts Postgres through Testcontainers - and it has to run against a real database, since the whole guarantee
 * rests on PostgreSQL's row locking under ON CONFLICT DO UPDATE.
 * */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = BuildDirCleanupClaimTestConfig.class)
public class BuildDirCleanupClaimTest {

    private static final Instant SLOT = Instant.parse("2026-03-10T02:00:00Z");
    private static final long    ONE_DAY_SECONDS = 86_400L;

    @Autowired private BuildDirCleanupClaimDao dao;
    @Autowired private JdbcTemplate             jdbcTemplate;

    /** The claim row outlives its transaction, so it outlives a test too; without this the first test to run
     *  would claim the slot for all the others. */
    @Before
    public void clearTheClaim() {
        jdbcTemplate.update("DELETE FROM xhbm_build_dir_cleanup_claim");
    }

    /** The reason this exists. Two nodes waking at the same instant must not both sweep. */
    @Test
    public void exactlyOneOfManySimultaneousClaimantsWins() throws Exception {
        final int nodes = 8;
        final CyclicBarrier startTogether = new CyclicBarrier(nodes);
        final ExecutorService pool = Executors.newFixedThreadPool(nodes);
        try {
            final List<Callable<Boolean>> attempts = new ArrayList<>();
            for (int i = 0; i < nodes; i++) {
                attempts.add(() -> {
                    startTogether.await();
                    return dao.claim(SLOT);
                });
            }
            int winners = 0;
            for (final Future<Boolean> attempt : pool.invokeAll(attempts)) {
                if (attempt.get()) {
                    winners++;
                }
            }
            assertThat("exactly one node may claim an occurrence", winners, is(1));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void anAlreadyClaimedOccurrenceIsRefused() {
        assertThat(dao.claim(SLOT), is(true));
        assertThat("a second attempt at the same slot stands down", dao.claim(SLOT), is(false));
        assertThat("and so does a third", dao.claim(SLOT), is(false));
    }

    /** The next day is a fresh claim, which is what makes a crashed node cost one cycle and nothing more. */
    @Test
    public void aLaterOccurrenceIsClaimable() {
        assertThat(dao.claim(SLOT), is(true));
        assertThat(dao.claim(SLOT.plusSeconds(ONE_DAY_SECONDS)), is(true));
    }

    /** An earlier occurrence must never win, or a lagging node could re-run a slot already done. */
    @Test
    public void anEarlierOccurrenceIsRefused() {
        assertThat(dao.claim(SLOT), is(true));
        assertThat(dao.claim(SLOT.minusSeconds(ONE_DAY_SECONDS)), is(false));
    }
}
