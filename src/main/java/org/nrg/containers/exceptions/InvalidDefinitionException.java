package org.nrg.containers.exceptions;

public class InvalidDefinitionException extends Exception {
    public InvalidDefinitionException(final String message) {
        super(message);
    }

    public InvalidDefinitionException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public InvalidDefinitionException(final Throwable cause) {
        super(cause);
    }
}
