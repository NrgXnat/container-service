package org.nrg.containers.aspects;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.nrg.containers.events.ContainerStatusEvent;
import org.nrg.containers.events.model.ContainerEvent;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.entity.ContainerEntity;
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
    @AfterReturning(pointcut = "execution(* org.nrg.containers.services.ContainerEntityService.addContainerEventToHistory(..)) " +
                            " && args(containerEvent, userI)", returning = "containerEntity")
        public void triggerOnContainerEventToHistory(JoinPoint joinPoint, ContainerEvent containerEvent, UserI userI, ContainerEntity containerEntity) throws Throwable {
        if(containerEntity != null) {
            log.debug("Intercepted triggerOnContainerEventToHistory");
            log.debug(containerEvent.toString());
            Container container = Container.create(containerEntity);
            if(container != null) {
                eventService.triggerEvent(new ContainerStatusEvent(container, userI.getLogin()));
            } else {
                log.error("Failed to trigger event. Could not convert ContainerEntity: " + containerEntity.getContainerId() + " to Container object.");
            }
        }
    }

}
