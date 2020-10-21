package org.nrg.containers.model.xnat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Function;
import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.nrg.containers.model.command.entity.CommandWrapperInputType;
import org.nrg.xdat.model.XnatAbstractprojectassetI;
import org.nrg.xdat.model.XnatAbstractresourceI;
import org.nrg.xdat.model.XnatProjectdataAliasI;
import org.nrg.xdat.om.XnatExperimentdata;
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
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@JsonInclude(Include.NON_NULL)
public class Project extends XnatModelObject {
    @JsonIgnore private XnatProjectdata xnatProjectdata;
    private List<Resource> resources;
    private List<Subject> subjects;
    @JsonProperty("project-assets") private List<ProjectAsset> projectAssets;
    private String directory;
    private String title;
    @JsonProperty("running-title") private String runningTitle;
    private String description;
    private String keywords;
    private String accessibility;
    private List<String> aliases;


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
        try {
            this.directory = xnatProjectdata.getRootArchivePath() + xnatProjectdata.getCurrentArc();
        } catch (NullPointerException e){log.error("Project could not get root archive path " + e.getMessage());}
        try {
            this.accessibility = xnatProjectdata.getPublicAccessibility();
        } catch (Throwable e){log.error("Could not get project accessibility.", e.getMessage());}
        this.label = !Strings.isNullOrEmpty(xnatProjectdata.getName()) ? xnatProjectdata.getName() : xnatProjectdata.getId();
        this.title = xnatProjectdata.getName();
        this.runningTitle = xnatProjectdata.getDisplayID();
        this.description = xnatProjectdata.getDescription();
        this.keywords = xnatProjectdata.getKeywords();
        this.aliases = xnatProjectdata.getAliases_alias().stream().map(XnatProjectdataAliasI::getAlias).collect(Collectors.toList());

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
        this.projectAssets = Lists.newArrayList();
        if (preload && (loadFiles || loadTypes.contains(CommandWrapperInputType.PROJECT_ASSET.getName()))) {
            for (final XnatExperimentdata xnatExperimentdata : xnatProjectdata.getExperiments()) {
                if (xnatExperimentdata instanceof XnatAbstractprojectassetI) {
                    projectAssets.add(new ProjectAsset((XnatAbstractprojectassetI) xnatExperimentdata, loadFiles, loadTypes,
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

    public String getTitle() { return title; }

    public void setTitle(String title) { this.title = title; }

    public String getRunningTitle() { return runningTitle; }

    public void setRunningTitle(String runningTitle) { this.runningTitle = runningTitle; }

    public String getDescription() { return description; }

    public void setDescription(String description) { this.description = description; }

    public String getKeywords() { return keywords; }

    public void setKeywords(String keywords) { this.keywords = keywords; }

    public List<String> getAliases() { return aliases; }

    public void setAliases(List<String> aliases) { this.aliases = aliases; }

    public String getAccessibility() { return accessibility; }

    public void setAccessibility(String accessibility) { this.accessibility = accessibility; }

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
        return Objects.hash(super.hashCode(), xnatProjectdata, projectAssets, directory, resources, subjects,
                title, runningTitle, description, keywords, aliases, accessibility);
    }

    @Override
    public String toString() {
        return addParentPropertiesToString(MoreObjects.toStringHelper(this))
                .add("title", title)
                .add("running-title", runningTitle)
                .add("description", description)
                .add("directory", directory)
                .add("resources", resources)
                .add("project-assets", projectAssets)
                .add("subjects", subjects)
                .toString();
    }
}
