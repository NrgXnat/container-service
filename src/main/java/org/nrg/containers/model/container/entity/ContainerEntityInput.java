package org.nrg.containers.model.container.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.MoreObjects;
import org.hibernate.envers.Audited;
import org.nrg.containers.model.container.ContainerInputType;
import org.nrg.containers.model.container.auto.Container;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.ManyToOne;
import java.util.Objects;

@Entity
public class ContainerEntityInput {
    private long id;
    @JsonIgnore private ContainerEntity containerEntity;
    @Enumerated(EnumType.STRING) private ContainerInputType type;
    private String name;
    private String value;
    private Boolean sensitive;

    public ContainerEntityInput() {}

    public static ContainerEntityInput create(final String name, final String value, final ContainerInputType type) {
        final ContainerEntityInput input = new ContainerEntityInput();
        input.type = type;
        input.name = name;
        input.value = value;
        input.sensitive = false;
        return input;
    }

    public static ContainerEntityInput fromPojo(final Container.ContainerInput containerInputPojo) {
        final ContainerEntityInput containerEntityInput = new ContainerEntityInput();
        containerEntityInput.update(containerInputPojo);
        return containerEntityInput;
    }

    public ContainerEntityInput update(final Container.ContainerInput containerInputPojo) {
        this.setId(containerInputPojo.databaseId());
        this.setType(containerInputPojo.type());
        this.setName(containerInputPojo.name());
        this.setValue(containerInputPojo.value());
        this.setSensitive(containerInputPojo.sensitive());
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

    public ContainerInputType getType() {
        return type;
    }

    public void setType(final ContainerInputType type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    @Column(columnDefinition = "TEXT")
    public String getValue() {
        return value;
    }

    public void setValue(final String value) {
        this.value = value;
    }

    public Boolean getSensitive() {
        return sensitive;
    }

    public void setSensitive(final Boolean sensitive) {
        this.sensitive = sensitive;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final ContainerEntityInput that = (ContainerEntityInput) o;
        return Objects.equals(this.containerEntity, that.containerEntity) &&
                type == that.type &&
                sensitive == that.sensitive &&
                Objects.equals(this.name, that.name) &&
                Objects.equals(this.value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(containerEntity, type, name, value, sensitive);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("id", id)
                .add("type", type)
                .add("name", name)
                .add("value", sensitive ? "*****" : value)
                .add("sensitive", sensitive)
                .toString();
    }

}
