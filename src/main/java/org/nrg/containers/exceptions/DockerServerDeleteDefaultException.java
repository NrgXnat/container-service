package org.nrg.containers.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
public class DockerServerDeleteDefaultException extends Exception {
    public DockerServerDeleteDefaultException(final String message) {
        super(message);
    }

    public DockerServerDeleteDefaultException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public DockerServerDeleteDefaultException(final Throwable cause) {
        super(cause);
    }
}