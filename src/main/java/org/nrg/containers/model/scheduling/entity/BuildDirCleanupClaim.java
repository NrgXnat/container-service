package org.nrg.containers.model.scheduling.entity;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;

/**
 * One row, recording which daily cleanup slot has already been taken by some node in the cluster.
 *
 * <p>Never read or written through Hibernate; the only operation is the atomic claim in
 * {@link org.nrg.containers.daos.BuildDirCleanupClaimDao}. Do not delete it as unused: the entity is what makes
 * hbm2ddl create the table.
 *
 * <p>Epoch millis rather than a timestamp: every node computes the value from the same configured time and
 * claiming just compares two of them, so there is no timestamp/timestamptz semantics to get wrong.
 */
@Entity
public class BuildDirCleanupClaim {

    private String taskId;
    private long   occurrenceMillis;

    @Id
    @Column(length = 64)
    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(final String taskId) {
        this.taskId = taskId;
    }

    @Column(nullable = false)
    public long getOccurrenceMillis() {
        return occurrenceMillis;
    }

    public void setOccurrenceMillis(final long occurrenceMillis) {
        this.occurrenceMillis = occurrenceMillis;
    }
}
