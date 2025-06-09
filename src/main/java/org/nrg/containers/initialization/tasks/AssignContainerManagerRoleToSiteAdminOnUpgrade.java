package org.nrg.containers.initialization.tasks;

import lombok.extern.slf4j.Slf4j;
import org.nrg.xdat.entities.UserRole;
import org.nrg.xdat.security.XDATUser;
import org.nrg.xdat.security.helpers.Users;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.services.UserRoleService;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.initialization.tasks.AbstractInitializingTask;
import org.nrg.xnat.initialization.tasks.InitializingTaskException;
import org.nrg.xnat.services.XnatAppInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;

@Component
@Slf4j
public class AssignContainerManagerRoleToSiteAdminOnUpgrade extends AbstractInitializingTask {

    @Autowired
    public AssignContainerManagerRoleToSiteAdminOnUpgrade(final XnatAppInfo appInfo,
                                                          final UserRoleService userRoleService,
                                                          final RoleHolder roleHolder) {
        super();
        this.appInfo                   = appInfo;
        this.userRoleService           = userRoleService;
        this.roleHolder                = roleHolder;

    }

    @Override
    public String getTaskName() {
        return "AssignContainerManagerRoleToSiteAdminOnUpgrade";
    }

    @Override
    protected void callImpl() throws InitializingTaskException {
        if (!appInfo.isInitialized() ) {
            throw new InitializingTaskException(InitializingTaskException.Level.RequiresInitialization);
        }
        final Map<String, Collection<String>> rolesAndUsers =  roleHolder.getRolesAndUsers();
        final Collection<String> allSiteAdmins =  rolesAndUsers.get(UserRole.ROLE_ADMINISTRATOR);
        if (noUserIsAssignedContainerManagerRole(rolesAndUsers)) {
            UserI adminUser = Users.getAdminUser();
            try {
                grantContainerManagerRole(adminUser, allSiteAdmins);
            } catch (Exception e) {
                throw new InitializingTaskException(InitializingTaskException.Level.Error);
            }
        }
    }

    private void grantContainerManagerRole(final UserI adminUser, final Collection<String> allSiteAdmins) throws Exception {
        if (!allSiteAdmins.isEmpty()) {
            for (String userName : allSiteAdmins) {
                UserI user = Users.getUser(userName);
                if (user instanceof  XDATUser) {
                    if (user.isEnabled()) {
                        if (!((XDATUser) user).checkRole(CONTAINER_MANAGER_ROLE)) {
                            if (!roleHolder.addRole(adminUser, user, CONTAINER_MANAGER_ROLE)) {
                                log.error("Could not assign user " +  userName + " the " + CONTAINER_MANAGER_ROLE + " role.");
                            }
                        }
                    } else {
                        log.error("User " + userName + "  is not enabled. Could not assign the " + CONTAINER_MANAGER_ROLE + " role.");
                    }
                }
            }
        }
    }

    private boolean noUserIsAssignedContainerManagerRole(final Map<String, Collection<String>> rolesAndUsers) {
        if (rolesAndUsers.containsKey(CONTAINER_MANAGER_ROLE)) {
            Collection<String> usersAsContainerManager = rolesAndUsers.get(CONTAINER_MANAGER_ROLE);
            if (usersAsContainerManager!= null && usersAsContainerManager.size() > 0) {
                return false;
            }
        }
        return true;
    }

    private final UserRoleService userRoleService;
    private final RoleHolder roleHolder;
    private final XnatAppInfo appInfo;
    private final String CONTAINER_MANAGER_ROLE = "ContainerManager";
    private final String PRIVILEGED_ROLE = "Privileged";

}
