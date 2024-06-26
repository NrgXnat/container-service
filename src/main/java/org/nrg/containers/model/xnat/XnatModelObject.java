package org.nrg.containers.model.xnat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.google.common.base.MoreObjects;
import org.nrg.xft.XFTItem;
import org.nrg.xft.security.UserI;

import java.util.Objects;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Project.class, name = "Project"),
        @JsonSubTypes.Type(value = ProjectAsset.class, name = "ProjectAsset"),
        @JsonSubTypes.Type(value = Subject.class, name = "Subject"),
        @JsonSubTypes.Type(value = Session.class, name = "Session"),
        @JsonSubTypes.Type(value = SubjectAssessor.class, name = "SubjectAssessor"),
        @JsonSubTypes.Type(value = Scan.class, name = "Scan"),
        @JsonSubTypes.Type(value = Assessor.class, name = "Assessor"),
        @JsonSubTypes.Type(value = Resource.class, name = "Resource"),
        @JsonSubTypes.Type(value = XnatFile.class, name = "File")
})
@JsonInclude(Include.NON_NULL)
public abstract class XnatModelObject {
    protected String id;
    protected String label;
    protected String xsiType;
    protected String uri;
    protected String directory;

    public String getId() {
        return id;
    }

    public void setId(final String id) {
        this.id = id;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(final String label) {
        this.label = label;
    }

    public String getXsiType() {
        return xsiType;
    }

    public void setXsiType(final String xsiType) {
        this.xsiType = xsiType;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(final String uri) {
        this.uri = uri;
    }

    public String getDirectory() {
        return directory;
    }

    public void setDirectory(String directory) {
        this.directory = directory;
    }

    @JsonIgnore
    public abstract XFTItem getXftItem(final UserI userI);

    @JsonIgnore
    public String getRootPath() {
        return getDirectory();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final XnatModelObject that = (XnatModelObject) o;
        return Objects.equals(id, that.id) &&
                Objects.equals(label, that.label) &&
                Objects.equals(xsiType, that.xsiType) &&
                Objects.equals(uri, that.uri) &&
                Objects.equals(directory, that.directory);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, label, xsiType, uri, directory);
    }

    public MoreObjects.ToStringHelper addParentPropertiesToString(final MoreObjects.ToStringHelper helper) {
        return helper
                .add("id", id)
                .add("label", label)
                .add("xsiType", xsiType)
                .add("uri", uri)
                .add("directory", directory);
    }
}
