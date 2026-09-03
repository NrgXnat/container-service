package org.nrg.containers.model.container;

import lombok.AllArgsConstructor;
import lombok.Getter;

import javax.annotation.Nullable;
import java.util.Date;

/**
 * A projection row for build directory cleanup: one container, optionally paired with one of its build-path mounts.
 *
 * A row with a null buildDirPath is a launch group member with no build mount of its own. Those rows matter: a
 * still-running main container often has none, and its status is what makes the group unsafe to clean up.
 *
 * Not a sparse ContainerEntity, because ContainerEntity#setStatus rewrites values through its status mapping.
 */
@Getter
@AllArgsConstructor
public class ContainerBuildDirRow {

    private final long containerId;

    /** The parent's id, or this container's own id when it has no parent. */
    private final long rootId;

    /** Status as stored, e.g. "Complete", "Failed (Setup)", "Running". Null on some old rows. */
    @Nullable
    private final String status;

    @Nullable
    private final Date statusTime;

    /** Mount path under the site build root as the XNAT JVM sees it, or null if this row has no build mount. */
    @Nullable
    private final String buildDirPath;

    public boolean hasBuildDirPath() {
        return buildDirPath != null;
    }
}
