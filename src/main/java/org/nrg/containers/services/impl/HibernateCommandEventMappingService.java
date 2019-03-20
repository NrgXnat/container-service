package org.nrg.containers.services.impl;


import org.nrg.containers.daos.CommandEventMappingDao;
import org.nrg.containers.model.CommandEventMapping;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.services.CommandEventMappingService;
import org.nrg.containers.services.CommandService;
import org.nrg.containers.services.ContainerService;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.framework.orm.hibernate.AbstractHibernateEntityService;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.eventservice.events.ScanEvent;
import org.nrg.xnat.eventservice.events.SessionEvent;
import org.nrg.xnat.eventservice.exceptions.UnsatisfiedDependencyException;
import org.nrg.xnat.eventservice.model.Action;
import org.nrg.xnat.eventservice.model.EventFilterCreator;
import org.nrg.xnat.eventservice.model.Subscription;
import org.nrg.xnat.eventservice.model.SubscriptionCreator;
import org.nrg.xnat.eventservice.services.EventService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

@Service
@Transactional
public class HibernateCommandEventMappingService extends AbstractHibernateEntityService<CommandEventMapping, CommandEventMappingDao>
        implements CommandEventMappingService {

    private EventService eventService;
    private ContainerService containerService;
    private CommandService commandService;

    @Autowired
    public HibernateCommandEventMappingService(EventService eventService,
                                               ContainerService containerService,
                                               CommandService commandService) {
        this.eventService = eventService;
        this.containerService = containerService;
        this.commandService = commandService;
    }

    // ** Convert Command-Event Mapping Automation to Event Service subscription ** //
    @Override
    public void convert(final long id, UserI user) throws Exception {
        CommandEventMapping commandEventMapping = get(id);

        Long commandId = commandEventMapping.getCommandId();
        String eventType = commandEventMapping.getEventType();
        String subscriptionUserName = commandEventMapping.getSubscriptionUserName();
        String projectId = commandEventMapping.getProjectId();
        String wrapperName = commandEventMapping.getXnatCommandWrapperName();

        // ** find corresponding Event Service Event ** //
        // **   only SessionArchived and ScanArchived events were implemented in CS automation ** //
        String eventStatus = "";
        if(eventType.contentEquals("SessionArchived")){
            eventType = "org.nrg.xnat.eventservice.events.SessionEvent";
            eventStatus = SessionEvent.Status.CREATED.name();
        } else if(eventType.contentEquals("ScanArchived")){
            eventType = "org.nrg.xnat.eventservice.events.ScanEvent";
            eventStatus = ScanEvent.Status.CREATED.name();
        } else {
            throw new UnsatisfiedDependencyException(eventType + " event conversion not supported");
        }

        // ** find Container Service Wrapper ** //
        Command.CommandWrapper commandWrapper = commandService.retrieveWrapper(commandId, wrapperName);

        if(commandWrapper == null){ throw new UnsatisfiedDependencyException("Could not load find Command Wrapper: " + wrapperName + " under Command ID: " + commandId); }

        // ** find corresponding Event Service Action ** //
        String actionKey = "org.nrg.containers.services.CommandActionProvider" + ":" + Long.toString(commandWrapper.id());
        Action action = eventService.getActionByKey(actionKey, user);

        if(action == null){ throw new UnsatisfiedDependencyException("Could not find corresponding Event Service Action: " + actionKey); }

        final EventFilterCreator filter = EventFilterCreator.builder()
                                                    .eventType(eventType)
                                                    .projectIds(Arrays.asList(projectId))
                                                    .status(eventStatus)
                                                    .build();


        final SubscriptionCreator subscriptionCreator = SubscriptionCreator.builder()
                                                       .name("Command Automation Conversion " + id)
                                                       .active(false)
                                                       .actAsEventUser(false)
                                                       .actionKey(action.actionKey())
                                                       .eventFilter(filter)
                                                       .build();

        Subscription toCreate = Subscription.create(subscriptionCreator, user.getLogin());

        eventService.throwExceptionIfNameExists(toCreate);

        Subscription created = eventService.createSubscription(toCreate, false);

        if(created == null){
            throw new UnsatisfiedDependencyException("Failed to create Event Service Subscription from Command Automation");
        }

        disable(id);
    }

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
}
