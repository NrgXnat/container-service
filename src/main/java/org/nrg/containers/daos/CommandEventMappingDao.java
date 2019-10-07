package org.nrg.containers.daos;

import com.google.common.collect.Lists;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.model.CommandEventMapping;
import org.nrg.framework.orm.hibernate.AbstractHibernateDAO;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class CommandEventMappingDao extends AbstractHibernateDAO<CommandEventMapping> {

    /**
     * Find CommandIds that are configured to run for a given eventType.
     * If eventType is null, return an emply list.
     *
     * @param eventType Find Commands that are triggered by this eventType.
     * @return List of Commands.
     */
    public List<CommandEventMapping> findByEventType(final String eventType) {
        return findByEventType(eventType, true);
    }

    /**
     * Find CommandIds that are configured to run for a given eventType.
     * If eventType is null, return an emply list.
     *
     * @param eventType Find Commands that are triggered by this eventType.
     * @param onlyEnabled Limit to enabled commands
     * @return List of Commands.
     */
    public List<CommandEventMapping> findByEventType(final String eventType, final boolean onlyEnabled) {
        if (eventType == null || StringUtils.isBlank(eventType)) {
            return Lists.newArrayList();
        }
        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put("eventType", eventType);
        if(onlyEnabled){
            properties.put("enabled", true);
        }
        return findByProperties(properties);
    }
}
