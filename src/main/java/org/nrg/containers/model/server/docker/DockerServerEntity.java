package org.nrg.containers.model.server.docker;

import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.framework.orm.hibernate.AbstractHibernateEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.Entity;
import java.util.Date;
import java.util.Objects;

@Entity
public class DockerServerEntity extends AbstractHibernateEntity {
    private static final Logger _log = LoggerFactory.getLogger(DockerServerEntity.class);
    private String name;
    private String host;
    private String certPath;
    private Date lastEventCheckTime;
    private boolean swarmMode;
    private String pathTranslationXnatPrefix;
    private String pathTranslationDockerPrefix;

    public static DockerServerEntity create(final DockerServer dockerServer) {
        return new DockerServerEntity().update(dockerServer);
    }

    public DockerServerEntity update(final DockerServer dockerServer) {
        this.host = dockerServer.host();
        this.name = dockerServer.name();
        this.certPath = dockerServer.certPath();
        this.swarmMode = dockerServer.swarmMode();
        this.lastEventCheckTime = dockerServer.lastEventCheckTime();
        this.pathTranslationXnatPrefix = dockerServer.pathTranslationXnatPrefix();
        this.pathTranslationDockerPrefix = dockerServer.pathTranslationDockerPrefix();
        return this;
    }

    public DockerServer toPojo() {
        return DockerServer.create(this);
    }

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public String getHost() {
        return host;
    }

    public void setHost(final String host) {
        this.host = host;
    }

    public String getCertPath() {
        return certPath;
    }

    public void setCertPath(final String certPath) {
        this.certPath = certPath;
    }

    public Date getLastEventCheckTime() {
        return lastEventCheckTime;
    }

    public void setLastEventCheckTime(final Date lastEventCheckTime) {
        this.lastEventCheckTime = lastEventCheckTime;
    }

    public boolean getSwarmMode() {
        return swarmMode;
    }

    public void setSwarmMode(final boolean swarmMode) {
        this.swarmMode = swarmMode;
    }

    public String getPathTranslationXnatPrefix() {
        return pathTranslationXnatPrefix;
    }

    public void setPathTranslationXnatPrefix(final String pathTranslationXnatPrefix) {
        this.pathTranslationXnatPrefix = pathTranslationXnatPrefix;
    }

    public String getPathTranslationDockerPrefix() {
        return pathTranslationDockerPrefix;
    }

    public void setPathTranslationDockerPrefix(final String pathTranslationDockerPrefix) {
        this.pathTranslationDockerPrefix = pathTranslationDockerPrefix;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        final DockerServerEntity that = (DockerServerEntity) o;
        return swarmMode == that.swarmMode &&
                Objects.equals(this.name, that.name) &&
                Objects.equals(this.host, that.host) &&
                Objects.equals(this.certPath, that.certPath) &&
                Objects.equals(this.lastEventCheckTime, that.lastEventCheckTime) &&
                Objects.equals(this.pathTranslationXnatPrefix, that.pathTranslationXnatPrefix) &&
                Objects.equals(this.pathTranslationDockerPrefix, that.pathTranslationDockerPrefix);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), name, host, certPath, lastEventCheckTime, swarmMode, pathTranslationXnatPrefix, pathTranslationDockerPrefix);
    }

}
