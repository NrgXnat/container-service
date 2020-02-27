package org.nrg.containers.model.command.entity;

import com.google.common.base.MoreObjects;
import org.hibernate.envers.Audited;
import org.nrg.containers.model.command.auto.Command;

import javax.annotation.Nonnull;
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
public class CommandOutputEntity implements Serializable {

    private long id;
    private CommandEntity commandEntity;
    private String name;
    private String description;
    private Boolean required;
    private String mount;
    private String path;
    private String glob;

    public static CommandOutputEntity fromPojo(final Command.CommandOutput commandOutput) {
        return new CommandOutputEntity().update(commandOutput);
    }

    @Nonnull
    public CommandOutputEntity update(final Command.CommandOutput commandOutput) {
        if (this.id == 0L || commandOutput.id() != 0L) {
            this.setId(commandOutput.id());
        }
        this.setName(commandOutput.name());
        this.setDescription(commandOutput.description());
        this.setRequired(commandOutput.required());
        this.setMount(commandOutput.mount());
        this.setPath(commandOutput.path());
        this.setGlob(commandOutput.glob());
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
    public CommandEntity getCommandEntity() {
        return commandEntity;
    }

    public void setCommandEntity(final CommandEntity commandEntity) {
        this.commandEntity = commandEntity;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Column(columnDefinition = "TEXT")
    public String getDescription() {
        return description;
    }

    public void setDescription(final String description) {
        this.description = description;
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

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final CommandOutputEntity that = (CommandOutputEntity) o;
        return Objects.equals(this.commandEntity, that.commandEntity) &&
                Objects.equals(this.name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commandEntity, name);

    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("id", id)
                .add("name", name)
                .add("description", description)
                .add("required", required)
                .add("mount", mount)
                .add("path", path)
                .add("glob", glob)
                .toString();
    }
}