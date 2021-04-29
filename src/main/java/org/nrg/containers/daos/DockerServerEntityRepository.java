package org.nrg.containers.daos;

import org.hibernate.Hibernate;
import org.nrg.containers.model.server.docker.DockerServerEntity;
import org.nrg.containers.model.server.docker.DockerServerEntitySwarmConstraint;
import org.nrg.framework.orm.hibernate.AbstractHibernateDAO;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;
import java.util.Objects;

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


    public DockerServerEntity getDefaultServer() {
        final DockerServerEntity dockerServerEntity = (DockerServerEntity) getSession()
                .createQuery("select server from DockerServerEntity as server where server.enabled = true and server.defaultServer = true")
                .uniqueResult();
        initialize(dockerServerEntity);
        return dockerServerEntity;
    }

    @Override
    public DockerServerEntity create(final DockerServerEntity dockerServerEntity) {
        // We only allow one default server at a time.
        // If this one is set as default, remove default setting from the previous one.
        if (dockerServerEntity.isDefaultServer()) {
            final DockerServerEntity currentDefaultServer = getDefaultServer();
            if (currentDefaultServer != null) {
                undefaultServer(currentDefaultServer);
            }
        }
        final Long id = (Long) super.create(dockerServerEntity);
        dockerServerEntity.setId(id);
        return dockerServerEntity;
    }

    @Override
    public void update(final DockerServerEntity dockerServerEntity) {
        super.update(dockerServerEntity);
    }

    private void undefaultServer(final DockerServerEntity currentlyEnabledServer) {
        final Date now = new Date();
        currentlyEnabledServer.setDefaultServer(false);
        currentlyEnabledServer.setTimestamp(now);
        getSession().update(currentlyEnabledServer);
    }

}
