package org.nrg.containers.model.xnat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Function;
import com.google.common.base.MoreObjects;
import com.google.common.collect.Lists;
import org.apache.commons.lang.StringUtils;
import org.nrg.containers.model.command.entity.CommandWrapperInputType;
import org.nrg.xdat.model.XnatAbstractresourceI;
import org.nrg.xdat.model.XnatExperimentdataI;
import org.nrg.xdat.model.XnatImagesessiondataI;
import org.nrg.xdat.model.XnatSubjectdataI;
import org.nrg.xdat.om.XnatResourcecatalog;
import org.nrg.xdat.om.XnatSubjectdata;
import org.nrg.xft.XFTItem;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.helpers.uri.URIManager;
import org.nrg.xnat.helpers.uri.UriParserUtils;
import org.nrg.xnat.helpers.uri.archive.SubjectURII;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@JsonInclude(Include.NON_NULL)
public class Subject extends XnatModelObject {

    @JsonIgnore private XnatSubjectdataI xnatSubjectdataI;
    private List<Session> sessions;
    private List<Resource> resources;
    @JsonProperty("project-id") private String projectId;

    public Subject() {}

    public Subject(final String subjectId, final UserI userI, final boolean loadFiles,
                   @Nonnull final Set<String> loadTypes) {
        this.id = subjectId;
        loadXnatSubjectdataI(userI);
        this.uri = UriParserUtils.getArchiveUri(xnatSubjectdataI);
        populateProperties(null, loadFiles, loadTypes);
    }

    public Subject(final SubjectURII subjectURII, final boolean loadFiles, @Nonnull final Set<String> loadTypes) {
        this.xnatSubjectdataI = subjectURII.getSubject();
        this.uri = ((URIManager.DataURIA) subjectURII).getUri();
        populateProperties(null, loadFiles, loadTypes);
    }

    public Subject(final XnatSubjectdataI xnatSubjectdataI, final boolean loadFiles, @Nonnull final Set<String> loadTypes) {
        this(xnatSubjectdataI, loadFiles, loadTypes, null, null);
    }

    public Subject(final XnatSubjectdataI xnatSubjectdataI, final boolean loadFiles,
                   @Nonnull final Set<String> loadTypes, final String parentUri, final String rootArchivePath) {
        this.xnatSubjectdataI = xnatSubjectdataI;
        if (parentUri == null) {
            this.uri = UriParserUtils.getArchiveUri(xnatSubjectdataI);
        } else {
            this.uri = parentUri + "/subjects/" + xnatSubjectdataI.getId();
        }
        populateProperties(rootArchivePath, loadFiles, loadTypes);
    }

    private void populateProperties(final String rootArchivePath, final boolean loadFiles,
                                    @Nonnull final Set<String> loadTypes) {
        this.id = xnatSubjectdataI.getId();
        this.label = xnatSubjectdataI.getLabel();
        this.xsiType = xnatSubjectdataI.getXSIType();
        this.projectId = xnatSubjectdataI.getProject();

        this.sessions = Lists.newArrayList();
        if (loadTypes.contains(CommandWrapperInputType.SESSION.getName())) {
            for (final XnatExperimentdataI xnatExperimentdataI : xnatSubjectdataI.getExperiments_experiment()) {
                if (xnatExperimentdataI instanceof XnatImagesessiondataI) {
                    sessions.add(new Session((XnatImagesessiondataI) xnatExperimentdataI, loadFiles,
                            loadTypes, this.uri, rootArchivePath));
                }
            }
        }

        this.resources = Lists.newArrayList();
        if (loadFiles || loadTypes.contains(CommandWrapperInputType.RESOURCE.getName())) {
            for (final XnatAbstractresourceI xnatAbstractresourceI : xnatSubjectdataI.getResources_resource()) {
                if (xnatAbstractresourceI instanceof XnatResourcecatalog) {
                    resources.add(new Resource((XnatResourcecatalog) xnatAbstractresourceI, loadFiles,
                            loadTypes, this.uri, rootArchivePath));
                }
            }
        }
    }

    public static Function<URIManager.ArchiveItemURI, Subject> uriToModelObject(final boolean loadFiles,
                                                                                @Nonnull final Set<String> loadTypes) {
        return new Function<URIManager.ArchiveItemURI, Subject>() {
            @Nullable
            @Override
            public Subject apply(@Nullable URIManager.ArchiveItemURI uri) {
                if (uri != null &&
                        SubjectURII.class.isAssignableFrom(uri.getClass())) {
                    return new Subject((SubjectURII) uri, loadFiles, loadTypes);
                }

                return null;
            }
        };
    }

    public static Function<String, Subject> idToModelObject(final UserI userI, final boolean loadFiles,
                                                            @Nonnull final Set<String> loadTypes) {
        return new Function<String, Subject>() {
            @Nullable
            @Override
            public Subject apply(@Nullable String s) {
                if (StringUtils.isBlank(s)) {
                    return null;
                }
                final XnatSubjectdata xnatSubjectdata = XnatSubjectdata.getXnatSubjectdatasById(s, userI, true);
                if (xnatSubjectdata != null) {
                    return new Subject(xnatSubjectdata, loadFiles, loadTypes);
                }
                return null;
            }
        };
    }

    public Project getProject(final UserI userI, final boolean loadFiles, @Nonnull final Set<String> loadTypes) {
        loadXnatSubjectdataI(userI);
        return new Project(xnatSubjectdataI.getProject(), userI, loadFiles, loadTypes);
    }

    public void loadXnatSubjectdataI(final UserI userI) {
        if (xnatSubjectdataI == null) {
            xnatSubjectdataI = XnatSubjectdata.getXnatSubjectdatasById(id, userI, false);
        }
    }

    public XnatSubjectdataI getXnatSubjectdataI() {
        return xnatSubjectdataI;
    }

    public void setXnatSubjectdataI(final XnatSubjectdataI xnatSubjectdataI) {
        this.xnatSubjectdataI = xnatSubjectdataI;
    }

    public List<Session> getSessions() {
        return sessions;
    }

    public void setSessions(final List<Session> sessions) {
        this.sessions = sessions;
    }

    public List<Resource> getResources() {
        return resources;
    }

    public void setResources(final List<Resource> resources) {
        this.resources = resources;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(final String projectId) {
        this.projectId = projectId;
    }

    @Override
    public XFTItem getXftItem(final UserI userI) {
        loadXnatSubjectdataI(userI);
        return xnatSubjectdataI == null ? null : ((XnatSubjectdata)xnatSubjectdataI).getItem();
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        final Subject that = (Subject) o;
        return Objects.equals(this.xnatSubjectdataI, that.xnatSubjectdataI) &&
                Objects.equals(this.sessions, that.sessions) &&
                Objects.equals(this.resources, that.resources) &&
                Objects.equals(this.projectId, that.projectId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), xnatSubjectdataI, sessions, resources, projectId);
    }

    @Override
    public String toString() {
        return addParentPropertiesToString(MoreObjects.toStringHelper(this))
                .add("sessions", sessions)
                .add("resources", resources)
                .add("projectId", projectId)
                .toString();
    }
}
