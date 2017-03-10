package org.nrg.containers.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.MoreObjects;
import org.hibernate.envers.Audited;

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
@Audited
public class ContainerEntityInput {
    private long id;
    @JsonIgnore private ContainerEntity containerEntity;
    @Enumerated(EnumType.STRING) private Type type;
    private String name;
    private String value;

    public ContainerEntityInput() {}

    public static ContainerEntityInput raw(final String name, final String value) {
        final ContainerEntityInput input = new ContainerEntityInput();
        input.type = Type.RAW;
        input.name = name;
        input.value = value;
        return input;
    }

    public static ContainerEntityInput wrapper(final String name, final String value) {
        final ContainerEntityInput input = new ContainerEntityInput();
        input.type = Type.WRAPPER;
        input.name = name;
        input.value = value;
        return input;
    }

    public static ContainerEntityInput command(final String name, final String value) {
        final ContainerEntityInput input = new ContainerEntityInput();
        input.type = Type.COMMAND;
        input.name = name;
        input.value = value;
        return input;
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

    public Type getType() {
        return type;
    }

    public void setType(final Type type) {
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

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final ContainerEntityInput that = (ContainerEntityInput) o;
        return Objects.equals(this.containerEntity, that.containerEntity) &&
                type == that.type &&
                Objects.equals(this.name, that.name) &&
                Objects.equals(this.value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(containerEntity, type, name, value);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("id", id)
                .add("type", type)
                .add("name", name)
                .add("value", value)
                .toString();
    }

    public enum Type {
        RAW,
        WRAPPER,
        COMMAND
    }
}
