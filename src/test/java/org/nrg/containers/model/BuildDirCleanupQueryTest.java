package org.nrg.containers.model;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nrg.containers.config.ContainerEntityTestConfig;
import org.nrg.containers.model.container.ContainerBuildDirRow;
import org.nrg.containers.model.container.entity.ContainerEntity;
import org.nrg.containers.model.container.entity.ContainerEntityMount;
import org.nrg.containers.services.ContainerEntityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

/**
 * The two Criteria queries that back build directory cleanup, covering only the ways a wrong query would lead to a
 * wrong deletion. Query A's range and prefix filters are deliberately not tested: narrowing them can only shrink
 * the candidate set, and the Java launch group rule remains authoritative over whatever they return.
 *
 * Requires Docker, because ContainerEntityTestConfig starts Postgres through Testcontainers.
 */
@RunWith(SpringJUnit4ClassRunner.class)
@Transactional
@ContextConfiguration(classes = ContainerEntityTestConfig.class)
public class BuildDirCleanupQueryTest {

    private static final String BUILD_ROOT   = "/data/xnat/build";
    private static final String BUILD_PREFIX = BUILD_ROOT + "/%";
    private static final String ARCHIVE_PATH = "/data/xnat/archive/PROJ/arc001/file.dcm";

    @Autowired private ContainerEntityService containerEntityService;

    private Date windowStart;
    private Date windowEnd;

    @Before
    public void setUp() {
        windowStart = daysAgo(365);
        windowEnd   = daysAgo(1);
    }

    private static Date daysAgo(final int days) {
        return new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days));
    }

    private static String buildPath() {
        return BUILD_ROOT + "/" + UUID.randomUUID();
    }

    /** Persist a container. A null parent means this is a launchGroup root. */
    private ContainerEntity save(final String status,
                                 final Date statusTime,
                                 final ContainerEntity parent,
                                 final String... mountPaths) {
        final ContainerEntity entity = new ContainerEntity();
        entity.setDockerImage("xnat/test:1.0");
        entity.setCommandLine("echo hi");
        entity.setStatus(status);
        entity.setStatusTime(statusTime);
        entity.setParentContainerEntity(parent);

        final List<ContainerEntityMount> mounts = new ArrayList<>();
        for (final String path : mountPaths) {
            final ContainerEntityMount mount = new ContainerEntityMount();
            mount.setName("mount-" + mounts.size());
            mount.setWritable(true);
            mount.setXnatHostPath(path);
            mount.setContainerHostPath(path);
            mount.setContainerPath("/output");
            mounts.add(mount);
        }
        entity.setMounts(mounts);

        final ContainerEntity created = containerEntityService.create(entity);
        containerEntityService.flush();
        return created;
    }

    private List<ContainerBuildDirRow> launchGroupRows(final Long... rootIds) {
        return containerEntityService.retrieveBuildDirRowsForLaunchGroups(Arrays.asList(rootIds), BUILD_PREFIX);
    }

    private static Set<Long> rootIdsOf(final List<ContainerBuildDirRow> rows) {
        return rows.stream().map(ContainerBuildDirRow::getRootId).collect(Collectors.toSet());
    }

    /**
     * The ON clause regression, and the one query mistake that would reintroduce the core hazard. Only the setup
     * child has a build mount; the parent has none, and is still Running. Moving the mount predicate from the
     * join's ON clause into WHERE would drop the parent row, the launch group would look all-terminal, and the
     * directory the parent is reading from would be deleted.
     *
     * The archive mount on the child proves the same predicate still filters paths off the returned row rather
     * than off the row itself.
     */
    @Test
    @DirtiesContext
    public void queryBReturnsEveryLaunchGroupMemberEvenWithoutABuildMount() {
        final String childPath = buildPath();
        final ContainerEntity parent = save("Running", daysAgo(30), null);                    // no mounts at all
        final ContainerEntity child  = save("Complete", daysAgo(30), parent, childPath);
        final ContainerEntity archiveOnly = save("Complete", daysAgo(30), parent, ARCHIVE_PATH);

        final List<ContainerBuildDirRow> rows = launchGroupRows(parent.getId());

        assertThat(rows, hasSize(3));
        assertThat(rows.stream().map(ContainerBuildDirRow::getContainerId).collect(Collectors.toSet()),
                is(new HashSet<>(Arrays.asList(parent.getId(), child.getId(), archiveOnly.getId()))));
        assertThat("all three rows share the launch group root",
                rootIdsOf(rows), is(Collections.singleton(parent.getId())));

        assertThat("parent has no build mount but must still be returned",
                rowFor(rows, parent).hasBuildDirPath(), is(false));
        assertThat("the parent's non-terminal status is what defers the group",
                rowFor(rows, parent).getStatus(), is("Running"));
        assertThat(rowFor(rows, child).getBuildDirPath(), is(childPath));
        assertThat("an archive mount is filtered off the row, not the row off the result",
                rowFor(rows, archiveOnly).hasBuildDirPath(), is(false));
    }

    /**
     * Query B must not inherit Query A's time window. A member stuck outside the lookback window would otherwise
     * be invisible, and its launch group would be wrongly judged all-terminal.
     */
    @Test
    @DirtiesContext
    public void queryBIsNotBoundedByTheTimeWindow() {
        final ContainerEntity parent = save("Running", daysAgo(500), null);
        save("Complete", daysAgo(30), parent, buildPath());

        final List<ContainerBuildDirRow> rows = launchGroupRows(parent.getId());

        assertThat(rows, hasSize(2));
        assertThat(rows.stream().anyMatch(r -> "Running".equals(r.getStatus())), is(true));
    }

    /**
     * A child's rootId must resolve to its parent. If a child were reported as its own launch group root, it would
     * be evaluated alone and deleted without its parent's status ever being consulted.
     */
    @Test
    @DirtiesContext
    public void queryAAttributesAChildToItsParentAsLaunchGroupRoot() {
        final ContainerEntity parent = save("Complete", daysAgo(30), null);
        final ContainerEntity child  = save("Complete", daysAgo(31), parent, buildPath());

        final List<ContainerBuildDirRow> rows =
                containerEntityService.retrieveBuildDirCandidates(windowStart, windowEnd, BUILD_PREFIX);

        assertThat(rows, hasSize(1));
        assertThat(rows.get(0).getContainerId(), is(child.getId()));
        assertThat(rows.get(0).getRootId(), is(parent.getId()));
    }

    private static ContainerBuildDirRow rowFor(final List<ContainerBuildDirRow> rows, final ContainerEntity entity) {
        return rows.stream()
                .filter(r -> r.getContainerId() == entity.getId())
                .findFirst()
                .orElseThrow(AssertionError::new);
    }
}
