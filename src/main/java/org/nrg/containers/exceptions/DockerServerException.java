package org.nrg.containers.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
public class DockerServerException extends ContainerBackendException {
    public DockerServerException(final String message) {
        super(message);
    }

    public DockerServerException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public DockerServerException(final Throwable cause) {
        super(cause);
    }
}