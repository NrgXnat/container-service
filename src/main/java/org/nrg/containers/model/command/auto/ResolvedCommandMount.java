package org.nrg.containers.model.command.auto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.auto.value.AutoValue;

import javax.annotation.Nullable;

@AutoValue
public abstract class ResolvedCommandMount {
    @JsonProperty("name") public abstract String name();
    @JsonProperty("writable") public abstract Boolean writable();
    @JsonProperty("container-path") public abstract String containerPath();
    @JsonProperty("xnat-host-path") public abstract String xnatHostPath();
    @JsonProperty("container-host-path") public abstract String containerHostPath();
    @Nullable @JsonProperty("from-wrapper-input") public abstract String fromWrapperInput();
    @Nullable @JsonProperty("via-setup-command") public abstract String viaSetupCommand();
    @Nullable @JsonProperty("from-uri") public abstract String fromUri();
    @Nullable @JsonProperty("from-root-directory") public abstract String fromRootDirectory();
    @Nullable @JsonProperty("from-file-path") public abstract String fromFilePath();

    public static Builder builder() {
        return new AutoValue_ResolvedCommandMount.Builder();
    }

    public abstract Builder toBuilder();

    @JsonIgnore
    public String toBindMountString() {
        return containerHostPath() + ":" + containerPath() + (writable() ? "" : ":ro");
    }

    @AutoValue.Builder
    public abstract static class Builder {
        public abstract Builder name(String name);
        public abstract Builder writable(Boolean writable);
        public abstract Builder xnatHostPath(String xnatHostPath);
        public abstract Builder containerHostPath(String containerHostPath);
        public abstract Builder containerPath(String containerPath);
        public abstract Builder fromWrapperInput(String fromWrapperInput);
        public abstract Builder viaSetupCommand(String viaSetupCommand);
        public abstract Builder fromUri(String fromUri);
        public abstract Builder fromRootDirectory(String fromRootDirectory);
        public abstract Builder fromFilePath(String fromFilePath);

        public abstract ResolvedCommandMount build();
    }
}
