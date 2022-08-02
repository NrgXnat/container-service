package org.nrg.containers.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
public class ContainerBackendException extends Exception {
    public ContainerBackendException(final String message) {
        super(message);
    }

    public ContainerBackendException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public ContainerBackendException(final Throwable cause) {
        super(cause);
    }
}
