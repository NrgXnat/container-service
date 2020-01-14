package org.nrg.containers.rest;

import com.google.common.collect.Maps;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.exceptions.BadRequestException;
import org.nrg.containers.exceptions.CommandResolutionException;
import org.nrg.containers.exceptions.CommandValidationException;
import org.nrg.containers.exceptions.ContainerException;
import org.nrg.containers.exceptions.DockerServerException;
import org.nrg.containers.exceptions.NoDockerServerException;
import org.nrg.containers.exceptions.UnauthorizedException;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.auto.Command.CommandWrapper;
import org.nrg.containers.model.command.auto.CommandSummaryForContext;
import org.nrg.containers.services.CommandService;
import org.nrg.framework.annotations.XapiRestController;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.framework.exceptions.NrgRuntimeException;
import org.nrg.xapi.rest.AbstractXapiRestController;
import org.nrg.xapi.rest.Project;
import org.nrg.xapi.rest.XapiRequestMapping;
import org.nrg.xdat.XDAT;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xft.exception.ElementNotFoundException;
import org.nrg.xft.security.UserI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.List;
import java.util.Map;

import static org.nrg.xdat.security.helpers.AccessLevel.Admin;
import static org.nrg.xdat.security.helpers.AccessLevel.Read;
import static org.springframework.web.bind.annotation.RequestMethod.DELETE;
import static org.springframework.web.bind.annotation.RequestMethod.GET;
import static org.springframework.web.bind.annotation.RequestMethod.POST;

@Slf4j
@XapiRestController
@Api("Command API for XNAT Container Service")
public class CommandRestApi extends AbstractXapiRestController {

    private static final String JSON = MediaType.APPLICATION_JSON_UTF8_VALUE;
    private static final String TEXT = MediaType.TEXT_PLAIN_VALUE;
    private static final String FORM = MediaType.APPLICATION_FORM_URLENCODED_VALUE;

    private static final String ID_REGEX = "\\d+";
    private static final String NAME_REGEX = "\\d*[^\\d]+\\d*";

    private CommandService commandService;

    @Autowired
    public CommandRestApi(final CommandService commandService,
                          final UserManagementServiceI userManagementService,
                          final RoleHolder roleHolder) {
        super(userManagementService, roleHolder);
        this.commandService = commandService;
    }

    /*
    COMMAND CRUD
     */
    @XapiRequestMapping(value = {"/commands"}, params = {"!name", "!version", "!image"}, method = GET)
    @ApiOperation(value = "Get all Commands")
    @ResponseBody
    public List<Command> getCommands() {
        return commandService.getAll();
    }

    @XapiRequestMapping(value = {"/commands"}, method = GET)
    @ApiOperation(value = "Get Commands by criteria")
    @ResponseBody
    public List<Command> getCommands(final @RequestParam(required = false) String name,
                                     final @RequestParam(required = false) String version,
                                     final @RequestParam(required = false) String image) throws BadRequestException {
        if (StringUtils.isBlank(name) && StringUtils.isBlank(version) && StringUtils.isBlank(image)) {
            return getCommands();
        }

        if (StringUtils.isBlank(name) && StringUtils.isNotBlank(version)) {
            throw new BadRequestException("If \"version\" is specified, must specify \"name\" as well.");
        }

        final Map<String, Object> properties = Maps.newHashMap();
        if (StringUtils.isNotBlank(name)) {
            properties.put("name", name);
        }
        if (StringUtils.isNotBlank(version)) {
            properties.put("version", version);
        }
        if (StringUtils.isNotBlank(image)) {
            properties.put("image", image);
        }

        return commandService.findByProperties(properties);
    }

    @XapiRequestMapping(value = {"/commands/{id}"}, method = GET)
    @ApiOperation(value = "Get a Command by ID")
    @ResponseBody
    public Command retrieveCommand(final @PathVariable long id) throws NotFoundException {
        return commandService.get(id);
    }

    @XapiRequestMapping(value = {"/commands"}, method = POST, produces = JSON)
    @ApiOperation(value = "Create a Command", code = 201)
    public ResponseEntity<Long> createCommand(final @RequestParam(value = "image", required=false) String image,
                                              final @RequestBody Command.CommandCreation command)
            throws BadRequestException, CommandValidationException, UnauthorizedException {
        checkAdminOrThrow();
        // The user may have sent IDs in their command, but we don't want them.
        // We must clean all the IDs before attempting to create.
        // For this, we use the "CommandCreation" object, which has
        // all the properties of a command except for ids.
        final Command toCreate = Command.create(command, image);
        try {
            commandService.throwExceptionIfCommandExists(toCreate);
        } catch (NrgRuntimeException e) {
            throw new BadRequestException(e);
        }
        try {
            final Command created = commandService.create(toCreate);
            if (created == null) {
                return new ResponseEntity<>(0L, HttpStatus.INTERNAL_SERVER_ERROR);
            }
            return new ResponseEntity<>(created.id(), HttpStatus.CREATED);
        } catch (NrgRuntimeException e) {
            throw new BadRequestException(e);
        }
    }

    @XapiRequestMapping(value = {"/commands/{id}"}, method = POST)
    @ApiOperation(value = "Update a Command")
    @ResponseBody
    public ResponseEntity<Void> updateCommand(final @RequestBody Command command,
                                              final @PathVariable long id)
            throws NotFoundException, CommandValidationException, UnauthorizedException, BadRequestException {
        checkAdminOrThrow();
        commandService.update(command.id() == id ? command : command.toBuilder().id(id).build());
        return ResponseEntity.ok().build();
    }

    @XapiRequestMapping(value = {"/commands/{id}"}, method = DELETE)
    @ApiOperation(value = "Delete a Command", code = 204)
    public ResponseEntity<Void> delete(final @PathVariable long id) throws UnauthorizedException {
        checkAdminOrThrow();
        commandService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /*
    WRAPPER CUD
     */
    @XapiRequestMapping(value = {"/commands/{id}/wrappers"}, method = POST, produces = JSON)
    @ApiOperation(value = "Create a Command Wrapper", code = 201)
    public ResponseEntity<Long> createWrapper(final @RequestBody Command.CommandWrapperCreation commandWrapperCreation,
                                              final @PathVariable long id)
            throws BadRequestException, CommandValidationException, NotFoundException, UnauthorizedException {
        checkAdminOrThrow();
        if (commandWrapperCreation == null) {
            throw new BadRequestException("The body of the request must be a CommandWrapper.");
        }
        final CommandWrapper created = commandService.addWrapper(id, CommandWrapper.create(commandWrapperCreation));
        if (created == null) {
            return new ResponseEntity<>(0L, HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return new ResponseEntity<>(created.id(), HttpStatus.CREATED);
    }

    @XapiRequestMapping(value = {"/commands/{commandId}/wrappers/{wrapperId}"}, method = POST)
    @ApiOperation(value = "Update a Command Wrapper")
    @ResponseBody
    public ResponseEntity<Void> updateWrapper(final @RequestBody CommandWrapper commandWrapper,
                                              final @PathVariable long commandId,
                                              final @PathVariable long wrapperId)
            throws NotFoundException, CommandValidationException, UnauthorizedException {
        checkAdminOrThrow();
        commandService.updateWrapper(commandId,
                commandWrapper.id() == wrapperId ? commandWrapper : commandWrapper.toBuilder().id(wrapperId).build());
        return ResponseEntity.ok().build();
    }

    @XapiRequestMapping(value = {"/wrappers/{wrapperId}"}, method = DELETE)
    @ApiOperation(value = "Delete a Command Wrapper", code = 204)
    public ResponseEntity<Void> deleteWrapper(final @PathVariable long wrapperId)
            throws NotFoundException, UnauthorizedException {
        checkAdminOrThrow();
        commandService.deleteWrapper(wrapperId);
        return ResponseEntity.noContent().build();
    }

    /*
    AVAILABLE FOR LAUNCHING
     */
    @XapiRequestMapping(value = {"/commands/available"}, params = {"project", "xsiType"}, method = GET, restrictTo = Read)
    @ApiOperation(value = "Get Commands available in given project context and XSIType")
    @ResponseBody
    public List<CommandSummaryForContext> availableCommands(final @RequestParam @Project String project,
                                                            final @RequestParam String xsiType)
            throws ElementNotFoundException {
        return commandService.available(project, xsiType, getSessionUser());
    }

    @XapiRequestMapping(value = {"/projects/{project}/commands/available"}, params = {"xsiType"}, method = GET, restrictTo = Read)
    @ApiOperation(value = "Get Commands available in given project context and XSIType")
    @ResponseBody
    public List<CommandSummaryForContext> availableCommands2(final @PathVariable @Project String project,
                                                             final @RequestParam String xsiType)
            throws ElementNotFoundException {
        return commandService.available(project, xsiType, getSessionUser());
    }

    @XapiRequestMapping(value = {"/commands/available/site"}, params = {"xsiType"}, method = GET, restrictTo = Admin)
    @ApiOperation(value = "Get Commands sitewide with given XSIType")
    @ResponseBody
    public List<CommandSummaryForContext> availableCommands(final @RequestParam String xsiType)
            throws ElementNotFoundException {
        final UserI userI = XDAT.getUserDetails();
        //We can permit any user to make this REST call since available should note return any available commands for users without permissions.
        return commandService.available(xsiType, userI);
    }

    private void checkAdminOrThrow() throws UnauthorizedException {
        checkAdminOrThrow(XDAT.getUserDetails());
    }

    private void checkAdminOrThrow(final UserI userI) throws UnauthorizedException {
        if (!isAdmin(userI)) {
            throw new UnauthorizedException(String.format("User %s is not an admin.", userI == null ? "" : userI.getLogin()));
        }
    }

    private boolean isAdmin(final UserI userI) throws UnauthorizedException {
        return getRoleHolder().isSiteAdmin(userI);
    }

    /*
    EXCEPTION HANDLING
     */
    @ResponseStatus(value = HttpStatus.NOT_FOUND)
    @ExceptionHandler(value = {NotFoundException.class})
    public String handleNotFound(final Exception e) {
        final String message = e.getMessage();
        log.debug(message);
        return message;
    }

    @ResponseStatus(value = HttpStatus.BAD_REQUEST)
    @ExceptionHandler(value = {ElementNotFoundException.class})
    public String handleXsiTypeNotFound(final ElementNotFoundException e) {
        final String message = "Bad XSI Type. " + e.getMessage();
        log.debug(message);
        return message;
    }

    @ResponseStatus(value = HttpStatus.FAILED_DEPENDENCY)
    @ExceptionHandler(value = {NoDockerServerException.class})
    public String handleFailedDependency(final Exception ignored) {
        final String message = "Set up Docker server before using this REST endpoint.";
        log.debug(message);
        return message;
    }

    @ResponseStatus(value = HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(value = {DockerServerException.class})
    public String handleDockerServerError(final Exception e) {
        final String message = "The Docker server returned an error:\n" + e.getMessage();
        log.debug(message);
        return message;
    }

    @ResponseStatus(value = HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(value = {ContainerException.class})
    public String handleContainerException(final Exception e) {
        final String message = "There was a problem with the container:\n" + e.getMessage();
        log.debug(message);
        return message;
    }

    @ResponseStatus(value = HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(value = {CommandResolutionException.class})
    public String handleCommandResolutionException(final CommandResolutionException e) {
        final String message = "The command could not be resolved.\n" + e.getMessage();
        log.debug(message);
        return message;
    }

    @ResponseStatus(value = HttpStatus.BAD_REQUEST)
    @ExceptionHandler(value = {BadRequestException.class})
    public String handleBadRequest(final Exception e) {
        final String message = "Bad request:\n" + e.getMessage();
        log.debug(message);
        return message;
    }

    @ResponseStatus(value = HttpStatus.BAD_REQUEST)
    @ExceptionHandler(value = {CommandValidationException.class})
    public String handleBadCommand(final CommandValidationException e) {
        String message = "Invalid command";
        if (e != null && e.getErrors() != null && !e.getErrors().isEmpty()) {
            message += ":\n\t";
            message += StringUtils.join(e.getErrors(), "\n\t");
        }
        log.debug(message);
        return message;
    }
}
