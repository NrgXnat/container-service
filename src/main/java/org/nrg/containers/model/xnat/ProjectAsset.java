package org.nrg.containers.model.xnat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import org.apache.commons.lang.StringUtils;
import org.nrg.containers.model.command.entity.CommandWrapperInputType;
import org.nrg.xdat.model.XnatAbstractprojectassetI;
import org.nrg.xdat.model.XnatAbstractresourceI;
import org.nrg.xdat.model.XnatExperimentdataI;
import org.nrg.xdat.model.XnatImagesessiondataI;
import org.nrg.xdat.model.XnatSubjectdataI;
import org.nrg.xdat.om.XnatAbstractprojectasset;
import org.nrg.xdat.om.XnatExperimentdata;
import org.nrg.xdat.om.XnatResourcecatalog;
import org.nrg.xdat.om.XnatSubjectdata;
import org.nrg.xdat.om.base.BaseXnatExperimentdata;
import org.nrg.xft.XFTItem;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.exceptions.InvalidArchiveStructure;
import org.nrg.xnat.helpers.uri.URIManager;
import org.nrg.xnat.helpers.uri.UriParserUtils;
import org.nrg.xnat.helpers.uri.archive.ExperimentURII;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@JsonInclude(Include.NON_NULL)
public class ProjectAsset extends XnatModelObject {

    @JsonIgnore private XnatAbstractprojectassetI xnatProjectAssetI;
    private List<Session> sessions;
    private List<Subject> subjects;
    private List<Resource> resources;
    @JsonProperty("project-id") private String projectId;
    @JsonProperty("datatype-string") private String datatypeString;

    @JsonIgnore private Project project = null;

    public ProjectAsset() {}

    public ProjectAsset(final String projectAssetId, final UserI userI, final boolean loadFiles,
                        @Nonnull final Set<String> loadTypes) {
        this.id = projectAssetId;
        loadXnatProjectAssetDataI(userI);
        this.uri = UriParserUtils.getArchiveUri(xnatProjectAssetI);
        populateProperties(null, loadFiles, loadTypes);
    }

    public ProjectAsset(final XnatAbstractprojectassetI xnatProjectAssetI, final boolean loadFiles,
                        @Nonnull final Set<String> loadTypes, final String parentUri, final String rootArchivePath) {
        this.xnatProjectAssetI = xnatProjectAssetI;
        if (parentUri == null) {
            this.uri = UriParserUtils.getArchiveUri(xnatProjectAssetI);
        } else {
            this.uri = parentUri + "/" + xnatProjectAssetI.getId();
        }
        populateProperties(rootArchivePath, loadFiles, loadTypes);
    }

    private void populateProperties(final String rootArchivePath, final boolean loadFiles,
                                    @Nonnull final Set<String> loadTypes) {
        this.id = xnatProjectAssetI.getId();
        this.label = xnatProjectAssetI.getLabel();
        this.xsiType = xnatProjectAssetI.getXSIType();
        this.projectId = xnatProjectAssetI.getProject();

        this.directory = null;
        try {
            this.directory = ((XnatAbstractprojectasset) xnatProjectAssetI).getCurrentSessionFolder(true);
        } catch (BaseXnatExperimentdata.UnknownPrimaryProjectException | InvalidArchiveStructure e) {
            // ignored, I guess?
        }

        this.subjects = new ArrayList<>();
        for (final XnatSubjectdataI xnatSubjectdataI : xnatProjectAssetI.getSubjects_subject()) {
            if (xnatSubjectdataI instanceof XnatSubjectdata) {
                subjects.add(new Subject(xnatSubjectdataI, loadFiles, loadTypes, this.uri, rootArchivePath));
            }
        }

        this.sessions = new ArrayList<>();
        for (final XnatExperimentdataI xnatExperimentdataI : xnatProjectAssetI.getExperiments_experiment()) {
            if (xnatExperimentdataI instanceof XnatImagesessiondataI) {
                sessions.add(new Session((XnatImagesessiondataI) xnatExperimentdataI, loadFiles, loadTypes, this.uri, rootArchivePath));
            }
        }

        this.resources = new ArrayList<>();
        for (final XnatAbstractresourceI xnatAbstractresourceI : xnatProjectAssetI.getResources_resource()) {
            if (xnatAbstractresourceI instanceof XnatResourcecatalog) {
                resources.add(new Resource((XnatResourcecatalog) xnatAbstractresourceI, loadFiles, loadTypes, this.uri, rootArchivePath));
            }
        }
        datatypeString = null;
        if(loadTypes.contains(CommandWrapperInputType.STRING.getName()) && xnatProjectAssetI != null){
            try {
                datatypeString = xnatProjectAssetI.toString();
            } catch (Throwable e){ }
        }
    }

    public static Function<URIManager.ArchiveItemURI, ProjectAsset> uriToModelObject(final boolean loadFiles,
                                                                                     @Nonnull final Set<String> loadTypes) {
        return uri -> {
            if (uri != null && ExperimentURII.class.isAssignableFrom(uri.getClass())) {
                final XnatExperimentdata experimentdata = ((ExperimentURII) uri).getExperiment();
                if (experimentdata != null &&
                        XnatAbstractprojectassetI.class.isAssignableFrom(experimentdata.getClass())) {
                    return new ProjectAsset((XnatAbstractprojectassetI) experimentdata,
                            loadFiles, loadTypes, null, null);
                }
            }
            return null;
        };
    }
    public static Function<String, ProjectAsset> idToModelObject(final UserI userI, final boolean loadFiles,
                                                                @Nonnull final Set<String> loadTypes) {
        return s -> {
            if (StringUtils.isBlank(s)) {
                return null;
            }
            final XnatAbstractprojectasset xnatAbstractprojectasset = XnatAbstractprojectasset.getXnatAbstractprojectassetsById(s, userI, true);
            if (xnatAbstractprojectasset != null) {
                return new ProjectAsset(xnatAbstractprojectasset.getId(), userI, loadFiles, loadTypes);
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

    public XnatAbstractprojectassetI getXnatProjectAssetI() {
        return xnatProjectAssetI;
    }

    public void setXnatProjectAssetI(XnatAbstractprojectassetI xnatProjectAssetI) {
        this.xnatProjectAssetI = xnatProjectAssetI;
    }

    private void loadXnatProjectAssetDataI(final UserI userI) {
        if (xnatProjectAssetI == null) {
            xnatProjectAssetI = XnatAbstractprojectasset.getXnatAbstractprojectassetsById(id, userI, false);
        }
    }

    public List<Session> getSessions() { return sessions; }

    public void setSessions(List<Session> sessions) {
        this.sessions = sessions;
    }

    public List<Subject> getSubjects() { return subjects; }

    public void setSubjects(List<Subject> subjects) {
        this.subjects = subjects;
    }

    public List<Resource> getResources() { return resources; }

    public void setResources(List<Resource> resources) {
        this.resources = resources;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    private void loadProjectId(UserI userI) {
        if (projectId == null) {
            loadXnatProjectAssetDataI(userI);
            projectId = xnatProjectAssetI.getProject();
        }
    }

    public String getDatatypeString() {
        return datatypeString;
    }

    public void setDatatypeString(String datatypeString) {
        this.datatypeString = datatypeString;
    }

    @Override
    public XFTItem getXftItem(final UserI userI) {
        loadXnatProjectAssetDataI(userI);
        return xnatProjectAssetI == null ? null : ((XnatExperimentdata)xnatProjectAssetI).getItem();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        final ProjectAsset that = (ProjectAsset) o;
        return Objects.equals(sessions, that.sessions) &&
                Objects.equals(subjects, that.subjects) &&
                Objects.equals(resources, that.resources) &&
                Objects.equals(projectId, that.projectId) &&
                Objects.equals(datatypeString, that.datatypeString);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), sessions, subjects, resources, projectId, datatypeString);
    }

    @Override
    public String toString() {
        return addParentPropertiesToString(MoreObjects.toStringHelper(this))
                .add("projectId", projectId)
                .add("subjects", subjects)
                .add("sessions", sessions)
                .add("resources", resources.stream().map(XnatModelObject::getLabel).distinct().collect(Collectors.toList()))
                .toString();
    }

}
