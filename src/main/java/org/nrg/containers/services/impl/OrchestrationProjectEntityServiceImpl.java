package org.nrg.containers.services.impl;

import org.nrg.containers.daos.OrchestrationProjectEntityDao;
import org.nrg.containers.model.orchestration.entity.OrchestrationProjectEntity;
import org.nrg.containers.services.OrchestrationProjectEntityService;
import org.nrg.framework.orm.hibernate.AbstractHibernateEntityService;
import org.springframework.stereotype.Service;

import javax.annotation.Nullable;
import javax.transaction.Transactional;

@Transactional
@Service
public class OrchestrationProjectEntityServiceImpl extends AbstractHibernateEntityService<OrchestrationProjectEntity, OrchestrationProjectEntityDao> implements OrchestrationProjectEntityService {
    @Override
    @Nullable
    public OrchestrationProjectEntity find(String project) {
        return getDao().findByUniqueProperty("projectId", project);
    }

    @Override
    public synchronized void checkAndDisable(String project, long wrapperId) {
        OrchestrationProjectEntity ope = find(project);
        if (ope == null) {
            return;
        }
        if (ope.getOrchestrationEntity().usesWrapper(wrapperId)) {
            delete(ope);
        }
        flush();
    }
}
