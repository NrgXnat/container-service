package org.nrg.containers.rest;

import com.spotify.docker.client.DockerException;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.nrg.containers.exceptions.BadRequestException;
import org.nrg.containers.exceptions.ContainerServerException;
import org.nrg.containers.exceptions.NoHubException;
import org.nrg.containers.exceptions.NoServerPrefException;
import org.nrg.containers.exceptions.NotFoundException;
import org.nrg.containers.model.Container;
import org.nrg.containers.model.ContainerHub;
import org.nrg.containers.model.ContainerServer;
import org.nrg.containers.model.Image;
import org.nrg.containers.services.ContainerService;
import org.nrg.prefs.exceptions.InvalidPreferenceName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.springframework.web.bind.annotation.RequestMethod.GET;
import static org.springframework.web.bind.annotation.RequestMethod.POST;
import static org.springframework.web.bind.annotation.RequestMethod.PUT;

@Api(description = "XNAT Container Services REST API")
@RestController
@RequestMapping(value = "/containers")
public class ContainersApi {
    private static final Logger _log = LoggerFactory.getLogger(ContainersApi.class);

    public static final String JSON = MediaType.APPLICATION_JSON_UTF8_VALUE;
    public static final String PLAIN_TEXT = MediaType.TEXT_PLAIN_VALUE;
    public static final String FORM = MediaType.APPLICATION_FORM_URLENCODED_VALUE;

    public static final String CONTAINER_VERBS = "start|stop|restart|pause|unpause|kill";
    public static final String CONTAINER_VERBS_CSV = "start,stop,restart,pause,unpause,kill";

    @Autowired
    @SuppressWarnings("SpringJavaAutowiringInspection") // IntelliJ does not process the excludeFilter in ContainersSpringConfig @ComponentScan, erroneously marks this red
    private ContainerService service;

    @ApiOperation(value = "Get list of containers.", httpMethod = "GET", produces = JSON,
            notes = "Returns a list of all containers on the container server.")
    @ApiResponses({
            @ApiResponse(code = 200, message = "A list of containers on the server."),
            @ApiResponse(code = 401, message = "Must be authenticated to access the XNAT REST API."),
            @ApiResponse(code = 424, message = "Container Server value is not set."),
            @ApiResponse(code = 500, message = "Unexpected error.")})
    @RequestMapping(value = {}, method = GET, produces = {JSON, PLAIN_TEXT})
    @ResponseBody
    public List<Container> getContainers(@ApiParam(value = "ID of the container to fetch", required = true)
                                             final @RequestParam MultiValueMap<String, String> queryParams)
            throws NoServerPrefException, ContainerServerException {
        _log.debug(String.format("%s: %s", "getContainers", queryParams));
        return service.getContainers(queryParams);
    }

    @ApiOperation(value = "Get container", httpMethod = "GET", produces = JSON,
            notes = "Gets the container with the given ID.")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Container successfully retrieved."),
            @ApiResponse(code = 401, message = "Must be authenticated to access the XNAT REST API."),
            @ApiResponse(code = 403, message = "Not authorized to view this container."),
            @ApiResponse(code = 404, message = "Container not found."),
            @ApiResponse(code = 424, message = "Container Server value is not set."),
            @ApiResponse(code = 500, message = "Unexpected error")})
    @RequestMapping(value = "/{id}", method = GET, produces = {JSON, PLAIN_TEXT})
    @ResponseBody
    public Container getContainer(@ApiParam(value = "ID of the container to fetch", required = true)
                                      final @PathVariable("id") String id)
            throws NoServerPrefException, NotFoundException, ContainerServerException {
        _log.debug(String.format("%s: %s", "getContainer", id));
        return service.getContainer(id);
    }

    @ApiOperation(value = "Get container status", httpMethod = "GET", produces = PLAIN_TEXT,
            notes = "Gets the status of the container with the given ID.")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Container successfully retrieved."),
            @ApiResponse(code = 401, message = "Must be authenticated to access the XNAT REST API."),
            @ApiResponse(code = 403, message = "Not authorized to view this container."),
            @ApiResponse(code = 404, message = "Container not found."),
            @ApiResponse(code = 424, message = "Container Server value is not set."),
            @ApiResponse(code = 500, message = "Unexpected error")})
    @RequestMapping(value = "/{id}/status", method = GET, produces = PLAIN_TEXT)
    @ResponseBody
    public String getContainerStatus(@ApiParam(value = "Get status of container with this ID", required = true)
                                         final @PathVariable("id") String id)
            throws NotFoundException, NoServerPrefException, ContainerServerException {
        _log.debug(String.format("%s: %s", "getContainerStatus", id));
        return service.getContainerStatus(id);
    }

    @ApiOperation(value = "Set container status", httpMethod = "PUT", produces = PLAIN_TEXT,
            notes = "Sets the status of the container with the given ID to the given value")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Container status successfully changed."),
            @ApiResponse(code = 401, message = "Must be authenticated to access the XNAT REST API."),
            @ApiResponse(code = 403, message = "Not authorized to modify this container's status."),
            @ApiResponse(code = 404, message = "Container not found."),
            @ApiResponse(code = 424, message = "Container Server value is not set."),
            @ApiResponse(code = 500, message = "Unexpected error")})
    @RequestMapping(value = "/{id}/{verb:(" + CONTAINER_VERBS + ")}", method = PUT, produces = PLAIN_TEXT)
    @ResponseBody
    public String verbContainer(@ApiParam(value = "Change status of container with this ID", required = true)
                                         final @PathVariable("id") String id,
                                     @ApiParam(value = "Perform this verb on container", required = true,
                                             allowableValues = CONTAINER_VERBS_CSV)
                                         final @PathVariable("verb") String verb)
            throws NotFoundException, NoServerPrefException, ContainerServerException {
        _log.debug(String.format("%s: %s, %s", "verbContainer", id, verb));
        return service.verbContainer(id, verb);
    }

    @ApiOperation(value = "Get container logs", httpMethod = "GET",
            notes = "Get stdout and stderr logs of the container with the given ID")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Logs successfully retrieved."),
            @ApiResponse(code = 401, message = "Must be authenticated to access the XNAT REST API."),
            @ApiResponse(code = 403, message = "Not authorized to view this container's logs."),
            @ApiResponse(code = 404, message = "Container not found."),
            @ApiResponse(code = 424, message = "Container Server value is not set."),
            @ApiResponse(code = 500, message = "Unexpected error")})
    @RequestMapping(value = "/{id}/logs", method = GET, produces = PLAIN_TEXT)
    @ResponseBody
    public String getContainerLogs(@ApiParam(value = "Get logs of container with this ID", required = true)
                                       final @PathVariable("id") String id)
        throws NotFoundException, NoServerPrefException, ContainerServerException, DockerException, InterruptedException {
        return service.getContainerLogs(id);
    }

    @ApiOperation(value = "Launch container.", httpMethod = "POST",
            notes = "Launches a container from the given image with the posted arguments.")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Container successfully launched."),
            @ApiResponse(code = 401, message = "Must be authenticated to access the XNAT REST API."),
            @ApiResponse(code = 403, message = "Not authorized to launch this container."),
            @ApiResponse(code = 404, message = "Image not found."),
            @ApiResponse(code = 424, message = "Container Server value is not set."),
            @ApiResponse(code = 500, message = "Unexpected error")})
    @RequestMapping(value = {"/launch/{image}", "/launch/{repo}/{image}"},
            method = POST, produces = PLAIN_TEXT, consumes = JSON)
    @ResponseBody
    public String launch(@ApiParam(value = "The image to launch.", required = true)
                             final @PathVariable("image") String image,
                         @ApiParam(value = "The repo of the image.", required = false)
                             final @PathVariable("repo") String repo,
                         final @RequestBody Map<String, String> launchArguments,
                         final @RequestParam(name = "wait", defaultValue = "false") Boolean wait)
            throws NoServerPrefException, NotFoundException, ContainerServerException {
        final String name = repo != null ? repo + "/" + image : image;
        return service.launch(name, launchArguments, wait);
    }

    @ApiOperation(value = "Launch container.", httpMethod = "POST",
            notes = "Launches a container from the given image with the posted arguments.")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Container successfully launched."),
            @ApiResponse(code = 401, message = "Must be authenticated to access the XNAT REST API."),
            @ApiResponse(code = 403, message = "Not authorized to launch this container."),
            @ApiResponse(code = 404, message = "Image not found."),
            @ApiResponse(code = 424, message = "Container Server value is not set."),
            @ApiResponse(code = 500, message = "Unexpected error")})
    @RequestMapping(value = {"/launch/{image}", "/launch/{repo}/{image}"},
            method = POST, produces = PLAIN_TEXT, consumes = FORM)
    @ResponseBody
    public String launch(@ApiParam(value = "The image to launch.", required = true)
                             final @PathVariable("image") String image,
                         @ApiParam(value = "The repo of the image.", required = false)
                             final @PathVariable("repo") String repo,
                         final @RequestBody MultiValueMap<String, String> launchArguments,
                         final @RequestParam(name = "wait", defaultValue = "false") Boolean wait)
            throws NoServerPrefException, NotFoundException, ContainerServerException {
        final String name = repo != null ? repo + "/" + image : image;
        return service.launch(name, launchArguments.toSingleValueMap(), wait);
    }

    @ApiOperation(value = "Launch container on XNAT object.", httpMethod = "POST",
            notes = "Launches a container from the given image on the given XNAT object with the posted arguments.")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Container successfully launched."),
            @ApiResponse(code = 401, message = "Must be authenticated to access the XNAT REST API."),
            @ApiResponse(code = 403, message = "Not authorized to launch this container."),
            @ApiResponse(code = 404, message = "Image not found."),
            @ApiResponse(code = 424, message = "Container Server value is not set."),
            @ApiResponse(code = 500, message = "Unexpected error")})
    @RequestMapping(value = {"/launch/{image}/on/{xnatId}", "/launch/{repo}/{image}/on/{xnatId}"},
            method = POST, produces = PLAIN_TEXT, consumes = JSON)
    @ResponseBody
    public String launchOn(@ApiParam(value = "The image to launch.", required = true)
                               final @PathVariable("image") String image,
                           @ApiParam(value = "The repo of the image.", required = false)
                               final @PathVariable("repo") String repo,
                           @ApiParam(value = "XNAT object on which to launch.", required = true)
                               final @PathVariable("xnatId") String xnatId,
                           final @RequestBody Map<String, String> launchArguments,
                           final @RequestParam(value = "type", required = false) String type,
                           final @RequestParam(name = "wait", defaultValue = "false") Boolean wait)
            throws NoServerPrefException, NotFoundException, ContainerServerException {
        final String name = repo != null ? repo + "/" + image : image;
        return service.launchOn(name, xnatId, type, launchArguments, wait);
    }

    @ApiOperation(value = "Launch container on XNAT object.", httpMethod = "POST",
            notes = "Launches a container from the given image on the given XNAT object with the posted arguments.")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Container successfully launched."),
            @ApiResponse(code = 401, message = "Must be authenticated to access the XNAT REST API."),
            @ApiResponse(code = 403, message = "Not authorized to launch this container."),
            @ApiResponse(code = 404, message = "Image not found."),
            @ApiResponse(code = 424, message = "Container Server value is not set."),
            @ApiResponse(code = 500, message = "Unexpected error")})
    @RequestMapping(value = {"/launch/{image}/on/{xnatId}", "/launch/{repo}/{image}/on/{xnatId}"},
            method = POST, produces = PLAIN_TEXT, consumes = FORM)
    @ResponseBody
    public String launchOn(@ApiParam(value = "The image to launch.", required = true)
                               final @PathVariable("image") String image,
                           @ApiParam(value = "The repo of the image.", required = false)
                               final @PathVariable("repo") String repo,
                           @ApiParam(value = "XNAT object on which to launch.", required = true)
                               final @PathVariable("xnatId") String xnatId,
                           final @RequestBody MultiValueMap<String, String> launchArguments,
                           final @RequestParam(value = "type", required = false) String type,
                           final @RequestParam(name = "wait", defaultValue = "false") Boolean wait)
            throws NoServerPrefException, NotFoundException, ContainerServerException {
        final String name = repo != null ? repo + "/" + image : image;
        return service.launchOn(name, xnatId, type, launchArguments.toSingleValueMap(), wait);
    }

    @ApiOperation(value = "Launch container from an XNAT script.", httpMethod = "POST",
            notes = "Launches a container from an XNAT script.")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Container successfully launched."),
            @ApiResponse(code = 401, message = "Must be authenticated to access the XNAT REST API."),
            @ApiResponse(code = 403, message = "Not authorized to launch this container."),
            @ApiResponse(code = 404, message = "Image not found."),
            @ApiResponse(code = 424, message = "Container Server value is not set."),
            @ApiResponse(code = 500, message = "Unexpected error")})
    @RequestMapping(value = {"/launch/script/{scriptId}"},
            method = POST, produces = PLAIN_TEXT, consumes = JSON)
    @ResponseBody
    public String launchFromScript(@ApiParam(value = "The script to launch.", required = true)
                               final @PathVariable("scriptId") String scriptId,
                           final @RequestBody Map<String, String> launchArguments,
                           final @RequestParam(name = "wait", defaultValue = "false") Boolean wait)
        throws Exception {
        _log.debug(String.format("%s: scriptId %s, args %s", "launchFromScript", scriptId, launchArguments));
        return service.launchFromScript(scriptId, launchArguments, wait);
    }

    @ApiOperation(value = "Launch container from an XNAT script.", httpMethod = "POST",
            notes = "Launches a container from an XNAT script.")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Container successfully launched."),
            @ApiResponse(code = 401, message = "Must be authenticated to access the XNAT REST API."),
            @ApiResponse(code = 403, message = "Not authorized to launch this container."),
            @ApiResponse(code = 404, message = "Image not found."),
            @ApiResponse(code = 424, message = "Container Server value is not set."),
            @ApiResponse(code = 500, message = "Unexpected error")})
    @RequestMapping(value = {"/launch/script/{scriptId}"},
            method = POST, produces = PLAIN_TEXT, consumes = FORM)
    @ResponseBody
    public String launchFromScript(@ApiParam(value = "The script to launch.", required = true)
                               final @PathVariable("scriptId") String scriptId,
                           final @RequestBody MultiValueMap<String, String> launchArguments,
                           final @RequestParam(name = "wait", defaultValue = "false") Boolean wait)
        throws Exception {
        _log.debug(String.format("%s: scriptId %s, args %s", "launchFromScript", scriptId, launchArguments));
        return service.launchFromScript(scriptId, launchArguments.toSingleValueMap(), wait);
    }

    @ApiOperation(value = "Get server", httpMethod = "GET",
            notes = "The container server URI stored in the XNAT database.")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Success."),
            @ApiResponse(code = 401, message = "Must be authenticated to access the XNAT REST API."),
            @ApiResponse(code = 404, message = "No value set."),
            @ApiResponse(code = 500, message = "Unexpected error")})
    @RequestMapping(value = "/server", method = GET, produces = {JSON, PLAIN_TEXT})
    @ResponseBody
    public ContainerServer getServer() throws NotFoundException {
        try {
            return service.getServer();
        } catch (NoServerPrefException e) {
            throw new NotFoundException(e);
        }
    }

    @RequestMapping(value = "/server", method = POST)
    @ResponseBody
    public void setServer(final @RequestBody ContainerServer containerServer) throws InvalidPreferenceName, BadRequestException {
        if (containerServer.host() == null || containerServer.host().equals("")) {
            throw new BadRequestException("Must set the \"host\" property in request body.");
        }
        service.setServer(containerServer);
    }

    @RequestMapping(value = "/hubs", method = GET, produces = {JSON, PLAIN_TEXT})
    @ResponseBody
    public List<ContainerHub> getHubs()
            throws NotFoundException {
        return service.getHubs();
    }

    @RequestMapping(value = "/hubs/{key}", method = GET, produces = {JSON, PLAIN_TEXT})
    @ResponseBody
    public ContainerHub getHub(final @PathVariable("key") String key)
        throws NotFoundException, IOException {
        return service.getHub(key);
    }

    @RequestMapping(value = "/hubs/url/{url}", method = GET, produces = {JSON, PLAIN_TEXT})
    @ResponseBody
    public List<ContainerHub> getHubsByUrl(final @PathVariable("url") String url)
        throws NotFoundException, IOException {
        return service.getHubsByUrl(url);
    }

    @RequestMapping(value = "/hubs", method = POST, consumes = JSON)
    @ResponseBody
    public void setHub(final @RequestBody ContainerHub hub) throws IOException {
        service.setHub(hub);
    }

    @RequestMapping(value = "/search", method = GET)
    @ResponseBody
    public String search(final @RequestParam(name = "term") String term) throws NoHubException {
        return service.search(term);
    }

    @RequestMapping(value = "/pull", method = GET, params = "image")
    @ResponseBody
    public Image pullByName(final @RequestParam("image") String image,
                            final @RequestParam(value = "hub", required = false) String hub,
                            final @RequestParam(name = "name", required = false) String name)
            throws ContainerServerException, NotFoundException, NoHubException {
        return service.pullByName(image, hub, name);
    }

    @RequestMapping(value = "/pull", method = GET, params = "source")
    @ResponseBody
    public Image pullFromSource(final @RequestParam("source") String source,
                        final @RequestParam(name = "name", required = false) String name)
            throws ContainerServerException, NotFoundException, NoHubException {
        return service.pullFromSource(source, name);
    }

    @RequestMapping(value = "/ping", method = GET)
    @ResponseBody
    public String ping()
        throws NoServerPrefException, InterruptedException, DockerException {
        return service.ping();
    }



    @ResponseStatus(value = HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(value = {InvalidPreferenceName.class})
    public void handleInvalidPreferenceNameException() {
        // Do nothing. HTTP 500 will be returned.
    }
}
