package org.nrg.containers.rest;

import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.exceptions.ContainerException;
import org.nrg.containers.exceptions.DockerServerException;
import org.nrg.containers.exceptions.NoDockerServerException;
import org.nrg.containers.exceptions.UnauthorizedException;
import org.nrg.containers.model.configuration.PluginVersionCheck;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.auto.ContainerPaginatedRequest;
import org.nrg.containers.security.ContainerControlUserAuthorization;
import org.nrg.containers.security.ContainerId;
import org.nrg.containers.services.ContainerService;
import org.nrg.framework.annotations.XapiRestController;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xapi.exceptions.InsufficientPrivilegesException;
import org.nrg.xapi.rest.AbstractXapiRestController;
import org.nrg.xapi.rest.AuthDelegate;
import org.nrg.xapi.rest.Project;
import org.nrg.xapi.rest.XapiRequestMapping;
import org.nrg.xdat.security.helpers.Groups;
import org.nrg.xdat.security.helpers.Permissions;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xft.security.UserI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.nrg.containers.services.ContainerService.XNAT_PASS;
import static org.nrg.xdat.security.helpers.AccessLevel.Admin;
import static org.nrg.xdat.security.helpers.AccessLevel.Authenticated;
import static org.nrg.xdat.security.helpers.AccessLevel.Read;
import static org.nrg.xdat.security.helpers.AccessLevel.Authorizer;
import static org.springframework.web.bind.annotation.RequestMethod.DELETE;
import static org.springframework.web.bind.annotation.RequestMethod.GET;
import static org.springframework.web.bind.annotation.RequestMethod.POST;

@Slf4j
@XapiRestController
@RequestMapping()
public class ContainerRestApi extends AbstractXapiRestController {

    private static final String JSON = MediaType.APPLICATION_JSON_UTF8_VALUE;
    private static final String TEXT = MediaType.TEXT_PLAIN_VALUE;
    private static final String ZIP = "application/zip";
    private static final String ATTACHMENT_DISPOSITION = "attachment; filename=\"%s.%s\"";

    private static final String CONTENT_KEY = "content";

    private ContainerService containerService;

    @Autowired
    public ContainerRestApi(final ContainerService containerService,
                            final UserManagementServiceI userManagementService,
                            final RoleHolder roleHolder) {
        super(userManagementService, roleHolder);
        this.containerService = containerService;
    }

    @XapiRequestMapping(value = "/containers/version", method = GET, restrictTo = Authenticated)
    @ApiOperation(value = "Check XNAT Version compatibility.")
    @ResponseBody
    public PluginVersionCheck versionCheck() {
        return containerService.checkXnatVersion();
    }

    @XapiRequestMapping(value = "/containers", method = GET, restrictTo = Authenticated)
    @ApiOperation(value = "Get all Containers")
    @ResponseBody
    public List<Container> getAll(final @RequestParam(required = false) Boolean nonfinalized) {
        final UserI userI = getSessionUser();
        return containerService.getAll(nonfinalized).stream()
                               .filter(c -> isUserOwnerOrAdmin(userI, c))
                               .map(this::scrubPasswordEnv)
                               .collect(Collectors.toList());
    }

    @XapiRequestMapping(value = "/containers", method = POST, restrictTo = Authenticated, consumes = JSON, produces = JSON)
    @ApiOperation(value = "Get paginated containers per request")
    @ResponseBody
    public List<Container> getPaginated(@RequestBody ContainerPaginatedRequest containerPaginatedRequest) {
        final UserI userI = getSessionUser();
        return containerService.getPaginated(containerPaginatedRequest).stream()
                               .map(this::scrubPasswordEnv)
                               .map(c -> isUserOwnerOrAdmin(userI, c) ? c : scrubProtectedData(c))
                               .collect(Collectors.toList());
    }

    @XapiRequestMapping(value = "/projects/{project}/containers", method = GET, restrictTo = Authenticated)
    @ApiOperation(value = "Get all Containers by project")
    @ResponseBody
    public List<Container> getAll(final @PathVariable @Project String project,
                                  final @RequestParam(required = false) Boolean nonfinalized) {
        final UserI userI = getSessionUser();
        return containerService.getAll(nonfinalized, project).stream()
                               .filter(c -> isUserOwnerOrAdmin(userI, c))
                               .map(this::scrubPasswordEnv)
                               .collect(Collectors.toList());
    }

    @XapiRequestMapping(value = "/projects/{project}/containers/name/{name}", method = GET, restrictTo = Authenticated)
    @ApiOperation(value = "Get Containers by name")
    @ResponseBody
    public Container getByName(final @PathVariable @Project String project,
                         final @PathVariable String name,
                               final @RequestParam(required = false) Boolean nonfinalized) throws NotFoundException {
        final UserI userI = getSessionUser();
        Container container = scrubPasswordEnv(containerService.getByName(project, name, nonfinalized));
        return isUserOwnerOrAdmin(userI, container) ?
                container :
                scrubProtectedData(container);
    }

    @XapiRequestMapping(value = "/container/name/{name}", method = GET, restrictTo = Admin)
    @ApiOperation(value = "Get Containers by database name")
    @ResponseBody
    public Container getByName(final @PathVariable String name,
                               final @RequestParam(required = false) Boolean nonfinalized) throws NotFoundException {
        return scrubPasswordEnv(containerService.getByName(name, nonfinalized));
    }

    @XapiRequestMapping(value = "/containers/{id}", method = GET, restrictTo = Authenticated)
    @ApiOperation(value = "Get Containers by database ID")
    @ResponseBody
    public Container get(final @PathVariable String id) throws NotFoundException {
        final UserI userI = getSessionUser();
        Container container = scrubPasswordEnv(containerService.get(id));
        return isUserOwnerOrAdmin(userI, container) ?
                container :
                scrubProtectedData(container);
    }

    @XapiRequestMapping(value = "/containers/{id}", method = DELETE, restrictTo = Authenticated)
    @ApiOperation(value = "Get Container by container server ID")
    public ResponseEntity<Void> delete(final @PathVariable String id) throws NotFoundException, UnauthorizedException {
        final UserI userI = getSessionUser();
        if(!isUserOwnerOrAdmin(userI, containerService.get(id))){
            throw new UnauthorizedException(String.format("User %s cannot delete container %s", userI.getLogin(), id));
        }
        containerService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @XapiRequestMapping(value = "/containers/{id}/finalize", method = POST, produces = JSON, restrictTo = Admin)
    @ApiOperation(value = "Finalize Container")
    public void finalize(final @PathVariable String id) throws NotFoundException, ContainerException, DockerServerException, NoDockerServerException {
        final UserI userI = getSessionUser();
        containerService.finalize(id, userI);
    }

    @XapiRequestMapping(value = "/containers/{id}/kill", method = POST, restrictTo = Authenticated)
    @ApiOperation(value = "Kill Container")
    @ResponseBody
    public String kill(final @PathVariable String id)
            throws NotFoundException, NoDockerServerException, DockerServerException, UnauthorizedException {
        return containerService.kill(id, getSessionUser());
    }

    @XapiRequestMapping(value = "/projects/{project}/containers/{id}/kill", method = POST, restrictTo = Read)
    @ApiOperation(value = "Kill Container")
    @ResponseBody
    public String kill(final @PathVariable @Project String project,
                       final @PathVariable String id)
            throws NotFoundException, NoDockerServerException, DockerServerException, UnauthorizedException {
        return containerService.kill(project, id, getSessionUser());
    }

    private Container scrubPasswordEnv(final Container container) {
        if (container == null) { return null; }

        final Map<String, String> scrubbedEnvironmentVariables = new HashMap<>();
        for (final Map.Entry<String, String> env : container.environmentVariables().entrySet()) {
            scrubbedEnvironmentVariables.put(env.getKey(),
                    env.getKey().equals(XNAT_PASS) ? "******" : env.getValue());
        }
        return container.toBuilder().environmentVariables(scrubbedEnvironmentVariables).build();
    }

    private Boolean isUserOwnerOrAdmin(UserI user, Container container){
        return (Groups.isSiteAdmin(user) || Groups.hasAllDataAccess(user) ||
                Permissions.isProjectOwner(user, container.project()) ||
                user.getLogin().contentEquals(container.userId()));
    }

    private Container scrubProtectedData(final Container c){
        return Container.builder()
                        .databaseId(c.databaseId())
                        .commandId(c.commandId())
                        .status(c.status())
                        .statusTime(c.statusTime())
                        .wrapperId(c.wrapperId())
                        .userId(c.userId())
                        .project(c.project())
                        .dockerImage(c.dockerImage())
                        .commandLine(StringUtils.isBlank(c.commandLine()) ? "" : "[REDACTED]")
                        .environmentVariables(new HashMap<>())
                        .ports(c.ports())
                        .mounts(new ArrayList<>())
                        .inputs(new ArrayList<>())
                        .outputs(new ArrayList<>())
                        .history(new ArrayList<>())
                        .build();
    }

    @AuthDelegate(ContainerControlUserAuthorization.class)
    @XapiRequestMapping(value = "/containers/{containerId}/logs", method = GET, restrictTo = Authorizer)
    @ApiOperation(value = "Get Container logs",
            notes = "Return stdout and stderr logs as a zip")
    public void getLogs(final @PathVariable @ContainerId String containerId,
                        final HttpServletResponse response)
            throws IOException, InsufficientPrivilegesException, NoDockerServerException, DockerServerException, NotFoundException {
        final Map<String, InputStream> logStreams = containerService.getLogStreams(containerId);

        try(final ZipOutputStream zipStream = new ZipOutputStream(response.getOutputStream()) ) {
            for(final String streamName : logStreams.keySet()){
                final InputStream inputStream = logStreams.get(streamName);
                final ZipEntry entry = new ZipEntry(streamName);
                try {
                    zipStream.putNextEntry(entry);
                    writeToOuputStream(inputStream, zipStream);
                } catch (IOException e) {
                    log.error("There was a problem writing %s to the zip. " + e.getMessage(), streamName);
                }
            }

            response.setStatus(HttpStatus.OK.value());
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, getAttachmentDisposition(containerId, "zip"));
            response.setHeader(HttpHeaders.CONTENT_TYPE, ZIP);
        } catch (IOException e) {
            log.error("There was a problem opening the zip stream.", e);
        }

        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
    }

    @AuthDelegate(ContainerControlUserAuthorization.class)
    @XapiRequestMapping(value = "/containers/{containerId}/logs/{file}", method = GET, restrictTo = Authorizer)
    @ApiOperation(value = "Get Container logs", notes = "Return either stdout or stderr logs")
    @ResponseBody
    public ResponseEntity<String> getLog(final @PathVariable @ContainerId String containerId,
                                         final @PathVariable @ApiParam(allowableValues = "stdout, stderr") String file)
            throws NoDockerServerException, DockerServerException, NotFoundException, IOException {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, getAttachmentDisposition(containerId + "-" + file, "log"))
                .header(HttpHeaders.CONTENT_TYPE, TEXT)
                .body(doGetLog(containerId, file));
    }

    @AuthDelegate(ContainerControlUserAuthorization.class)
    @XapiRequestMapping(value = "/containers/{containerId}/logSince/{file}", method = GET, restrictTo = Authorizer)
    @ApiOperation(value = "Get Container logs", notes = "Return either stdout or stderr logs")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> pollLog(final @PathVariable @ContainerId String containerId,
                                       final @PathVariable @ApiParam(allowableValues = "stdout, stderr") String file,
                                       final @RequestParam(required = false) Long since,
                                       final @RequestParam(required = false) Long bytesRead,
                                       final @RequestParam(required = false) Boolean loadAll)
            throws NoDockerServerException, DockerServerException, NotFoundException, IOException {

        // IntelliJ hits a breakpoint set on the return line twice if I don't define a local var here. No idea why.
        Map<String, Object> body = doGetLog(containerId, file, since, bytesRead, loadAll != null && loadAll);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, JSON)
                .body(body);
    }

    private String doGetLog(String containerId, String file)
            throws NotFoundException, IOException {
        return (String) doGetLog(containerId, file, null, null, true).get(CONTENT_KEY);
    }

    private Map<String, Object> doGetLog(String containerId, String file, Long since, Long bytesRead, boolean loadAll)
            throws NotFoundException, IOException {
        Integer sinceInt = null;
        try {
            sinceInt = since == null ? null : Math.toIntExact(since);
        } catch (ArithmeticException e) {
            log.error("Unable to convert since parameter to integer", e);
        }

        long queryTime = System.currentTimeMillis() / 1000L;
        boolean containerDone = containerService.containerStatusIsTerminal(containerService.get(containerId));
        final InputStream logStream = containerService.getLogStream(containerId, file, true, sinceInt);

        String logContent;
        long lastTime = -1;
        long currentBytesRead = -1;
        boolean fromFile = false;
        if (logStream == null) {
            logContent = "";
            lastTime = since == null ? queryTime : since;
        } else {
            final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            if (logStream instanceof FileInputStream) {
                long maxBytes = 1048576; // 1MB chunks
                if (loadAll) {
                    maxBytes = Long.MAX_VALUE;
                }
                // 1MB chunks
                currentBytesRead = writeToOuputStream(logStream, byteArrayOutputStream,
                        maxBytes, bytesRead == null ? 0 : bytesRead);
                logContent = byteArrayOutputStream.toString(StandardCharsets.UTF_8.name());
                fromFile = true;
            } else {
                // It's not a file and the container/service is still active (aka still potentially logging)
                writeToOuputStream(logStream, byteArrayOutputStream);
                logContent = byteArrayOutputStream.toString(StandardCharsets.UTF_8.name());

                if (!containerDone) {
                    // Determine what to pass for "since" querying based on timestamps,
                    // leave as -1 to stop querying if container finished
                    String[] lines = logContent.replaceAll("(\\n)+$", "").split("\\n");
                    String lastLine;
                    try {
                        if (lines.length == 0 ||
                                StringUtils.isBlank(lastLine = lines[lines.length - 1].replaceAll(" .*", ""))) {
                            throw new ParseException(null, 0);
                        }
                        lastTime = Instant.parse(StringUtils.substringBefore(lastLine, "\r"))
                                          .plus(1L, ChronoUnit.SECONDS).getEpochSecond();
                    } catch (ParseException e) {
                        lastTime = since == null ? queryTime : since;
                    }
                }

                // Strip the timestamps
                logContent = logContent.replaceAll("(^|\\n)\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{9}Z ","\n");
            }
        }
        Map<String, Object> jsonContent = new HashMap<>();
        jsonContent.put(CONTENT_KEY, logContent);
        jsonContent.put("timestamp", lastTime);
        jsonContent.put("bytesRead", currentBytesRead);
        jsonContent.put("fromFile", fromFile);
        return jsonContent;
    }

    private void writeToOuputStream(InputStream inputStream, OutputStream outputStream) throws IOException {
        writeToOuputStream(inputStream, outputStream, Long.MAX_VALUE, 0);
    }

    private long writeToOuputStream(InputStream inputStream, OutputStream outputStream, long maxBytes, long bytesRead)
            throws IOException {

        long totalSkipped = 0;
        if (bytesRead > 0) {
            long skipped;
            while (totalSkipped < bytesRead && (skipped = inputStream.skip(bytesRead - totalSkipped)) > 0) {
                totalSkipped += skipped;
            }
            if (totalSkipped != bytesRead) {
                log.error("Error skipping to proper location of input stream for paginated logging display");
            }
        }
        byte[] buffer = new byte[1024];
        int length;
        long totalBytes = 0;
        while ((length = inputStream.read(buffer)) > 0) {
            outputStream.write(buffer, 0, length);
            totalBytes += length;
            if (totalBytes >= maxBytes) {
                //TODO update so that we break on a line break or at least a whitespace character?
                return totalBytes + totalSkipped;
            }
        }
        // Done reading
        return -1;
    }

    private static String getAttachmentDisposition(final String name, final String extension) {
        return String.format(ATTACHMENT_DISPOSITION, name, extension);
    }

    @ResponseStatus(value = HttpStatus.NOT_FOUND)
    @ExceptionHandler(value = {NotFoundException.class})
    public String handleNotFound(final Exception e) {
        return e.getMessage();
    }

    @ResponseStatus(value = HttpStatus.FAILED_DEPENDENCY)
    @ExceptionHandler(value = {NoDockerServerException.class})
    public String handleFailedDependency() { return "Set up Docker server before using this REST endpoint."; }

    @ResponseStatus(value = HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(value = {DockerServerException.class, ContainerException.class})
    public String handleDockerServerException(final Exception e) {
        return e.getMessage();
    }

}
