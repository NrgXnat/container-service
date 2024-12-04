package org.nrg.containers.events.model;

import com.google.auto.value.AutoAnnotation;
import com.google.auto.value.AutoValue;
import org.nrg.containers.model.xnat.Scan;
import org.nrg.framework.event.EventI;
import org.nrg.xft.security.UserI;

import java.util.HashSet;

@AutoValue
public abstract class ScanArchiveEventToLaunchCommands implements EventI {
    private static final long serialVersionUID = 6052791990440957304L;

    public static final String QUEUE = "scanArchiveEventToLaunchCommandsQueue";

    public abstract Scan scan();
    public abstract String project();
    public abstract UserI user();

    public static ScanArchiveEventToLaunchCommands create(final Scan scan,
                                                          final String project,
                                                          final UserI user) {
        return new AutoValue_ScanArchiveEventToLaunchCommands(scan, project, user);
    }

    public static ScanArchiveEventToLaunchCommands create(final Scan scan,
                                                          final UserI user) {
        return create(scan, scan.getProject(user, false, new HashSet<>()).getId(), user);
    }
}