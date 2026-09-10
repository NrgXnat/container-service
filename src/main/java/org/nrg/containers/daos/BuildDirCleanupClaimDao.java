package org.nrg.containers.daos;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * Decides which node in a cluster runs a given day's build directory cleanup.
 *
 * <p>No owner, lease, expiry or heartbeat: a run is bounded to finish before the next occurrence, so a claim never
 * has to be taken from a node that might still be working. A node that crashes costs that one day.
 *
 * <p>Raw SQL, not Criteria: an atomic compare-and-set cannot be expressed in Criteria.
 */
@Slf4j
@Repository
public class BuildDirCleanupClaimDao {

    private static final String TASK_ID = "build-dir-cleanup";

    /**
     * ON CONFLICT DO UPDATE takes a row-level lock, so two nodes racing are serialized: the loser re-evaluates its
     * predicate against the winner's committed row and updates nothing.
     */
    private static final String CLAIM_SQL =
            "INSERT INTO xhbm_build_dir_cleanup_claim (task_id, occurrence_millis) VALUES (?, ?) " +
            "ON CONFLICT (task_id) DO UPDATE SET occurrence_millis = ? " +
            "WHERE xhbm_build_dir_cleanup_claim.occurrence_millis < ?";

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public BuildDirCleanupClaimDao(final JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @return true if this node may run the cleanup for {@code occurrence}, false if another node already has it.
     */
    public boolean claim(final Instant occurrence) {
        final long millis = occurrence.toEpochMilli();
        final int rows = jdbcTemplate.update(CLAIM_SQL, TASK_ID, millis, millis, millis);
        if (rows == 0) {
            log.debug("Build directory cleanup for {} is already claimed by another node.", occurrence);
        }
        return rows == 1;
    }
}
