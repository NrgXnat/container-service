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
import org.nrg.xdat.model.XnatImagesessiondataI;
import org.nrg.xdat.om.XnatExperimentdata;
import org.nrg.xdat.om.XnatImagesessiondata;
import org.nrg.xdat.om.XnatResourcecatalog;
import org.nrg.xdat.om.base.BaseXnatExperimentdata.UnknownPrimaryProjectException;
import org.nrg.xft.XFTItem;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.exceptions.InvalidArchiveStructure;
import org.nrg.xnat.helpers.uri.URIManager;
import org.nrg.xnat.helpers.uri.UriParserUtils;
import org.nrg.xnat.helpers.uri.archive.AssessedURII;
import org.nrg.xnat.helpers.uri.archive.ExperimentURII;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@JsonInclude(Include.NON_NULL)
public class Session extends XnatModelObject {
    @JsonIgnore private XnatImagesessiondataI xnatImagesessiondataI;
    private List<Scan> scans;
    private List<Assessor> assessors;
    private List<Resource> resources;
    @JsonProperty("project-id") private String projectId;
    @JsonProperty("subject-id") private String subjectId;
    @JsonProperty("datatype-string") private String datatypeString;
    @JsonProperty("visit-id") private String visitId;

    @JsonIgnore private Project project = null;
    @JsonIgnore private Subject subject = null;

    public Session() {}

    public Session(final String sessionId, final UserI userI, final boolean loadFiles,
                   @Nonnull final Set<String> loadTypes) {
        this.id = sessionId;
        loadXnatImagesessiondata(userI);
        this.uri = UriParserUtils.getArchiveUri(xnatImagesessiondataI);
        populateProperties(null, loadFiles, loadTypes);
    }

    public Session(final AssessedURII assessedURII, final boolean loadFiles, @Nonnull final Set<String> loadTypes) {
        final XnatImagesessiondata imagesessiondata = assessedURII.getSession();
        if (imagesessiondata != null) {
            this.xnatImagesessiondataI = imagesessiondata;
            this.uri = ((URIManager.DataURIA) assessedURII).getUri();
            populateProperties(null, loadFiles, loadTypes);
        }
    }

    public Session(final XnatImagesessiondataI xnatImagesessiondataI, final boolean loadFiles,
                   @Nonnull final Set<String> loadTypes) {
        this(xnatImagesessiondataI, loadFiles, loadTypes, null, null);
    }

    public Session(final XnatImagesessiondataI xnatImagesessiondataI, final boolean loadFiles,
                   @Nonnull final Set<String> loadTypes, final String parentUri, final String rootArchivePath) {
        this.xnatImagesessiondataI = xnatImagesessiondataI;
        if (parentUri == null) {
            this.uri = UriParserUtils.getArchiveUri(xnatImagesessiondataI);
        } else {
            this.uri = parentUri + "/experiments/" + xnatImagesessiondataI.getId();
        }
        populateProperties(rootArchivePath, loadFiles, loadTypes);
    }

    private void populateProperties(final String rootArchivePath, final boolean loadFiles,
                                    @Nonnull final Set<String> loadTypes) {
        this.id = xnatImagesessiondataI.getId();
        this.label = xnatImagesessiondataI.getLabel();
        this.xsiType = xnatImagesessiondataI.getXSIType();
        this.projectId = xnatImagesessiondataI.getProject();
        this.subjectId = xnatImagesessiondataI.getSubjectId();
        this.visitId = xnatImagesessiondataI.getVisitId();

        this.directory = null;
        try {
            this.directory = ((XnatExperimentdata) xnatImagesessiondataI).getCurrentSessionFolder(true);
        } catch (UnknownPrimaryProjectException | InvalidArchiveStructure e) {
            // ignored, I guess?
        }

        this.scans = new ArrayList<>();
        if (loadFiles || loadTypes.contains(CommandWrapperInputType.SCAN.getName())) {
            for (final XnatImagescandataI xnatImagescandataI : xnatImagesessiondataI.getScans_scan()) {
                this.scans.add(new Scan(xnatImagescandataI, loadFiles, loadTypes, this.uri, rootArchivePath));
            }
        }

        this.resources = new ArrayList<>();
        if (loadFiles || loadTypes.contains(CommandWrapperInputType.RESOURCE.getName())) {
            for (final XnatAbstractresourceI xnatAbstractresourceI : xnatImagesessiondataI.getResources_resource()) {
                if (xnatAbstractresourceI instanceof XnatResourcecatalog) {
                    resources.add(new Resource((XnatResourcecatalog) xnatAbstractresourceI, loadFiles,
                            loadTypes, this.uri, rootArchivePath));
                }
            }
        }

        this.assessors = new ArrayList<>();
        if (loadFiles || loadTypes.contains(CommandWrapperInputType.ASSESSOR.getName())) {
            for (final XnatImageassessordataI xnatImageassessordataI : xnatImagesessiondataI.getAssessors_assessor()) {
                assessors.add(new Assessor(xnatImageassessordataI, loadFiles, loadTypes, this.uri, rootArchivePath));
            }
        }

        datatypeString = null;
        if (loadTypes.contains(CommandWrapperInputType.STRING.getName()) && xnatImagesessiondataI != null){
            try {
                datatypeString = xnatImagesessiondataI.toString();
            } catch (Throwable e){ }
        }
    }

    public static Function<URIManager.ArchiveItemURI, Session> uriToModelObject(final boolean loadFiles,
                                                                                @Nonnull final Set<String> loadTypes) {
        return uri -> {
            if (uri == null) {
                return null;
            }

            XnatImagesessiondata imageSession = null;
            if (ExperimentURII.class.isAssignableFrom(uri.getClass())) {
                final XnatExperimentdata expt = ((ExperimentURII) uri).getExperiment();
                if (expt != null &&
                        XnatImagesessiondataI.class.isAssignableFrom(expt.getClass())) {
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

            return new Session(imageSession, loadFiles, loadTypes);
        };
    }

    public static Function<String, Session> idToModelObject(final UserI userI, final boolean loadFiles,
                                                            @Nonnull final Set<String> loadTypes) {
        return s -> {
            if (StringUtils.isBlank(s)) {
                return null;
            }
            final XnatImagesessiondata imagesessiondata = XnatImagesessiondata.getXnatImagesessiondatasById(s,
                    userI, true);
            if (imagesessiondata != null) {
                return new Session(imagesessiondata, loadFiles, loadTypes);
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

    public XnatImagesessiondataI getXnatImagesessiondataI() {
        return xnatImagesessiondataI;
    }

    public void setXnatImagesessiondataI(final XnatImagesessiondataI xnatImagesessiondataI) {
        this.xnatImagesessiondataI = xnatImagesessiondataI;
    }

    private void loadXnatImagesessiondata(final UserI userI) {
        if (xnatImagesessiondataI == null) {
            xnatImagesessiondataI = XnatImagesessiondata.getXnatImagesessiondatasById(id, userI, false);
        }
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
            projectId = xnatImagesessiondataI.getProject();
        }
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
            subjectId = xnatImagesessiondataI.getSubjectId();
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
        return xnatImagesessiondataI == null ? null : ((XnatImagesessiondata)xnatImagesessiondataI).getItem();
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
                Objects.equals(subjectId, that.subjectId) &&
                Objects.equals(datatypeString, that.datatypeString) &&
                Objects.equals(visitId, that.visitId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), scans, assessors, resources, projectId, subjectId, datatypeString, visitId);
    }

    @Override
    public String toString() {
        return addParentPropertiesToString(MoreObjects.toStringHelper(this))
                .add("projectId", projectId)
                .add("subjectId", subjectId)
                .add("scans", scans)
                .add("assessors", assessors)
                .add("resources", resources.stream().map(XnatModelObject::getLabel).distinct().collect(Collectors.toList()))
                .toString();
    }
}
