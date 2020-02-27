package org.nrg.containers.rest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.nrg.containers.model.command.auto.LaunchReport;
import org.nrg.containers.model.command.auto.LaunchUi;
import org.nrg.containers.model.command.auto.ResolvedCommand.PartiallyResolvedCommand;
import org.nrg.containers.model.configuration.CommandConfiguration;
import org.nrg.containers.services.CommandResolutionService;
import org.nrg.containers.services.CommandService;
import org.nrg.containers.services.ContainerService;
import org.nrg.containers.services.DockerServerService;
import org.nrg.framework.annotations.XapiRestController;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xapi.rest.AbstractXapiRestController;
import org.nrg.xapi.rest.Project;
import org.nrg.xapi.rest.XapiRequestMapping;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xft.event.persist.PersistentWorkflowI;
import org.nrg.xft.event.persist.PersistentWorkflowUtils;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.utils.WorkflowUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolExecutorFactoryBean;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

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
    private final DockerServerService dockerServerService;
    private final ObjectMapper mapper;
    private final ExecutorService executorService;

    @Autowired
    public LaunchRestApi(final CommandService commandService,
                         final ContainerService containerService,
                         final CommandResolutionService commandResolutionService,
                         final DockerServerService dockerServerService,
                         final UserManagementServiceI userManagementService,
                         final RoleHolder roleHolder,
                         final ObjectMapper mapper,
                         @Qualifier("containerServiceThreadPoolExecutorFactoryBean")
                             final ThreadPoolExecutorFactoryBean containerServiceThreadPoolExecutorFactoryBean) {
        super(userManagementService, roleHolder);
        this.commandService = commandService;
        this.containerService = containerService;
        this.dockerServerService = dockerServerService;
        this.commandResolutionService = commandResolutionService;
        this.mapper = mapper;
        this.executorService = containerServiceThreadPoolExecutorFactoryBean.getObject();
    }

    /*
    GET A LAUNCH UI
     */
    @XapiRequestMapping(value = {"/wrappers/{wrapperId}/launch"}, method = GET)
    @ApiOperation(value = "Get Launch UI for wrapper", notes = "DOES NOT WORK PROPERLY IN SWAGGER UI")
    @ResponseBody
    public LaunchUi getLaunchUi(final @PathVariable long wrapperId,
                                final @RequestParam Map<String, String> allRequestParams)
            throws NotFoundException, CommandResolutionException, UnauthorizedException {
        log.info("Launch UI requested for wrapper {}", wrapperId);

        return getLaunchUi(null, 0L, null, wrapperId, allRequestParams);
    }

    @XapiRequestMapping(value = {"/commands/{commandId}/wrappers/{wrapperName}/launch"}, method = GET)
    @ApiOperation(value = "Get Launch UI for wrapper", notes = "DOES NOT WORK PROPERLY IN SWAGGER UI")
    @ResponseBody
    public LaunchUi getLaunchUi(final @PathVariable long commandId,
                                final @PathVariable String wrapperName,
                                final @RequestParam Map<String, String> allRequestParams)
            throws NotFoundException, CommandResolutionException, UnauthorizedException {
        log.info("Launch UI requested for command {}, wrapper {}", commandId, wrapperName);

        return getLaunchUi(null, commandId, wrapperName, 0L, allRequestParams);
    }

    @XapiRequestMapping(value = {"/projects/{project}/wrappers/{wrapperId}/launch"}, method = GET, restrictTo = Read)
    @ApiOperation(value = "Get Launch UI for wrapper", notes = "DOES NOT WORK PROPERLY IN SWAGGER UI")
    @ResponseBody
    public LaunchUi getLaunchUi(final @PathVariable @Project String project,
                                final @PathVariable long wrapperId,
                                final @RequestParam Map<String, String> allRequestParams)
            throws NotFoundException, CommandResolutionException, UnauthorizedException {
        log.info("Launch UI requested for project {}, wrapper {}", project, wrapperId);

        return getLaunchUi(project, 0L, null, wrapperId, allRequestParams);
    }

    @XapiRequestMapping(value = {"/projects/{project}/commands/{commandId}/wrappers/{wrapperName}/launch"}, method = GET, restrictTo = Read)
    @ApiOperation(value = "Get Launch UI for wrapper", notes = "DOES NOT WORK PROPERLY IN SWAGGER UI")
    @ResponseBody
    public LaunchUi getLaunchUi(final @PathVariable @Project String project,
                                final @PathVariable long commandId,
                                final @PathVariable String wrapperName,
                                final @RequestParam Map<String, String> allRequestParams)
            throws NotFoundException, CommandResolutionException, UnauthorizedException {
        log.info("Launch UI requested for project {}, command {}, wrapper {}", project, commandId, wrapperName);

        return getLaunchUi(project, commandId, wrapperName, 0L, allRequestParams);
    }

    private LaunchUi getLaunchUi(final String project,
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
            return LaunchUi.create(partiallyResolvedCommand,
                    commandConfiguration.inputs(),
                    dockerServerService.getServer(),
                    false);
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
    public LaunchUi getBulkLaunchUi(final @PathVariable long wrapperId,
                                    final @RequestParam("sampleTarget") String target,
                                    final @RequestParam("rootElement") String rootElement)
            throws NotFoundException, CommandResolutionException, UnauthorizedException {
        log.info("Launch UI requested for wrapper {}", wrapperId);

        return getBulkLaunchUi(null, 0L, null, wrapperId, target, rootElement);
    }

    @XapiRequestMapping(value = {"/commands/{commandId}/wrappers/{wrapperName}/bulklaunch"}, method = GET)
    @ApiOperation(value = "Get Bulk Launch UI for wrapper", notes = "DOES NOT WORK PROPERLY IN SWAGGER UI")
    @ResponseBody
    public LaunchUi getBulkLaunchUi(final @PathVariable long commandId,
                                    final @PathVariable String wrapperName,
                                    final @RequestParam("sampleTarget") String target,
                                    final @RequestParam("rootElement") String rootElement)
            throws NotFoundException, CommandResolutionException, UnauthorizedException {
        log.info("Bulk Launch UI requested for command {}, wrapper {}", commandId, wrapperName);

        return getBulkLaunchUi(null, commandId, wrapperName, 0L, target, rootElement);
    }

    @XapiRequestMapping(value = {"/projects/{project}/wrappers/{wrapperId}/bulklaunch"}, method = GET,
            restrictTo = Read)
    @ApiOperation(value = "Get Bulk Launch UI for wrapper", notes = "DOES NOT WORK PROPERLY IN SWAGGER UI")
    @ResponseBody
    public LaunchUi getBulkLaunchUi(final @PathVariable @Project String project,
                                    final @PathVariable long wrapperId,
                                    final @RequestParam("sampleTarget") String target,
                                    final @RequestParam("rootElement") String rootElement)
            throws NotFoundException, CommandResolutionException, UnauthorizedException {
        log.info("Launch UI requested for project {}, wrapper {}", project, wrapperId);

        return getBulkLaunchUi(project, 0L, null, wrapperId, target, rootElement);
    }

    @XapiRequestMapping(value = {"/projects/{project}/commands/{commandId}/wrappers/{wrapperName}/bulklaunch"},
            method = GET, restrictTo = Read)
    @ApiOperation(value = "Get Bulk Launch UI for wrapper", notes = "DOES NOT WORK PROPERLY IN SWAGGER UI")
    @ResponseBody
    public LaunchUi getBulkLaunchUi(final @PathVariable @Project String project,
                                    final @PathVariable long commandId,
                                    final @PathVariable String wrapperName,
                                    final @RequestParam("sampleTarget") String target,
                                    final @RequestParam("rootElement") String rootElement)
            throws NotFoundException, CommandResolutionException, UnauthorizedException {
        log.info("Launch UI requested for project {}, command {}, wrapper {}", project, commandId, wrapperName);

        return getBulkLaunchUi(project, commandId, wrapperName, 0L, target, rootElement);
    }

    private LaunchUi getBulkLaunchUi(final String project,
                                     final long commandId,
                                     final String wrapperName,
                                     final long wrapperId,
                                     final String target,
                                     final String rootElement)
            throws NotFoundException, CommandResolutionException, UnauthorizedException {

        if (StringUtils.isBlank(target)) {
            throw new CommandResolutionException("No sample target specified");
        }
        
        // For now, we just use the sample target for command preresolution
        Map<String, String> prms = Maps.newHashMap();
        prms.put(rootElement, target);
        
        try {
            log.debug("Getting {} configuration for command {}, wrapper name {}, wrapper id {}.",
                    project == null ? "site" : "project " + project, commandId, wrapperName, wrapperId);
            final CommandConfiguration commandConfiguration = getCommandConfiguration(project, commandId,
                    wrapperName, wrapperId);

            final UserI userI = getSessionUser();
            final PartiallyResolvedCommand partiallyResolvedCommand = preResolve(project, commandId, wrapperName,
                    wrapperId, prms, userI);

            log.debug("Creating launch UI.");
            return LaunchUi.create(partiallyResolvedCommand,
                    commandConfiguration.inputs(),
                    dockerServerService.getServer(),
                    true);
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
    @XapiRequestMapping(value = {"/wrappers/{wrapperId}/root/{rootElement}/launch"}, method = POST)
    @ApiOperation(value = "Resolve a command from the variable values in the query params, and launch it",
            notes = "DOES NOT WORK PROPERLY IN SWAGGER UI")
    public ResponseEntity<LaunchReport> launchCommandWQueryParams(final @PathVariable long wrapperId,
                                                                  final @PathVariable String rootElement,
                                                                  final @RequestParam Map<String, String> allRequestParams) {
        log.info("Launch requested for command id " + wrapperId);

        return returnLaunchReportWithStatus(launchContainer(null, 0L, null,
                wrapperId, rootElement, allRequestParams));
    }

    @XapiRequestMapping(value = {"/wrappers/{wrapperId}/root/{rootElement}/launch"}, method = POST, consumes = {JSON})
    @ApiOperation(value = "Resolve a command from the variable values in the request body, and launch it")
    public ResponseEntity<LaunchReport> launchCommandWJsonBody(final @PathVariable long wrapperId,
                                                               final @PathVariable String rootElement,
                                                               final @RequestBody Map<String, String> allRequestParams) {
        log.info("Launch requested for command wrapper id " + wrapperId);

        return returnLaunchReportWithStatus(launchContainer(null, 0L, null,
                wrapperId, rootElement, allRequestParams));
    }

    @XapiRequestMapping(value = {"/projects/{project}/wrapper/{wrapperId}/root/{rootElement}/launch"},
            method = POST, restrictTo = Read)
    @ApiOperation(value = "Resolve a command from the variable values in the query params, and launch it",
            notes = "DOES NOT WORK PROPERLY IN SWAGGER UI")
    public ResponseEntity<LaunchReport> launchCommandWQueryParams(final @PathVariable @Project String project,
                                                                  final @PathVariable long wrapperId,
                                                                  final @PathVariable String rootElement,
                                                                  final @RequestParam Map<String, String> allRequestParams) {
        log.info("Launch requested for wrapper id " + wrapperId);

        return returnLaunchReportWithStatus(launchContainer(project, 0L, null,
                wrapperId, rootElement, allRequestParams));
    }

    @XapiRequestMapping(value = {"/projects/{project}/wrappers/{wrapperId}/root/{rootElement}/launch"},
            method = POST, consumes = {JSON}, restrictTo = Read)
    @ApiOperation(value = "Resolve a command from the variable values in the request body, and launch it")
    public ResponseEntity<LaunchReport> launchCommandWJsonBody(final @PathVariable @Project String project,
                                                               final @PathVariable long wrapperId,
                                                               final @PathVariable String rootElement,
                                                               final @RequestBody Map<String, String> allRequestParams) {
        log.info("Launch requested for wrapper id " + wrapperId);

        return returnLaunchReportWithStatus(launchContainer(project, 0L, null,
                wrapperId, rootElement, allRequestParams));
    }

    /*
    LAUNCH COMMAND + WRAPPER BY NAME
     */
    @XapiRequestMapping(value = {"/commands/{commandId}/wrappers/{wrapperName}/root/{rootElement}/launch"},
            method = POST)
    @ApiOperation(value = "Resolve a command from the variable values in the query params, and launch it",
            notes = "DOES NOT WORK PROPERLY IN SWAGGER UI")
    public ResponseEntity<LaunchReport> launchCommandWQueryParams(final @PathVariable long commandId,
                                                                  final @PathVariable String wrapperName,
                                                                  final @PathVariable String rootElement,
                                                                  final @RequestParam Map<String, String> allRequestParams){
        log.info("Launch requested for command {}, wrapper {}", commandId, wrapperName);

        return returnLaunchReportWithStatus(launchContainer(null, commandId, wrapperName,
                0L, rootElement, allRequestParams));
    }

    @XapiRequestMapping(value = {"/commands/{commandId}/wrappers/{wrapperName}/root/{rootElement}/launch"},
            method = POST, consumes = {JSON})
    @ApiOperation(value = "Resolve a command from the variable values in the request body, and launch it")
    public ResponseEntity<LaunchReport> launchCommandWJsonBody(final @PathVariable long commandId,
                                                               final @PathVariable String wrapperName,
                                                               final @PathVariable String rootElement,
                                                               final @RequestBody Map<String, String> allRequestParams) {
        log.info("Launch requested for command {}, wrapper {}", commandId, wrapperName);

        return returnLaunchReportWithStatus(launchContainer(null, commandId, wrapperName,
                0L, rootElement, allRequestParams));
    }

    @XapiRequestMapping(value = {"/projects/{project}/commands/{commandId}/wrappers/{wrapperName}/root/{rootElement}/launch"},
            method = POST, restrictTo = Read)
    @ApiOperation(value = "Resolve a command from the variable values in the query params, and launch it",
            notes = "DOES NOT WORK PROPERLY IN SWAGGER UI")
    public ResponseEntity<LaunchReport> launchCommandWQueryParams(final @PathVariable @Project String project,
                                                                  final @PathVariable long commandId,
                                                                  final @PathVariable String wrapperName,
                                                                  final @PathVariable String rootElement,
                                                                  final @RequestParam Map<String, String> allRequestParams) {
        log.info("Launch requested for command {}, wrapper {}", commandId, wrapperName);

        return returnLaunchReportWithStatus(launchContainer(project, commandId, wrapperName,
                0L, rootElement, allRequestParams));
    }

    @XapiRequestMapping(value = {"/projects/{project}/commands/{commandId}/wrappers/{wrapperName}/root/{rootElement}/launch"},
            method = POST, consumes = {JSON}, restrictTo = Read)
    @ApiOperation(value = "Resolve a command from the variable values in the request body, and launch it")
    public ResponseEntity<LaunchReport> launchCommandWJsonBody(final @PathVariable @Project String project,
                                                               final @PathVariable long commandId,
                                                               final @PathVariable String wrapperName,
                                                               final @PathVariable String rootElement,
                                                               final @RequestBody Map<String, String> allRequestParams) {
        log.info("Launch requested for command {}, wrapper {}", commandId, wrapperName);

        return returnLaunchReportWithStatus(launchContainer(project, commandId, wrapperName,
                0L, rootElement, allRequestParams));
    }

    @Nonnull
    private LaunchReport launchContainer(@Nullable final String project,
                                         final long commandId,
                                         @Nullable final String wrapperName,
                                         final long wrapperId,
                                         @Nullable final String rootElement,
                                         final Map<String, String> allRequestParams) {

        return launchContainer(project, commandId, wrapperName, wrapperId, rootElement, allRequestParams,
                getSessionUser());
    }

    @Nonnull
    private LaunchReport launchContainer(@Nullable final String project,
                                         final long commandId,
                                         @Nullable final String wrapperName,
                                         final long wrapperId,
                                         @Nullable final String rootElement,
                                         final Map<String, String> allRequestParams,
                                         final UserI userI) {

        PersistentWorkflowI workflow = null;
        String workflowid = "";

        try {
            // Create workflow first
            String xnatIdOrUri;
            if (rootElement != null && (xnatIdOrUri = allRequestParams.get(rootElement)) != null) {
                // Note: for scans, this can fail with a duplicate key value violates unique constraint
                // (id, pipeline_name, launch_time) since they'll share a root element. But, we try again later, so no biggie
                workflow = containerService.createContainerWorkflow(xnatIdOrUri, rootElement,
                        StringUtils.defaultIfBlank(wrapperName, commandService.retrieveWrapper(wrapperId).name()),
                        StringUtils.defaultString(project, ""), userI);
                workflowid = workflow.getWorkflowId().toString();
            }

            // Queue command resolution and container launch
            containerService.queueResolveCommandAndLaunchContainer(project, wrapperId, commandId,
                    wrapperName, allRequestParams, userI, workflow);

            String msg = "To be assigned";
            if (StringUtils.isNotBlank(workflowid)) {
                msg += ", see workflow " + workflowid;
            }
            return LaunchReport.ContainerSuccess.create(msg, allRequestParams, null, commandId, wrapperId);

        } catch (Throwable t) {
            if (workflow != null) {
                String failedStatus = PersistentWorkflowUtils.FAILED + " (Staging)";
                workflow.setStatus(failedStatus);
                workflow.setDetails(t.getMessage());
                try {
                    WorkflowUtils.save(workflow, workflow.buildEvent());
                } catch (Exception we) {
                    log.error("Unable to set workflow status to {} for wfid={}", failedStatus, workflow.getWorkflowId(), we);
                }
            }
            if (log.isInfoEnabled()) {
                log.error("Unable to queue container launch for command wrapper name {}.", wrapperName);
                log.error(mapLogString("Params: ", allRequestParams));
                log.error("Exception: ", t);
            }
            return LaunchReport.Failure.create(t.getMessage() != null ? t.getMessage() : "Unable to queue container launch",
                    allRequestParams, commandId, wrapperId);
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
    @XapiRequestMapping(value = {"/commands/{commandId}/wrappers/{wrapperName}/root/{rootElement}/bulklaunch"},
            method = POST, consumes = {JSON})
    @ApiOperation(value = "Resolve a command from the variable values in the request body, and launch it")
    @ResponseBody
    public LaunchReport.BulkLaunchReport bulklaunch(final @PathVariable long commandId,
                                                    final @PathVariable String wrapperName,
                                                    final @PathVariable String rootElement,
                                                    final @RequestBody Map<String, String> allRequestParams) throws IOException {
        log.info("Bulk launch requested for command {}, wrapper name {}.", commandId, wrapperName);
        return bulkLaunch(null, commandId, wrapperName, 0L, rootElement, allRequestParams);
    }

    @XapiRequestMapping(value = {"/wrappers/{wrapperId}/root/{rootElement}/bulklaunch"}, method = POST, consumes = {JSON})
    @ApiOperation(value = "Resolve a command from the variable values in the request body, and launch it")
    @ResponseBody
    public LaunchReport.BulkLaunchReport bulklaunch(final @PathVariable long wrapperId,
                                                    final @PathVariable String rootElement,
                                                    final @RequestBody Map<String, String> allRequestParams) throws IOException {
        log.info("Bulk launch requested for wrapper id {}.", wrapperId);
        return bulkLaunch(null, 0L, null, wrapperId, rootElement, allRequestParams);
    }

    @XapiRequestMapping(value = {"/projects/{project}/commands/{commandId}/wrappers/{wrapperName}/root/{rootElement}/bulklaunch"},
            method = POST, consumes = {JSON})
    @ApiOperation(value = "Resolve a command from the variable values in the request body, and launch it")
    @ResponseBody
    public LaunchReport.BulkLaunchReport bulklaunch(final @PathVariable @Project String project,
                                                    final @PathVariable long commandId,
                                                    final @PathVariable String wrapperName,
                                                    final @PathVariable String rootElement,
                                                    final @RequestBody Map<String, String> allRequestParams) throws IOException {
        log.info("Bulk launch requested for command {}, wrapper name {}, project {}.", commandId, wrapperName, project);
        return bulkLaunch(project, commandId, wrapperName, 0L, rootElement, allRequestParams);
    }

    @XapiRequestMapping(value = {"/projects/{project}/wrappers/{wrapperId}/root/{rootElement}/bulklaunch"},
            method = POST, consumes = {JSON})
    @ApiOperation(value = "Resolve a command from the variable values in the request body, and launch it")
    @ResponseBody
    public LaunchReport.BulkLaunchReport bulklaunch(final @PathVariable @Project String project,
                                                    final @PathVariable long wrapperId,
                                                    final @PathVariable String rootElement,
                                                    final @RequestBody Map<String, String> allRequestParams) throws IOException {
        log.info("Bulk launch requested for wrapper id {}, project {}.", wrapperId, project);
        return bulkLaunch(project, 0L, null, wrapperId, rootElement, allRequestParams);
    }

    private LaunchReport.BulkLaunchReport bulkLaunch(final String project,
                                                     final long commandId,
                                                     final String wrapperName,
                                                     final long wrapperId,
                                                     final String rootElement,
                                                     final Map<String, String> allRequestParams) throws IOException {
        final UserI userI = getSessionUser();
        final LaunchReport.BulkLaunchReport.Builder reportBuilder = LaunchReport.BulkLaunchReport.builder();
        List<String> targets = mapper.readValue(allRequestParams.get(rootElement), new TypeReference<List<String>>() {});
        log.debug("Bulk launching on {} targets", targets.size());
        for (final String target : targets) {
            Map<String, String> paramsSet = Maps.newHashMap(allRequestParams);
            paramsSet.put(rootElement, target);
            try {
                executorService.submit(() -> {
                    launchContainer(project, commandId, wrapperName, wrapperId, rootElement, paramsSet, userI);
                });
                reportBuilder.addSuccess(LaunchReport.ContainerSuccess.create("To be assigned",
                        paramsSet, null, commandId, wrapperId));
            } catch (Exception e) {
                // Most exceptions should be "logged" to the workflow but this is meant to catch
                // issues submitting to the executorService
                reportBuilder.addFailure(LaunchReport.Failure.create(e.getMessage() != null ?
                                e.getMessage() : "Unable to queue container launch",
                        paramsSet, commandId, wrapperId));
            }
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
