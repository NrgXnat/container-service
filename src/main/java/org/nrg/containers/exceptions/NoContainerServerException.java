package org.nrg.containers.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.FAILED_DEPENDENCY)
public class NoContainerServerException extends Exception {
    public NoContainerServerException(final String message) {
        super(message);
    }

    public NoContainerServerException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public NoContainerServerException(final Throwable cause) {
        super(cause);
    }
}