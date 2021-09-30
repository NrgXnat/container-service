package org.nrg.containers.daos;


import lombok.extern.slf4j.Slf4j;
import org.nrg.containers.model.orchestration.entity.OrchestrationProjectEntity;
import org.nrg.framework.orm.hibernate.AbstractHibernateDAO;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
public class OrchestrationProjectEntityDao extends AbstractHibernateDAO<OrchestrationProjectEntity> {
}
