package org.nrg.containers.security;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.nrg.config.services.ConfigService;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.services.ContainerService;
import org.nrg.containers.services.impl.ContainerServiceImpl;
import org.nrg.containers.utils.ContainerUtils;
import org.nrg.framework.constants.Scope;
import org.nrg.xapi.authorization.AbstractXapiAuthorization;
import org.nrg.xapi.exceptions.InsufficientPrivilegesException;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.security.XDATUser;
import org.nrg.xdat.security.helpers.AccessLevel;
import org.nrg.xdat.security.helpers.Features;
import org.nrg.xdat.security.helpers.Permissions;
import org.nrg.xdat.security.helpers.Roles;
import org.nrg.xft.event.persist.PersistentWorkflowI;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.configuration.FeaturesConfig;
import org.nrg.xnat.utils.WorkflowUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
@Slf4j
public class ContainerLogControlUserAuthorization extends AbstractXapiAuthorization {
    private final ContainerService _containerService;
    private final ConfigService _configService;
    private final SiteConfigPreferences _siteConfigPrefs;
    private final FeaturesConfig _featuresConfig;
    private final String AllowMembersToViewContainerLogs = "allowMembersToViewContainerLogs";

    @Autowired
    public ContainerLogControlUserAuthorization(final ContainerService containerService,
                                                final ConfigService configService,
                                                final FeaturesConfig featuresConfig,
                                                final SiteConfigPreferences siteConfigPrefs) {
        _containerService = containerService;
        _configService = configService;
        _siteConfigPrefs = siteConfigPrefs;
        _featuresConfig = featuresConfig;

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
                canAccessContainerLogs(user, getContainerIds(user, joinPoint))) {
            return true;
        }

        throw new InsufficientPrivilegesException(user.getUsername());
    }


    // Check for annotated container ids (@ContainerId). If not found, check for workflow id (@WorkflowId) and derive from workflow
    private List<String> getContainerIds(UserI user, JoinPoint joinPoint) {
        List<String> containerIds = getAnnotatedParameters(joinPoint, ContainerId.class);
        if (containerIds.isEmpty()) {
            List<String> workflowIds = getAnnotatedParameters(joinPoint, WorkflowId.class);
            containerIds = workflowIds.stream().map(wfid ->
                    getContainerIdFromWorkflow(user, wfid)).filter(Objects::nonNull).collect(Collectors.toList());
        }
        return containerIds;
    }

    private Boolean canAccessContainerLogs(UserI user, List<String> containerIds) {
        return containerIds.stream().allMatch(containerId -> canAccessContainerLogs(user, containerId));
    }

    private Boolean canAccessContainerLogs(UserI user, String containerId) {
        Container container = _containerService.retrieve(containerId);
        String projectContainerExecutedOnId = container.project();
        if (Permissions.isProjectOwner(user, projectContainerExecutedOnId) || user.getLogin().contentEquals(container.userId())) {
            return true;
        } else {
            final XnatProjectdata projectContainerExecutedOn = XnatProjectdata.getXnatProjectdatasById(projectContainerExecutedOnId, user, false);
            if (projectContainerExecutedOn != null) {
                return Features.checkFeature(user,projectContainerExecutedOn.getSecurityTags().getHash().values(), "container-service-log-access");
            }
        }
        return false;
    }

    private String getContainerIdFromWorkflow(UserI user, String workflowId) {
        PersistentWorkflowI wrk = WorkflowUtils.getUniqueWorkflow(user, workflowId);
        return wrk != null && ContainerServiceImpl.containerLaunchJustification.equals(wrk.getJustification()) ?
                wrk.getComments() : null;
    }

    @Override
    protected boolean considerGuests() {
        return false;
    }
}
