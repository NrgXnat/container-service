package org.nrg.containers.model.xnat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import org.apache.commons.lang.StringUtils;
import org.nrg.containers.model.command.entity.CommandWrapperInputType;
import org.nrg.xdat.model.XnatAbstractresourceI;
import org.nrg.xdat.model.XnatExperimentdataI;
import org.nrg.xdat.model.XnatSubjectassessordataI;
import org.nrg.xdat.model.XnatSubjectdataI;
import org.nrg.xdat.om.XnatImagesessiondata;
import org.nrg.xdat.om.XnatResourcecatalog;
import org.nrg.xdat.om.XnatSubjectassessordata;
import org.nrg.xdat.om.XnatSubjectdata;
import org.nrg.xft.XFTItem;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.helpers.uri.URIManager;
import org.nrg.xnat.helpers.uri.UriParserUtils;
import org.nrg.xnat.helpers.uri.archive.SubjectURII;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@JsonInclude(Include.NON_NULL)
public class Subject extends XnatModelObject {

    @JsonIgnore private XnatSubjectdataI xnatSubjectdataI;
    private List<Session> sessions;
    private List<Resource> resources;
    @JsonProperty("subject-assessors")  private List<SubjectAssessor> subjectAssessors;
    @JsonProperty("project-id") private String projectId;
    @JsonProperty("datatype-string") private String datatypeString;
    @JsonProperty("group") private String group;
    @JsonProperty("source") private String source;
    @JsonProperty("initials") private String initials;

    @JsonIgnore private Project project = null;

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
        this.group = xnatSubjectdataI.getGroup();
        this.source = xnatSubjectdataI.getSrc();
        this.initials = xnatSubjectdataI.getInitials();
        this.directory = null;

        this.sessions = new ArrayList<>();
        if (loadTypes.contains(CommandWrapperInputType.SESSION.getName())) {
            for (final XnatExperimentdataI xnatExperimentdataI : xnatSubjectdataI.getExperiments_experiment()) {
                if (xnatExperimentdataI instanceof XnatImagesessiondata) {
                    sessions.add(new Session((XnatImagesessiondata) xnatExperimentdataI, loadFiles,
                            loadTypes, this.uri, rootArchivePath));
                }
            }
        }

        this.subjectAssessors = new ArrayList<>();
        if (loadTypes.contains(CommandWrapperInputType.SUBJECT_ASSESSOR.getName())) {
            for (final XnatSubjectassessordataI xnatExperimentdataI : xnatSubjectdataI.getExperiments_experiment()) {
                if (xnatExperimentdataI instanceof XnatSubjectassessordata) {
                    subjectAssessors.add(new SubjectAssessor(xnatExperimentdataI, loadFiles,
                            loadTypes, this.uri, rootArchivePath));
                }
            }
        }

        this.resources = new ArrayList<>();
        if (loadFiles || loadTypes.contains(CommandWrapperInputType.RESOURCE.getName())) {
            for (final XnatAbstractresourceI xnatAbstractresourceI : xnatSubjectdataI.getResources_resource()) {
                if (xnatAbstractresourceI instanceof XnatResourcecatalog) {
                    resources.add(new Resource((XnatResourcecatalog) xnatAbstractresourceI, loadFiles,
                            loadTypes, this.uri, rootArchivePath));
                }
            }
        }
        datatypeString = null;
        if(loadTypes.contains(CommandWrapperInputType.STRING.getName()) && xnatSubjectdataI != null){
            try {
                datatypeString = xnatSubjectdataI.toString();
            } catch (Throwable e){ }
        }
    }

    public static Function<URIManager.ArchiveItemURI, Subject> uriToModelObject(final boolean loadFiles,
                                                                                @Nonnull final Set<String> loadTypes) {
        return uri -> {
            if (uri != null &&
                    SubjectURII.class.isAssignableFrom(uri.getClass())) {
                return new Subject((SubjectURII) uri, loadFiles, loadTypes);
            }

            return null;
        };
    }

    public static Function<String, Subject> idToModelObject(final UserI userI, final boolean loadFiles,
                                                            @Nonnull final Set<String> loadTypes) {
        return s -> {
            if (StringUtils.isBlank(s)) {
                return null;
            }
            final XnatSubjectdata xnatSubjectdata = XnatSubjectdata.getXnatSubjectdatasById(s, userI, true);
            if (xnatSubjectdata != null) {
                return new Subject(xnatSubjectdata, loadFiles, loadTypes);
            }
            return null;
        };
    }

    public Project getProject(final UserI userI, final boolean loadFiles, @Nonnull final Set<String> loadTypes) {
        loadProject(userI, loadFiles, loadTypes);
        return project;
    }

    private void loadProject(final UserI userI, final boolean loadFiles, @Nonnull final Set<String> loadTypes) {
        if (project == null) {
            loadProjectId(userI);
            project = new Project(projectId, userI, loadFiles, loadTypes);
        }
    }

    public XnatSubjectdataI getXnatSubjectdataI() {
        return xnatSubjectdataI;
    }

    public void setXnatSubjectdataI(final XnatSubjectdataI xnatSubjectdataI) {
        this.xnatSubjectdataI = xnatSubjectdataI;
    }

    private void loadXnatSubjectdataI(final UserI userI) {
        if (xnatSubjectdataI == null) {
            xnatSubjectdataI = XnatSubjectdata.getXnatSubjectdatasById(id, userI, false);
        }
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

    public List<SubjectAssessor> getSubjectAssessors() {
        return subjectAssessors;
    }

    public void setSubjectAssessors(List<SubjectAssessor> subjectAssessors) {
        this.subjectAssessors = subjectAssessors;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(final String projectId) {
        this.projectId = projectId;
    }

    private void loadProjectId(UserI userI) {
        if (projectId == null) {
            loadXnatSubjectdataI(userI);
            projectId = xnatSubjectdataI.getProject();
        }
    }

    public String getGroup() { return group; }

    public void setGroup(String group) { this.group = group; }

    public String getSource() { return source; }

    public void setSource(String source) { this.source = source; }

    public String getInitials() { return initials; }

    public void setInitials(String initials) { this.initials = initials; }

    public String getDatatypeString() {
        return datatypeString;
    }

    @Override
    public XFTItem getXftItem(final UserI userI) {
        loadXnatSubjectdataI(userI);
        return xnatSubjectdataI == null ? null : ((XnatSubjectdata)xnatSubjectdataI).getItem();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        final Subject that = (Subject) o;
        return Objects.equals(sessions, that.sessions) &&
                Objects.equals(resources, that.resources) &&
                Objects.equals(subjectAssessors, that.subjectAssessors) &&
                Objects.equals(projectId, that.projectId) &&
                Objects.equals(datatypeString, that.datatypeString) &&
                Objects.equals(group, that.group) &&
                Objects.equals(source, that.source) &&
                Objects.equals(initials, that.initials);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), sessions, resources, subjectAssessors, projectId, datatypeString, group, source, initials);
    }

    @Override
    public String toString() {
        return addParentPropertiesToString(MoreObjects.toStringHelper(this))
                .add("projectId", projectId)
                .add("sessions", sessions)
                .add("resources", resources.stream().map(XnatModelObject::getLabel).distinct().collect(Collectors.toList()))
                .toString();
    }
}
