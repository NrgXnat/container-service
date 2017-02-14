package org.nrg.containers.model;

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.collect.Maps;
import org.hibernate.envers.Audited;
import org.nrg.containers.model.auto.CommandPojo;

import javax.persistence.Column;
import javax.persistence.DiscriminatorColumn;
import javax.persistence.DiscriminatorType;
import javax.persistence.DiscriminatorValue;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Transient;
import java.util.Map;
import java.util.Objects;

@Entity
@DiscriminatorValue("docker")
public class DockerCommand extends Command {
    public static final CommandType type = CommandType.DOCKER;

    private String index;
    private String hash;
    private Map<String, String> ports;

    static DockerCommand fromPojo(final CommandPojo commandPojo) {
        final DockerCommand command = new DockerCommand();
        command.setIndex(commandPojo.index());
        command.setHash(commandPojo.hash());
        command.setPorts(commandPojo.ports());
        return command;
    }

    @Transient
    public CommandType getType() {
        return type;
    }

    public void setType(final CommandType type) {}

    public String getIndex() {
        return index;
    }

    public void setIndex(final String index) {
        this.index = index;
    }

    public String getHash() {
        return hash;
    }

    public void setHash(final String hash) {
        this.hash = hash;
    }

    @ElementCollection
    public Map<String, String> getPorts() {
        return ports;
    }

    public void setPorts(final Map<String, String> ports) {
        this.ports = ports == null ?
                Maps.<String, String>newHashMap() :
                ports;

    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        final DockerCommand that = (DockerCommand) o;
        return Objects.equals(this.index, that.index) &&
                Objects.equals(this.hash, that.hash) &&
                Objects.equals(this.ports, that.ports);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), index, hash, ports);
    }

    @Override
    public ToStringHelper addParentPropertiesToString(final ToStringHelper helper) {
        return super.addParentPropertiesToString(helper)
                .add("index", index)
                .add("hash", hash)
                .add("ports", ports);
    }

    @Override
    public String toString() {
        return addParentPropertiesToString(MoreObjects.toStringHelper(this))
                .toString();
    }
}
