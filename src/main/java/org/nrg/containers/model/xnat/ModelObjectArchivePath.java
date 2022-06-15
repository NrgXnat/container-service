package org.nrg.containers.model.xnat;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ModelObjectArchivePath {
    public ModelObjectArchivePath(@Nonnull XnatModelObject sourceObject, @Nonnull String path, @Nullable String relativePath, boolean isOpaqueOverlay) {
        this.sourceObject = sourceObject;
        this.path = path;
        this.relativePath = relativePath;
        this.isOpaqueOverlay = isOpaqueOverlay;
    }

    public ModelObjectArchivePath(XnatModelObject sourceObject, String path, String relativePath) {
        this(sourceObject, path, relativePath, false);
    }

    @Nonnull
    public XnatModelObject getSourceObject() {
        return sourceObject;
    }

    @Nonnull
    public String getPath() {
        return path;
    }

    @Nonnull
    public Path getPathObj() {
        return Paths.get(path);
    }

    @Nonnull
    public File getPathFile() {
        return getPathObj().toFile();
    }

    @Nullable
    public String getRelativePath() {
        return relativePath;
    }

    public boolean isOpaqueOverlay() {
        return isOpaqueOverlay;
    }

    private @Nonnull final XnatModelObject sourceObject;
    private @Nonnull final String path;
    private @Nullable final String relativePath;
    private final boolean isOpaqueOverlay;
}
