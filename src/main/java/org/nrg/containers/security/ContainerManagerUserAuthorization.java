package org.nrg.containers.security;

import org.nrg.containers.utils.ContainerUtils;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.nrg.xapi.authorization.AbstractXapiAuthorization;
import org.nrg.xdat.security.helpers.Roles;
import org.nrg.xft.security.UserI;
import javax.servlet.http.HttpServletRequest;
import org.nrg.xdat.security.helpers.AccessLevel;


import org.springframework.stereotype.Component;


/**
 * Checks whether user has a ContainerManager Role.
 */
@Slf4j
@Component
public class ContainerManagerUserAuthorization extends AbstractXapiAuthorization {
    @Override
    protected boolean checkImpl(final AccessLevel accessLevel, final JoinPoint joinPoint, final UserI user, final HttpServletRequest request) {
        return Roles.checkRole(user, ContainerUtils.CONTAINER_MANAGER_ROLE);
    }

    @Override
    protected boolean considerGuests() {
        return false;
    }
}
