package org.nrg.containers.model.xnat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import org.nrg.containers.model.command.entity.CommandWrapperInputType;
import org.nrg.xdat.model.XnatAbstractresourceI;
import org.nrg.xdat.model.XnatImagescandataI;
import org.nrg.xdat.om.XnatImagescandata;
import org.nrg.xdat.om.XnatResourcecatalog;
import org.nrg.xft.XFTItem;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.helpers.uri.URIManager;
import org.nrg.xnat.helpers.uri.UriParserUtils;
import org.nrg.xnat.helpers.uri.archive.ScanURII;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@JsonInclude(Include.NON_NULL)
public class Scan extends XnatModelObject {
    @JsonIgnore private XnatImagescandataI xnatImagescandataI;
    @JsonProperty("integer-id") private Integer integerId;
    @JsonProperty("scan-type") private String scanType;
    @JsonProperty("project-id") private String projectId;
    @JsonProperty("session-id") private String sessionId;
    private List<Resource> resources;

    private Integer frames;
    private String note;
    private String modality;
    private String quality;
    private String scanner;
    @JsonProperty("scanner-manufacturer") private String scannerManufacturer;
    @JsonProperty("scanner-model") private String scannerModel;
    @JsonProperty("scanner-software-version") private String scannerSoftwareVersion;
    @JsonProperty("series-description") private String seriesDescription;
    @JsonProperty("start-time") private Object startTime;
    private String uid;
    @JsonProperty("datatype-string") private String datatypeString;

    @JsonIgnore private Project project = null;
    @JsonIgnore private Session session = null;

    public Scan() {}

    public Scan(final ScanURII scanURII, final boolean loadFiles,
                @Nonnull final Set<String> loadTypes) {
        this.xnatImagescandataI = scanURII.getScan();
        this.uri = ((URIManager.ArchiveItemURI)scanURII).getUri();
        populateProperties(null, loadFiles, loadTypes);
    }

    public Scan(final XnatImagescandataI xnatImagescandataI, final boolean loadFiles,
                @Nonnull final Set<String> loadTypes, final String parentUri, final String rootArchivePath) {
        this.xnatImagescandataI = xnatImagescandataI;
        if (parentUri == null) {
            this.uri = UriParserUtils.getArchiveUri(xnatImagescandataI);
        } else {
            this.uri = parentUri + "/scans/" + xnatImagescandataI.getId();
        }
        populateProperties(rootArchivePath, loadFiles, loadTypes);
    }

    private void populateProperties(final String rootArchivePath, final boolean loadFiles,
                                    @Nonnull final Set<String> loadTypes) {
        this.integerId = xnatImagescandataI.getXnatImagescandataId();
        this.id = xnatImagescandataI.getId();
        this.sessionId = xnatImagescandataI.getImageSessionId();
        this.projectId = xnatImagescandataI.getProject();
        this.xsiType = xnatImagescandataI.getXSIType();
        this.scanType = xnatImagescandataI.getType();
        this.label = String.format("%s - %s", this.id, this.scanType);

        this.frames = xnatImagescandataI.getFrames();
        this.note = xnatImagescandataI.getNote();
        this.modality = xnatImagescandataI.getModality();
        this.quality = xnatImagescandataI.getQuality();
        this.scanner = xnatImagescandataI.getScanner();
        this.scannerManufacturer = xnatImagescandataI.getScanner_manufacturer();
        this.scannerModel = xnatImagescandataI.getScanner_model();
        this.scannerSoftwareVersion = xnatImagescandataI.getScanner_softwareversion();
        this.seriesDescription = xnatImagescandataI.getSeriesDescription();
        this.startTime = xnatImagescandataI.getStarttime();
        this.uid = xnatImagescandataI.getUid();

        this.directory = null;
        if (this.xnatImagescandataI instanceof XnatImagescandata) {
            this.directory = ((XnatImagescandata) xnatImagescandataI).deriveScanDir();
        }

        this.resources = new ArrayList<>();
        if (loadFiles || loadTypes.contains(CommandWrapperInputType.RESOURCE.getName())) {
            for (final XnatAbstractresourceI xnatAbstractresourceI : this.xnatImagescandataI.getFile()) {
                if (xnatAbstractresourceI instanceof XnatResourcecatalog) {
                    resources.add(new Resource((XnatResourcecatalog) xnatAbstractresourceI, loadFiles,
                            loadTypes, this.uri, rootArchivePath));
                }
            }
        }

        datatypeString = null;
        if(loadTypes.contains(CommandWrapperInputType.STRING.getName()) && xnatImagescandataI != null){
            try {
                datatypeString = xnatImagescandataI.toString();
            } catch (Throwable e){ }
        }

    }

    public static Function<URIManager.ArchiveItemURI, Scan> uriToModelObject(final boolean loadFiles,
                                                                             @Nonnull final Set<String> loadTypes) {
        return uri -> {
            if (uri != null &&
                    ScanURII.class.isAssignableFrom(uri.getClass())) {
                return new Scan((ScanURII) uri, loadFiles, loadTypes);
            }

            return null;
        };
    }

    public static Function<String, Scan> idToModelObject(final UserI userI, final boolean loadFiles,
                                                         @Nonnull final Set<String> loadTypes) {
        return null;
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

    public XnatImagescandataI getXnatImagescandataI() {
        return xnatImagescandataI;
    }

    public void setXnatImagescandataI(final XnatImagescandataI xnatImagescandataI) {
        this.xnatImagescandataI = xnatImagescandataI;
    }

    private void loadXnatImagescandataI(final UserI userI) {
        if (xnatImagescandataI == null) {
            xnatImagescandataI = XnatImagescandata.getXnatImagescandatasByXnatImagescandataId(integerId, userI, false);
        }
    }

    public String getScanType() {
        return scanType;
    }

    public void setScanType(final String scanType) {
        this.scanType = scanType;
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
            loadXnatImagescandataI(userI);
            projectId = xnatImagescandataI.getProject();
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
            loadXnatImagescandataI(userI);
            sessionId = xnatImagescandataI.getImageSessionId();
        }
    }

    public Integer getIntegerId() {
        return integerId;
    }

    public void setIntegerId(final Integer integerId) {
        this.integerId = integerId;
    }

    public Integer getFrames() {
        return frames;
    }

    public void setFrames(final Integer frames) {
        this.frames = frames;
    }

    public String getNote() {
        return note;
    }

    public void setNote(final String note) {
        this.note = note;
    }

    public String getModality() {
        return modality;
    }

    public void setModality(final String modality) {
        this.modality = modality;
    }

    public String getQuality() {
        return quality;
    }

    public void setQuality(final String quality) {
        this.quality = quality;
    }

    public String getScanner() {
        return scanner;
    }

    public void setScanner(final String scanner) {
        this.scanner = scanner;
    }

    public String getScannerManufacturer() {
        return scannerManufacturer;
    }

    public void setScannerManufacturer(final String scannerManufacturer) {
        this.scannerManufacturer = scannerManufacturer;
    }

    public String getScannerModel() {
        return scannerModel;
    }

    public void setScannerModel(final String scannerModel) {
        this.scannerModel = scannerModel;
    }

    public String getScannerSoftwareVersion() {
        return scannerSoftwareVersion;
    }

    public void setScannerSoftwareVersion(final String scannerSoftwareVersion) {
        this.scannerSoftwareVersion = scannerSoftwareVersion;
    }

    public String getSeriesDescription() {
        return seriesDescription;
    }

    public void setSeriesDescription(final String seriesDescription) {
        this.seriesDescription = seriesDescription;
    }

    public Object getStartTime() {
        return startTime;
    }

    public void setStartTime(final Object startTime) {
        this.startTime = startTime;
    }

    public String getUid() {
        return uid;
    }

    public void setUid(final String uid) {
        this.uid = uid;
    }

    public String getDatatypeString() {
        return datatypeString;
    }

    @Override
    public XFTItem getXftItem(final UserI userI) {
        loadXnatImagescandataI(userI);
        return xnatImagescandataI == null ? null : ((XnatImagescandata)xnatImagescandataI).getItem();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        final Scan that = (Scan) o;
        return Objects.equals(integerId, that.integerId) &&
                Objects.equals(scanType, that.scanType) &&
                Objects.equals(projectId, that.projectId) &&
                Objects.equals(sessionId, that.sessionId) &&
                Objects.equals(resources, that.resources) &&
                Objects.equals(frames, that.frames) &&
                Objects.equals(note, that.note) &&
                Objects.equals(modality, that.modality) &&
                Objects.equals(quality, that.quality) &&
                Objects.equals(scanner, that.scanner) &&
                Objects.equals(scannerManufacturer, that.scannerManufacturer) &&
                Objects.equals(scannerModel, that.scannerModel) &&
                Objects.equals(scannerSoftwareVersion, that.scannerSoftwareVersion) &&
                Objects.equals(seriesDescription, that.seriesDescription) &&
                Objects.equals(startTime, that.startTime) &&
                Objects.equals(uid, that.uid) &&
                Objects.equals(datatypeString, that.datatypeString);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), integerId, scanType, projectId, sessionId, resources,
                frames, note, modality, quality, scanner, scannerManufacturer, scannerModel, scannerSoftwareVersion,
                seriesDescription, startTime, uid, datatypeString);
    }

    @Override
    public String toString() {
        return addParentPropertiesToString(MoreObjects.toStringHelper(this))
                .add("integerId", integerId)
                .add("sessionId", sessionId)
                .add("scanType", scanType)
                .add("modality", modality)
                .add("resources", resources.stream().map(XnatModelObject::getLabel).distinct().collect(Collectors.toList()))
                .toString();
    }
}
