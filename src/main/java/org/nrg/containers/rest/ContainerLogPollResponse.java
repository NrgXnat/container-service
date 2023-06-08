package org.nrg.containers.rest;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

@Data
public class ContainerLogPollResponse {
    @JsonIgnore public static final String LOG_COMPLETE_TIMESTAMP = "-1";
    private final String content;
    private final boolean fromFile;
    private final String timestamp;
    private final long bytesRead;

    public static ContainerLogPollResponse fromFile(final String content) {
        return new ContainerLogPollResponse(content, true, LOG_COMPLETE_TIMESTAMP, -1);
    }

    public static ContainerLogPollResponse fromLive(final String content, final String timestamp) {
        return new ContainerLogPollResponse(content, false, timestamp, -1);
    }

    public static ContainerLogPollResponse fromComplete(final String content) {
        return new ContainerLogPollResponse(content, false, LOG_COMPLETE_TIMESTAMP, -1);
    }
}
