package org.nrg.containers.model.container.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Function;
import com.google.common.base.MoreObjects;
import com.google.common.collect.Lists;
import org.hibernate.envers.Audited;
import org.nrg.containers.model.container.auto.Container;

import javax.persistence.CascadeType;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.Transient;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Entity
public class ContainerEntityMount implements Serializable {
    private long id;
    @JsonIgnore private ContainerEntity containerEntity;
    @JsonProperty(required = true) private String name;
    @JsonProperty("writable") private boolean writable;
    @JsonProperty("xnat-host-path") private String xnatHostPath;
    @JsonProperty("container-host-path") private String containerHostPath;
    @JsonProperty("container-path") private String containerPath;
    @JsonProperty("input-files") private List<ContainerMountFilesEntity> inputFiles;

    public ContainerEntityMount() {}

    public static ContainerEntityMount fromPojo(final Container.ContainerMount containerMountPojo) {
        final ContainerEntityMount containerEntityMount = new ContainerEntityMount();
        containerEntityMount.update(containerMountPojo);
        return containerEntityMount;
    }

    @SuppressWarnings("deprecation")
    public ContainerEntityMount update(final Container.ContainerMount containerMountPojo) {
        this.setId(containerMountPojo.databaseId());
        this.setName(containerMountPojo.name());
        this.setWritable(containerMountPojo.writable());
        this.setXnatHostPath(containerMountPojo.xnatHostPath());
        this.setContainerHostPath(containerMountPojo.containerHostPath());
        this.setContainerPath(containerMountPojo.containerPath());
        this.setInputFiles(
                Lists.newArrayList(Lists.transform(containerMountPojo.inputFiles(),
                        new Function<Container.ContainerMountFiles, ContainerMountFilesEntity>() {
                    @Override
                    public ContainerMountFilesEntity apply(final Container.ContainerMountFiles input) {
                        return ContainerMountFilesEntity.fromPojo(input);
                    }
                })));
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
    public ContainerEntity getContainerEntity() {
        return containerEntity;
    }

    public void setContainerEntity(final ContainerEntity containerEntity) {
        this.containerEntity = containerEntity;
    }

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public boolean getWritable() {
        return writable;
    }

    public void setWritable(final boolean writable) {
        this.writable = writable;
    }

    public String getXnatHostPath() {
        return xnatHostPath;
    }

    public void setXnatHostPath(final String xnatHostPath) {
        this.xnatHostPath = xnatHostPath;
    }

    public String getContainerHostPath() {
        return containerHostPath;
    }

    public void setContainerHostPath(final String containerHostPath) {
        this.containerHostPath = containerHostPath;
    }

    public String getContainerPath() {
        return containerPath;
    }

    public void setContainerPath(final String containerPath) {
        this.containerPath = containerPath;
    }

    @Transient
    @JsonIgnore
    public boolean isWritable() {
        return writable;
    }

    /**
     * This used to return a list of the files that were found in an input mount. But we didn't use it anywhere in
     * the code. Now I think it just takes up space in the database for nothing.
     *
     * @return A list of the mounted files
     * @deprecated Since 2.0.0
     */
    @Deprecated
    @OneToMany(mappedBy = "containerEntityMount", cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
    public List<ContainerMountFilesEntity> getInputFiles() {
        return inputFiles;
    }

    /**
     * This used to return a list of the files that were found in an input mount. But we didn't use it anywhere in
     * the code. Now I think it just takes up space in the database for nothing.
     *
     * @param inputFiles the input files
     * @deprecated Since 2.0.0
     */
    @Deprecated
    public void setInputFiles(final List<ContainerMountFilesEntity> inputFiles) {
        this.inputFiles = inputFiles == null ?
                Lists.<ContainerMountFilesEntity>newArrayList() :
                inputFiles;
        for (final ContainerMountFilesEntity files : this.inputFiles) {
            files.setContainerEntityMount(this);
        }
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final ContainerEntityMount that = (ContainerEntityMount) o;
        return Objects.equals(this.containerEntity, that.containerEntity) &&
                Objects.equals(this.name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(containerEntity, name);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("id", id)
                .add("name", name)
                .add("writable", writable)
                .add("xnatHostPath", xnatHostPath)
                .add("containerHostPath", containerHostPath)
                .add("remotePath", containerPath)
                .add("inputFiles", inputFiles)
                .toString();
    }
}