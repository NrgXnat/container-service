package org.nrg.containers.services;

import org.nrg.containers.model.server.docker.DockerServerEntity;
import org.nrg.framework.orm.hibernate.BaseHibernateService;

public interface DockerServerEntityService extends BaseHibernateService<DockerServerEntity> {
    DockerServerEntity getDefaultServer();
    DockerServerEntity getServer(Long id);
}
