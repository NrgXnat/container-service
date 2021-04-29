package org.nrg.containers.services.impl;

import org.nrg.containers.daos.DockerServerEntityRepository;
import org.nrg.containers.model.server.docker.DockerServerEntity;
import org.nrg.containers.services.DockerServerEntityService;
import org.nrg.framework.orm.hibernate.AbstractHibernateEntityService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class HibernateDockerServerEntityService
        extends AbstractHibernateEntityService<DockerServerEntity, DockerServerEntityRepository>
        implements DockerServerEntityService {
    @Override
    public DockerServerEntity getDefaultServer() {
        return getDao().getDefaultServer();
    }

    @Override
    public DockerServerEntity getServer(Long id) {
        return getDao().findById(id);
    }
}
