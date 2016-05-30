package org.nrg.actions.daos;

import org.hibernate.Criteria;
import org.hibernate.criterion.Restrictions;
import org.nrg.actions.model.Action;
import org.nrg.framework.orm.hibernate.AbstractHibernateDAO;

import java.util.List;

public class ActionDao extends AbstractHibernateDAO<Action> {

    /**
     * Find Actions with that are configured to run for a given xsiType.
     * If xsiType is null, that means Actions are site-wide.
     *
     * @param xsiType Find Actions that can run on this. Can be null for site-wide Actions.
     * @return List of Actions that can run on given xsiType.
     */
    public List<Action> findByRootXsiType(final String xsiType) {
        Criteria criteria = getCriteriaForType();
        criteria.add(Restrictions.eqOrIsNull("??????", xsiType));
        criteria.add(Restrictions.eq("enabled", true));

        final List list = criteria.list();
        if (list == null || list.size() == 0) {
            return null;
        } else {
            return (List<Action>)list;
        }
    }
}
