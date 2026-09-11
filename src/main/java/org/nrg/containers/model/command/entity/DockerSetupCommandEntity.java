package org.nrg.containers.model.command.entity;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Transient;

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
