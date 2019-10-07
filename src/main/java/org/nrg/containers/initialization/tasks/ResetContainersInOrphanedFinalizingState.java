package org.nrg.containers.initialization.tasks;

import org.nrg.containers.services.ContainerService;
import org.nrg.xnat.initialization.tasks.AbstractInitializingTask;
import org.nrg.xnat.initialization.tasks.InitializingTaskException;
import org.nrg.xnat.services.XnatAppInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * @author Mohana Ramaratnam
 */
@Slf4j
@Component
public class ResetContainersInOrphanedFinalizingState extends AbstractInitializingTask {
    private final ContainerService containerService;


    @Autowired
    public ResetContainersInOrphanedFinalizingState(final ContainerService containerService, final XnatAppInfo appInfo) {
        this.containerService = containerService;
        this._appInfo = appInfo;
    }

    @Override
    public String getTaskName() {
        return "Reset Finalizing State to Waiting";
    }

    @Override
    protected void callImpl() throws InitializingTaskException {
        if (!_appInfo.isPrimaryNode()) {
            log.debug("This is not the primary node. Skippping Reset Containers In Orphaned Finalizing State");
            return;
        }
        //TODO containers can be finalized on non-primary node and this method inappropriately restarts them
        log.debug("This is the primary node on restart. Checking if any containers exist in orphaned Finalizing " +
                "state in database. If they do, resetting them to Waiting/Failed");
        //MR: 10/30/2018 - If this is the first time the DockerStatusUpdater is running
        //Look for all containers which are in Finalizing state
        //These are probably in "hung" state
        //Change the state of these to Waiting
        containerService.resetFinalizingStatusToWaitingOrFailed();
        log.debug("Reset Complete Orphaned Finalizing states to Waiting/Failed State");

    }

    private final XnatAppInfo _appInfo;


}
