package org.nrg.containers.jms.tasks;

import lombok.extern.slf4j.Slf4j;
import org.nrg.containers.services.ContainerService;
import org.nrg.xdat.security.helpers.Users;
import org.nrg.xft.schema.XFTManager;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.services.XnatAppInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class QueueManager implements Runnable {

    private final ContainerService containerService;
    private final XnatAppInfo xnatAppInfo;
    private boolean haveLoggedXftInitFailure = false;

    @Autowired
    public QueueManager(final ContainerService containerService, final XnatAppInfo appInfo) {
        this.containerService = containerService;
        this.xnatAppInfo = appInfo;
    }

    @Override
    public void run() {
		if (!xnatAppInfo.isPrimaryNode()) {
	        return;
    	}

        if (!XFTManager.isInitialized()) {
            if (!haveLoggedXftInitFailure) {
                log.info("XFT is not initialized, skipping queue manager task");
                haveLoggedXftInitFailure = true;
            }
            return;
        }

        log.trace("Queue manager task running");
        UserI user = Users.getAdminUser();
        containerService.checkQueuedContainerJobs(user);
        containerService.checkWaitingContainerJobs(user);
        log.trace("Queue manager task done");
    }

}
