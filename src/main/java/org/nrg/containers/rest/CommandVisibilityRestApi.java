package org.nrg.containers.rest;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.nrg.xapi.rest.AuthDelegate;
import org.nrg.containers.exceptions.BadRequestException;
import org.nrg.containers.exceptions.CommandValidationException;
import org.nrg.containers.exceptions.UnauthorizedException;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.services.CommandService;
import org.nrg.framework.annotations.XapiRestController;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xapi.rest.AbstractXapiRestController;
import org.nrg.xapi.rest.XapiRequestMapping;
import org.nrg.xdat.XDAT;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.nrg.containers.utils.ContainerServicePermissionUtils;

import static org.nrg.xdat.security.helpers.AccessLevel.Authorizer;
import static org.springframework.web.bind.annotation.RequestMethod.POST;

import org.nrg.containers.security.ContainerManagerUserAuthorization;
import org.springframework.web.bind.annotation.ResponseStatus;


@Slf4j
@XapiRestController
@Api("Command Visibility API for XNAT Container Service")
public class CommandVisibilityRestApi extends AbstractXapiRestController {
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    private CommandService commandService;

    @Autowired
    public CommandVisibilityRestApi(final CommandService commandService,
                          final UserManagementServiceI userManagementService,
                          final RoleHolder roleHolder) {
        super(userManagementService, roleHolder);
        this.commandService = commandService;
    }

    @AuthDelegate(ContainerManagerUserAuthorization.class)
    @XapiRequestMapping(value = {"/command/{id}/visibility/{visibility}"}, method = POST, produces = JSON, restrictTo=Authorizer)
    @ApiOperation(value = "Modify Command visibility", code = 201)
    public ResponseEntity<Void> setVisibility(final @PathVariable long id, final @PathVariable  String visibility)
            throws BadRequestException, CommandValidationException, UnauthorizedException, NotFoundException  {
        ContainerServicePermissionUtils.checkContainerManagerOrThrow(XDAT.getUserDetails());
        Command toUpdate = commandService.retrieve(id);
        if (toUpdate == null) {
            throw new BadRequestException(String.format("The command identified by id %s does not exist.", id ));
        }
        commandService.update(toUpdate.toBuilder().visibility(visibility).build());
        return ResponseEntity.ok().build();
    }

    @ResponseStatus(value = HttpStatus.BAD_REQUEST)
    @ExceptionHandler(value = {NotFoundException.class})
    public String handleNotFound(final Exception e) {
        return e.getMessage();
    }

    @ResponseStatus(value = HttpStatus.BAD_REQUEST)
    @ExceptionHandler(value = {CommandValidationException.class})
    public String handleInValid(final Exception e) {
        if (e instanceof CommandValidationException)  {
            return ((CommandValidationException)e).getErrors().toString();
        } else {
            return e.getMessage();
        }
    }

}
