package org.nrg.containers.events.listeners;

import lombok.extern.slf4j.Slf4j;
import org.nrg.containers.events.model.BulkLaunchEvent;
import org.nrg.xnat.event.EventListener;
import org.nrg.xnat.tracking.TrackEvent;
import reactor.bus.Event;
import reactor.fn.Consumer;

@Slf4j
@EventListener
public class BulkLaunchEventListener implements Consumer<Event<BulkLaunchEvent>> {
    /**
     * {@inheritDoc}
     */
    @Override
    @TrackEvent
    public void accept(Event<BulkLaunchEvent> busEvent) {
        log.trace("Received event {} for bulk launch event {}", busEvent.getId(), busEvent.getData());
    }
}
