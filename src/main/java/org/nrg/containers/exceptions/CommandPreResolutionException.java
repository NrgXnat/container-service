package org.nrg.containers.exceptions;

public class CommandPreResolutionException extends CommandResolutionException{

    public CommandPreResolutionException(final String message) { super(message); }

    public CommandPreResolutionException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public CommandPreResolutionException(final Throwable cause) {
        super(cause);
    }
}
