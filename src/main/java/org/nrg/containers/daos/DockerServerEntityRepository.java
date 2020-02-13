package org.nrg.containers.daos;

import org.hibernate.Hibernate;
import org.nrg.containers.model.server.docker.DockerServerBase;
import org.nrg.containers.model.server.docker.DockerServerEntity;
import org.nrg.containers.model.server.docker.DockerServerEntitySwarmConstraint;
import org.nrg.framework.orm.hibernate.AbstractHibernateDAO;
import org.springframework.stereotype.Repository;

import java.util.Date;

@Repository
public class DockerServerEntityRepository extends AbstractHibernateDAO<DockerServerEntity> {
    @Override
    @SuppressWarnings("deprecation")
    public void initialize(final DockerServerEntity entity) {
        //TODO is this the appropriate alternative to eager loading constraints?
        if (entity == null) {
            return;
        }
        Hibernate.initialize(entity);
        Hibernate.initialize(entity.getSwarmConstraints());
        if (entity.getSwarmConstraints() != null) {
            for (final DockerServerEntitySwarmConstraint constraint : entity.getSwarmConstraints()) {
                Hibernate.initialize(constraint.getValues());
            }
        }
    }

    public static DockerServerEntity create(final DockerServerBase.DockerServer dockerServer) {
        return new DockerServerEntity().update(dockerServer);
    }

    public DockerServerEntity getUniqueEnabledServer() {
        final DockerServerEntity dockerServerEntity = (DockerServerEntity) getSession()
                .createQuery("select server from DockerServerEntity as server where server.enabled = true")
                .uniqueResult();
        initialize(dockerServerEntity);
        return dockerServerEntity;
    }

    public long getUniqueEnabledServerId() {
        final Long serverId = (Long) getSession()
                .createQuery("select server.id from DockerServerEntity as server where server.enabled = true")
                .uniqueResult();
        return serverId == null ? 0L : serverId;
    }

    @Override
    public DockerServerEntity create(final DockerServerEntity dockerServerEntity) {
        // We only allow one enabled server at a time. To create this one, we must disable
        // the previous one.
        final DockerServerEntity currentlyEnabledServer = getUniqueEnabledServer();
        if (currentlyEnabledServer != null) {
            disableServer(currentlyEnabledServer);
        }
        final Long id = (Long) super.create(dockerServerEntity);
        dockerServerEntity.setId(id);
        return dockerServerEntity;
    }

    @Override
    public void update(final DockerServerEntity dockerServerEntity) {
        if (dockerServerEntity.isEnabled() && dockerServerEntity.getId() != getUniqueEnabledServerId()) {
            // If the caller wants to update this server to be "enabled", we want to disable
            // the currently enabled server. Unless they are the same.
            disableServer(getUniqueEnabledServer());
        }
        super.update(dockerServerEntity);
    }

    private void disableServer(final DockerServerEntity currentlyEnabledServer) {
        final Date now = new Date();
        currentlyEnabledServer.setEnabled(false);
        currentlyEnabledServer.setDisabled(now);
        currentlyEnabledServer.setTimestamp(now);
        getSession().update(currentlyEnabledServer);
    }
}
