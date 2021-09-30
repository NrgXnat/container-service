package org.nrg.containers.daos;


import lombok.extern.slf4j.Slf4j;
import org.hibernate.Criteria;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.nrg.containers.model.orchestration.entity.OrchestrationEntity;
import org.nrg.framework.generics.GenericUtils;
import org.nrg.framework.orm.hibernate.AbstractHibernateDAO;
import org.springframework.stereotype.Repository;

import java.util.List;

@Slf4j
@Repository
public class OrchestrationEntityDao extends AbstractHibernateDAO<OrchestrationEntity> {
    public List<OrchestrationEntity> findAllWithDisabledAndOrder() {
        final Criteria criteria = getCriteriaForType();
        criteria.addOrder(Order.asc("id"));
        return GenericUtils.convertToTypedList(criteria.list(), getParameterizedType());
    }

    public List<OrchestrationEntity> findEnabledUsingWrapper(long wrapperId) {
        final Criteria criteria = getCriteriaForType();
        criteria.add(Restrictions.eq("enabled", true));
        final Criteria wrapperCriteria = criteria.createCriteria("wrapperList");
        final Criteria commandCriteria = wrapperCriteria.createCriteria("commandWrapperEntity");
        commandCriteria.add(Restrictions.eq("id", wrapperId));
        return GenericUtils.convertToTypedList(criteria.list(), getParameterizedType());
    }
}
