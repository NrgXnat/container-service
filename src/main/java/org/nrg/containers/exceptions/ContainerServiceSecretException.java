package org.nrg.containers.exceptions;

public class ContainerServiceSecretException extends Exception {
    public ContainerServiceSecretException(final String message) {
        super(message);
    }
    public ContainerServiceSecretException(final String message, final Throwable t) {
        super(message, t);
    }
}
