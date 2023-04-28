package org.nrg.containers.rest;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

@Data
public class ContainerLogPollResponse {
    @JsonIgnore public static final long LOG_COMPLETE_TIMESTAMP = -1;
    private final String content;
    private final boolean fromFile;
    private final long timestamp;
    private final long bytesRead;
}
