package org.nrg.containers.services;


import org.nrg.containers.model.CommandEventMapping;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.framework.orm.hibernate.BaseHibernateService;

import java.util.List;


public interface CommandEventMappingService extends BaseHibernateService<CommandEventMapping> {
    void convert(long id) throws Exception;
    void enable(long id) throws NotFoundException;
    void enable(CommandEventMapping commandEventMapping);
    void disable(long id) throws NotFoundException;
    void disable(CommandEventMapping commandEventMapping);

    List<CommandEventMapping> findByEventType(String eventType);
    List<CommandEventMapping> findByEventType(String eventType, boolean onlyEnabled);
    
    List<CommandEventMapping> findByProject(String project);
    List<CommandEventMapping> findByProject(String project, boolean onlyEnabled);
}
