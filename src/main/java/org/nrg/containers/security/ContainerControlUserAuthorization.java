package org.nrg.containers.security;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.JoinPoint;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.services.ContainerService;
import org.nrg.containers.services.impl.ContainerServiceImpl;
import org.nrg.containers.utils.ContainerUtils;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xapi.authorization.AbstractXapiAuthorization;
import org.nrg.xapi.exceptions.InsufficientPrivilegesException;
import org.nrg.xdat.security.XDATUser;
import org.nrg.xdat.security.helpers.AccessLevel;
import org.nrg.xdat.security.helpers.Permissions;
import org.nrg.xdat.security.helpers.Roles;
import org.nrg.xft.event.persist.PersistentWorkflowI;
import org.nrg.xft.security.UserI;

import org.nrg.xnat.utils.WorkflowUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Checks whether user can control CS container features.
 */
@Slf4j
@Component
public class ContainerControlUserAuthorization extends AbstractXapiAuthorization {
    private final ContainerService _containerService;

    @Autowired
    public ContainerControlUserAuthorization(final ContainerService containerService){
        _containerService = containerService;
    }


    /**
     * Tests whether the current user should be able to control a container. Authorized container control users
     * include Container Manager, Project Owner, user with All Data Access, and user that launched container.
     * Derives container id(s) from @ContainerId annotation, or alternately the @WorkflowId annotation.
     * If no valid containerIds are found in @ContainerId or derived @WorkflowId comments, grant permission by default
     */
    protected boolean checkImpl(final AccessLevel accessLevel, final JoinPoint joinPoint, final UserI user, final HttpServletRequest request)
            throws InsufficientPrivilegesException {

        if (Roles.checkRole(user, ContainerUtils.CONTAINER_MANAGER_ROLE) || ((XDATUser) user).isDataAccess() ||
                isContainerController(user, getContainerIds(user, joinPoint))) {
            return true;
        }

        throw new InsufficientPrivilegesException(user.getUsername());
    }


    // Check for annotated container ids (@ContainerId). If not found, check for workflow id (@WorkflowId) and derive from workflow
    private List<String> getContainerIds(UserI user, JoinPoint joinPoint){
        List<String> containerIds = getAnnotatedParameters(joinPoint, ContainerId.class);
        if (containerIds.isEmpty()) {
            List<String> workflowIds = getAnnotatedParameters(joinPoint, WorkflowId.class);
            containerIds = workflowIds.stream().map(wfid ->
                        getContainerIdFromWorkflow(user, wfid)).filter(Objects::nonNull).collect(Collectors.toList());
        }
        return containerIds;
    }

    private Boolean isContainerController(UserI user, List<String> containerIds) {
        return containerIds.stream().allMatch(containerId -> isContainerController(user, containerId));
    }

    private Boolean isContainerController(UserI user, String containerId) {
        Container container = _containerService.retrieve(containerId);
        return Permissions.isProjectOwner(user, container.project()) ||
                user.getLogin().contentEquals(container.userId());
    }

    private String getContainerIdFromWorkflow(UserI user, String workflowId) {
        PersistentWorkflowI wrk = WorkflowUtils.getUniqueWorkflow(user, workflowId);
        return wrk != null && ContainerServiceImpl.containerLaunchJustification.equals(wrk.getJustification()) ?
            wrk.getComments() : null;
    }

    @Override
    protected boolean considerGuests() { return false; }

}
