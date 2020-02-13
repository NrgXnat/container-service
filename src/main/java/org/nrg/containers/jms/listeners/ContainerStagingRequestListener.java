package org.nrg.containers.jms.listeners;

import lombok.extern.slf4j.Slf4j;
import org.nrg.containers.jms.requests.ContainerStagingRequest;
import org.nrg.containers.jms.utils.QueueUtils;
import org.nrg.containers.services.ContainerService;
import org.nrg.xdat.XDAT;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xdat.security.user.exceptions.UserInitException;
import org.nrg.xdat.security.user.exceptions.UserNotFoundException;
import org.nrg.xft.security.UserI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ContainerStagingRequestListener {
	private final ContainerService containerService;
	private final UserManagementServiceI userManagementServiceI;

	@Autowired
	public ContainerStagingRequestListener(ContainerService containerService,
								   UserManagementServiceI userManagementServiceI) {
		this.containerService = containerService;
		this.userManagementServiceI = userManagementServiceI;
	}
	
	
	@JmsListener(containerFactory = "stagingQueueListenerFactory", destination = "containerStagingRequest")
	public void onRequest(ContainerStagingRequest request) {
		UserI user;
		try {
			user = userManagementServiceI.getUser(request.getUsername());
		} catch (UserInitException | UserNotFoundException e) {
			log.error(e.getMessage(), e);
			return;
		}

		String count = "[not computed]";
		if (log.isTraceEnabled()) {
			count = Integer.toString(QueueUtils.count(request.getDestination()));
		}
		log.debug("Consuming staging queue: count {}, project {}, wrapperId {}, commandId {}, wrapperName {}, " +
						"inputValues {}, username {}, workflowId {}", count, request.getProject(),
				request.getWrapperId(), request.getCommandId(), request.getWrapperName(),
				request.getInputValues(), request.getUsername(), request.getWorkflowid());

		containerService.consumeResolveCommandAndLaunchContainer(request.getProject(), request.getWrapperId(),
				request.getCommandId(), request.getWrapperName(), request.getInputValues(),
				user, request.getWorkflowid());
    }
	
}
