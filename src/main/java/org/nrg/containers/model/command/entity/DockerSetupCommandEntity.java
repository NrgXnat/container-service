package org.nrg.containers.model.command.entity;

import javax.persistence.DiscriminatorValue;
import javax.persistence.Entity;
import javax.persistence.Transient;

@Entity
@DiscriminatorValue("docker-setup")
public class DockerSetupCommandEntity extends CommandEntity {
    public static final CommandType type = CommandType.DOCKER_SETUP;

    @Transient
    public CommandType getType() {
        return type;
    }

    public void setType(final CommandType type) {}
}
