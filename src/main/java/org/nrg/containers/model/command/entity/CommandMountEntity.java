package org.nrg.containers.model.command.entity;

import com.google.common.base.MoreObjects;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.auto.Command.CommandMount;

import javax.annotation.Nonnull;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.ManyToOne;

import javax.persistence.Transient;
import java.io.Serializable;
import java.util.Objects;

@Entity
public class CommandMountEntity implements Serializable {
    private static final long serialVersionUID = -739696438807760178L;

    private long id;
    private CommandEntity commandEntity;
    private String name;
    private Boolean writable;
    private String containerPath;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public long getId() {
        return id;
    }

    public void setId(final long id) {
        this.id = id;
    }

    @ManyToOne
    public CommandEntity getCommandEntity() {
        return commandEntity;
    }

    public void setCommandEntity(final CommandEntity commandEntity) {
        this.commandEntity = commandEntity;
    }

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public Boolean isWritable() {
        return writable;
    }

    @Transient
    public Boolean getWritable() {
        return writable;
    }

    public void setWritable(final Boolean writable) {
        this.writable = writable;
    }

    public String getContainerPath() {
        return containerPath;
    }

    public void setContainerPath(final String remotePath) {
        this.containerPath = remotePath;
    }

    public static CommandMountEntity fromPojo(final CommandMount commandMount) {
        return new CommandMountEntity().update(commandMount);
    }

    @Nonnull
    public CommandMountEntity update(final Command.CommandMount commandMount) {
        this.setName(commandMount.name());
        this.setWritable(commandMount.writable());
        this.setContainerPath(commandMount.path());
        return this;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final CommandMountEntity that = (CommandMountEntity) o;
        return id == that.id &&
                Objects.equals(name, that.name) &&
                Objects.equals(writable, that.writable) &&
                Objects.equals(containerPath, that.containerPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id,
                name,
                writable,
                containerPath);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("id", id)
                .add("name", name)
                .add("writable", writable)
                .add("containerPath", containerPath)
                .toString();
    }
}