package org.nrg.containers.utils;

import lombok.extern.slf4j.Slf4j;
import org.nrg.framework.utilities.Patterns;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;

/**
 * Path safety and recursive deletion for Container Service build directories, which are UUID-named immediate
 * children of the site build path (see CommandResolutionServiceImpl#getBuildDirectory). The only place that decides
 * whether a path may be deleted, and the only place that deletes it.
 *
 * For use cases, see tests in org.nrg.containers.utils.BuildDirectoryDeleterTest.
 */
@Slf4j
public class BuildDirectoryDeleter {

    /**
     * The build root is shared with XNAT core, which creates project-named directories directly under it, so
     * requiring a bare UUID is the strongest guard available.
     */

    private BuildDirectoryDeleter() {}

    /** Whether a single file name (not a path) is a bare UUID, i.e. looks like a build directory. */
    public static boolean isBuildDirName(final @Nullable String name) {
        return name != null && Patterns.UUID.matcher(name).matches();
    }

    /**
     * Resolve a persisted mount path to the build directory that may be deleted for it, or null if the path must
     * not be touched. Always returns the UUID-named child of the build root, never the path itself: when a mount
     * source is a single file, xnatHostPath points at that file *inside* the build directory.
     *
     * @param xnatHostPath the mount path as persisted on ContainerEntityMount
     * @param buildRoot    absolute site build path
     */
    @Nullable
    public static Path resolveBuildDirToDelete(final @Nullable String xnatHostPath,
                                               final Path buildRoot) {
        if (StringUtils.isBlank(xnatHostPath)) {
            return null;
        }

        final Path candidate;
        try {
            candidate = Paths.get(xnatHostPath);
        } catch (InvalidPathException e) {
            log.warn("Not a usable path, refusing to delete: \"{}\"", xnatHostPath);
            return null;
        }

        if (!candidate.isAbsolute()) {
            return null;
        }

        // Normalize before comparing, so "<build>/../../etc" cannot masquerade as being under the root.
        final Path normalizedRoot = buildRoot.normalize();
        final Path normalized = candidate.normalize();

        // startsWith is element-wise, so "/data/xnat/build-other/x" does not match root "/data/xnat/build".
        if (!normalized.startsWith(normalizedRoot) || normalized.equals(normalizedRoot)) {
            return null;
        }

        final Path relative = normalizedRoot.relativize(normalized);
        if (relative.getNameCount() < 1) {
            return null;
        }

        // Taking only the first element bounds deletion to exactly one level below the root.
        final String firstElement = relative.getName(0).toString();
        if (!isBuildDirName(firstElement)) {
            return null;
        }
        return normalizedRoot.resolve(firstElement);
    }

    /**
     * Recursively delete a build directory, reclaiming as much as possible and reporting what it could not remove.
     *
     * Walked without FOLLOW_LINKS, so a symlinked directory is reported as a file and unlinked rather than
     * traversed: a container that writes a symlink into its output mount cannot cause anything outside the build
     * directory to be deleted. Individual failures are recorded and the walk continues, so a directory owned by
     * another user (containers commonly run as root) yields a partial delete and is retried on the next run.
     *
     * @throws IOException if the walk itself cannot be started
     */
    public static DeleteResult deleteRecursively(final Path dir) throws IOException {
        final DeleteResult result = new DeleteResult();

        if (Files.notExists(dir, LinkOption.NOFOLLOW_LINKS)) {
            result.alreadyGone = true;
            return result;
        }

        // A symlink where we expected a build directory: unlink it, never traverse into it.
        if (Files.isSymbolicLink(dir)) {
            try {
                Files.delete(dir);
                result.filesDeleted++;
            } catch (IOException e) {
                result.recordFailure(dir, e);
            }
            return result;
        }

        Files.walkFileTree(dir, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
                        final long size = attrs.isRegularFile() ? attrs.size() : 0L;
                        try {
                            Files.delete(file);
                            result.filesDeleted++;
                            result.bytesFreed += size;
                        } catch (IOException e) {
                            result.recordFailure(file, e);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(final Path file, final IOException e) {
                        result.recordFailure(file, e);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(final Path directory, final IOException e) {
                        if (e != null) {
                            result.recordFailure(directory, e);
                        }
                        try {
                            Files.delete(directory);
                        } catch (IOException ex) {
                            result.recordFailure(directory, ex);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });

        return result;
    }

    /**
     * What a single {@link #deleteRecursively(Path)} call accomplished.
     */
    public static class DeleteResult {
        private int     filesDeleted;
        private long    bytesFreed;
        private int     failures;
        private int     accessDenied;
        private int     alreadyDeleted;
        private boolean alreadyGone;

        public int getFilesDeleted()   { return filesDeleted; }
        public long getBytesFreed()    { return bytesFreed; }
        public int getFailures()       { return failures; }
        public int getAccessDenied()   { return accessDenied; }
        public int getAlreadyDeleted() { return alreadyDeleted; }
        public boolean isAlreadyGone() { return alreadyGone; }

        /** True when nothing was left behind, i.e. the build directory is entirely gone. */
        public boolean isComplete() {
            return failures == 0;
        }

        /**
         * NoSuchFileException means another sweep removed the entry first, which the schedule permits by design.
         * Counting it as a failure would leave isComplete() false, so a directory that is entirely gone would be
         * reported as only partially removed.
         *
         * Package-private so the classification can be tested without provoking a real race.
         */
        void recordFailure(final Path path, final IOException e) {
            if (e instanceof NoSuchFileException) {
                alreadyDeleted++;
                log.trace("{} was already gone while removing build directory", path);
                return;
            }
            failures++;
            if (e instanceof AccessDeniedException) {
                accessDenied++;
            }
            log.debug("Could not delete {} while removing build directory", path, e);
        }
    }
}
