package org.nrg.containers.secrets;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class SystemPropertySecretSource implements SecretSource.ValueObtainingSecretSource {
    @JsonIgnore public static final String JSON_TYPE_NAME = "system-property";

    @JsonProperty("identifier") private final String systemPropertyName;

    @JsonCreator
    public SystemPropertySecretSource(@JsonProperty("identifier") final String systemPropertyName) {
        this.systemPropertyName = systemPropertyName;
    }

    public String systemPropertyName() {
        return systemPropertyName;
    }

    @Override
    @JsonIgnore
    public String type() {
        return JSON_TYPE_NAME;
    }

    @Override
    @JsonIgnore
    public String identifier() {
        return systemPropertyName;
    }

    @JsonAnyGetter
    @Override
    public Map<String, String> otherProperties() {
        return Collections.emptyMap();
    }

    @Override
    public String toString() {
        return "SystemProperty{\"" + systemPropertyName + "\"}";
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final SystemPropertySecretSource that = (SystemPropertySecretSource) o;
        return Objects.equals(systemPropertyName, that.systemPropertyName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(systemPropertyName);
    }

    @Component
    public static class ValueObtainer extends SecretValueObtainer<SystemPropertySecretSource> {
        public ValueObtainer() {
            super(SystemPropertySecretSource.class);
        }

        @Override
        public Optional<String> obtainValue(final ValueObtainingSecretSource source) {
            return Optional.ofNullable(System.getProperty(source.identifier()));
        }
    }
}
