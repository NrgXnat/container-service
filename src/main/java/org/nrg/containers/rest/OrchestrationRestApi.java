package org.nrg.containers.rest;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.nrg.containers.model.orchestration.auto.Orchestration;
import org.nrg.containers.model.orchestration.auto.OrchestrationProject;
import org.nrg.containers.services.ContainerConfigService;
import org.nrg.containers.services.OrchestrationService;
import org.nrg.framework.annotations.XapiRestController;
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

import javax.annotation.Nullable;
import java.security.InvalidParameterException;
import java.util.List;

import static org.nrg.xdat.security.helpers.AccessLevel.*;
import static org.springframework.web.bind.annotation.RequestMethod.*;

@Slf4j
@XapiRestController
@RequestMapping(value = "/orchestration")
@Api("Orchestration API for XNAT Container service")
public class OrchestrationRestApi extends AbstractXapiRestController {
    private final OrchestrationService orchestrationService;

    @Autowired
    public OrchestrationRestApi(final OrchestrationService orchestrationService,
                                final UserManagementServiceI userManagementService,
                                final RoleHolder roleHolder) {
        super(userManagementService, roleHolder);
        this.orchestrationService = orchestrationService;
    }

    @XapiRequestMapping(method = POST, restrictTo = Admin, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiOperation(value = "Create or update orchestration")
    public ResponseEntity<Orchestration> setupOrchestration(final @RequestBody Orchestration orchestration)
            throws NotFoundException {
        return new ResponseEntity<>(orchestrationService.createOrUpdate(orchestration), HttpStatus.OK);
    }

    @XapiRequestMapping(method = GET, restrictTo = Admin, produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiOperation(value = "Get orchestrations")
    public ResponseEntity<List<Orchestration>> getOrchestrations() {
        return new ResponseEntity<>(orchestrationService.getAllPojos(), HttpStatus.OK);
    }

    @XapiRequestMapping(value = "/project/{project}", method = GET, restrictTo = Edit, produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiOperation(value = "Get orchestrations")
    public ResponseEntity<OrchestrationProject> getOrchestrations(@Project @PathVariable final String project) {
        return new ResponseEntity<>(orchestrationService.getAvailableForProject(project), HttpStatus.OK);
    }

    @XapiRequestMapping(value = "/project/{project}", method = PUT, restrictTo = Edit)
    @ApiOperation(value = "Set orchestration on project")
    public ResponseEntity<Void> setProjectOrchestration(@Project @PathVariable final String project,
                                                        @RequestParam final long orchestrationId)
            throws NotFoundException, ContainerConfigService.CommandConfigurationException {
        orchestrationService.setProjectOrchestration(project, orchestrationId, getSessionUser());
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @XapiRequestMapping(value = "/project/{project}", method = DELETE, restrictTo = Edit)
    @ApiOperation(value = "Remove orchestration from project")
    public ResponseEntity<Void> setProjectOrchestration(@Project @PathVariable final String project) {
        orchestrationService.removeProjectOrchestration(project);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @XapiRequestMapping(value = "/{id}", method = DELETE, restrictTo = Admin)
    @ApiOperation(value = "Delete orchestration")
    public ResponseEntity<Void> deleteOrchestration(@PathVariable final long id) {
        orchestrationService.delete(id);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @XapiRequestMapping(value = "/{id}/enabled/{enabled}", method = PUT, restrictTo = Admin)
    @ApiOperation(value = "Enable or disable orchestration")
    public ResponseEntity<Void> enableOrDisableOrchestration(@PathVariable final long id,
                                                             @PathVariable final boolean enabled)
            throws NotFoundException, ContainerConfigService.CommandConfigurationException {
        orchestrationService.setEnabled(id, enabled, getSessionUser());
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
