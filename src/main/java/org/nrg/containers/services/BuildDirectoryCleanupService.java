package org.nrg.containers.services;

import org.nrg.containers.exceptions.BuildDirectoryCleanupException;

import javax.annotation.Nonnull;

public interface BuildDirectoryCleanupService {

    /** Whether cleanup is switched on for the configured container server. Cheap enough to call as a pre-check. */
    boolean isEnabled();

    /**
     * Remove build directories of finalized containers that have aged past their retention threshold.
     *
     * @return a one-line summary, for the ADMIN workflow details field and one INFO log line. Per-directory
     *         detail goes to the log as it happens.
     * @throws BuildDirectoryCleanupException if the run cannot proceed, e.g. the build path is unusable
     */
    @Nonnull
    String cleanup() throws BuildDirectoryCleanupException;
}
