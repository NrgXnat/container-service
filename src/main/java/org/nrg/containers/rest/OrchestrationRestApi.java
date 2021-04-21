package org.nrg.containers.rest;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.nrg.containers.exceptions.UnauthorizedException;
import org.nrg.containers.model.orchestration.auto.Orchestration;
import org.nrg.containers.services.OrchestrationEntityService;
import org.nrg.framework.annotations.XapiRestController;
import org.nrg.framework.constants.Scope;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xapi.rest.AbstractXapiRestController;
import org.nrg.xapi.rest.Project;
import org.nrg.xapi.rest.XapiRequestMapping;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.InvalidParameterException;

import static org.nrg.xdat.security.helpers.AccessLevel.*;
import static org.springframework.web.bind.annotation.RequestMethod.*;

@Slf4j
@XapiRestController
@RequestMapping(value = "/orchestration")
@Api("Orchestration API for XNAT Container service")
public class OrchestrationRestApi extends AbstractXapiRestController {
    private final OrchestrationEntityService orchestrationEntityService;

    @Autowired
    public OrchestrationRestApi(final OrchestrationEntityService orchestrationEntityService,
                                final UserManagementServiceI userManagementService,
                                final RoleHolder roleHolder) {
        super(userManagementService, roleHolder);
        this.orchestrationEntityService = orchestrationEntityService;
    }

    @XapiRequestMapping(method = POST, restrictTo = Admin, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiOperation(value = "Create or update project orchestration")
    public ResponseEntity<Orchestration> setupOrchestration(final @RequestBody Orchestration orchestration)
            throws NotFoundException {
        return new ResponseEntity<>(orchestrationEntityService.createOrUpdate(orchestration), HttpStatus.OK);
    }

    @XapiRequestMapping(method = GET, restrictTo = Edit, produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiOperation(value = "Get project orchestration")
    public ResponseEntity<Orchestration> getOrchestration(@RequestParam @Project final String project)
            throws NotFoundException {
        return new ResponseEntity<>(orchestrationEntityService.find(Scope.Project, project), HttpStatus.OK);
    }

    @XapiRequestMapping(value = "/{id}", method = DELETE, restrictTo = Admin)
    @ApiOperation(value = "Delete project orchestration")
    public ResponseEntity<Void> deleteOrchestration(@PathVariable final long id) {
        orchestrationEntityService.delete(id);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @XapiRequestMapping(value = "/{id}/disable", method = PUT, restrictTo = Admin)
    @ApiOperation(value = "Disable project orchestration")
    public ResponseEntity<Void> disableOrchestration(@PathVariable final long id) throws NotFoundException {
        orchestrationEntityService.disable(id);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @ResponseStatus(value = HttpStatus.NOT_FOUND)
    @ExceptionHandler(value = {NotFoundException.class})
    public String handleNotFound(final Exception e) {
        final String message = e.getMessage();
        log.debug(message);
        return message;
    }

    @ResponseStatus(value = HttpStatus.BAD_REQUEST)
    @ExceptionHandler(value = {InvalidParameterException.class})
    public String handleInvalidParam(final Exception e) {
        final String message = e.getMessage();
        log.debug(message);
        return message;
    }
}
