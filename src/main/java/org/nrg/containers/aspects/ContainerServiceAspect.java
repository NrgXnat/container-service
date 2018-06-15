package org.nrg.containers.aspects;

import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.nrg.containers.events.model.ContainerEvent;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.eventservice.services.EventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class ContainerServiceAspect {
    private static final Logger log = LoggerFactory.getLogger(ContainerServiceAspect.class);


    private EventService eventService;

    @Autowired
    public ContainerServiceAspect(EventService eventService) {
        this.eventService = eventService;
    }

    // ** Capture Container History Item ** //
    @AfterReturning(pointcut = "execution(* org.nrg.containers.services.impl.ContainerServiceImpl.addContainerEventToHistory(..))" +
                                " && args(containerEvent, user, ..)")
        public void triggerOnContainerHistory(ContainerEvent containerEvent, UserI user) {
            log.debug("Intercepted container history event: \n" + containerEvent.toString());

    }

}
