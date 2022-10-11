package org.nrg.containers.secrets;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

public class EnvironmentVariableSecretDestination implements SecretDestination {
    @JsonIgnore public static final String JSON_TYPE_NAME = "environment-variable";

    @JsonProperty("identifier") private final String envName;

    @JsonCreator
    public EnvironmentVariableSecretDestination(@JsonProperty("identifier") final String envName) {
        this.envName = envName;
    }

    public String envName() {
        return envName;
    }

    @Override
    @JsonIgnore
    public String type() {
        return JSON_TYPE_NAME;
    }

    @Override
    public String identifier() {
        return envName;
    }

    @Override
    public Map<String, String> otherProperties() {
        return Collections.emptyMap();
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final EnvironmentVariableSecretDestination that = (EnvironmentVariableSecretDestination) o;
        return Objects.equals(envName, that.envName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(envName);
    }

    @Override
    public String toString() {
        return "EnvironmentVariable{" +
                "name=\"" + envName + "\"" +
                "}";
    }
}
