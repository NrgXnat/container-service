package org.nrg.containers.model.command.auto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.auto.value.AutoValue;

import javax.annotation.Nullable;

@AutoValue
public abstract class ResolvedCommandMount {
    public static final String INPUT = "input";
    public static final String OUTPUT = "output";
    public static final String SETUP_WRAPUP_INPUT_PATH = "/" + INPUT;
    public static final String SETUP_WRAPUP_OUTPUT_PATH = "/" + OUTPUT;

    @JsonProperty("name") public abstract String name();
    @JsonProperty("writable") public abstract Boolean writable();
    @JsonProperty("container-path") public abstract String containerPath();
    @JsonProperty("xnat-host-path") public abstract String xnatHostPath();
    @JsonProperty("container-host-path") public abstract String containerHostPath();
    @Nullable @JsonProperty("via-setup-command") public abstract String viaSetupCommand();
    @Nullable @JsonProperty("mount-pvc-name") public abstract String mountPvcName();

    public static ResolvedCommandMount specialInput(final String xnatHostPath, final String containerHostPath, final String mountPvcName) {
        return ResolvedCommandMount.builder()
                .name(INPUT)
                .writable(false)
                .xnatHostPath(xnatHostPath)
                .containerHostPath(containerHostPath)
                .containerPath(SETUP_WRAPUP_INPUT_PATH)
                .mountPvcName(mountPvcName)
                .build();
    }

    public static ResolvedCommandMount output(final String name, final String xnatHostPath, final String containerHostPath,
                                              final String containerPath, final String buildPvcName) {
        return ResolvedCommandMount.builder()
                .name(name)
                .writable(true)
                .xnatHostPath(xnatHostPath)
                .containerHostPath(containerHostPath)
                .containerPath(containerPath)
                .mountPvcName(buildPvcName)
                .build();
    }

    public static ResolvedCommandMount specialOutput(final String xnatHostPath, final String containerHostPath, final String mountPvcName) {
        return output(OUTPUT, xnatHostPath, containerHostPath, SETUP_WRAPUP_OUTPUT_PATH, mountPvcName);
    }

    public static Builder builder() {
        return new AutoValue_ResolvedCommandMount.Builder();
    }

    public abstract Builder toBuilder();

    @AutoValue.Builder
    public abstract static class Builder {
        public abstract Builder name(String name);
        public abstract Builder writable(Boolean writable);
        public abstract Builder xnatHostPath(String xnatHostPath);
        public abstract Builder containerHostPath(String containerHostPath);
        public abstract Builder containerPath(String containerPath);
        public abstract Builder viaSetupCommand(String viaSetupCommand);
        public abstract Builder mountPvcName(String mountPvcName);
        public abstract ResolvedCommandMount build();
    }
}
