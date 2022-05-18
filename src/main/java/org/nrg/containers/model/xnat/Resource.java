package org.nrg.containers.model.xnat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nrg.action.ServerException;
import org.nrg.containers.model.command.entity.CommandWrapperInputType;
import org.nrg.xdat.bean.CatCatalogBean;
import org.nrg.xdat.model.XnatAbstractresourceI;
import org.nrg.xdat.model.XnatResourcecatalogI;
import org.nrg.xdat.om.XnatAbstractresource;
import org.nrg.xdat.om.XnatResourcecatalog;
import org.nrg.xft.XFTItem;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.helpers.uri.URIManager;
import org.nrg.xnat.helpers.uri.UriParserUtils;
import org.nrg.xnat.helpers.uri.archive.ResourceURII;
import org.nrg.xnat.utils.CatalogUtils;

import javax.annotation.Nonnull;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

@Slf4j
@JsonInclude(Include.NON_NULL)
public class Resource extends XnatModelObject {

    @JsonIgnore private XnatResourcecatalog xnatResourcecatalog;
    @JsonProperty("integer-id") private Integer integerId;
    private List<XnatFile> files;
    @JsonProperty("datatype-string") private String datatypeString;
    @JsonProperty("parent-uri") private String parentUri;

    public Resource() {}

    public Resource(final ResourceURII resourceURII, final boolean loadFiles,
                    @Nonnull final Set<String> loadTypes) {
        final XnatAbstractresourceI xnatAbstractresourceI = resourceURII.getXnatResource();
        if (xnatAbstractresourceI instanceof XnatResourcecatalog) {
            this.xnatResourcecatalog = (XnatResourcecatalog) xnatAbstractresourceI;
        }
        this.uri = resourceURII.getUri();
        populateProperties(null, loadFiles, loadTypes);
    }

    public Resource(final XnatResourcecatalog xnatResourcecatalog, final boolean loadFiles,
                    @Nonnull final Set<String> loadTypes) {
        this(xnatResourcecatalog, loadFiles, loadTypes, null, null);
    }

    public Resource(final XnatResourcecatalog xnatResourcecatalog, final boolean loadFiles,
                    @Nonnull final Set<String> loadTypes,
                    final String parentUri, final String rootArchivePath) {
        this.xnatResourcecatalog = xnatResourcecatalog;
        this.parentUri = parentUri;

        if (parentUri == null) {
            this.uri = xnatResourcecatalog.getUri();
            this.parentUri = UriParserUtils.getArchiveUri(xnatResourcecatalog.getParent());
        } else {
            this.uri = parentUri + "/resources/" + xnatResourcecatalog.getLabel();
        }
        populateProperties(rootArchivePath, loadFiles, loadTypes);
    }

    private void populateProperties(final String rootArchivePath, final boolean loadFiles,
                                    @Nonnull final Set<String> loadTypes) {
        this.integerId = xnatResourcecatalog.getXnatAbstractresourceId();
        this.id = xnatResourcecatalog.getLabel();
        this.label = xnatResourcecatalog.getLabel();
        this.xsiType = xnatResourcecatalog.getXSIType();

        String project = null;  // TODO pass in project
        this.directory = null;
        try {
            File catalogFile = CatalogUtils.getOrCreateCatalogFile(rootArchivePath, xnatResourcecatalog, project);
            this.directory = catalogFile.getParent();
        } catch (ServerException e) {
            log.error("Could not get catalog file: {}", e.getMessage(), e);
        }
        this.files = new ArrayList<>();

        // Only get catalog entry details if we need them
        if (loadFiles || loadTypes.contains(CommandWrapperInputType.FILE.getName()) ||
                loadTypes.contains(CommandWrapperInputType.FILES.getName())) {
            final CatCatalogBean cat = CatalogUtils.getCatalog(rootArchivePath, xnatResourcecatalog, project);
            if (cat == null) {
                // would prefer to throw CommandResolutionException, but Functions, below, can't throw checked exceptions
                throw new RuntimeException("Unable to load catalog for resource " + xnatResourcecatalog
                        + ", have your admin check xdat.log for the cause");
            }
            final Path parentPath = Paths.get(this.uri + "/files/");

            // includeFile = false rather than includeFile = loadFiles because we don't want to retrieve the actual file
            // object from the catalog entry since this will pull remote files into the archive & we want them in build
            final List<Object[]> entryDetails = CatalogUtils.getEntryDetails(cat, this.directory, parentPath.toString(),
                    xnatResourcecatalog, false, null, null, "URI");

            for (final Object[] entry : entryDetails) {
                String uri      = (String) entry[2]; // This is the parentUri + relative path to file
                String relPath  = parentPath.relativize(Paths.get(uri)).toString(); // get that relative path
                String filePath = Paths.get(this.directory).resolve(relPath).toString(); // append rel path to parent dir
                String tagsCsv  = (String) entry[4];
                String format   = (String) entry[5];
                String content  = (String) entry[5];
                String sizeStr  = StringUtils.defaultIfBlank((String) entry[1], null);
                Long size       = sizeStr == null ? null : Long.parseLong(sizeStr);
                String checksum = (String) entry[8];
                files.add(new XnatFile(this.uri, relPath, filePath, tagsCsv, format, content, size, checksum));
            }
        }

        datatypeString = null;
        if(loadTypes!= null && loadTypes.contains(CommandWrapperInputType.STRING.getName()) && xnatResourcecatalog != null){
            try {
                datatypeString = xnatResourcecatalog.toString();
            } catch (Throwable e){ }
        }

    }

    public static Function<URIManager.ArchiveItemURI, Resource> uriToModelObject(final boolean loadFiles,
                                                                                 @Nonnull final Set<String> loadTypes) {
        return uri -> {
            if (uri != null &&
                    ResourceURII.class.isAssignableFrom(uri.getClass())) {
                final XnatAbstractresourceI resource = ((ResourceURII) uri).getXnatResource();
                if (resource != null) {
                    return new Resource((ResourceURII) uri, loadFiles, loadTypes);
                }
            }

            return null;
        };
    }

    public static Function<String, Resource> idToModelObject(final UserI userI, final boolean loadFiles,
                                                             @Nonnull final Set<String> loadTypes) {
        return s -> {
            if (StringUtils.isBlank(s)) {
                return null;
            }
            final XnatAbstractresourceI xnatAbstractresourceI =
                    XnatAbstractresource.getXnatAbstractresourcesByXnatAbstractresourceId(s, userI, true);
            if (xnatAbstractresourceI instanceof XnatResourcecatalog) {
                return new Resource((XnatResourcecatalog) xnatAbstractresourceI, loadFiles, loadTypes);
            }
            return null;
        };
    }

    public Project getProject(final UserI userI) {
        loadXnatResourcecatalog(userI);
        // TODO This does not work. I wish it did.
        // return new Project(xnatResourcecatalog.getProject(), userI);
        return null;
    }

    public XnatResourcecatalogI getXnatResourcecatalog() {
        return xnatResourcecatalog;
    }

    public void setXnatResourcecatalog(final XnatResourcecatalog xnatResourcecatalog) {
        this.xnatResourcecatalog = xnatResourcecatalog;
    }

    private void loadXnatResourcecatalog(final UserI userI) {
        if (xnatResourcecatalog == null) {
            xnatResourcecatalog = XnatResourcecatalog.getXnatResourcecatalogsByXnatAbstractresourceId(integerId,
                    userI, false);
        }
    }

    public List<XnatFile> getFiles() {
        return files;
    }

    public void setFiles(final List<XnatFile> files) {
        this.files = files;
    }

    public String getDatatypeString() {
        return datatypeString;
    }

    public String getParentUri() {
        return parentUri;
    }

    public void setParentUri(String parentUri) {
        this.parentUri = parentUri;
    }

    @Override
    public XFTItem getXftItem(final UserI userI) {
        loadXnatResourcecatalog(userI);
        return xnatResourcecatalog == null ? null : xnatResourcecatalog.getItem();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        final Resource that = (Resource) o;
        return Objects.equals(integerId, that.integerId) &&
                Objects.equals(files, that.files) &&
                Objects.equals(datatypeString, that.datatypeString) &&
                Objects.equals(parentUri, that.parentUri);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), integerId, files, datatypeString, parentUri);
    }

    @Override
    public String toString() {
        return addParentPropertiesToString(MoreObjects.toStringHelper(this))
                .add("files", files)
                .toString();
    }
}
