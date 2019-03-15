package org.nrg.containers.model.xnat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Function;
import com.google.common.base.MoreObjects;
import com.google.common.collect.Lists;
import org.apache.commons.lang3.StringUtils;
import org.nrg.xdat.model.XnatAbstractresourceI;
import org.nrg.xdat.model.XnatSubjectassessordataI;
import org.nrg.xdat.om.XnatExperimentdata;
import org.nrg.xdat.om.XnatResourcecatalog;
import org.nrg.xdat.om.XnatSubjectassessordata;
import org.nrg.xdat.om.base.BaseXnatExperimentdata.UnknownPrimaryProjectException;
import org.nrg.xft.XFTItem;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.exceptions.InvalidArchiveStructure;
import org.nrg.xnat.helpers.uri.URIManager;
import org.nrg.xnat.helpers.uri.UriParserUtils;
import org.nrg.xnat.helpers.uri.archive.AssessedURII;
import org.nrg.xnat.helpers.uri.archive.ExperimentURII;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;

@JsonInclude(Include.NON_NULL)
public class SubjectAssessor extends XnatModelObject {
    @JsonIgnore
    private XnatSubjectassessordataI xnatSubjectassessordataI;
    private List<Resource> resources;
    @JsonProperty("project-id")
    private String projectId;
    @JsonProperty("subject-id")
    private String subjectId;
    private String directory;

    public SubjectAssessor() {}

    public SubjectAssessor(final String assessorId, final UserI userI) {
        this.id = assessorId;
        loadXnatSubjectAssessordata(userI);
        this.uri = UriParserUtils.getArchiveUri(xnatSubjectassessordataI);
        populateProperties(null);
    }

    public SubjectAssessor(final AssessedURII assessedURII) {
        final XnatSubjectassessordata assessorData = assessedURII.getSession();
        if (assessorData != null && XnatSubjectassessordata.class.isAssignableFrom(assessorData.getClass())) {
            this.xnatSubjectassessordataI = assessorData;
            this.uri = ((URIManager.DataURIA) assessedURII).getUri();
            populateProperties(null);
        }
    }

    public SubjectAssessor(final XnatSubjectassessordataI xnatSubjectassessordataI) {
        this(xnatSubjectassessordataI, null, null);
    }

    public SubjectAssessor(final XnatSubjectassessordataI xnatSubjectassessordataI, final String parentUri,
                           final String rootArchivePath) {
        this.xnatSubjectassessordataI = xnatSubjectassessordataI;
        if (parentUri == null) {
            this.uri = UriParserUtils.getArchiveUri(xnatSubjectassessordataI);
        } else {
            this.uri = parentUri + "/experiments/" + xnatSubjectassessordataI.getId();
        }
        populateProperties(rootArchivePath);
    }

    private void populateProperties(final String rootArchivePath) {
        this.id = xnatSubjectassessordataI.getId();
        this.label = xnatSubjectassessordataI.getLabel();
        this.xsiType = "xnat:subjectAssessorData";
        try { this.xsiType = xnatSubjectassessordataI.getXSIType();} catch (NullPointerException e) { }
        this.projectId = xnatSubjectassessordataI.getProject();
        this.subjectId = xnatSubjectassessordataI.getSubjectId();

        try {
            if (XnatExperimentdata.class.isAssignableFrom(xnatSubjectassessordataI.getClass()))
                this.directory = ((XnatExperimentdata) xnatSubjectassessordataI).getCurrentSessionFolder(true);
        } catch (UnknownPrimaryProjectException | InvalidArchiveStructure e) {
            // ignored, I guess?
        }

        this.resources = Lists.newArrayList();
        for (final XnatAbstractresourceI xnatAbstractresourceI : xnatSubjectassessordataI.getResources_resource()) {
            if (xnatAbstractresourceI instanceof XnatResourcecatalog) {
                resources.add(new Resource((XnatResourcecatalog) xnatAbstractresourceI, this.uri, rootArchivePath));
            }
        }

    }


    public static Function<URIManager.ArchiveItemURI, SubjectAssessor> uriToModelObject() {
        return new Function<URIManager.ArchiveItemURI, SubjectAssessor>() {
            @Nullable
            @Override
            public SubjectAssessor apply(@Nullable URIManager.ArchiveItemURI uri) {
                XnatSubjectassessordata imageSession;
                if (uri != null &&
                        AssessedURII.class.isAssignableFrom(uri.getClass())) {
                    imageSession = ((AssessedURII) uri).getSession();

                    if (imageSession != null &&
                            XnatSubjectassessordata.class.isAssignableFrom(imageSession.getClass())) {
                        return new SubjectAssessor((AssessedURII) uri);
                    }
                } else if (uri != null &&
                        ExperimentURII.class.isAssignableFrom(uri.getClass())) {
                    final XnatExperimentdata experimentdata = ((ExperimentURII) uri).getExperiment();
                    if (experimentdata != null &&
                            XnatSubjectassessordataI.class.isAssignableFrom(experimentdata.getClass())) {
                        return new SubjectAssessor((XnatSubjectassessordataI) experimentdata);
                    }
                }

                return null;
            }
        };
    }

    public static Function<String, SubjectAssessor> idToModelObject(final UserI userI) {
        return new Function<String, SubjectAssessor>() {
            @Nullable
            @Override
            public SubjectAssessor apply(@Nullable String s) {
                if (StringUtils.isBlank(s)) {
                    return null;
                }
                final XnatSubjectassessordata subjectAssessorData = XnatSubjectassessordata.getXnatSubjectassessordatasById(s, userI, true);
                if (subjectAssessorData != null) {
                    return new SubjectAssessor(subjectAssessorData);
                }
                return null;
            }
        };
    }

    public Project getProject(final UserI userI) {
        loadXnatSubjectAssessordata(userI);
        return new Project(xnatSubjectassessordataI.getProject(), userI);
    }

    public Subject getSubject(final UserI userI) {
        loadXnatSubjectAssessordata(userI);
        return new Subject(xnatSubjectassessordataI.getSubjectId(), userI);
    }

    public void loadXnatSubjectAssessordata(final UserI userI) {
        if (xnatSubjectassessordataI == null) {
            xnatSubjectassessordataI = XnatSubjectassessordata.getXnatSubjectassessordatasById(id, userI, false);
        }
    }

    public XnatSubjectassessordataI getXnatSubjectassessordataI() {
        return xnatSubjectassessordataI;
    }

    public void setXnatSubjectassessordataI(final XnatSubjectassessordataI xnatSubjectassessordataI) {
        this.xnatSubjectassessordataI = xnatSubjectassessordataI;
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

    public String getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(final String subjectId) {
        this.subjectId = subjectId;
    }

    public String getDirectory() {
        return directory;
    }

    public void setDirectory(final String directory) {
        this.directory = directory;
    }

    @Override
    public XFTItem getXftItem(final UserI userI) {
        loadXnatSubjectAssessordata(userI);
        return xnatSubjectassessordataI == null ? null : ((XnatSubjectassessordata) xnatSubjectassessordataI).getItem();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SubjectAssessor)) return false;
        if (!super.equals(o)) return false;
        SubjectAssessor that = (SubjectAssessor) o;
        return Objects.equals(xnatSubjectassessordataI, that.xnatSubjectassessordataI) &&
                Objects.equals(resources, that.resources) &&
                Objects.equals(projectId, that.projectId) &&
                Objects.equals(subjectId, that.subjectId) &&
                Objects.equals(directory, that.directory);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), xnatSubjectassessordataI, resources, projectId, subjectId, directory);
    }

    @Override
    public String toString() {
        return addParentPropertiesToString(MoreObjects.toStringHelper(this))
                .add("resources", resources)
                .add("projectId", projectId)
                .add("subjectId", subjectId)
                .add("directory", directory)
                .toString();

    }
}