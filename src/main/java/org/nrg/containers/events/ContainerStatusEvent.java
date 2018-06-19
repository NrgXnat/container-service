package org.nrg.containers.events;

import org.nrg.containers.model.container.auto.Container;
import org.nrg.framework.event.XnatEventServiceEvent;
import org.nrg.xnat.eventservice.events.CombinedEventServiceEvent;
import org.nrg.xnat.eventservice.listeners.EventServiceListener;
import org.springframework.stereotype.Service;

@Service
@XnatEventServiceEvent(name="ContainerStatusEvent")
public class ContainerStatusEvent extends CombinedEventServiceEvent<ContainerStatusEvent, Container> {
    final String displayName = "Container Status Update";
    final String description = "Container execution status has been updated.";

    public ContainerStatusEvent(){};

    public ContainerStatusEvent(final Container container, final String eventUser){super(container, eventUser);}

    @Override
    public String getDisplayName() { return displayName; }

    @Override
    public String getDescription() { return description; }

    @Override
    public String getPayloadXnatType() { return "org.nrg.containers.model.container.auto.Container"; }

    @Override
    public Boolean isPayloadXsiType() { return false; }

    @Override
    public EventServiceListener getInstance() { return new ContainerStatusEvent(); }
}
