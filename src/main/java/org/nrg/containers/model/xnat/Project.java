package org.nrg.containers.model.xnat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.google.common.base.Function;
import com.google.common.base.MoreObjects;
import com.google.common.collect.Lists;
import org.apache.commons.lang.StringUtils;
import org.nrg.containers.model.command.entity.CommandWrapperInputType;
import org.nrg.xdat.model.XnatAbstractresourceI;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xdat.om.XnatResourcecatalog;
import org.nrg.xdat.om.XnatSubjectdata;
import org.nrg.xft.XFTItem;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.helpers.uri.URIManager;
import org.nrg.xnat.helpers.uri.UriParserUtils;
import org.nrg.xnat.helpers.uri.archive.ProjectURII;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@JsonInclude(Include.NON_NULL)
public class Project extends XnatModelObject {
    @JsonIgnore private XnatProjectdata xnatProjectdata;
    private List<Resource> resources;
    private List<Subject> subjects;
    private String directory;

    public Project() {}

    public Project(final String projectId, final UserI userI, final boolean loadFiles,
                   @Nonnull final Set<String> loadTypes) {
        this(projectId, userI, loadFiles, loadTypes, true);
    }

    public Project(final String projectId, final UserI userI, final boolean loadFiles,
                   @Nonnull final Set<String> loadTypes, final boolean preload) {
        this.id = projectId;
        loadXnatProjectdata(userI);
        this.uri = UriParserUtils.getArchiveUri(xnatProjectdata);
        populateProperties(loadFiles, loadTypes, preload);
    }

    public Project(final ProjectURII projectURII, final boolean loadFiles, @Nonnull final Set<String> loadTypes) {
        this(projectURII, loadFiles, loadTypes, true);
    }

    public Project(final ProjectURII projectURII, final boolean loadFiles, @Nonnull final Set<String> loadTypes,
                   final boolean preload) {
        this.xnatProjectdata = projectURII.getProject();
        this.uri = ((URIManager.DataURIA) projectURII).getUri();
        populateProperties(loadFiles, loadTypes, preload);
    }

    public Project(final XnatProjectdata xnatProjectdata, final boolean loadFiles,
                   @Nonnull final Set<String> loadTypes) {
        this(xnatProjectdata, loadFiles, loadTypes, true);
    }

    public Project(final XnatProjectdata xnatProjectdata, final boolean loadFiles,
                   @Nonnull final Set<String> loadTypes, final boolean preload) {
        this.xnatProjectdata = xnatProjectdata;
        this.uri = UriParserUtils.getArchiveUri(xnatProjectdata);
        populateProperties(loadFiles, loadTypes, preload);
    }

    private void populateProperties(final boolean loadFiles, @Nonnull final Set<String> loadTypes, final boolean preload) {
        this.id = xnatProjectdata.getId();
        this.label = xnatProjectdata.getName();
        this.xsiType = xnatProjectdata.getXSIType();
        this.directory = xnatProjectdata.getRootArchivePath() + xnatProjectdata.getCurrentArc();

        this.subjects = Lists.newArrayList();
        if (preload && loadTypes.contains(CommandWrapperInputType.SUBJECT.getName())) {
            for (final XnatSubjectdata subject : xnatProjectdata.getParticipants_participant()) {
                subjects.add(new Subject(subject, loadFiles, loadTypes, this.uri, xnatProjectdata.getRootArchivePath()));
            }
        }

        this.resources = Lists.newArrayList();
        if (preload && (loadFiles || loadTypes.contains(CommandWrapperInputType.RESOURCE.getName()))) {
            for (final XnatAbstractresourceI xnatAbstractresourceI : xnatProjectdata.getResources_resource()) {
                if (xnatAbstractresourceI instanceof XnatResourcecatalog) {
                    resources.add(new Resource((XnatResourcecatalog) xnatAbstractresourceI, loadFiles, loadTypes,
                            this.uri, xnatProjectdata.getRootArchivePath()));
                }
            }
        }
    }

    public static Function<URIManager.ArchiveItemURI, Project> uriToModelObject(final boolean loadFiles,
                                                                                @Nonnull final Set<String> loadTypes) {
        return uriToModelObject(loadFiles, loadTypes, true);
    }

    public static Function<URIManager.ArchiveItemURI, Project> uriToModelObject(final boolean loadFiles,
                                                                                @Nonnull final Set<String> loadTypes,
                                                                                final boolean preload) {
        return new Function<URIManager.ArchiveItemURI, Project>() {
            @Nullable
            @Override
            public Project apply(@Nullable URIManager.ArchiveItemURI uri) {
                if (uri != null &&
                        ProjectURII.class.isAssignableFrom(uri.getClass())) {
                    return new Project((ProjectURII) uri, loadFiles, loadTypes, preload);
                }

                return null;
            }
        };
    }

    public static Function<String, Project> idToModelObject(final UserI userI, final boolean loadFiles,
                                                            @Nonnull final Set<String> loadTypes) {
        return idToModelObject(userI, loadFiles, loadTypes, true);
    }

    public static Function<String, Project> idToModelObject(final UserI userI, final boolean loadFiles,
                                                            @Nonnull final Set<String> loadTypes,
                                                            final boolean preload) {
        return new Function<String, Project>() {
            @Nullable
            @Override
            public Project apply(@Nullable String s) {
                if (StringUtils.isBlank(s)) {
                    return null;
                }
                final XnatProjectdata xnatProjectdata = XnatProjectdata.getXnatProjectdatasById(s, userI, false);
                if (xnatProjectdata != null) {
                    return new Project(xnatProjectdata, loadFiles, loadTypes, preload);
                }
                return null;
            }
        };
    }

    public Project getProject(final UserI userI) {
        loadXnatProjectdata(userI);
        return this;
    }

    public void loadXnatProjectdata(final UserI userI) {
        if (xnatProjectdata == null) {
            xnatProjectdata = XnatProjectdata.getXnatProjectdatasById(id, userI, false);
        }
    }

    public XnatProjectdata getXnatProjectdata() {
        return xnatProjectdata;
    }

    public void setXnatProjectdata(final XnatProjectdata xnatProjectdata) {
        this.xnatProjectdata = xnatProjectdata;
    }

    public List<Resource> getResources() {
        return resources;
    }

    public void setResources(final List<Resource> resources) {
        this.resources = resources;
    }

    public List<Subject> getSubjects() {
        return subjects;
    }

    public void setSubjects(final List<Subject> subjects) {
        this.subjects = subjects;
    }

    public String getDirectory() {
        return directory;
    }

    public void setDirectory(final String directory) {
        this.directory = directory;
    }

    @Override
    public XFTItem getXftItem(final UserI userI) {
        loadXnatProjectdata(userI);
        return xnatProjectdata == null ? null : xnatProjectdata.getItem();
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        final Project that = (Project) o;
        return Objects.equals(this.xnatProjectdata, that.xnatProjectdata) &&
                Objects.equals(this.directory, that.directory) &&
                Objects.equals(this.resources, that.resources) &&
                Objects.equals(this.subjects, that.subjects);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), xnatProjectdata, directory, resources, subjects);
    }


    @Override
    public String toString() {
        return addParentPropertiesToString(MoreObjects.toStringHelper(this))
                .add("directory", directory)
                .add("resources", resources)
                .add("subjects", subjects)
                .toString();
    }
}
