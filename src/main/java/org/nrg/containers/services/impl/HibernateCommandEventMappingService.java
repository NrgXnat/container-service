package org.nrg.containers.services.impl;


import org.nrg.containers.daos.CommandEventMappingDao;
import org.nrg.containers.model.CommandEventMapping;
import org.nrg.containers.services.CommandEventMappingService;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.framework.orm.hibernate.AbstractHibernateEntityService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.util.Date;
import java.util.List;

@Service
@Transactional
public class HibernateCommandEventMappingService extends AbstractHibernateEntityService<CommandEventMapping, CommandEventMappingDao>
        implements CommandEventMappingService {

    @Override
    public void enable(final long id) throws NotFoundException {
        enable(get(id));
    }

    @Override
    public void enable(final CommandEventMapping commandEventMapping) {
        commandEventMapping.setEnabled(true);
        update(commandEventMapping);
    }

    @Override
    public void disable(final long id) throws NotFoundException {
        disable(get(id));
    }

    @Override
    public void disable(final CommandEventMapping commandEventMapping) {
        commandEventMapping.setEnabled(false);
        commandEventMapping.setDisabled(new Date());
        update(commandEventMapping);
    }

    @Override
    public List<CommandEventMapping> findByEventType(String eventType) {
            return getDao().findByEventType(eventType);
    }

    @Override
    public List<CommandEventMapping> findByEventType(String eventType, boolean onlyEnabled) {
        return getDao().findByEventType(eventType, onlyEnabled);
    }
    
    @Override
    public List<CommandEventMapping> findByProject(String project) {
            return getDao().findByProject(project);
    }

    @Override
    public List<CommandEventMapping> findByProject(String project, boolean onlyEnabled) {
        return getDao().findByProject(project, onlyEnabled);
    }
}
