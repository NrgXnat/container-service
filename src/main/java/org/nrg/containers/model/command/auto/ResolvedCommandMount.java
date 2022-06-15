package org.nrg.containers.model.command.auto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.auto.value.AutoValue;
import org.nrg.containers.model.command.MountPoint;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@AutoValue
public abstract class ResolvedCommandMount {
    public static final String INPUT = "input";
    public static final String OUTPUT = "output";
    public static final String SETUP_WRAPUP_INPUT_PATH = "/" + INPUT;
    public static final String SETUP_WRAPUP_OUTPUT_PATH = "/" + OUTPUT;

    @JsonProperty("name") public abstract String name();
    @JsonProperty("writable") public abstract Boolean writable();
    @Nullable @JsonProperty("root-container-path") public abstract String rootContainerPath();
    @JsonProperty("mount-points") public abstract List<MountPoint> mountPoints();
    @Nullable @JsonProperty("via-setup-command") public abstract String viaSetupCommand();

    public static ResolvedCommandMount specialInput(final List<MountPoint> inputMountPoints, final String inputMountPointRootContainerPath) {
        return ResolvedCommandMount.builder()
                .name(INPUT)
                .writable(false)
                .rootContainerPath(SETUP_WRAPUP_INPUT_PATH)
                .mountPoints(inputMountPoints.stream()
                        .map(inputMount -> inputMount.newWithSwappedContainerPathPrefix(inputMountPointRootContainerPath, SETUP_WRAPUP_INPUT_PATH))
                        .collect(Collectors.toList()))
                .build();
    }

    public static ResolvedCommandMount specialInputSingleMountPoint(final String xnatHostPath, final String containerHostPath) {
        return specialInput(Collections.singletonList(new MountPoint(xnatHostPath, containerHostPath, null)), null);
    }

    public static ResolvedCommandMount output(final String name, final String xnatHostPath, final String containerHostPath, final String containerPath) {
        return ResolvedCommandMount.builder()
                .name(name)
                .writable(true)
                .rootContainerPath(containerPath)
                .mountPoints(Collections.singletonList(new MountPoint(xnatHostPath, containerHostPath, containerPath)))
                .build();
    }

    public static ResolvedCommandMount specialOutput(final String xnatHostPath, final String containerHostPath) {
        return output(OUTPUT, xnatHostPath, containerHostPath, SETUP_WRAPUP_OUTPUT_PATH);
    }

    public static Builder builder() {
        return new AutoValue_ResolvedCommandMount.Builder();
    }

    public abstract Builder toBuilder();

    @AutoValue.Builder
    public abstract static class Builder {
        public abstract Builder name(String name);
        public abstract Builder writable(Boolean writable);
        public abstract Builder rootContainerPath(String rootContainerPath);
        public abstract Builder viaSetupCommand(String viaSetupCommand);
        public abstract Builder mountPoints(List<MountPoint> mountPoints);

        public abstract ResolvedCommandMount build();
    }
}
