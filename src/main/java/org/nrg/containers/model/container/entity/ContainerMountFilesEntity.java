package org.nrg.containers.model.container.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import org.hibernate.envers.Audited;
import org.nrg.containers.model.container.auto.Container;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.ManyToOne;
import java.util.Objects;

/**
 * This represents a file that was mounted into a container. But we didn't use it anywhere in
 * the code. Now I think it just takes up space in the database for nothing.
 *
 * @deprecated Since 2.0.0
 */
@Entity
@Deprecated
public class ContainerMountFilesEntity {
    private long id;
    @JsonIgnore private ContainerEntityMount containerEntityMount;
    @JsonProperty("from-xnat-input") private String fromXnatInput;
    @JsonProperty("from-uri") private String fromUri;
    @JsonProperty("root-directory") private String rootDirectory;
    private String path;

    public ContainerMountFilesEntity() {}

    public static ContainerMountFilesEntity fromPojo(final Container.ContainerMountFiles containerMountFilesPojo) {
        final ContainerMountFilesEntity containerMountFilesEntity = new ContainerMountFilesEntity();
        containerMountFilesEntity.update(containerMountFilesPojo);
        return containerMountFilesEntity;
    }

    public ContainerMountFilesEntity update(final Container.ContainerMountFiles containerMountFilesPojo) {
        this.setId(containerMountFilesPojo.databaseId());
        this.setFromXnatInput(containerMountFilesPojo.fromXnatInput());
        this.setFromUri(containerMountFilesPojo.fromUri());
        this.setRootDirectory(containerMountFilesPojo.rootDirectory());
        this.setPath(containerMountFilesPojo.path());
        return this;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public long getId() {
        return id;
    }

    public void setId(final long id) {
        this.id = id;
    }

    @ManyToOne
    public ContainerEntityMount getContainerEntityMount() {
        return containerEntityMount;
    }

    public void setContainerEntityMount(final ContainerEntityMount containerEntityMount) {
        this.containerEntityMount = containerEntityMount;
    }

    public String getFromXnatInput() {
        return fromXnatInput;
    }

    public void setFromXnatInput(final String fromXnatInput) {
        this.fromXnatInput = fromXnatInput;
    }

    public String getFromUri() {
        return fromUri;
    }

    public void setFromUri(final String fromUri) {
        this.fromUri = fromUri;
    }

    public String getRootDirectory() {
        return rootDirectory;
    }

    public void setRootDirectory(final String rootDirectory) {
        this.rootDirectory = rootDirectory;
    }

    public String getPath() {
        return path;
    }

    public void setPath(final String path) {
        this.path = path;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final ContainerMountFilesEntity that = (ContainerMountFilesEntity) o;
        return Objects.equals(this.containerEntityMount, that.containerEntityMount) &&
                Objects.equals(this.fromXnatInput, that.fromXnatInput) &&
                Objects.equals(this.fromUri, that.fromUri) &&
                Objects.equals(this.rootDirectory, that.rootDirectory) &&
                Objects.equals(this.path, that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(containerEntityMount, fromXnatInput, fromUri, rootDirectory, path);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("id", id)
                .add("fromWrapperInput", fromXnatInput)
                .add("fromUri", fromUri)
                .add("rootDirectory", rootDirectory)
                .add("path", path)
                .toString();
    }
}
