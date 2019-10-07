package org.nrg.containers.model.server.docker;

import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.framework.orm.hibernate.AbstractHibernateEntity;

import javax.persistence.*;
import java.util.*;

@Entity
public class DockerServerEntity extends AbstractHibernateEntity {
    private String name;
    private String host;
    private String certPath;
    private Date lastEventCheckTime;
    private boolean swarmMode;
    private String pathTranslationXnatPrefix;
    private String pathTranslationDockerPrefix;
    private Boolean pullImagesOnXnatInit;
    private String containerUser;
    private List<DockerServerEntitySwarmConstraint> swarmConstraints = new ArrayList<>();

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
        this.pullImagesOnXnatInit = dockerServer.pullImagesOnXnatInit();
        this.containerUser = dockerServer.containerUser();

        final Map<String, DockerServerBase.DockerServerSwarmConstraint> pojoConstraintsToAdd = new HashMap<>();
        List<DockerServerBase.DockerServerSwarmConstraint> pojoConstraints = dockerServer.swarmConstraints();
        pojoConstraints = pojoConstraints == null ? Collections.<DockerServerBase.DockerServerSwarmConstraint>emptyList()
                : pojoConstraints;
        for (final DockerServerBase.DockerServerSwarmConstraint constraint : pojoConstraints) {
            pojoConstraintsToAdd.put(constraint.attribute(), constraint);
        }
        final List<DockerServerEntitySwarmConstraint> toRemove = new ArrayList<>();
        for (final DockerServerEntitySwarmConstraint constraintEntity : this.swarmConstraints) {
            String attr = constraintEntity.getAttribute();
            if (pojoConstraintsToAdd.containsKey(attr)) {
                // Already have the constraint, update it here and remove it from toAdd
                constraintEntity.update(pojoConstraintsToAdd.get(attr));
                pojoConstraintsToAdd.remove(attr);
            } else {
                // Constraint was removed
                toRemove.add(constraintEntity);
            }
        }
        for (final DockerServerBase.DockerServerSwarmConstraint constraint : pojoConstraintsToAdd.values()) {
            this.addSwarmConstraint(DockerServerEntitySwarmConstraint.fromPojo(constraint));
        }
        for (final DockerServerEntitySwarmConstraint constraintEntity : toRemove) {
            this.removeSwarmConstraint(constraintEntity);
        }
        return this;
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

    public Boolean getPullImagesOnXnatInit() {
        return pullImagesOnXnatInit;
    }

    public void setPullImagesOnXnatInit(final Boolean pullImagesOnXnatInit) {
        this.pullImagesOnXnatInit = pullImagesOnXnatInit;
    }

    public String getContainerUser() {
        return containerUser;
    }

    public void setContainerUser(final String containerUser) {
        this.containerUser = containerUser;
    }

    @OneToMany(mappedBy = "dockerServerEntity", cascade = CascadeType.ALL, orphanRemoval = true)
    public List<DockerServerEntitySwarmConstraint> getSwarmConstraints() {
        return swarmConstraints;
    }

    public void setSwarmConstraints(final List<DockerServerEntitySwarmConstraint> swarmConstraints) {
        this.swarmConstraints = swarmConstraints == null ?
                Collections.<DockerServerEntitySwarmConstraint>emptyList() : swarmConstraints;
        for (final DockerServerEntitySwarmConstraint constraint : this.swarmConstraints) {
            constraint.setDockerServerEntity(this);
        }
    }

    public void addSwarmConstraint(final DockerServerEntitySwarmConstraint swarmConstraint) {
        if (swarmConstraint == null || this.swarmConstraints.contains(swarmConstraint)) {
            return;
        }
        this.swarmConstraints.add(swarmConstraint);
        swarmConstraint.setDockerServerEntity(this);
    }

    public void removeSwarmConstraint(final DockerServerEntitySwarmConstraint swarmConstraint) {
        if (swarmConstraint == null || !this.swarmConstraints.contains(swarmConstraint)) {
            return;
        }
        this.swarmConstraints.remove(swarmConstraint);
        swarmConstraint.setDockerServerEntity(null);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final DockerServerEntity that = (DockerServerEntity) o;

        boolean constrEqual = this.swarmConstraints == null && that.swarmConstraints == null;
        if (!constrEqual) {
            constrEqual = true;
            if (this.swarmConstraints == null || that.swarmConstraints == null ||
                    this.swarmConstraints.size() != that.swarmConstraints.size()) {
                constrEqual = false;
            } else {
                for (DockerServerEntitySwarmConstraint c : this.swarmConstraints) {
                    if (!that.swarmConstraints.contains(c)) {
                        constrEqual = false;
                        break;
                    }
                }
                if (constrEqual) {
                    for (DockerServerEntitySwarmConstraint c : that.swarmConstraints) {
                        if (!this.swarmConstraints.contains(c)) {
                            constrEqual = false;
                            break;
                        }
                    }
                }
            }
        }

        return swarmMode == that.swarmMode &&
                Objects.equals(this.name, that.name) &&
                Objects.equals(this.host, that.host) &&
                Objects.equals(this.certPath, that.certPath) &&
                Objects.equals(this.pathTranslationXnatPrefix, that.pathTranslationXnatPrefix) &&
                Objects.equals(this.pathTranslationDockerPrefix, that.pathTranslationDockerPrefix) &&
                Objects.equals(this.pullImagesOnXnatInit, that.pullImagesOnXnatInit) &&
                Objects.equals(this.containerUser, that.containerUser) &&
                constrEqual;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, host, certPath, lastEventCheckTime, swarmMode, pathTranslationXnatPrefix,
                pathTranslationDockerPrefix, pullImagesOnXnatInit, containerUser, swarmConstraints);
    }

}
