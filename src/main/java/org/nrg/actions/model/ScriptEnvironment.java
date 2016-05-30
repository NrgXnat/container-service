package org.nrg.actions.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.envers.Audited;
import org.nrg.containers.model.DockerImage;
import org.nrg.framework.orm.hibernate.AbstractHibernateEntity;

import javax.persistence.Entity;
import javax.persistence.ManyToOne;
import java.util.Objects;

@Audited
@Entity
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE, region = "nrg")
public class ScriptEnvironment extends AbstractHibernateEntity {
    private String name;
    private String description;
    @JsonProperty("docker-image") private DockerImage dockerImage;
    @JsonProperty("run-prefix") private String runPrefix;

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(final String description) {
        this.description = description;
    }

    @ManyToOne
    public DockerImage getDockerImage() {
        return dockerImage;
    }

    public void setDockerImage(final DockerImage dockerImage) {
        this.dockerImage = dockerImage;
    }

    public String getRunPrefix() {
        return runPrefix;
    }

    public void setRunPrefix(final String runPrefix) {
        this.runPrefix = runPrefix;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        final ScriptEnvironment that = (ScriptEnvironment) o;
        return Objects.equals(this.name, that.name) &&
                Objects.equals(this.description, that.description) &&
                Objects.equals(this.dockerImage, that.dockerImage) &&
                Objects.equals(this.runPrefix, that.runPrefix);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), name, description, dockerImage, runPrefix);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("name", name)
                .add("description", description)
                .add("dockerImage", dockerImage)
                .add("runPrefix", runPrefix)
                .toString();
    }
}
