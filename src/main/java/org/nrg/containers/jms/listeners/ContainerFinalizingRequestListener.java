package org.nrg.containers.jms.listeners;

import org.nrg.containers.exceptions.ContainerException;
import org.nrg.containers.exceptions.DockerServerException;
import org.nrg.containers.exceptions.NoDockerServerException;
import org.nrg.containers.jms.requests.ContainerFinalizingRequest;
import org.nrg.containers.jms.utils.QueueUtils;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.services.ContainerService;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xdat.security.user.exceptions.UserInitException;
import org.nrg.xdat.security.user.exceptions.UserNotFoundException;
import org.nrg.xft.security.UserI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
@Slf4j
@Component
public class ContainerFinalizingRequestListener {
	private final ContainerService containerService;
	private final UserManagementServiceI userManagementServiceI;

	@Autowired
	public ContainerFinalizingRequestListener(ContainerService containerService,
										   UserManagementServiceI userManagementServiceI) {
		this.containerService = containerService;
		this.userManagementServiceI = userManagementServiceI;
	}

	@JmsListener(containerFactory = "finalizingQueueListenerFactory", destination = "containerFinalizingRequest")
	public void onRequest(ContainerFinalizingRequest request)
			throws UserNotFoundException, NotFoundException, UserInitException, DockerServerException,
			NoDockerServerException, ContainerException {
		Container container = containerService.get(request.getId());
		UserI user = userManagementServiceI.getUser(request.getUsername());
		if (log.isDebugEnabled()) {
			int count = QueueUtils.count(request.getDestination());
			log.debug("Consuming finalizing queue: count {}, exitcode {}, issuccessfull {}, id {}, username {}, status {}",
					count, request.getExitCodeString(), request.isSuccessful(), request.getId(), request.getUsername(), container.status());
		}
    	containerService.consumeFinalize(request.getExitCodeString(), request.isSuccessful(), container, user);
    }
	
}
