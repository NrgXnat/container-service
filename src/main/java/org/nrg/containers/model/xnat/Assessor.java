package org.nrg.containers.model.xnat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import org.apache.commons.lang.StringUtils;
import org.nrg.containers.model.command.entity.CommandWrapperInputType;
import org.nrg.xdat.model.XnatImageassessordataI;
import org.nrg.xdat.om.XnatExperimentdata;
import org.nrg.xdat.om.XnatImageassessordata;
import org.nrg.xdat.om.XnatImagesessiondata;
import org.nrg.xdat.om.XnatResourcecatalog;
import org.nrg.xdat.security.helpers.Users;
import org.nrg.xft.XFTItem;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.helpers.uri.URIManager;
import org.nrg.xnat.helpers.uri.UriParserUtils;
import org.nrg.xnat.helpers.uri.archive.AssessorURII;
import org.nrg.xnat.helpers.uri.archive.impl.ExptAssessorURI;
import org.nrg.xnat.helpers.uri.archive.impl.ExptURI;
import org.nrg.xnat.helpers.uri.archive.impl.ProjSubjAssExptURI;

import javax.annotation.Nonnull;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@JsonInclude(Include.NON_NULL)
public class Assessor extends XnatModelObject {
    @JsonIgnore private XnatImageassessordataI xnatImageassessordataI;
    @JsonIgnore private XnatImagesessiondata parent;
    private List<Resource> resources;
    @JsonProperty("project-id") private String projectId;
    @JsonProperty("session-id") private String sessionId;
    @JsonProperty("datatype-string") private String datatypeString;
    private String name;

    @JsonIgnore private Project project = null;
    @JsonIgnore private Session session = null;

    public Assessor() {}

    public Assessor(final AssessorURII assessorURII, final boolean loadFiles,
                    @Nonnull final Set<String> loadTypes) {
        this.xnatImageassessordataI = assessorURII.getAssessor();
        if (ExptAssessorURI.class.isAssignableFrom(assessorURII.getClass())) {
            parent = ((ExptAssessorURI) assessorURII).getSession();
        } else if (ProjSubjAssExptURI.class.isAssignableFrom(assessorURII.getClass())) {
            parent = ((ProjSubjAssExptURI) assessorURII).getSession();
        }
        this.uri = ((URIManager.DataURIA) assessorURII).getUri();
        populateProperties(null, loadFiles, loadTypes);
    }

    public Assessor(final XnatImageassessordataI xnatImageassessordataI, final boolean loadFiles,
                    @Nonnull final Set<String> loadTypes) {
        this(xnatImageassessordataI, loadFiles, loadTypes, null, null);
    }

    public Assessor(final XnatImageassessordataI xnatImageassessordataI, final boolean loadFiles,
                    @Nonnull final Set<String> loadTypes, final String parentUri, final String rootArchivePath) {
        this.xnatImageassessordataI = xnatImageassessordataI;

        if (parentUri == null) {
            final String parentId = xnatImageassessordataI.getImagesessionId();
            if (StringUtils.isNotBlank(parentId)) {
                this.uri = "/archive/experiments/" + parentId + "/assessors/" + xnatImageassessordataI.getId();
            } else {
                this.uri = UriParserUtils.getArchiveUri(xnatImageassessordataI);
            }
        } else {
            this.uri = parentUri + "/assessors/" + xnatImageassessordataI.getId();
        }
        populateProperties(rootArchivePath, loadFiles, loadTypes);
    }

    private void populateProperties(final String rootArchivePath, final boolean loadFiles,
                                    @Nonnull final Set<String> loadTypes) {
        this.id = xnatImageassessordataI.getId();
        this.label = xnatImageassessordataI.getLabel();
        this.xsiType = xnatImageassessordataI.getXSIType();
        this.projectId = xnatImageassessordataI.getProject();
        try {
            this.name = (String) ((XnatImageassessordata) xnatImageassessordataI).getItem().getProperty("name");
        }catch (Throwable e){}

        XnatImageassessordata assessor = null;
        if (this.xnatImageassessordataI instanceof XnatImageassessordata) {
            assessor = (XnatImageassessordata) xnatImageassessordataI;
            parent = assessor.getImageSessionData();
        } else {
            parent = XnatImagesessiondata.getXnatImagesessiondatasById(xnatImageassessordataI.getImagesessionId(), Users.getAdminUser(), false);
        }

        this.sessionId = parent == null ? xnatImageassessordataI.getImagesessionId() : parent.getId();

        this.directory = null;
        if (parent == null) {
            // No parent -> treat it like an experiment. (This doesn't really make sense since an Image Assessor
            // must have a parent...)
            directory = assessor != null ? assessor.getSessionDir().toString() : null;
        } else {
            File sessionDir = parent.getSessionDir();
            if (sessionDir != null && sessionDir.isDirectory()) {
                final File assessorsDir = new File(sessionDir, "ASSESSORS");
                if (assessorsDir.isDirectory()) {
                    String dirname = assessor != null ? assessor.getArchiveDirectoryName() : label;
                    final File assessorDir = new File(assessorsDir, dirname);
                    if (assessorDir.isDirectory()) {
                        this.directory = assessorDir.getAbsolutePath();
                    }
                }
            }
        }

        if (loadFiles || loadTypes.contains(CommandWrapperInputType.RESOURCE.getName())) {
            // Image assessor resources are stored as out files rather that generic resources by default
            // Query both to be safe & consistent with legacy code
            resources = Stream.concat(
                        xnatImageassessordataI.getResources_resource().stream(),
                        xnatImageassessordataI.getOut_file().stream()
                    )
                    .filter(r -> r instanceof XnatResourcecatalog)
                    .map(r -> new Resource((XnatResourcecatalog) r, loadFiles, loadTypes, this.uri, rootArchivePath))
                    .collect(Collectors.toList());
        } else {
            resources = Collections.emptyList();
        }

        datatypeString = null;
        if((loadFiles || loadTypes.contains(CommandWrapperInputType.STRING.getName())) && xnatImageassessordataI != null){
            try {
                datatypeString = xnatImageassessordataI.toString();
            } catch (Throwable e){ }

        }
    }

    public static Function<URIManager.ArchiveItemURI, Assessor> uriToModelObject(final UserI userI, final boolean loadFiles,
                                                                                 @Nonnull final Set<String> loadTypes) {
        return uri -> {
            XnatImageassessordata assessor = null;
            if (uri != null) {
                if (AssessorURII.class.isAssignableFrom(uri.getClass())) {
                    assessor = ((AssessorURII) uri).getAssessor();
                    if (assessor != null) {
                        // Loading an Assessor by URI neglects to load any custom fields into the parent xnat object. Loading by ID.
                        assessor = XnatImageassessordata.getXnatImageassessordatasById(assessor.getId(), userI, true);
                    }
                } else if (ExptURI.class.isAssignableFrom(uri.getClass())) {
                    final XnatExperimentdata expt = ((ExptURI) uri).getExperiment();
                    if (expt != null &&
                        XnatImageassessordata.class.isAssignableFrom(expt.getClass())) {
                        // Loading an Assessor by URI neglects to load any custom fields into the parent xnat object. Loading by ID.
                        assessor = XnatImageassessordata.getXnatImageassessordatasById(expt.getId(), userI, true);
                    }
                }
            }
            return assessor == null ? null : new Assessor(assessor, loadFiles, loadTypes);
        };
    }

    public static Function<String, Assessor> idToModelObject(final UserI userI, final boolean loadFiles,
                                                             @Nonnull final Set<String> loadTypes) {
        return s -> {
            if (StringUtils.isBlank(s)) {
                return null;
            }
            final XnatImageassessordata xnatImageassessordata =
                    XnatImageassessordata.getXnatImageassessordatasById(s, userI, true);
            if (xnatImageassessordata != null) {
                return new Assessor(xnatImageassessordata, loadFiles, loadTypes);
            }
            return null;
        };
    }

    public Project getProject(final UserI userI, final boolean loadFiles,
                              @Nonnull final Set<String> loadTypes) {
        loadProject(userI, loadFiles, loadTypes);
        return project;
    }

    private void loadProject(final UserI userI, final boolean loadFiles, @Nonnull final Set<String> loadTypes) {
        if (project == null) {
            loadProjectId(userI);
            project = new Project(projectId, userI, loadFiles, loadTypes);
        }
    }

    public Session getSession(final UserI userI, final boolean loadFiles,
                              @Nonnull final Set<String> loadTypes) {
        loadSession(userI, loadFiles, loadTypes);
        return session;
    }

    private void loadSession(final UserI userI, final boolean loadFiles, @Nonnull final Set<String> loadTypes) {
        if (session == null) {
            loadSessionId(userI);
            session = new Session(sessionId, userI, loadFiles, loadTypes);
        }
    }

    public XnatImageassessordataI getXnatImageassessordataI() {
        return xnatImageassessordataI;
    }

    public void setXnatImageassessordataI(final XnatImageassessordataI xnatImageassessordataI) {
        this.xnatImageassessordataI = xnatImageassessordataI;
    }

    private void loadXnatImageassessordataI(final UserI userI) {
        if (xnatImageassessordataI == null) {
            xnatImageassessordataI = XnatImageassessordata.getXnatImageassessordatasById(id, userI, false);
        }
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

    private void loadProjectId(UserI userI) {
        if (projectId == null) {
            loadXnatImageassessordataI(userI);
            projectId = xnatImageassessordataI.getProject();
        }
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(final String sessionId) {
        this.sessionId = sessionId;
    }

    private void loadSessionId(UserI userI) {
        if (sessionId == null) {
            loadXnatImageassessordataI(userI);
            sessionId = xnatImageassessordataI.getImagesessionId();
        }
    }

    public String getDatatypeString() {
        return datatypeString;
    }

    public String getName() {
        return name;
    }

    @Override
    public XFTItem getXftItem(final UserI userI) {
        loadXnatImageassessordataI(userI);
        return xnatImageassessordataI == null ? null : ((XnatImageassessordata) xnatImageassessordataI).getItem();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        final Assessor that = (Assessor) o;
        return Objects.equals(resources, that.resources) &&
                Objects.equals(projectId, that.projectId) &&
                Objects.equals(sessionId, that.sessionId) &&
                Objects.equals(datatypeString, that.datatypeString) &&
                Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), resources, projectId, sessionId, datatypeString, name);
    }

    @Override
    public String toString() {
        return addParentPropertiesToString(MoreObjects.toStringHelper(this))
                .add("projectId", projectId)
                .add("sessionId", sessionId)
                .add("resources", resources.stream().map(XnatModelObject::getLabel).distinct().collect(Collectors.toList()))
                .toString();
    }
}
