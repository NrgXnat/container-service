package org.nrg.containers.services;

import org.nrg.containers.model.orchestration.entity.OrchestrationProjectEntity;
import org.nrg.framework.orm.hibernate.BaseHibernateService;

public interface OrchestrationProjectEntityService extends BaseHibernateService<OrchestrationProjectEntity> {
    OrchestrationProjectEntity find(String project);

    void checkAndDisable(String project, long wrapperId);
}
