package org.nrg.containers.secrets;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

public class Secret {
    @JsonProperty("source") private final SecretSource source;
    @JsonProperty("destination") private final SecretDestination destination;

    @JsonCreator
    public Secret(
            @JsonProperty("source") final SecretSource source,
            @JsonProperty("destination") final SecretDestination destination
    ) {
        this.source = source;
        this.destination = destination;
    }

    public SecretSource source() {
        return source;
    }

    public SecretDestination destination() {
        return destination;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final Secret that = (Secret) o;
        return Objects.equals(source, that.source) &&
                Objects.equals(destination, that.destination);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, destination);
    }

    @Override
    public String toString() {
        return "Secret{" +
                "source=" + source +
                ", destination=" + destination +
                "}";
    }
}
