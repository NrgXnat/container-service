package org.nrg.containers.rest;

import com.google.common.collect.Lists;
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
import org.nrg.containers.model.command.auto.Command.Input;
import org.nrg.containers.model.command.auto.LaunchReport;
import org.nrg.containers.model.command.auto.LaunchUi;
import org.nrg.containers.model.command.auto.ResolvedCommand.PartiallyResolvedCommand;
import org.nrg.containers.model.command.auto.ResolvedInputTreeNode;
import org.nrg.containers.model.configuration.CommandConfiguration;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.services.CommandResolutionService;
import org.nrg.containers.services.CommandService;
import org.nrg.containers.services.ContainerService;
import org.nrg.framework.annotations.XapiRestController;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xapi.rest.AbstractXapiRestController;
import org.nrg.xapi.rest.Project;
import org.nrg.xapi.rest.XapiRequestMapping;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.nrg.xdat.security.helpers.AccessLevel.Read;
import static org.springframework.web.bind.annotation.RequestMethod.GET;
import static org.springframework.web.bind.annotation.RequestMethod.POST;

@Slf4j
@XapiRestController
@Api("API for Launching Containers with XNAT Container service")
public class LaunchRestApi extends AbstractXapiRestController {

    private static final String JSON = MediaType.APPLICATION_JSON_UTF8_VALUE;
    private static final String TEXT = MediaType.TEXT_PLAIN_VALUE;
    private static final String FORM = MediaType.APPLICATION_FORM_URLENCODED_VALUE;

    private static final String ID_REGEX = "\\d+";
    private static final String NAME_REGEX = "\\d*[^\\d]+\\d*";

    private final CommandService commandService;
    private final ContainerService containerService;
    private final CommandResolutionService commandResolutionService;

    @Autowired
    public LaunchRestApi(final CommandService commandService,
                         final ContainerService containerService,
                         final CommandResolutionService commandResolutionService,
                         final UserManagementServiceI userManagementService,
                         final RoleHolder roleHolder) {
        super(userManagementService, roleHolder);
        this.commandService = commandService;
        this.containerService = containerService;
        this.commandResolutionService = commandResolutionService;
    }

    /*
    GET A LAUNCH UI
     */
    @XapiRequestMapping(value = {"/wrappers/{wrapperId}/launch"}, method = GET)
    @ApiOperation(value = "Get Launch UI for wrapper", notes = "DOES NOT WORK PROPERLY IN SWAGGER UI")
    @ResponseBody
    public LaunchUi.SingleLaunchUi getLaunchUi(final @PathVariable long wrapperId,
                                               final @RequestParam Map<String, String> allRequestParams)
            throws NotFoundException, CommandResolutionException, UnauthorizedException {
        log.info("Launch UI requested for wrapper {}", wrapperId);

        return getLaunchUi(null, 0L, null, wrapperId, allRequestParams);
    }

    @XapiRequestMapping(value = {"/commands/{commandId}/wrappers/{wrapperName}/launch"}, method = GET)
    @ApiOperation(value = "Get Launch UI for wrapper", notes = "DOES NOT WORK PROPERLY IN SWAGGER UI")
    @ResponseBody
    public LaunchUi.SingleLaunchUi getLaunchUi(final @PathVariable long commandId,
                                               final @PathVariable String wrapperName,
                                               final @RequestParam Map<String, String> allRequestParams)
            throws NotFoundException, CommandResolutionException, UnauthorizedException {
        log.info("Launch UI requested for command {}, wrapper {}", commandId, wrapperName);

        return getLaunchUi(null, commandId, wrapperName, 0L, allRequestParams);
    }

    @XapiRequestMapping(value = {"/projects/{project}/wrappers/{wrapperId}/launch"}, method = GET, restrictTo = Read)
    @ApiOperation(value = "Get Launch UI for wrapper", notes = "DOES NOT WORK PROPERLY IN SWAGGER UI")
    @ResponseBody
    public LaunchUi.SingleLaunchUi getLaunchUi(final @PathVariable @Project String project,
                                               final @PathVariable long wrapperId,
                                               final @RequestParam Map<String, String> allRequestParams)
            throws NotFoundException, CommandResolutionException, UnauthorizedException {
        log.info("Launch UI requested for project {}, wrapper {}", project, wrapperId);

        return getLaunchUi(project, 0L, null, wrapperId, allRequestParams);
    }

    @XapiRequestMapping(value = {"/projects/{project}/commands/{commandId}/wrappers/{wrapperName}/launch"}, method = GET, restrictTo = Read)
    @ApiOperation(value = "Get Launch UI for wrapper", notes = "DOES NOT WORK PROPERLY IN SWAGGER UI")
    @ResponseBody
    public LaunchUi.SingleLaunchUi getLaunchUi(final @PathVariable @Project String project,
                                               final @PathVariable long commandId,
                                               final @PathVariable String wrapperName,
                                               final @RequestParam Map<String, String> allRequestParams)
            throws NotFoundException, CommandResolutionException, UnauthorizedException {
        log.info("Launch UI requested for project {}, command {}, wrapper {}", project, commandId, wrapperName);

        return getLaunchUi(project, commandId, wrapperName, 0L, allRequestParams);
    }

    private LaunchUi.SingleLaunchUi getLaunchUi(final String project,
                                                final long commandId,
                                                final String wrapperName,
                                                final long wrapperId,
                                                final Map<String, String> allRequestParams)
            throws NotFoundException, CommandResolutionException, UnauthorizedException {
        log.debug("Getting {} configuration for command {}, wrapper name {}, wrapper id {}.", project == null ? "site" : "project " + project, commandId, wrapperName, wrapperId);
        final CommandConfiguration commandConfiguration = getCommandConfiguration(project, commandId, wrapperName, wrapperId);
        try {
            log.debug("Preparing to pre-resolve command {}, wrapperName {}, wrapperId {}, in project {} with inputs {}.", commandId, wrapperName, wrapperId, project, allRequestParams);
            final UserI userI = getSessionUser();
            final PartiallyResolvedCommand partiallyResolvedCommand = preResolve(project, commandId, wrapperName, wrapperId, allRequestParams, userI);
            log.debug("Done pre-resolving command {}, wrapperName {}, wrapperId {}, in project {}.", commandId, wrapperName, wrapperId, project);


            log.debug("Creating launch UI.");
            return LaunchUi.SingleLaunchUi.create(partiallyResolvedCommand, commandConfiguration.inputs());
        } catch (Throwable t) {
            log.error("Error getting launch UI.", t);
            if (Exception.class.isAssignableFrom(t.getClass())) {
                // We can re-throw Exceptions, because Spring has methods to catch them.
                throw t;
            }
            return null;
        }
    }

    private PartiallyResolvedCommand preResolve(final String project,
                                                final long commandId,
                                                final String wrapperName,
                                                final long wrapperId,
                                                final Map<String, String> allRequestParams,
                                                final UserI userI)
            throws NotFoundException, CommandResolutionException, UnauthorizedException {
        return project == null ?
                (commandId == 0L && wrapperName == null ?
                        commandResolutionService.preResolve(wrapperId, allRequestParams, userI) :
                        commandResolutionService.preResolve(commandId, wrapperName, allRequestParams, userI)) :
                (commandId == 0L && wrapperName == null ?
                        commandResolutionService.preResolve(project, wrapperId, allRequestParams, userI) :
                        commandResolutionService.preResolve(project, commandId, wrapperName, allRequestParams, userI));
    }

    private CommandConfiguration getCommandConfiguration(final String project,
                                                         final long commandId,
                                                         final String wrapperName,
                                                         final long wrapperId) throws NotFoundException {
        return project == null ?
                (commandId == 0L && wrapperName == null ?
                        commandService.getSiteConfiguration(wrapperId) :
                        commandService.getSiteConfiguration(commandId, wrapperName)) :
                (commandId == 0L && wrapperName == null ?
                        commandService.getProjectConfiguration(project, wrapperId) :
                        commandService.getProjectConfiguration(project, commandId, wrapperName));
    }

    /*
    BULK LAUNCH UI
     */
    @XapiRequestMapping(value = {"/wrappers/{wrapperId}/bulklaunch"}, method = GET)
    @ApiOperation(value = "Get Bulk Launch UI for wrapper", notes = "DOES NOT WORK PROPERLY IN SWAGGER UI")
    @ResponseBody
    public LaunchUi.BulkLaunchUi getBulkLaunchUi(final @PathVariable long wrapperId,
                                                 final @RequestParam Map<String, String> allRequestParams)
            throws NotFoundException, CommandResolutionException, UnauthorizedException {
        log.info("Launch UI requested for wrapper {}", wrapperId);

        return getBulkLaunchUi(null, 0L, null, wrapperId, allRequestParams);
    }

    @XapiRequestMapping(value = {"/commands/{commandId}/wrappers/{wrapperName}/bulklaunch"}, method = GET)
    @ApiOperation(value = "Get Bulk Launch UI for wrapper", notes = "DOES NOT WORK PROPERLY IN SWAGGER UI")
    @ResponseBody
    public LaunchUi.BulkLaunchUi getBulkLaunchUi(final @PathVariable long commandId,
                                                 final @PathVariable String wrapperName,
                                                 final @RequestParam Map<String, String> allRequestParams)
            throws NotFoundException, CommandResolutionException, UnauthorizedException {
        log.info("Bulk Launch UI requested for command {}, wrapper {}", commandId, wrapperName);

        return getBulkLaunchUi(null, commandId, wrapperName, 0L, allRequestParams);
    }

    @XapiRequestMapping(value = {"/projects/{project}/wrappers/{wrapperId}/bulklaunch"}, method = GET, restrictTo = Read)
    @ApiOperation(value = "Get Bulk Launch UI for wrapper", notes = "DOES NOT WORK PROPERLY IN SWAGGER UI")
    @ResponseBody
    public LaunchUi.BulkLaunchUi getBulkLaunchUi(final @PathVariable @Project String project,
                                                 final @PathVariable long wrapperId,
                                                 final @RequestParam Map<String, String> allRequestParams)
            throws NotFoundException, CommandResolutionException, UnauthorizedException {
        log.info("Launch UI requested for project {}, wrapper {}", project, wrapperId);

        return getBulkLaunchUi(project, 0L, null, wrapperId, allRequestParams);
    }

    @XapiRequestMapping(value = {"/projects/{project}/commands/{commandId}/wrappers/{wrapperName}/bulklaunch"}, method = GET, restrictTo = Read)
    @ApiOperation(value = "Get Bulk Launch UI for wrapper", notes = "DOES NOT WORK PROPERLY IN SWAGGER UI")
    @ResponseBody
    public LaunchUi.BulkLaunchUi getBulkLaunchUi(final @PathVariable @Project String project,
                                                 final @PathVariable long commandId,
                                                 final @PathVariable String wrapperName,
                                                 final @RequestParam Map<String, String> allRequestParams)
            throws NotFoundException, CommandResolutionException, UnauthorizedException {
        log.info("Launch UI requested for project {}, command {}, wrapper {}", project, commandId, wrapperName);

        return getBulkLaunchUi(project, commandId, wrapperName, 0L, allRequestParams);
    }

    private LaunchUi.BulkLaunchUi getBulkLaunchUi(final String project,
                                                  final long commandId,
                                                  final String wrapperName,
                                                  final long wrapperId,
                                                  final Map<String, String> allRequestParams)
            throws NotFoundException, CommandResolutionException, UnauthorizedException {

        final List<Map<String, String>> paramsMapList = Lists.newArrayList();
        paramsMapList.add(Maps.<String, String>newHashMap());
        for (final Map.Entry<String, String> param : allRequestParams.entrySet()) {
            final String[] splitValue = StringUtils.split(param.getValue(), ",");
            if (splitValue.length > 1) {
                // The param is a CSV. We must add each value in the CSV to each param map in the list.
                final List<Map<String, String>> paramsMapListCopy = Lists.newArrayList(paramsMapList);
                paramsMapList.clear();
                for (final Map<String, String> paramsMap : paramsMapListCopy) {
                    for (final String value : splitValue) {
                        final Map<String, String> paramsMapCopy = Maps.newHashMap(paramsMap);
                        paramsMapCopy.put(param.getKey(), value);
                        paramsMapList.add(paramsMapCopy);
                    }
                }
            } else {
                // The param was not a CSV, so add it to all the maps in the list
                for (final Map<String, String> paramsMap : paramsMapList) {
                    paramsMap.put(param.getKey(), param.getValue());
                }
            }
        }

        try {
            log.debug("Getting {} configuration for command {}, wrapper name {}, wrapper id {}.", project == null ? "site" : "project " + project, commandId, wrapperName, wrapperId);
            final CommandConfiguration commandConfiguration = getCommandConfiguration(project, commandId, wrapperName, wrapperId);

            final UserI userI = getSessionUser();

            PartiallyResolvedCommand aPartiallyResolvedCommand = null;
            final List<List<ResolvedInputTreeNode<? extends Input>>> listOfResolvedInputTrees = new ArrayList<>();
            for (final Map<String, String> paramsMap : paramsMapList) {
                log.debug("Preparing to pre-resolve command {}, wrapperName {}, wrapperId {}, in project {} with inputs {}.", commandId, wrapperName, wrapperId, project, paramsMap);
                final PartiallyResolvedCommand partiallyResolvedCommand = preResolve(project, commandId, wrapperName, wrapperId, paramsMap, userI);
                if (aPartiallyResolvedCommand == null) {
                    aPartiallyResolvedCommand = partiallyResolvedCommand; // We use this to populate Meta info, which  should be the same in all
                }
                listOfResolvedInputTrees.add(partiallyResolvedCommand.resolvedInputTrees());
                log.debug("Done pre-resolving command {}, wrapperName {}, wrapperId {}, in project {}.", commandId, wrapperName, project);
            }

            if (aPartiallyResolvedCommand == null) {
                log.error("Could not populate Launch UI meta information. Something must have gone wrong.");
                throw new CommandResolutionException("Unknown error. Inform your admin to consult container logs.");
            }

            log.debug("Creating launch UI.");
            return LaunchUi.BulkLaunchUi.builder()
                    .meta(LaunchUi.LaunchUiMeta.create(aPartiallyResolvedCommand))
                    .populateInputTreeAndInputValueTreeFromResolvedInputTrees(listOfResolvedInputTrees, commandConfiguration.inputs())
                    .build();
        } catch (Throwable t) {
            log.error("Error getting launch UI.", t);
            if (Exception.class.isAssignableFrom(t.getClass())) {
                // We can re-throw Exceptions, because Spring has methods to catch them.
                throw t;
            }
            return null;
        }
    }

    /*
    LAUNCH CONTAINERS
     */
    @XapiRequestMapping(value = {"/wrappers/{wrapperId}/launch"}, method = POST)
    @ApiOperation(value = "Resolve a command from the variable values in the query params, and launch it", notes = "DOES NOT WORK PROPERLY IN SWAGGER UI")
    public ResponseEntity<LaunchReport> launchCommandWQueryParams(final @PathVariable long wrapperId,
                                                                  final @RequestParam Map<String, String> allRequestParams) {
        log.info("Launch requested for command id " + wrapperId);

        return returnLaunchReportWithStatus(launchContainer(null, 0L, null, wrapperId, allRequestParams));
    }

    @XapiRequestMapping(value = {"/wrappers/{wrapperId}/launch"}, method = POST, consumes = {JSON})
    @ApiOperation(value = "Resolve a command from the variable values in the request body, and launch it")
    public ResponseEntity<LaunchReport> launchCommandWJsonBody(final @PathVariable long wrapperId,
                                                               final @RequestBody Map<String, String> allRequestParams) {
        log.info("Launch requested for command wrapper id " + String.valueOf(wrapperId));

        return returnLaunchReportWithStatus(launchContainer(null, 0L, null, wrapperId, allRequestParams));
    }

    @XapiRequestMapping(value = {"/projects/{project}/wrapper/{wrapperId}/launch"}, method = POST, restrictTo = Read)
    @ApiOperation(value = "Resolve a command from the variable values in the query params, and launch it", notes = "DOES NOT WORK PROPERLY IN SWAGGER UI")
    public ResponseEntity<LaunchReport> launchCommandWQueryParams(final @PathVariable @Project String project,
                                                                  final @PathVariable long wrapperId,
                                                                  final @RequestParam Map<String, String> allRequestParams) {
        log.info("Launch requested for wrapper id " + String.valueOf(wrapperId));

        return returnLaunchReportWithStatus(launchContainer(project, 0L, null, wrapperId, allRequestParams));
    }

    @XapiRequestMapping(value = {"/projects/{project}/wrappers/{wrapperId}/launch"}, method = POST, consumes = {JSON}, restrictTo = Read)
    @ApiOperation(value = "Resolve a command from the variable values in the request body, and launch it")
    public ResponseEntity<LaunchReport> launchCommandWJsonBody(final @PathVariable @Project String project,
                                                               final @PathVariable long wrapperId,
                                                               final @RequestBody Map<String, String> allRequestParams) {
        log.info("Launch requested for wrapper id " + String.valueOf(wrapperId));

        return returnLaunchReportWithStatus(launchContainer(project, 0L, null, wrapperId, allRequestParams));
    }

    /*
    LAUNCH COMMAND + WRAPPER BY NAME
     */
    @XapiRequestMapping(value = {"/commands/{commandId}/wrappers/{wrapperName}/launch"}, method = POST)
    @ApiOperation(value = "Resolve a command from the variable values in the query params, and launch it", notes = "DOES NOT WORK PROPERLY IN SWAGGER UI")
    public ResponseEntity<LaunchReport> launchCommandWQueryParams(final @PathVariable long commandId,
                                                                  final @PathVariable String wrapperName,
                                                                  final @RequestParam Map<String, String> allRequestParams)
            throws NoDockerServerException, DockerServerException, NotFoundException, BadRequestException, CommandResolutionException, ContainerException {
        log.info("Launch requested for command {}, wrapper {}", commandId, wrapperName);

        return returnLaunchReportWithStatus(launchContainer(null, commandId, wrapperName, 0L, allRequestParams));
    }

    @XapiRequestMapping(value = {"/commands/{commandId}/wrappers/{wrapperName}/launch"}, method = POST, consumes = {JSON})
    @ApiOperation(value = "Resolve a command from the variable values in the request body, and launch it")
    public ResponseEntity<LaunchReport> launchCommandWJsonBody(final @PathVariable long commandId,
                                                               final @PathVariable String wrapperName,
                                                               final @RequestBody Map<String, String> allRequestParams)
            throws NoDockerServerException, DockerServerException, NotFoundException, BadRequestException, CommandResolutionException, ContainerException {
        log.info("Launch requested for command {}, wrapper {}", commandId, wrapperName);

        return returnLaunchReportWithStatus(launchContainer(null, commandId, wrapperName, 0L, allRequestParams));
    }

    @XapiRequestMapping(value = {"/projects/{project}/commands/{commandId}/wrappers/{wrapperName}/launch"}, method = POST, restrictTo = Read)
    @ApiOperation(value = "Resolve a command from the variable values in the query params, and launch it", notes = "DOES NOT WORK PROPERLY IN SWAGGER UI")
    public ResponseEntity<LaunchReport> launchCommandWQueryParams(final @PathVariable @Project String project,
                                                                  final @PathVariable long commandId,
                                                                  final @PathVariable String wrapperName,
                                                                  final @RequestParam Map<String, String> allRequestParams) {
        log.info("Launch requested for command {}, wrapper {}", commandId, wrapperName);

        return returnLaunchReportWithStatus(launchContainer(project, commandId, wrapperName, 0L, allRequestParams));
    }

    @XapiRequestMapping(value = {"/projects/{project}/commands/{commandId}/wrappers/{wrapperName}/launch"}, method = POST, consumes = {JSON}, restrictTo = Read)
    @ApiOperation(value = "Resolve a command from the variable values in the request body, and launch it")
    public ResponseEntity<LaunchReport> launchCommandWJsonBody(final @PathVariable @Project String project,
                                                               final @PathVariable long commandId,
                                                               final @PathVariable String wrapperName,
                                                               final @RequestBody Map<String, String> allRequestParams) {
        log.info("Launch requested for command {}, wrapper {}", commandId, wrapperName);

        final LaunchReport launchReport = launchContainer(project, commandId, wrapperName, 0L, allRequestParams);
        return returnLaunchReportWithStatus(launchReport);
    }

    @Nonnull
    private LaunchReport launchContainer(@Nullable final String project,
                                         final long commandId,
                                         @Nullable final String wrapperName,
                                         final long wrapperId,
                                         final Map<String, String> allRequestParams) {
        final UserI userI = getSessionUser();
        try {
            final Container container =
                    project == null ?
                            (commandId == 0L && wrapperName == null ?
                                    containerService.resolveCommandAndLaunchContainer(wrapperId, allRequestParams, userI) :
                                    containerService.resolveCommandAndLaunchContainer(commandId, wrapperName, allRequestParams, userI)) :
                            (commandId == 0L && wrapperName == null ?
                                    containerService.resolveCommandAndLaunchContainer(project, wrapperId, allRequestParams, userI) :
                                    containerService.resolveCommandAndLaunchContainer(project, commandId, wrapperName, allRequestParams, userI));
            if (container == null) {
                throw new CommandResolutionException("Something happened but I do not know what.");
            }
            if (log.isInfoEnabled()) {
                log.info("Launched command {}, wrapper {} {}. Produced container {}.", commandId, wrapperId, wrapperName, container.databaseId());
                if (log.isDebugEnabled()) {
                    log.debug("" + container);
                }
            }

            return container.isSwarmService() ?
                    LaunchReport.ServiceSuccess.create(container) :
                    LaunchReport.ContainerSuccess.create(container);
        } catch (Throwable t) {
            if (log.isInfoEnabled()) {
                log.error("Launch failed for command wrapper name {}.", wrapperName);
                log.error(mapLogString("Params: ", allRequestParams));
                log.error("Exception: ", t);
            }
            return LaunchReport.Failure.create(t.getMessage() != null ? t.getMessage() : "", allRequestParams, commandId, wrapperId);
        }
    }

    private String mapLogString(final String title, final Map<String, String> map) {
        final StringBuilder messageBuilder = new StringBuilder(title);
        for (Map.Entry<String, String> entry : map.entrySet()) {
            messageBuilder.append(entry.getKey());
            messageBuilder.append(": ");
            messageBuilder.append(entry.getValue());
            messageBuilder.append(", ");
        }
        return messageBuilder.substring(0, messageBuilder.length() - 2);
    }

    private ResponseEntity<LaunchReport> returnLaunchReportWithStatus(final LaunchReport launchReport) {
        if (launchReport instanceof LaunchReport.Success) {
            return ResponseEntity.ok(launchReport);
        } else {
            // TODO It would be better to return different stati for the different exception types.
            // But I don't think I want to throw an exception here, because I want the report to
            // be in the body. So it is what it is.
            return ResponseEntity.status(500).body(launchReport);
            // return new ResponseEntity<>(launchReport, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /*
    BULK LAUNCH
     */
    @XapiRequestMapping(value = {"/commands/{commandId}/wrappers/{wrapperName}/bulklaunch"}, method = POST, consumes = {JSON})
    @ApiOperation(value = "Resolve a command from the variable values in the request body, and launch it")
    @ResponseBody
    public LaunchReport.BulkLaunchReport bulklaunch(final @PathVariable long commandId,
                                                    final @PathVariable String wrapperName,
                                                    final @RequestBody List<Map<String, String>> allRequestParams) {
        log.info("Launch requested for command {}, wrapper name {}.", commandId, wrapperName);
        return bulkLaunch(null, commandId, wrapperName, 0L, allRequestParams);
    }

    @XapiRequestMapping(value = {"/wrappers/{wrapperId}/bulklaunch"}, method = POST, consumes = {JSON})
    @ApiOperation(value = "Resolve a command from the variable values in the request body, and launch it")
    @ResponseBody
    public LaunchReport.BulkLaunchReport bulklaunch(final @PathVariable long wrapperId,
                                                    final @RequestBody List<Map<String, String>> allRequestParams) {
        log.info("Launch requested for wrapper id {}.", wrapperId);
        return bulkLaunch(null, 0L, null, wrapperId, allRequestParams);
    }

    @XapiRequestMapping(value = {"/projects/{project}/commands/{commandId}/wrappers/{wrapperName}/bulklaunch"}, method = POST, consumes = {JSON})
    @ApiOperation(value = "Resolve a command from the variable values in the request body, and launch it")
    @ResponseBody
    public LaunchReport.BulkLaunchReport bulklaunch(final @PathVariable @Project String project,
                                                    final @PathVariable long commandId,
                                                    final @PathVariable String wrapperName,
                                                    final @RequestBody List<Map<String, String>> allRequestParams) {
        log.info("Launch requested for command {}, wrapper name {}, project {}.", commandId, wrapperName, project);
        return bulkLaunch(project, commandId, wrapperName, 0L, allRequestParams);
    }

    @XapiRequestMapping(value = {"/projects/{project}/wrappers/{wrapperId}/bulklaunch"}, method = POST, consumes = {JSON})
    @ApiOperation(value = "Resolve a command from the variable values in the request body, and launch it")
    @ResponseBody
    public LaunchReport.BulkLaunchReport bulklaunch(final @PathVariable @Project String project,
                                                    final @PathVariable long wrapperId,
                                                    final @RequestBody List<Map<String, String>> allRequestParams) {
        log.info("Launch requested for wrapper id {}, project {}.", wrapperId, project);
        return bulkLaunch(project, 0L, null, wrapperId, allRequestParams);
    }

    private LaunchReport.BulkLaunchReport bulkLaunch(final String project,
                                                     final long commandId,
                                                     final String wrapperName,
                                                     final long wrapperId,
                                                     final List<Map<String, String>> allRequestParams) {

        final LaunchReport.BulkLaunchReport.Builder reportBuilder = LaunchReport.BulkLaunchReport.builder();
        for (final Map<String, String> paramsSet : allRequestParams) {
            reportBuilder.addReport(launchContainer(project, commandId, wrapperName, wrapperId, paramsSet));
        }

        return reportBuilder.build();
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
