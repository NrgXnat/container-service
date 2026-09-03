package org.nrg.containers.utils;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

/**
 * The safety contract for deletion. Every test here exists because its failure would mean deleting something that
 * must not be deleted; robustness and reporting behaviour is deliberately not covered, because those failures
 * abort a run rather than destroy data.
 */
public class BuildDirectoryDeleterTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path buildRoot;
    private Path archive;
    private String uuid;

    @Before
    public void setUp() throws IOException {
        buildRoot = tmp.newFolder("build").toPath().toAbsolutePath().normalize();
        archive   = tmp.newFolder("archive").toPath().toAbsolutePath().normalize();
        uuid      = UUID.randomUUID().toString();
    }

    private Path resolve(final String xnatHostPath) {
        return BuildDirectoryDeleter.resolveBuildDirToDelete(xnatHostPath, buildRoot);
    }

    // ---------- what may be deleted ----------

    /**
     * The positive control. Without it every rejection test below would still pass against a resolver that
     * always returns null, and cleanup would silently never delete anything.
     */
    @Test
    public void resolvesTheBuildDirItself() throws IOException {
        final Path dir = Files.createDirectory(buildRoot.resolve(uuid));
        assertThat(resolve(dir.toString()), is(equalTo(dir)));
    }

    /**
     * Deletion always targets the UUID directory, never the recorded path itself. Load-bearing because it widens
     * the target: when a mount source is a single file, CommandResolutionServiceImpl persists xnatHostPath as the
     * file inside the build directory, and we must still remove the directory.
     */
    @Test
    public void resolvesDeeperPathsToTheBuildDir() throws IOException {
        final Path dir = Files.createDirectory(buildRoot.resolve(uuid));
        Files.createDirectory(dir.resolve("sub"));
        Files.createFile(dir.resolve("sub").resolve("file.txt"));
        Files.createFile(dir.resolve("image.dcm"));

        assertThat(resolve(dir.resolve("sub").resolve("file.txt").toString()), is(equalTo(dir)));
        assertThat(resolve(dir.resolve("image.dcm").toString()), is(equalTo(dir)));
    }

    // ---------- what may not ----------

    /** The most destructive single case: never the build root itself. */
    @Test
    public void refusesTheBuildRootItself() {
        assertThat(resolve(buildRoot.toString()), is(nullValue()));
    }

    /**
     * Containment is element-wise and applied after normalize(), so a traversal, an unrelated absolute path, and a
     * sibling whose name merely starts with the build root's name are all refused. A String.startsWith check would
     * accept the last of these.
     */
    @Test
    public void refusesAnythingOutsideTheBuildRoot() throws IOException {
        final Path sibling = tmp.newFolder("build-other").toPath().toAbsolutePath().normalize();

        assertThat("archive path", resolve(archive.resolve("PROJ").resolve("arc001").toString()), is(nullValue()));
        assertThat("parent escape", resolve(buildRoot.resolve("..").resolve("archive").toString()), is(nullValue()));
        assertThat("deep escape", resolve(buildRoot + "/../../etc"), is(nullValue()));
        assertThat("name-prefixed sibling", resolve(sibling.resolve(uuid).toString()), is(nullValue()));
        assertThat("relative path", resolve("build/" + uuid), is(nullValue()));
        assertThat("bare name", resolve(uuid), is(nullValue()));
        assertThat(resolve(null), is(nullValue()));
        assertThat(resolve(""), is(nullValue()));
        assertThat(resolve("   "), is(nullValue()));
    }

    /**
     * Only UUID-named immediate children are ours. XNAT core writes project-named directories directly under the
     * build root, and this is what keeps them out of reach. isBuildDirName is the same predicate the service
     * applies to its directory listing, so both callers are covered here.
     */
    @Test
    public void refusesNonUuidNames() {
        assertThat(resolve(buildRoot.resolve("PROJECT_ID").toString()), is(nullValue()));
        assertThat(resolve(buildRoot.resolve("container-service").resolve("x").toString()), is(nullValue()));
        assertThat(resolve(buildRoot.resolve(uuid + "-extra").toString()), is(nullValue()));

        assertThat(BuildDirectoryDeleter.isBuildDirName(uuid), is(true));
        assertThat(BuildDirectoryDeleter.isBuildDirName(uuid.toUpperCase()), is(true));
        assertThat(BuildDirectoryDeleter.isBuildDirName("PROJECT_ID"), is(false));
        assertThat(BuildDirectoryDeleter.isBuildDirName(uuid + "x"), is(false));
        assertThat(BuildDirectoryDeleter.isBuildDirName(null), is(false));
    }

    // ---------- deleteRecursively ----------

    @Test
    public void deletesTheWholeTree() throws IOException {
        final Path dir = Files.createDirectory(buildRoot.resolve(uuid));
        Files.write(dir.resolve("a.txt"), new byte[]{1, 2, 3});
        final Path sub = Files.createDirectory(dir.resolve("sub"));
        Files.write(sub.resolve("b.txt"), new byte[]{4, 5});

        final BuildDirectoryDeleter.DeleteResult result = BuildDirectoryDeleter.deleteRecursively(dir);

        assertThat(Files.exists(dir), is(false));
        assertThat(result.isComplete(), is(true));
    }

    /**
     * The regression that protects the archive. A container may write symlinks into its output mount; the walk runs
     * without FOLLOW_LINKS, so each link is unlinked rather than traversed and its target survives.
     */
    @Test
    public void unlinksSymlinksInsteadOfFollowingThemOutOfTheTree() throws IOException {
        final Path outsideDir  = Files.createDirectory(archive.resolve("precious"));
        final Path outsideFile = Files.write(outsideDir.resolve("scan.dcm"), new byte[]{9});
        final Path relTarget   = Files.write(archive.resolve("target.txt"), new byte[]{3});

        final Path dir = Files.createDirectory(buildRoot.resolve(uuid));
        try {
            Files.createSymbolicLink(dir.resolve("link-to-dir"), outsideDir);
            Files.createSymbolicLink(dir.resolve("link-to-file"), outsideFile);
            Files.createSymbolicLink(dir.resolve("rel"), Paths.get("..", "..", "archive", "target.txt"));
        } catch (IOException | UnsupportedOperationException e) {
            Assume.assumeNoException("Filesystem does not support symlinks", e);
        }

        final BuildDirectoryDeleter.DeleteResult result = BuildDirectoryDeleter.deleteRecursively(dir);

        assertThat(Files.exists(dir), is(false));
        assertThat(result.isComplete(), is(true));
        assertThat(Files.isDirectory(outsideDir), is(true));
        assertThat(Files.exists(outsideFile), is(true));
        assertThat(Files.exists(relTarget), is(true));
    }

    /** Same guarantee when the build directory entry is itself a symlink rather than containing one. */
    @Test
    public void unlinksASymlinkFoundWhereABuildDirWasExpected() throws IOException {
        final Path outsideDir = Files.createDirectory(archive.resolve("elsewhere"));
        final Path link = buildRoot.resolve(uuid);
        try {
            Files.createSymbolicLink(link, outsideDir);
        } catch (IOException | UnsupportedOperationException e) {
            Assume.assumeNoException("Filesystem does not support symlinks", e);
        }

        final BuildDirectoryDeleter.DeleteResult result = BuildDirectoryDeleter.deleteRecursively(link);

        assertThat(Files.exists(link), is(false));
        assertThat(result.isComplete(), is(true));
        assertThat(Files.isDirectory(outsideDir), is(true));
    }
}
