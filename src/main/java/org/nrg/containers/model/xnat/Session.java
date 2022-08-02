package org.nrg.containers.model.xnat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.model.command.entity.CommandWrapperInputType;
import org.nrg.xdat.model.XnatAbstractresourceI;
import org.nrg.xdat.model.XnatImageassessordataI;
import org.nrg.xdat.model.XnatImagescandataI;
import org.nrg.xdat.om.XnatExperimentdata;
import org.nrg.xdat.om.XnatImagesessiondata;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xdat.om.XnatResourcecatalog;
import org.nrg.xdat.om.base.BaseXnatExperimentdata.UnknownPrimaryProjectException;
import org.nrg.xft.XFTItem;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.exceptions.InvalidArchiveStructure;
import org.nrg.xnat.helpers.uri.URIManager;
import org.nrg.xnat.helpers.uri.UriParserUtils;
import org.nrg.xnat.helpers.uri.archive.AssessedURII;
import org.nrg.xnat.helpers.uri.archive.ExperimentURII;
import org.nrg.xnat.helpers.uri.archive.ProjectURII;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@JsonInclude(Include.NON_NULL)
public class Session extends XnatModelObject {
    @JsonIgnore private XnatImagesessiondata xnatImagesessiondata;
    private List<Scan> scans;
    private List<Assessor> assessors;
    private List<Resource> resources;
    @JsonProperty("project-id") private String projectId;
    @JsonProperty("shared-from-project-id") private String sharedFromProjectId;
    @JsonProperty("subject-id") private String subjectId;
    @JsonProperty("datatype-string") private String datatypeString;
    @JsonProperty("visit-id") private String visitId;

    @JsonIgnore private Project project = null;
    @JsonIgnore private Subject subject = null;

    public Session() {}

    public Session(final String sessionId,
                   final UserI userI,
                   final boolean loadFiles,
                   @Nonnull final Set<String> loadTypes) {
        this(sessionId, null, userI, loadFiles, loadTypes);
    }

    public Session(final String sessionId,
                   final String project,
                   final UserI userI,
                   final boolean loadFiles,
                   @Nonnull final Set<String> loadTypes) {
        this(loadXnatImageSessionData(sessionId, userI), project, loadFiles, loadTypes, null, null);
    }

    public Session(final XnatImagesessiondata xnatImagesessiondata,
                   final boolean loadFiles,
                   @Nonnull final Set<String> loadTypes) {
        this(xnatImagesessiondata, null, loadFiles, loadTypes);
    }

    public Session(final XnatImagesessiondata xnatImagesessiondata,
                   final String project,
                   final boolean loadFiles,
                   @Nonnull final Set<String> loadTypes) {
        this(xnatImagesessiondata, project, loadFiles, loadTypes, null, null);
    }

    public Session(final XnatImagesessiondata xnatImagesessiondata,
                   final boolean loadFiles,
                   @Nonnull final Set<String> loadTypes,
                   final String parentUri,
                   final String rootArchivePath) {
        this(xnatImagesessiondata, null, loadFiles, loadTypes, parentUri, rootArchivePath);
    }

    public Session(final XnatImagesessiondata xnatImagesessiondata,
                   final String project,
                   final boolean loadFiles,
                   @Nonnull final Set<String> loadTypes,
                   final String parentUri,
                   final String rootArchivePath) {
        this.xnatImagesessiondata = xnatImagesessiondata;
        if (parentUri == null) {
            this.uri = UriParserUtils.getArchiveUri(xnatImagesessiondata);
        } else {
            this.uri = parentUri + "/experiments/" + xnatImagesessiondata.getId();
        }

        this.id = xnatImagesessiondata.getId();
        this.label = xnatImagesessiondata.getLabel();
        this.xsiType = xnatImagesessiondata.getXSIType();
        this.subjectId = xnatImagesessiondata.getSubjectId();
        this.visitId = xnatImagesessiondata.getVisitId();


        if (StringUtils.isNotBlank(project)) {
            // If they passed in a project, use it as our projectId
            projectId = project;

            // If the project they passed in is not the session's primary project,
            //  then the session is shared
            final String primaryProject = xnatImagesessiondata.getProject();
            if (!project.equals(primaryProject)) {
                sharedFromProjectId = primaryProject;
            } else {
                sharedFromProjectId = null;
            }

            // Ensure the project ID is valid
            if (!xnatImagesessiondata.hasProject(project)) {
                throw new RuntimeException("Session is not a member of project " + project);
            }
        } else {
            projectId = xnatImagesessiondata.getProject();
            sharedFromProjectId = null;
        }

        this.directory = null;
        try {
            this.directory = xnatImagesessiondata.getCurrentSessionFolder(true);
        } catch (UnknownPrimaryProjectException | InvalidArchiveStructure e) {
            // ignored, I guess?
        }

        this.scans = new ArrayList<>();
        if (loadFiles || loadTypes.contains(CommandWrapperInputType.SCAN.getName())) {
            for (final XnatImagescandataI xnatImagescandataI : xnatImagesessiondata.getScans_scan()) {
                this.scans.add(new Scan(xnatImagescandataI, loadFiles, loadTypes, this.uri, rootArchivePath));
            }
        }

        this.resources = new ArrayList<>();
        if (loadFiles || loadTypes.contains(CommandWrapperInputType.RESOURCE.getName())) {
            for (final XnatAbstractresourceI xnatAbstractresourceI : xnatImagesessiondata.getResources_resource()) {
                if (xnatAbstractresourceI instanceof XnatResourcecatalog) {
                    resources.add(new Resource((XnatResourcecatalog) xnatAbstractresourceI, loadFiles,
                            loadTypes, this.uri, rootArchivePath));
                }
            }
        }

        this.assessors = new ArrayList<>();
        if (loadFiles || loadTypes.contains(CommandWrapperInputType.ASSESSOR.getName())) {
            for (final XnatImageassessordataI xnatImageassessordataI : xnatImagesessiondata.getAssessors_assessor()) {
                assessors.add(new Assessor(xnatImageassessordataI, loadFiles, loadTypes, this.uri, rootArchivePath));
            }
        }

        datatypeString = null;
        if (loadTypes.contains(CommandWrapperInputType.STRING.getName()) && xnatImagesessiondata != null){
            try {
                datatypeString = xnatImagesessiondata.toString();
            } catch (Throwable e){ }
        }
    }

    public static Function<URIManager.ArchiveItemURI, Session> uriToModelObject(final String projectId,
                                                                                final boolean loadFiles,
                                                                                @Nonnull final Set<String> loadTypes) {
        return uri -> {
            if (uri == null) {
                return null;
            }

            XnatImagesessiondata imageSession = null;
            if (ExperimentURII.class.isAssignableFrom(uri.getClass())) {
                final XnatExperimentdata expt = ((ExperimentURII) uri).getExperiment();
                if (expt != null &&
                        XnatImagesessiondata.class.isAssignableFrom(expt.getClass())) {
                    imageSession = (XnatImagesessiondata) expt;
                }
            } else if (AssessedURII.class.isAssignableFrom(uri.getClass())) {
                // Do we actually need this branch?
                // I think this might be for assessors...
                // JF 2022-05-12
                imageSession = ((AssessedURII) uri).getSession();
            }

            if (imageSession == null) {
                return null;
            }

            // LET THE USER TELL US A PROJECT FOR A SHARED SESSION

            // A project id sent into this method comes in from the
            //  path of the launch URL itself.
            String project = projectId;

            // We're only in this method because the user sent the input
            //  value for a session input as a URL.
            // If that URL has the form
            //  /projects/{project}/experiments/{session}
            //  then we can get a project ID from that.
            // This project ID will take precedence over the
            //  one from the launch URL.
            if (ProjectURII.class.isAssignableFrom(uri.getClass())) {
                XnatProjectdata projectData = ((ProjectURII) uri).getProject();
                if (projectData != null) {
                    project = projectData.getId();
                }
            }

            return new Session(imageSession, project, loadFiles, loadTypes);
        };
    }

    public static Function<String, Session> idToModelObject(final UserI userI,
                                                            final String projectId,
                                                            final boolean loadFiles,
                                                            @Nonnull final Set<String> loadTypes) {
        return s -> {
            if (StringUtils.isBlank(s)) {
                return null;
            }
            final XnatImagesessiondata imageSession = XnatImagesessiondata.getXnatImagesessiondatasById(s,
                    userI, true);
            if (imageSession == null) {
                return null;
            }

            return new Session(imageSession, projectId, loadFiles, loadTypes);
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

    public Subject getSubject(final UserI userI, final boolean loadFiles, @Nonnull final Set<String> loadTypes) {
        loadSubject(userI, loadFiles, loadTypes);
        return subject;
    }

    private void loadSubject(final UserI userI, final boolean loadFiles, @Nonnull final Set<String> loadTypes) {
        if (subject == null) {
            loadSubjectId(userI);
            subject = new Subject(subjectId, userI, loadFiles, loadTypes);
        }
    }

    private void loadXnatImagesessiondata(final UserI userI) {
        if (xnatImagesessiondata == null) {
            xnatImagesessiondata = loadXnatImageSessionData(this.id, userI);
        }
    }

    public static XnatImagesessiondata loadXnatImageSessionData(final String id, final UserI userI) {
        return XnatImagesessiondata.getXnatImagesessiondatasById(id, userI, false);
    }

    public List<Resource> getResources() {
        return resources;
    }

    public void setResources(final List<Resource> resources) {
        this.resources = resources;
    }

    public List<Assessor> getAssessors() {
        return assessors;
    }

    public void setAssessors(final List<Assessor> assessors) {
        this.assessors = assessors;
    }

    public List<Scan> getScans() {
        return scans;
    }

    public void setScans(final List<Scan> scans) {
        this.scans = scans;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(final String projectId) {
        this.projectId = projectId;
    }

    private void loadProjectId(UserI userI) {
        if (projectId == null) {
            loadXnatImagesessiondata(userI);
            projectId = xnatImagesessiondata.getProject();
        }
    }

    public String getSharedFromProjectId() {
        return sharedFromProjectId;
    }

    public void setSharedFromProjectId(String sharedFromProjectId) {
        this.sharedFromProjectId = sharedFromProjectId;
    }

    public String getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(final String subjectId) {
        this.subjectId = subjectId;
    }

    private void loadSubjectId(UserI userI) {
        if (subjectId == null) {
            loadXnatImagesessiondata(userI);
            subjectId = xnatImagesessiondata.getSubjectId();
        }
    }

    public String getDatatypeString() {
        return datatypeString;
    }

    public void setDatatypeString(String datatypeString) {
        this.datatypeString = datatypeString;
    }


    public String getVisitId() {
        return visitId;
    }

    public void setVisitId(String visitId) {
        this.visitId = visitId;
    }

    @Override
    public XFTItem getXftItem(final UserI userI) {
        loadXnatImagesessiondata(userI);
        return xnatImagesessiondata == null ? null : xnatImagesessiondata.getItem();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        final Session that = (Session) o;
        return Objects.equals(scans, that.scans) &&
                Objects.equals(assessors, that.assessors) &&
                Objects.equals(resources, that.resources) &&
                Objects.equals(projectId, that.projectId) &&
                Objects.equals(sharedFromProjectId, that.sharedFromProjectId) &&
                Objects.equals(subjectId, that.subjectId) &&
                Objects.equals(datatypeString, that.datatypeString) &&
                Objects.equals(visitId, that.visitId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), scans, assessors, resources, projectId, sharedFromProjectId, subjectId, datatypeString, visitId);
    }

    @Override
    public String toString() {
        return addParentPropertiesToString(MoreObjects.toStringHelper(this))
                .add("projectId", projectId)
                .add("subjectId", subjectId)
                .add("sharedFromProject", sharedFromProjectId)
                .add("scans", scans)
                .add("assessors", assessors)
                .add("resources", resources.stream().map(XnatModelObject::getLabel).distinct().collect(Collectors.toList()))
                .toString();
    }
}
