package org.nrg.containers.rest;

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
import org.nrg.containers.model.configuration.CommandConfiguration;
import org.nrg.containers.model.configuration.ProjectEnabledReport;
import org.nrg.containers.services.CommandService;
import org.nrg.containers.services.ContainerConfigService.CommandConfigurationException;
import org.nrg.framework.annotations.XapiRestController;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xapi.rest.AbstractXapiRestController;
import org.nrg.xapi.rest.Project;
import org.nrg.xapi.rest.XapiRequestMapping;
import org.nrg.xdat.XDAT;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xft.security.UserI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import static org.nrg.xdat.security.helpers.AccessLevel.Admin;
import static org.nrg.xdat.security.helpers.AccessLevel.Delete;
import static org.nrg.xdat.security.helpers.AccessLevel.Edit;
import static org.springframework.web.bind.annotation.RequestMethod.DELETE;
import static org.springframework.web.bind.annotation.RequestMethod.GET;
import static org.springframework.web.bind.annotation.RequestMethod.POST;
import static org.springframework.web.bind.annotation.RequestMethod.PUT;

@Slf4j
@XapiRestController
@Api("Command Configuration API for XNAT Container service")
public class CommandConfigurationRestApi extends AbstractXapiRestController {

    private CommandService commandService;

    @Autowired
    public CommandConfigurationRestApi(final CommandService commandService,
                                       final UserManagementServiceI userManagementService,
                                       final RoleHolder roleHolder) {
        super(userManagementService, roleHolder);
        this.commandService = commandService;
    }

    // Configure for site + command wrapper
    @XapiRequestMapping(value = {"/commands/{commandId}/wrappers/{wrapperName}/config"}, method = POST, restrictTo = Admin)
    @ApiOperation(value = "Configure (site)", code = 201)
    public ResponseEntity<Void> createConfiguration(final @RequestBody CommandConfiguration commandConfiguration,
                                                    final @PathVariable long commandId,
                                                    final @PathVariable String wrapperName,
                                                    final @RequestParam(required = false, defaultValue = "true") boolean enable,
                                                    final @RequestParam(required = false) String reason)
            throws CommandConfigurationException, NotFoundException {
        final UserI userI = XDAT.getUserDetails();

        commandService.configureForSite(commandConfiguration, commandId, wrapperName, enable, userI.getLogin(), reason);
        return new ResponseEntity<>(HttpStatus.CREATED);
    }

    @XapiRequestMapping(value = {"/wrappers/{wrapperId}/config"}, method = POST, restrictTo = Admin)
    @ApiOperation(value = "Configure (site)", code = 201)
    public ResponseEntity<Void> createConfiguration(final @RequestBody CommandConfiguration commandConfiguration,
                                                    final @PathVariable long wrapperId,
                                                    final @RequestParam(required = false, defaultValue = "true") boolean enable,
                                                    final @RequestParam(required = false) String reason)
            throws CommandConfigurationException, NotFoundException {
        final UserI userI = XDAT.getUserDetails();

        commandService.configureForSite(commandConfiguration, wrapperId, enable, userI.getLogin(), reason);
        return new ResponseEntity<>(HttpStatus.CREATED);
    }

    // Get configuration for site + command wrapper
    @XapiRequestMapping(value = {"/commands/{commandId}/wrappers/{wrapperName}/config"}, method = GET, restrictTo = Admin)
    @ApiOperation(value = "Get (site)")
    @ResponseBody
    public CommandConfiguration getConfiguration(final @PathVariable long commandId,
                                                 final @PathVariable String wrapperName) throws NotFoundException {
        return commandService.getSiteConfiguration(commandId, wrapperName);
    }

    @XapiRequestMapping(value = {"/wrappers/{wrapperId}/config"}, method = GET, restrictTo = Admin)
    @ApiOperation(value = "Get (site)")
    @ResponseBody
    public CommandConfiguration getConfiguration(final @PathVariable long wrapperId) throws NotFoundException {
        return commandService.getSiteConfiguration(wrapperId);
    }

    // Delete configuration for site + command wrapper
    @XapiRequestMapping(value = {"/commands/{commandId}/wrappers/{wrapperName}/config"}, method = DELETE, restrictTo = Admin)
    @ApiOperation(value = "Delete (site)", code = 204)
    public ResponseEntity<Void> deleteConfiguration(final @PathVariable long commandId,
                                                    final @PathVariable String wrapperName)
            throws CommandConfigurationException, NotFoundException {
        final UserI userI = XDAT.getUserDetails();
        commandService.deleteSiteConfiguration(commandId, wrapperName, userI.getLogin());
        return ResponseEntity.noContent().build();
    }

    @XapiRequestMapping(value = {"/wrappers/{wrapperId}/config"}, method = DELETE, restrictTo = Admin)
    @ApiOperation(value = "Delete (site)", code = 204)
    public ResponseEntity<Void> deleteConfiguration(final @PathVariable long wrapperId)
            throws CommandConfigurationException, NotFoundException {
        final UserI userI = XDAT.getUserDetails();
        commandService.deleteSiteConfiguration(wrapperId, userI.getLogin());
        return ResponseEntity.noContent().build();
    }

    // Configure for project + command wrapper
    @XapiRequestMapping(value = {"/projects/{project}/commands/{commandId}/wrappers/{wrapperName}/config"}, method = POST, restrictTo = Delete)
    @ApiOperation(value = "Configure (project)", code = 201)
    public ResponseEntity<Void> createConfiguration(final @RequestBody CommandConfiguration commandConfiguration,
                                                    final @PathVariable @Project String project,
                                                    final @PathVariable long commandId,
                                                    final @PathVariable String wrapperName,
                                                    final @RequestParam(required = false, defaultValue = "true") boolean enable,
                                                    final @RequestParam(required = false) String reason)
            throws CommandConfigurationException, NotFoundException {
        final UserI userI = XDAT.getUserDetails();

        commandService.configureForProject(commandConfiguration, project, commandId, wrapperName, enable, userI.getLogin(), reason);
        return new ResponseEntity<>(HttpStatus.CREATED);
    }

    @XapiRequestMapping(value = {"/projects/{project}/wrappers/{wrapperId}/config"}, method = POST, restrictTo = Delete)
    @ApiOperation(value = "Configure (project)", code = 201)
    public ResponseEntity<Void> createConfiguration(final @RequestBody CommandConfiguration commandConfiguration,
                                                    final @PathVariable @Project String project,
                                                    final @PathVariable long wrapperId,
                                                    final @RequestParam(required = false, defaultValue = "true") boolean enable,
                                                    final @RequestParam(required = false) String reason)
            throws CommandConfigurationException, NotFoundException {
        final UserI userI = XDAT.getUserDetails();

        commandService.configureForProject(commandConfiguration, project, wrapperId, enable, userI.getLogin(), reason);
        return new ResponseEntity<>(HttpStatus.CREATED);
    }

    // Get configuration for project + command wrapper
    @XapiRequestMapping(value = {"/projects/{project}/commands/{commandId}/wrappers/{wrapperName}/config"}, method = GET, restrictTo = Edit)
    @ApiOperation(value = "Get (project)")
    @ResponseBody
    public CommandConfiguration getConfiguration(final @PathVariable @Project String project,
                                                 final @PathVariable long commandId,
                                                 final @PathVariable String wrapperName) throws NotFoundException {
        return commandService.getProjectConfiguration(project, commandId, wrapperName);
    }

    @XapiRequestMapping(value = {"/projects/{project}/wrappers/{wrapperId}/config"}, method = GET, restrictTo = Edit)
    @ApiOperation(value = "Get (project)")
    @ResponseBody
    public CommandConfiguration getConfiguration(final @PathVariable @Project String project,
                                                 final @PathVariable long wrapperId) throws NotFoundException {
        return commandService.getProjectConfiguration(project, wrapperId);
    }

    // Delete configuration for project + command wrapper
    @XapiRequestMapping(value = {"/projects/{project}/commands/{commandId}/wrappers/{wrapperName}/config"}, method = DELETE, restrictTo = Delete)
    @ApiOperation(value = "Delete (project)", code = 204)
    public ResponseEntity<Void> deleteConfiguration(final @PathVariable @Project String project,
                                                    final @PathVariable long commandId,
                                                    final @PathVariable String wrapperName)
            throws CommandConfigurationException, NotFoundException {
        final UserI userI = XDAT.getUserDetails();
        commandService.deleteProjectConfiguration(project, commandId, wrapperName, userI.getLogin());
        return ResponseEntity.noContent().build();
    }

    @XapiRequestMapping(value = {"/projects/{project}/wrappers/{wrapperId}/config"}, method = DELETE, restrictTo = Delete)
    @ApiOperation(value = "Delete (project)", code = 204)
    public ResponseEntity<Void> deleteConfiguration(final @PathVariable @Project String project,
                                                    final @PathVariable long wrapperId)
            throws CommandConfigurationException, NotFoundException {
        final UserI userI = XDAT.getUserDetails();
        commandService.deleteProjectConfiguration(project, wrapperId, userI.getLogin());
        return ResponseEntity.noContent().build();
    }

    /*
    ENABLE/DISABLE
     */
    @XapiRequestMapping(value = {"/commands/{commandId}/wrappers/{wrapperName}/enabled"}, method = GET, restrictTo = Admin)
    @ApiOperation(value = "Is Enabled (site)")
    @ResponseBody
    public Boolean isConfigurationEnabled(final @PathVariable long commandId,
                                          final @PathVariable String wrapperName)
            throws CommandConfigurationException, NotFoundException {
        return commandService.isEnabledForSite(commandId, wrapperName);
    }

    @XapiRequestMapping(value = {"/wrappers/{wrapperId}/enabled"}, method = GET, restrictTo = Admin)
    @ApiOperation(value = "Is Enabled (site)")
    @ResponseBody
    public Boolean isConfigurationEnabled(final @PathVariable long wrapperId)
            throws CommandConfigurationException, NotFoundException {
        return commandService.isEnabledForSite(wrapperId);
    }

    @XapiRequestMapping(value = {"/commands/{commandId}/wrappers/{wrapperName}/enabled"}, method = PUT, restrictTo = Admin)
    @ApiOperation(value = "Enable (site)")
    public ResponseEntity<Void> enableConfiguration(final @PathVariable long commandId,
                                                    final @PathVariable String wrapperName,
                                                    final @RequestParam(required = false) String reason)
            throws CommandConfigurationException, NotFoundException {
        final UserI userI = XDAT.getUserDetails();
        commandService.enableForSite(commandId, wrapperName, userI.getLogin(), reason);
        return ResponseEntity.ok().build();
    }

    @XapiRequestMapping(value = {"/wrappers/{wrapperId}/enabled"}, method = PUT, restrictTo = Admin)
    @ApiOperation(value = "Enable (site)")
    public ResponseEntity<Void> enableConfiguration(final @PathVariable long wrapperId,
                                                    final @RequestParam(required = false) String reason)
            throws CommandConfigurationException, NotFoundException {
        final UserI userI = XDAT.getUserDetails();
        commandService.enableForSite(wrapperId, userI.getLogin(), reason);
        return ResponseEntity.ok().build();
    }

    @XapiRequestMapping(value = {"/commands/{commandId}/wrappers/{wrapperName}/disabled"}, method = PUT, restrictTo = Admin)
    @ApiOperation(value = "Disable (site)")
    public ResponseEntity<Void> disableConfiguration(final @PathVariable long commandId,
                                                     final @PathVariable String wrapperName,
                                                     final @RequestParam(required = false) String reason)
            throws CommandConfigurationException, NotFoundException {
        final UserI userI = XDAT.getUserDetails();
        commandService.disableForSite(commandId, wrapperName, userI.getLogin(), reason);
        return ResponseEntity.ok().build();
    }

    @XapiRequestMapping(value = {"/wrappers/{wrapperId}/disabled"}, method = PUT, restrictTo = Admin)
    @ApiOperation(value = "Disable (site)")
    public ResponseEntity<Void> disableConfiguration(final @PathVariable long wrapperId,
                                                     final @RequestParam(required = false) String reason)
            throws CommandConfigurationException, NotFoundException {
        final UserI userI = XDAT.getUserDetails();
        commandService.disableForSite(wrapperId, userI.getLogin(), reason);
        return ResponseEntity.ok().build();
    }

    @XapiRequestMapping(value = {"/projects/{project}/commands/{commandId}/wrappers/{wrapperName}/enabled"}, method = GET, restrictTo = Edit)
    @ApiOperation(value = "Is Enabled (project)")
    public ProjectEnabledReport isConfigurationEnabled(final @PathVariable @Project String project,
                                                                       final @PathVariable long commandId,
                                                                       final @PathVariable String wrapperName)
            throws CommandConfigurationException, NotFoundException {
        return commandService.isEnabledForProjectAsReport(project, commandId, wrapperName);
    }

    @XapiRequestMapping(value = {"/projects/{project}/wrappers/{wrapperId}/enabled"}, method = GET, restrictTo = Edit)
    @ApiOperation(value = "Is Enabled (project)")
    @ResponseBody
    public ProjectEnabledReport isConfigurationEnabled(final @PathVariable @Project String project,
                                                                       final @PathVariable long wrapperId)
            throws CommandConfigurationException, NotFoundException {
        return commandService.isEnabledForProjectAsReport(project, wrapperId);
    }

    @XapiRequestMapping(value = {"/projects/{project}/commands/{commandId}/wrappers/{wrapperName}/enabled"}, method = PUT, restrictTo = Delete)
    @ApiOperation(value = "Enable (project)")
    public ResponseEntity<Void> enableConfiguration(final @PathVariable @Project String project,
                                                    final @PathVariable long commandId,
                                                    final @PathVariable String wrapperName,
                                                    final @RequestParam(required = false) String reason)
            throws CommandConfigurationException, NotFoundException {
        final UserI userI = XDAT.getUserDetails();
        commandService.enableForProject(project, commandId, wrapperName, userI.getLogin(), reason);
        return ResponseEntity.ok().build();
    }

    @XapiRequestMapping(value = {"/projects/{project}/wrappers/{wrapperId}/enabled"}, method = PUT, restrictTo = Delete)
    @ApiOperation(value = "Enable (project)")
    public ResponseEntity<Void> enableConfiguration(final @PathVariable @Project String project,
                                                    final @PathVariable long wrapperId,
                                                    final @RequestParam(required = false) String reason)
            throws CommandConfigurationException, NotFoundException {
        final UserI userI = XDAT.getUserDetails();
        commandService.enableForProject(project, wrapperId, userI.getLogin(), reason);
        return ResponseEntity.ok().build();
    }

    @XapiRequestMapping(value = {"/projects/{project}/commands/{commandId}/wrappers/{wrapperName}/disabled"}, method = PUT, restrictTo = Delete)
    @ApiOperation(value = "Disable (project)")
    public ResponseEntity<Void> disableConfiguration(final @PathVariable @Project String project,
                                                     final @PathVariable long commandId,
                                                     final @PathVariable String wrapperName,
                                                     final @RequestParam(required = false) String reason)
            throws CommandConfigurationException, NotFoundException {
        final UserI userI = XDAT.getUserDetails();
        commandService.disableForProject(project, commandId, wrapperName, userI.getLogin(), reason);
        return ResponseEntity.ok().build();
    }

    @XapiRequestMapping(value = {"/projects/{project}/wrappers/{wrapperId}/disabled"}, method = PUT, restrictTo = Delete)
    @ApiOperation(value = "Disable (project)")
    public ResponseEntity<Void> disableConfiguration(final @PathVariable @Project String project,
                                                     final @PathVariable long wrapperId,
                                                     final @RequestParam(required = false) String reason)
            throws CommandConfigurationException, NotFoundException {
        final UserI userI = XDAT.getUserDetails();
        commandService.disableForProject(project, wrapperId, userI.getLogin(), reason);
        return ResponseEntity.ok().build();
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
