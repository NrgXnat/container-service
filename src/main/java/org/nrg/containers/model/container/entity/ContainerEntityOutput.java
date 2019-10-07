package org.nrg.containers.model.container.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.MoreObjects;
import org.hibernate.envers.Audited;
import org.nrg.containers.model.container.auto.Container;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.ManyToOne;
import javax.persistence.Transient;
import java.io.Serializable;
import java.util.Objects;

@Entity
public class ContainerEntityOutput implements Serializable {
    private long id;
    @JsonIgnore private ContainerEntity containerEntity;
    private String name;
    private String fromCommandOutput;
    private String fromOutputHandler;
    private String type;
    private Boolean required;
    private String mount;
    private String path;
    private String glob;
    private String label;
    private String format;
    private String created;
    private String handledByXnatCommandInput;
    private String viaWrapupContainer;

    public ContainerEntityOutput() {}

    public static ContainerEntityOutput fromPojo(final Container.ContainerOutput containerOutputPojo) {
        final ContainerEntityOutput containerEntityOutput = new ContainerEntityOutput();
        containerEntityOutput.update(containerOutputPojo);
        return containerEntityOutput;
    }

    public ContainerEntityOutput update(final Container.ContainerOutput containerOutputPojo) {
        this.setId(containerOutputPojo.databaseId());
        this.setName(containerOutputPojo.name());
        this.setFromCommandOutput(containerOutputPojo.fromCommandOutput());
        this.setFromOutputHandler(containerOutputPojo.fromOutputHandler());
        this.setType(containerOutputPojo.type());
        this.setRequired(containerOutputPojo.required());
        this.setMount(containerOutputPojo.mount());
        this.setPath(containerOutputPojo.path());
        this.setGlob(containerOutputPojo.glob());
        this.setLabel(containerOutputPojo.label());
        this.setFormat(containerOutputPojo.format());
        this.setCreated(containerOutputPojo.created());
        this.setHandledByXnatCommandInput(containerOutputPojo.handledBy());
        this.setViaWrapupContainer(containerOutputPojo.viaWrapupContainer());
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

    public void setName(String name) {
        this.name = name;
    }

    public String getFromCommandOutput() {
        return fromCommandOutput;
    }

    public void setFromCommandOutput(final String fromCommandOutput) {
        this.fromCommandOutput = fromCommandOutput;
    }

    public String getFromOutputHandler() {
        return fromOutputHandler;
    }

    public void setFromOutputHandler(final String fromOutputHandler) {
        this.fromOutputHandler = fromOutputHandler;
    }

    public String getType() {
        return type;
    }

    public void setType(final String type) {
        this.type = type;
    }

    public Boolean getRequired() {
        return required;
    }

    @Transient
    public boolean isRequired() {
        return required != null && required;
    }

    public void setRequired(final Boolean required) {
        this.required = required;
    }

    public String getMount() {
        return mount;
    }

    public void setMount(final String mount) {
        this.mount = mount;
    }

    public String getPath() {
        return path;
    }

    public void setPath(final String path) {
        this.path = path;
    }

    public String getGlob() {
        return glob;
    }

    public void setGlob(final String glob) {
        this.glob = glob;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(final String label) {
        this.label = label;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(final String format) {
        this.format = format;
    }

    public String getHandledByXnatCommandInput() {
        return handledByXnatCommandInput;
    }

    public void setHandledByXnatCommandInput(final String handledByXnatCommandInput) {
        this.handledByXnatCommandInput = handledByXnatCommandInput;
    }

    public String getViaWrapupContainer() {
        return viaWrapupContainer;
    }

    public void setViaWrapupContainer(final String viaWrapupContainer) {
        this.viaWrapupContainer = viaWrapupContainer;
    }

    @Column(columnDefinition = "TEXT")
    public String getCreated() {
        return created;
    }

    public void setCreated(final String created) {
        this.created = created;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final ContainerEntityOutput that = (ContainerEntityOutput) o;
        return Objects.equals(this.id, that.id) &&
                Objects.equals(this.name, that.name) &&
                Objects.equals(this.fromCommandOutput, that.fromCommandOutput) &&
                Objects.equals(this.fromOutputHandler, that.fromOutputHandler) &&
                Objects.equals(this.type, that.type) &&
                Objects.equals(this.required, that.required) &&
                Objects.equals(this.mount, that.mount) &&
                Objects.equals(this.path, that.path) &&
                Objects.equals(this.glob, that.glob) &&
                Objects.equals(this.label, that.label) &&
                Objects.equals(this.format, that.format) &&
                Objects.equals(this.handledByXnatCommandInput, that.handledByXnatCommandInput) &&
                Objects.equals(this.viaWrapupContainer, that.viaWrapupContainer) &&
                Objects.equals(this.created, that.created);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, fromCommandOutput, fromOutputHandler, type, required, mount, path, glob, label, format, handledByXnatCommandInput, viaWrapupContainer, created);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("id", id)
                .add("name", name)
                .add("fromCommandOutput", fromCommandOutput)
                .add("fromOutputHandler", fromOutputHandler)
                .add("type", type)
                .add("required", required)
                .add("mount", mount)
                .add("path", path)
                .add("glob", glob)
                .add("label", label)
                .add("format", format)
                .add("handledByXnatInput", handledByXnatCommandInput)
                .add("viaWrapupContainer", viaWrapupContainer)
                .add("created", created)
                .toString();
    }
}