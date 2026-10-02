package org.nrg.containers.secrets;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Deliver a secret to the container as files under a directory (one file per key),
 * mounted on tmpfs by the backend. The {@code identifier} is the mount path inside the container.
 */
public class FileSecretDestination implements SecretDestination {
    @JsonIgnore public static final String JSON_TYPE_NAME = "file";

    @JsonProperty("identifier") private final String mountPath;

    @JsonCreator
    public FileSecretDestination(@JsonProperty("identifier") final String mountPath) {
        this.mountPath = mountPath;
    }

    public String mountPath() {
        return mountPath;
    }

    @Override
    @JsonIgnore
    public String type() {
        return JSON_TYPE_NAME;
    }

    @Override
    public String identifier() {
        return mountPath;
    }

    @Override
    public Map<String, String> otherProperties() {
        return Collections.emptyMap();
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final FileSecretDestination that = (FileSecretDestination) o;
        return Objects.equals(mountPath, that.mountPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mountPath);
    }

    @Override
    public String toString() {
        return "File{mountPath=\"" + mountPath + "\"}";
    }
}
