package org.nrg.containers.exceptions;

/**
 * Thrown when a build directory cleanup run cannot start at all. Failures deleting individual directories are
 * logged and counted rather than thrown.
 */
public class BuildDirectoryCleanupException extends Exception {
    public BuildDirectoryCleanupException(final String message) {
        super(message);
    }

    public BuildDirectoryCleanupException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
