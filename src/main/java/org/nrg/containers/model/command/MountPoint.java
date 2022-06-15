package org.nrg.containers.model.command;

import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.nio.file.Paths;
import java.util.Objects;

@Slf4j
public class MountPoint {
    @Nullable private final String xnatHostPath;
    @Nullable private final String containerHostPath;
    @Nullable private final String containerPath;

    public MountPoint(@Nullable String xnatHostPath, @Nullable String containerHostPath, @Nullable String containerPath) {
        this.xnatHostPath = xnatHostPath;
        this.containerHostPath = containerHostPath;
        this.containerPath = containerPath;
    }

    public MountPoint newWithSwappedContainerPathPrefix(@Nullable String existingContainerPathPrefix,
                                                        @Nullable String newContainerPathPrefix) {
        newContainerPathPrefix = newContainerPathPrefix == null ? "" : newContainerPathPrefix;
        final String newContainerPath;
        if (containerPath == null) {
            newContainerPath = newContainerPathPrefix;
        } else if (existingContainerPathPrefix == null) {
            newContainerPath = Paths.get(newContainerPathPrefix, containerPath).toString();
        } else {
            newContainerPath = containerPath.replace(existingContainerPathPrefix, newContainerPathPrefix);
        }
        return new MountPoint(xnatHostPath, containerHostPath, newContainerPath);
    }

    @Nullable
    public String getContainerPath() {
        return containerPath;
    }

    public boolean isOpaqueOverlay() {
        return xnatHostPath == null;
    }

    @Nullable
    public String getXnatHostPath() {
        return xnatHostPath;
    }

    @Nullable
    public String getContainerHostPath() {
        return containerHostPath;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MountPoint that = (MountPoint) o;
        return Objects.equals(xnatHostPath, that.xnatHostPath) &&
                Objects.equals(containerHostPath, that.containerHostPath) &&
                Objects.equals(containerPath, that.containerPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(xnatHostPath, containerHostPath, containerPath);
    }

    @Override
    public String toString() {
        return "MountPoint{" +
                "xnatHostPath=" + xnatHostPath +
                ", containerHostPath=" + containerHostPath +
                ", containerPath=" + containerPath +
                '}';
    }
}
