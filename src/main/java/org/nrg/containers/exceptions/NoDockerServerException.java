package org.nrg.containers.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.FAILED_DEPENDENCY)
public class NoDockerServerException extends NoContainerServerException {
    public NoDockerServerException(final String message) {
        super(message);
    }

    public NoDockerServerException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public NoDockerServerException(final Throwable cause) {
        super(cause);
    }
}