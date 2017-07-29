package org.nrg.containers.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.exceptions.BadRequestException;
import org.nrg.containers.exceptions.DockerServerException;
import org.nrg.containers.exceptions.NoServerPrefException;
import org.nrg.containers.exceptions.NotUniqueException;
import org.nrg.containers.exceptions.UnauthorizedException;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.image.docker.DockerImage;
import org.nrg.containers.model.server.docker.DockerServer;
import org.nrg.containers.model.dockerhub.DockerHub;
import org.nrg.containers.model.image.docker.DockerImageAndCommandSummary;
import org.nrg.containers.services.DockerHubService.DockerHubDeleteDefaultException;
import org.nrg.containers.services.DockerService;
import org.nrg.framework.annotations.XapiRestController;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.framework.exceptions.NrgServiceRuntimeException;
import org.nrg.prefs.exceptions.InvalidPreferenceName;
import org.nrg.xapi.rest.XapiRequestMapping;
import org.nrg.xdat.XDAT;
import org.nrg.xapi.rest.AbstractXapiRestController;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xft.security.UserI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.List;

import static org.nrg.containers.services.CommandLabelService.LABEL_KEY;
import static org.nrg.xdat.security.helpers.AccessLevel.Admin;
import static org.springframework.web.bind.annotation.RequestMethod.DELETE;
import static org.springframework.web.bind.annotation.RequestMethod.GET;
import static org.springframework.web.bind.annotation.RequestMethod.POST;

@XapiRestController
@RequestMapping(value = "/docker")
public class DockerRestApi extends AbstractXapiRestController {

    private static final String ID_REGEX = "\\d+";
    private static final String NAME_REGEX = "\\d*[^\\d]+\\d*";

    private static final String JSON = MediaType.APPLICATION_JSON_UTF8_VALUE;
    private static final String TEXT = MediaType.TEXT_PLAIN_VALUE;
    private static final String ALL = MediaType.ALL_VALUE;

    private DockerService dockerService;
    private ObjectMapper mapper;

    @Autowired
    public DockerRestApi(final DockerService dockerService,
                         final ObjectMapper objectMapper,
                         final UserManagementServiceI userManagementService,
                         final RoleHolder roleHolder) {
        super(userManagementService, roleHolder);
        this.dockerService = dockerService;
        this.mapper = objectMapper;
    }

    @ApiOperation(value = "Docker server", notes = "Returns Docker server configuration values",
            response = DockerServer.class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "The Docker server configuration"),
            @ApiResponse(code = 400, message = "The server has not been configured"),
            @ApiResponse(code = 500, message = "Unexpected error")})
    @XapiRequestMapping(value = "/server", method = GET, produces = JSON)
    @ResponseBody
    public DockerServer getServer() throws NotFoundException {
        return dockerService.getServer();
    }

    @ApiOperation(value = "Set Docker server configuration",
            notes = "Save new Docker server configuration values")
    @ApiResponses({
            @ApiResponse(code = 201, message = "The Docker server configuration was saved"),
            @ApiResponse(code = 400, message = "Must set the \"host\" property in request body"),
            @ApiResponse(code = 500, message = "Unexpected error")})
    @XapiRequestMapping(value = "/server", method = POST, restrictTo = Admin)
    public ResponseEntity<String> setServer(final @RequestBody DockerServer dockerServer)
            throws InvalidPreferenceName, JsonProcessingException, UnauthorizedException {
        if (StringUtils.isBlank(dockerServer.host())) {
            return new ResponseEntity<>("Must set the \"host\" property in request body.",
                    HttpStatus.BAD_REQUEST);
        }

        final DockerServer server = dockerService.setServer(dockerServer);
        return new ResponseEntity<>(mapper.writeValueAsString(server), HttpStatus.CREATED);
    }

    @XapiRequestMapping(value = "/server/ping", method = GET)
    @ResponseBody
    public String pingServer()
            throws NoServerPrefException, DockerServerException, UnauthorizedException {
        return dockerService.pingServer();
    }

    @XapiRequestMapping(value = "/hubs", method = GET)
    @ResponseBody
    public List<DockerHub> getHubs() throws UnauthorizedException {
        return dockerService.getHubs();
    }

    @XapiRequestMapping(value = "/hubs/{id:" + ID_REGEX + "}", method = GET)
    @ResponseBody
    public DockerHub getHub(final @PathVariable long id) throws NotFoundException {
        return dockerService.getHub(id);
    }

    @XapiRequestMapping(value = "/hubs/{name:" + NAME_REGEX + "}", method = GET)
    @ResponseBody
    public DockerHub getHub(final @PathVariable String name) throws NotFoundException, NotUniqueException {
        return dockerService.getHub(name);
    }

    @XapiRequestMapping(value = "/hubs", method = POST, restrictTo = Admin)
    @ResponseBody
    public ResponseEntity<DockerHub> createHub(final @RequestBody DockerHub hub,
                                               final @RequestParam(value = "default", defaultValue = "false") boolean setDefault,
                                               final @RequestParam(value = "reason", defaultValue = "User request") String reason)
            throws NrgServiceRuntimeException {
        final UserI userI = XDAT.getUserDetails();
        if (!setDefault) {
            return new ResponseEntity<>(dockerService.createHub(hub), HttpStatus.CREATED);
        } else {
            return new ResponseEntity<>(dockerService.createHubAndSetDefault(hub, userI.getUsername(), reason), HttpStatus.CREATED);
        }
    }

    @XapiRequestMapping(value = "/hubs/{id:" + ID_REGEX + "}", method = POST, restrictTo = Admin)
    @ResponseBody
    public ResponseEntity<Void> updateHub(final @PathVariable long id,
                                          final @RequestBody(required = false) DockerHub hub,
                                          final @RequestParam(value = "default", defaultValue = "false") boolean setDefault,
                                          final @RequestParam(value = "reason", defaultValue = "User request") String reason)
            throws NrgServiceRuntimeException {
        final UserI userI = XDAT.getUserDetails();
        if (hub != null) {
            final DockerHub toUpdate = id == hub.id() ? hub : DockerHub.create(id, hub.name(), hub.url(), setDefault);

            if (!setDefault) {
                dockerService.updateHub(toUpdate);
            } else {
                dockerService.updateHubAndSetDefault(hub, userI.getUsername(), reason);
            }
        } else {
            dockerService.setDefaultHub(id, userI.getUsername(), reason);
        }
        return ResponseEntity.ok().build();
    }

    @XapiRequestMapping(value = "/hubs/{id:" + ID_REGEX + "}", method = DELETE, restrictTo = Admin)
    @ResponseBody
    public ResponseEntity<Void> deleteHub(final @PathVariable long id)
            throws DockerHubDeleteDefaultException {
        dockerService.deleteHub(id);
        return ResponseEntity.noContent().build();
    }

    @XapiRequestMapping(value = "/hubs/{name:" + NAME_REGEX + "}", method = DELETE, restrictTo = Admin)
    @ResponseBody
    public ResponseEntity<Void> deleteHub(final @PathVariable String name)
            throws DockerHubDeleteDefaultException, NotUniqueException {
        dockerService.deleteHub(name);
        return ResponseEntity.noContent().build();
    }

    @XapiRequestMapping(value = "/hubs/{id:" + ID_REGEX + "}/ping", method = GET)
    @ResponseBody
    public String pingHub(final @PathVariable long id,
                          final @RequestParam(value = "username", required = false) String username,
                          final @RequestParam(value = "password", required = false) String password)
            throws NoServerPrefException, DockerServerException, NotFoundException {
        return dockerService.pingHub(id, username, password);
    }

    @XapiRequestMapping(value = "/hubs/{name:" + NAME_REGEX + "}/ping", method = GET)
    @ResponseBody
    public String pingHub(final @PathVariable String name,
                          final @RequestParam(value = "username", required = false) String username,
                          final @RequestParam(value = "password", required = false) String password)
            throws NoServerPrefException, DockerServerException, NotFoundException, NotUniqueException {
        return dockerService.pingHub(name, username, password);
    }

    @XapiRequestMapping(value = "/hubs/{id:" + ID_REGEX + "}/pull", params = {"image"}, method = POST, restrictTo = Admin)
    public void pullImageFromHub(final @PathVariable long id,
                                 final @RequestParam(value = "image") String image,
                                 final @RequestParam(value = "save-commands", defaultValue = "true") Boolean saveCommands,
                                 final @RequestParam(value = "username", required = false) String username,
                                 final @RequestParam(value = "password", required = false) String password)
            throws DockerServerException, NotFoundException, NoServerPrefException, BadRequestException {
        checkImageOrThrow(image);
        dockerService.pullFromHub(id, image, saveCommands, username, password);
    }

    @XapiRequestMapping(value = "/hubs/{name:" + NAME_REGEX + "}/pull", params = {"image"}, method = POST, restrictTo = Admin)
    public void pullImageFromHub(final @PathVariable String name,
                                 final @RequestParam(value = "image") String image,
                                 final @RequestParam(value = "save-commands", defaultValue = "true") Boolean saveCommands,
                                 final @RequestParam(value = "username", required = false) String username,
                                 final @RequestParam(value = "password", required = false) String password)
            throws DockerServerException, NotFoundException, NoServerPrefException, NotUniqueException, BadRequestException {
        checkImageOrThrow(image);
        dockerService.pullFromHub(name, image, saveCommands, username, password);
    }

    @XapiRequestMapping(value = "/pull", params = {"image"}, method = POST, restrictTo = Admin)
    public void pullImageFromDefaultHub(final @RequestParam(value = "image") String image,
                                        final @RequestParam(value = "save-commands", defaultValue = "true")
                                                Boolean saveCommands)
            throws DockerServerException, NotFoundException, NoServerPrefException, BadRequestException {
        checkImageOrThrow(image);
        dockerService.pullFromHub(image, saveCommands);
    }

    @ApiOperation(value = "Get list of images.", notes = "Returns a list of all Docker images.")
    @ApiResponses({
            @ApiResponse(code = 200, message = "A list of images on the server"),
            @ApiResponse(code = 424, message = "Admin must set up Docker server."),
            @ApiResponse(code = 500, message = "Unexpected error")})
    @XapiRequestMapping(value = "/images", method = GET, produces = JSON)
    @ResponseBody
    public List<DockerImage> getImages() throws NoServerPrefException, DockerServerException {
        return dockerService.getImages();
    }

    @ApiOperation(value = "Get summary list of images and commands.")
    @XapiRequestMapping(value = "/image-summaries", method = GET, produces = JSON)
    @ResponseBody
    public List<DockerImageAndCommandSummary> getImageSummaries()
            throws NoServerPrefException, DockerServerException {
        return dockerService.getImageSummaries();
    }

    @ApiOperation(value = "Get Docker image",
            notes = "Retrieve information about a Docker image from the docker server")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Found the image"),
            @ApiResponse(code = 404, message = "No docker image with given id on the server"),
            @ApiResponse(code = 424, message = "Admin must set up Docker server."),
            @ApiResponse(code = 500, message = "Unexpected error")})
    @XapiRequestMapping(value = "/images/{id}", method = GET, produces = JSON)
    @ResponseBody
    public DockerImage getImage(final @PathVariable("id") String id)
            throws NoServerPrefException, NotFoundException {
        return dockerService.getImage(id);
    }

    @ApiOperation(value = "Delete Docker image",
            notes = "Remove information about a Docker image")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Image was removed"),
            @ApiResponse(code = 404, message = "No docker image with given id on docker server"),
            @ApiResponse(code = 424, message = "Admin must set up Docker server."),
            @ApiResponse(code = 500, message = "Unexpected error")})
    @XapiRequestMapping(value = "/images/{id}", method = DELETE, restrictTo = Admin)
    @ResponseBody
    public ResponseEntity<Void> deleteImage(final @PathVariable("id") String id,
                                            final @RequestParam(value = "force", defaultValue = "false") Boolean force)
            throws NotFoundException, NoServerPrefException, DockerServerException {
        dockerService.removeImage(id, force);
        return ResponseEntity.noContent().build();
    }

    @ApiOperation(value = "Save Commands from labels",
            notes = "Read labels from Docker image. If any labels contain key " +
                    LABEL_KEY + ", parse value as list of Commands.")
//    @ApiResponses({
//            @ApiResponse(code = 200, message = "Image was removed"),
//            @ApiResponse(code = 404, message = "No docker image with given id on docker server"),
//            @ApiResponse(code = 424, message = "Admin must set up Docker server."),
//            @ApiResponse(code = 500, message = "Unexpected error")})
    @XapiRequestMapping(value = "/images/save", params = "image", method = POST, restrictTo = Admin)
    @ResponseBody
    public List<Command> saveFromLabels(final @RequestParam("image") String imageId)
            throws NotFoundException, NoServerPrefException, DockerServerException {
        return dockerService.saveFromImageLabels(imageId);
    }

    private void checkImageOrThrow(final String image) throws BadRequestException {
        if (!image.contains("/")) {
            throw new BadRequestException(String.format("Cannot pull an image by ID."));
        }
    }

    @ResponseStatus(value = HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(value = {InvalidPreferenceName.class})
    public String handleInvalidPreferenceName(final Exception e) {
        return e.getMessage();
    }

    @ResponseStatus(value = HttpStatus.NOT_FOUND)
    @ExceptionHandler(value = {NotFoundException.class})
    public String handleNotFound(final Exception e) {
        return e.getMessage();
    }

    @ResponseStatus(value = HttpStatus.BAD_REQUEST)
    @ExceptionHandler(value = {NotUniqueException.class})
    public String handleNotUnique(final Exception e) {
        return e.getMessage();
    }

    @ResponseStatus(value = HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(value = {DockerServerException.class})
    public String handleDockerServerError(final Exception e) {
        return "The Docker server returned an error:\n" + e.getMessage();
    }

    @ResponseStatus(value = HttpStatus.BAD_REQUEST)
    @ExceptionHandler(value = {NrgServiceRuntimeException.class})
    public String handleBadRequest() {
        return "Body was not a valid Docker Hub.";
    }

    @ResponseStatus(value = HttpStatus.FAILED_DEPENDENCY)
    @ExceptionHandler(value = {NoServerPrefException.class})
    public String handleFailedDependency() {
        return "Set up Docker server before using this REST endpoint.";
    }

    @ResponseStatus(value = HttpStatus.CONFLICT)
    @ExceptionHandler(value = {DockerHubDeleteDefaultException.class})
    public String handleHubDelete(final DockerHubDeleteDefaultException e) {
        return e.getMessage();
    }


}

