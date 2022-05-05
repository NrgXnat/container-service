package org.nrg.containers.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.exceptions.BadRequestException;
import org.nrg.containers.exceptions.DockerServerException;
import org.nrg.containers.exceptions.NoDockerServerException;
import org.nrg.containers.exceptions.NotUniqueException;
import org.nrg.containers.exceptions.UnauthorizedException;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.dockerhub.DockerHubBase.DockerHub;
import org.nrg.containers.model.dockerhub.DockerHubBase.DockerHubWithPing;
import org.nrg.containers.model.image.docker.DockerImage;
import org.nrg.containers.model.image.docker.DockerImageAndCommandSummary;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServerWithPing;
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
import org.springframework.beans.factory.annotation.Autowired;
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

import java.util.List;

import static org.nrg.containers.services.CommandLabelService.LABEL_KEY;
import static org.nrg.xdat.security.helpers.AccessLevel.Admin;
import static org.springframework.web.bind.annotation.RequestMethod.DELETE;
import static org.springframework.web.bind.annotation.RequestMethod.GET;
import static org.springframework.web.bind.annotation.RequestMethod.POST;

@XapiRestController
@RequestMapping(value = "/docker")
@Api("Docker API for XNAT Container Service")
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
    public DockerServerWithPing getServer() throws NotFoundException {
        return dockerService.getServer();
    }

    @ApiOperation(value = "Set Docker server configuration",
            notes = "Save new Docker server configuration values")
    @ApiResponses({
            @ApiResponse(code = 201, message = "The Docker server configuration was saved"),
            @ApiResponse(code = 400, message = "Must set the \"host\" property in request body"),
            @ApiResponse(code = 500, message = "Unexpected error")})
    @XapiRequestMapping(value = "/server", method = POST, restrictTo = Admin)
    public ResponseEntity<DockerServerWithPing> setServer(final @RequestBody DockerServer dockerServer)
            throws JsonProcessingException, UnauthorizedException, BadRequestException {
        if (StringUtils.isBlank(dockerServer.host())) {
            throw new BadRequestException("Must set the \"host\" property in request body.");
        }

        final DockerServerWithPing server = dockerService.setServer(dockerServer);
        return new ResponseEntity<>(server, HttpStatus.CREATED);
    }

    @XapiRequestMapping(value = "/server/ping", method = GET)
    @ApiOperation(value = "Ping docker server.", notes = "Returns \"OK\" on success.")
    @ResponseBody
    public String pingServer()
            throws NoDockerServerException, DockerServerException, UnauthorizedException {
        return dockerService.ping();
    }

    @XapiRequestMapping(value = "/hubs", method = GET)
    @ApiOperation(value = "Get Docker Hubs")
    @ResponseBody
    public List<DockerHubWithPing> getHubs() throws UnauthorizedException {
        return dockerService.getHubs();
    }

    @XapiRequestMapping(value = "/hubs/{id:" + ID_REGEX + "}", method = GET)
    @ApiOperation(value = "Get Docker Hub by ID")
    @ResponseBody
    public DockerHubWithPing getHub(final @PathVariable long id) throws NotFoundException {
        return dockerService.getHub(id);
    }

    @XapiRequestMapping(value = "/hubs/{name:" + NAME_REGEX + "}", method = GET)
    @ApiOperation(value = "Get Docker Hub by Name")
    @ResponseBody
    public DockerHubWithPing getHub(final @PathVariable String name) throws NotFoundException, NotUniqueException {
        return dockerService.getHub(name);
    }

    @XapiRequestMapping(value = "/hubs", method = POST, restrictTo = Admin)
    @ApiOperation(value = "Create new Docker Hub", code = 201)
    @ResponseBody
    public ResponseEntity<DockerHubWithPing> createHub(final @RequestBody DockerHub hub,
                                                       final @RequestParam(value = "default", defaultValue = "false") boolean setDefault,
                                                       final @RequestParam(value = "reason", defaultValue = "User request") String reason)
            throws NrgServiceRuntimeException {
        final UserI userI = XDAT.getUserDetails();
        final DockerHubWithPing created = setDefault ?
                dockerService.createHubAndSetDefault(hub, userI.getUsername(), reason) :
                dockerService.createHub(hub);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @XapiRequestMapping(value = "/hubs/{id:" + ID_REGEX + "}", method = POST, restrictTo = Admin)
    @ApiOperation(value = "Update Docker Hub by ID")
    @ResponseBody
    public ResponseEntity<Void> updateHub(final @PathVariable long id,
                                          final @RequestBody(required = false) DockerHub hub,
                                          final @RequestParam(value = "default", defaultValue = "false") boolean setDefault,
                                          final @RequestParam(value = "reason", defaultValue = "User request") String reason)
            throws NrgServiceRuntimeException {
        final UserI userI = XDAT.getUserDetails();
        if (hub != null) {
            final DockerHub toUpdate = id == hub.id() ? hub : DockerHub.create(id, hub.name(), hub.url(), setDefault,
                    hub.username(), hub.password(), hub.email(), hub.token());

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
    @ApiOperation(value = "Delete Docker Hub by ID")
    @ResponseBody
    public ResponseEntity<Void> deleteHub(final @PathVariable long id)
            throws DockerHubDeleteDefaultException {
        dockerService.deleteHub(id);
        return ResponseEntity.noContent().build();
    }

    @XapiRequestMapping(value = "/hubs/{name:" + NAME_REGEX + "}", method = DELETE, restrictTo = Admin)
    @ApiOperation(value = "Delete Docker Hub by Name", code = 204)
    @ResponseBody
    public ResponseEntity<Void> deleteHub(final @PathVariable String name)
            throws DockerHubDeleteDefaultException, NotUniqueException {
        dockerService.deleteHub(name);
        return ResponseEntity.noContent().build();
    }

    @XapiRequestMapping(value = "/hubs/{id:" + ID_REGEX + "}/ping", method = GET)
    @ApiOperation(value = "Ping Docker Hub by ID", notes = "Returns \"OK\" on success.")
    @ResponseBody
    public String pingHub(final @PathVariable long id,
                          final @RequestParam(value = "username", required = false) String username,
                          final @RequestParam(value = "password", required = false) String password)
            throws NoDockerServerException, DockerServerException, NotFoundException {
        return dockerService.pingHub(id, username, password, null, null);
    }

    @XapiRequestMapping(value = "/hubs/{name:" + NAME_REGEX + "}/ping", method = GET)
    @ApiOperation(value = "Ping Docker Hub by Name", notes = "Returns \"OK\" on success.")
    @ResponseBody
    public String pingHub(final @PathVariable String name,
                          final @RequestParam(value = "username", required = false) String username,
                          final @RequestParam(value = "password", required = false) String password)
            throws NoDockerServerException, DockerServerException, NotFoundException, NotUniqueException {
        return dockerService.pingHub(name, username, password, null, null);
    }

    @XapiRequestMapping(value = "/hubs/{id:" + ID_REGEX + "}/pull", params = {"image"}, method = POST, restrictTo = Admin)
    @ApiOperation(value = "Pull image from Docker Hub by ID")
    public void pullImageFromHub(final @PathVariable long id,
                                 final @RequestParam(value = "image") String image,
                                 final @RequestParam(value = "save-commands", defaultValue = "true") Boolean saveCommands,
                                 final @RequestParam(value = "username", required = true) String username,
                                 final @RequestParam(value = "password", required = true) String password)
            throws DockerServerException, NotFoundException, NoDockerServerException, BadRequestException {
        checkImageOrThrow(image);
        dockerService.pullFromHub(id, image, saveCommands, username, password, null, null);
    }

    @XapiRequestMapping(value = "/hubs/{name:" + NAME_REGEX + "}/pull", params = {"image"}, method = POST, restrictTo = Admin)
    @ApiOperation(value = "Pull image from Docker Hub by Name")
    public void pullImageFromHub(final @PathVariable String name,
                                 final @RequestParam(value = "image") String image,
                                 final @RequestParam(value = "save-commands", defaultValue = "true") Boolean saveCommands,
                                 final @RequestParam(value = "username", required = true) String username,
                                 final @RequestParam(value = "password", required = true) String password)
            throws DockerServerException, NotFoundException, NoDockerServerException, NotUniqueException, BadRequestException {
        checkImageOrThrow(image);
        dockerService.pullFromHub(name, image, saveCommands, username, password, null, null);
    }

    @XapiRequestMapping(value = "/hubs/{id:" + ID_REGEX + "}/pull", params = {"image", "!username", "!password"}, method = POST, restrictTo = Admin)
    @ApiOperation(value = "Pull image from Docker Hub by ID")
    public void pullImageFromHub(final @PathVariable long id,
                                 final @RequestParam(value = "image") String image,
                                 final @RequestParam(value = "save-commands", defaultValue = "true") Boolean saveCommands)
            throws DockerServerException, NotFoundException, NoDockerServerException, BadRequestException {
        checkImageOrThrow(image);
        dockerService.pullFromHub(id, image, saveCommands);
    }

    @XapiRequestMapping(value = "/hubs/{name:" + NAME_REGEX + "}/pull", params = {"image", "!username", "!password"}, method = POST, restrictTo = Admin)
    @ApiOperation(value = "Pull image from Docker Hub by Name")
    public void pullImageFromHub(final @PathVariable String name,
                                 final @RequestParam(value = "image") String image,
                                 final @RequestParam(value = "save-commands", defaultValue = "true") Boolean saveCommands)
            throws DockerServerException, NotFoundException, NoDockerServerException, NotUniqueException, BadRequestException {
        checkImageOrThrow(image);
        dockerService.pullFromHub(name, image, saveCommands);
    }

    @XapiRequestMapping(value = "/pull", params = {"image"}, method = POST, restrictTo = Admin)
    @ApiOperation(value = "Pull image from default Docker Hub")
    public void pullImageFromDefaultHub(final @RequestParam(value = "image") String image,
                                        final @RequestParam(value = "save-commands", defaultValue = "true")
                                                Boolean saveCommands)
            throws DockerServerException, NotFoundException, NoDockerServerException, BadRequestException {
        checkImageOrThrow(image);
        dockerService.pullFromHub(image, saveCommands);
    }

    @ApiOperation(value = "Get list of images.", notes = "Returns a list of all Docker images on the Docker server.")
    @ApiResponses({
            @ApiResponse(code = 200, message = "A list of images on the server"),
            @ApiResponse(code = 424, message = "Admin must set up Docker server."),
            @ApiResponse(code = 500, message = "Unexpected error")})
    @XapiRequestMapping(value = "/images", method = GET, produces = JSON)
    @ResponseBody
    public List<DockerImage> getImages(final @RequestParam(value = "installed", defaultValue = "false") Boolean installed)
            throws NoDockerServerException, DockerServerException {
        return installed == true ? dockerService.getInstalledImages() : dockerService.getAllImages();
    }

    @ApiOperation(value = "Get summary list of images and commands.")
    @XapiRequestMapping(value = "/image-summaries", method = GET, produces = JSON)
    @ResponseBody
    public List<DockerImageAndCommandSummary> getImageSummaries()
            throws NoDockerServerException, DockerServerException {
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
            throws NoDockerServerException, NotFoundException, BadRequestException {
        try {
            return dockerService.getImage(id);
        } catch (NotFoundException e) {
            // CS-62 We will catch the case where the image id is "save"
            // This is likely because they meant to POST to /images/save.
            if ("save".equals(id)) {
                throw new BadRequestException("To save commands from image labels POST to /images/save.");
            } else {
                throw e;
            }
        }
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
            throws NotFoundException, NoDockerServerException, DockerServerException {
        dockerService.removeImageById(id, force);
        return ResponseEntity.noContent().build();
    }

    @ApiOperation(value = "Save Commands from labels",
            notes = "Read labels from Docker image. If any labels contain key " +
                    LABEL_KEY + ", parse value as list of Commands.")
    @XapiRequestMapping(value = "/images/save", params = "image", method = POST, restrictTo = Admin)
    @ResponseBody
    public List<Command> saveFromLabels(final @RequestParam("image") String imageId)
            throws NotFoundException, NoDockerServerException, DockerServerException {
        return dockerService.saveFromImageLabels(imageId);
    }

    private void checkImageOrThrow(final String image) throws BadRequestException {
        if (!image.contains("/") && image.contains("sha256:")) {
            throw new BadRequestException(String.format("Cannot pull an image by sha256 ID. Use image label instead."));
        }
    }

    @ResponseStatus(value = HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(value = {InvalidPreferenceName.class})
    public String handleInvalidPreferenceName(final Exception e) {
        return e.getMessage();
    }

    @ResponseStatus(value = HttpStatus.BAD_REQUEST)
    @ExceptionHandler(value = {BadRequestException.class})
    public String handleBadRequest(final Exception e) {
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
    public String handleInvalidDockerHub() {
        return "Body was not a valid Docker Hub.";
    }

    @ResponseStatus(value = HttpStatus.FAILED_DEPENDENCY)
    @ExceptionHandler(value = {NoDockerServerException.class})
    public String handleFailedDependency() {
        return "Set up Docker server before using this REST endpoint.";
    }

    @ResponseStatus(value = HttpStatus.CONFLICT)
    @ExceptionHandler(value = {DockerHubDeleteDefaultException.class})
    public String handleHubDelete(final DockerHubDeleteDefaultException e) {
        return e.getMessage();
    }


}

