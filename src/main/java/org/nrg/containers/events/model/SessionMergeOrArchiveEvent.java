package org.nrg.containers.events.model;

import com.google.auto.value.AutoValue;
import org.apache.commons.lang3.StringUtils;
import org.nrg.framework.event.EventI;
import org.nrg.xdat.om.XnatImagesessiondata;
import org.nrg.xft.security.UserI;

@AutoValue
public abstract class SessionMergeOrArchiveEvent implements EventI {
    private static final long serialVersionUID = 5840070113252303212L;

    public static final String QUEUE = "sessionMergeOrArchiveEventQueue";

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
        if (session == null) {
            throw new IllegalArgumentException("Session cannot be null.");
        }
        if (StringUtils.isBlank(session.getProject())) {
            throw new IllegalArgumentException("Session must have an associated project: session " + session.getId() + " does not.");
        }
        return create(session, session.getProject(), userI, eventId);
    }
}