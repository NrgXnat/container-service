package org.nrg.containers.events.model;

import com.google.auto.value.AutoValue;
import org.nrg.framework.event.EventI;
import org.nrg.xdat.om.XnatImagesessiondata;
import org.nrg.xft.security.UserI;

@AutoValue
public abstract class SessionMergeOrArchiveEvent implements EventI {
    public abstract XnatImagesessiondata session();
    public abstract String project();
    public abstract UserI user();
    public abstract String eventId();

    public static SessionMergeOrArchiveEvent create(final XnatImagesessiondata session,
                                                    final String project,
                                                    final UserI user,
                                                    final String eventId) {
        return new AutoValue_SessionMergeOrArchiveEvent(session, project, user, eventId);
    }

    public static SessionMergeOrArchiveEvent create(final XnatImagesessiondata session,
                                                    final UserI userI,
                                                    final String eventId) {
        return create(session, session.getProject(), userI, eventId);
    }
}