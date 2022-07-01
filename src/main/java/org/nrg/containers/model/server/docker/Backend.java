package org.nrg.containers.model.server.docker;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public enum Backend {
    DOCKER,
    SWARM,
    KUBERNETES;

    @JsonValue
    @Override
    public String toString() {
        return this.name().toLowerCase();
    }

    public static final Set<Backend> SUPPORTS_CONSTRAINTS =
            Stream.of(Backend.SWARM, Backend.KUBERNETES).collect(Collectors.toSet());
}
