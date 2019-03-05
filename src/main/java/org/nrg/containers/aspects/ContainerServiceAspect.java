package org.nrg.containers.aspects;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.nrg.containers.events.ContainerStatusEvent;
import org.nrg.containers.events.model.ContainerEvent;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.entity.ContainerEntity;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.eventservice.services.EventService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
public class ContainerServiceAspect {

    private EventService eventService;

    @Autowired
    public ContainerServiceAspect(EventService eventService) {
        this.eventService = eventService;
    }


    // ** Capture Container History Item ** //
    @AfterReturning(pointcut = "execution(* org.nrg.containers.services.ContainerEntityService.addContainerEventToHistory(..)) " +
                            " && args(containerEvent, userI)", returning = "containerEntity")
        public void triggerOnContainerEventToHistory(JoinPoint joinPoint, ContainerEvent containerEvent, UserI userI, ContainerEntity containerEntity) throws Throwable {
        try {
            if (containerEntity != null) {
                Container container = Container.create(containerEntity);
                log.debug("Intercepted triggerOnContainerEventToHistory");
                if (container != null) {
                    eventService.triggerEvent(new ContainerStatusEvent(container, userI.getLogin(), containerEvent.status(), container.project()));
                } else {
                    log.error("Failed to trigger event. Could not convert ContainerEntity: " + containerEntity.getContainerId() + " to Container object.");
                }
            }
        } catch (Throwable e){
            log.error("Exception attempting to call EventService from ContainerEvent. ", e.getMessage());
        }
    }

}
