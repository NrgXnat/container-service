package org.nrg.containers.rest;

import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.nrg.containers.exceptions.ContainerServerException;
import org.nrg.containers.exceptions.NoServerPrefException;
import org.nrg.containers.exceptions.NotFoundException;
import org.nrg.containers.metadata.ImageMetadata;
import org.nrg.containers.model.Image;
import org.nrg.containers.services.ContainerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.springframework.web.bind.annotation.RequestMethod.DELETE;
import static org.springframework.web.bind.annotation.RequestMethod.GET;
import static org.springframework.web.bind.annotation.RequestMethod.POST;

//@Api(description = "XNAT Container Services REST API")
@RestController
@RequestMapping(value = "/containers")
public class ImagesApi {
    private static final Logger _log = LoggerFactory.getLogger(ImagesApi.class);

    @Autowired
    @SuppressWarnings("SpringJavaAutowiringInspection") // IntelliJ does not process the excludeFilter in ContainersSpringConfig @ComponentScan, erroneously marks this red
    private ContainerService service;

    public static final String ID_REGEX = "(?:[a-zA-Z0-9-_+.]+:)?[a-fA-F0-9]{6,}";

    public static final String JSON = MediaType.APPLICATION_JSON_UTF8_VALUE;
    public static final String PLAIN_TEXT = MediaType.TEXT_PLAIN_VALUE;

    @ApiOperation(value = "Get list of images.", notes = "Returns a list of all images on the container server.", response = Image.class, responseContainer = "List")
    @ApiResponses({
            @ApiResponse(code = 200, message = "A list of images on the server"),
            @ApiResponse(code = 500, message = "Unexpected error")})
    @RequestMapping(value = "/images", method = GET, produces = {JSON, PLAIN_TEXT})
    public List<Image> getAllImages(final @RequestParam(value = "project", required = false) String projectParam,
                                    final @RequestParam(value = "metadata", required = false) Boolean metadata)
        throws NoServerPrefException, ContainerServerException {
        return service.getAllImages();
    }

    @RequestMapping(value = "/images/{id:"+ID_REGEX+"}", method = GET, produces = {JSON, PLAIN_TEXT})
    @ResponseBody
    public Image getImageByIdWithNameFallback(final @PathVariable("id") String id,
                              final @RequestParam(name = "project", required = false) String projectParam,
                              final @RequestParam(name = "metadata", required = false) Boolean metadata)
            throws NotFoundException, NoServerPrefException, ContainerServerException {
        try {
            return service.getImageById(id);
        } catch (NotFoundException e) {
            _log.warn("Could not find image with id " + id + ". Trying as name.");
            return service.getImageByName(id);
        }
    }

    @RequestMapping(value = "/images/id/{id}", method = GET, produces = {JSON, PLAIN_TEXT})
    @ResponseBody
    public Image getImageById(final @PathVariable("id") String id,
                              final @RequestParam(name = "project", required = false) String projectParam,
                              final @RequestParam(name = "metadata", required = false) Boolean metadata)
            throws NotFoundException, NoServerPrefException, ContainerServerException {
        return service.getImageById(id);
    }

    @RequestMapping(value = {"/images/{name:.*}", "/images/name/{name}"}, method = GET, produces = {JSON, PLAIN_TEXT})
    @ResponseBody
    public Image getImageByName(final @PathVariable("name") String name,
                                final @RequestParam(name = "project", required = false) String projectParam,
                                final @RequestParam(name = "metadata", required = false) Boolean metadata)
            throws NotFoundException, ContainerServerException, NoServerPrefException {
        return service.getImageByName(name);
    }

    @RequestMapping(value = {"/images/{repo}/{image}", "/images/name/{repo}/{image}"}, method = GET, produces = {JSON, PLAIN_TEXT})
    @ResponseBody
    public Image getImageByNameWithRepo(final @PathVariable("image") String image,
                                        final @PathVariable("repo") String repo,
                                        final @RequestParam(name = "project", required = false) String projectParam,
                                        final @RequestParam(name = "metadata", required = false) Boolean metadata)
            throws NotFoundException, ContainerServerException, NoServerPrefException {
        final String name = repo + "/" + image;
        return service.getImageByName(name);
    }

    @RequestMapping(value = {"/images/name/{name}"},
            method = DELETE, produces = PLAIN_TEXT)
    @ResponseBody
    public String deleteImageByName(final @PathVariable("name") String name,
                                    final @RequestParam(name = "project", required = false) String projectParam,
                                    final @RequestParam(name = "server", defaultValue = "false") Boolean deleteOnServer)
            throws NotFoundException, NoServerPrefException, ContainerServerException {
        return service.deleteImageByName(name, deleteOnServer);
    }

    @RequestMapping(value = {"/images/name/{repo}/{image}"},
            method = DELETE, produces = PLAIN_TEXT)
    @ResponseBody
    public String deleteImageByNameWithRepo(final @PathVariable("image") String image,
                                    final @PathVariable("repo") String repo,
                                    final @RequestParam(name = "project", required = false) String projectParam,
                                    final @RequestParam(name = "server", defaultValue = "false") Boolean deleteOnServer)
            throws NotFoundException, NoServerPrefException, ContainerServerException {
        final String name = repo != null ? repo + "/" + image : image;
        return service.deleteImageByName(name, deleteOnServer);
    }

    @RequestMapping(value = "/images/id/{id}", method = DELETE, produces = PLAIN_TEXT)
    @ResponseBody
    public String deleteImageById(final @PathVariable("id") String id,
                                  final @RequestParam(name = "project", required = false) String projectParam,
                                  final @RequestParam(name = "server", defaultValue = "false") Boolean deleteOnServer)
            throws NotFoundException, NoServerPrefException, ContainerServerException {
        return service.deleteImageById(id, deleteOnServer);
    }

    @RequestMapping(value = {"/images/{name:.*}", "/images/name/{name}"}, method = POST, consumes = JSON)
    @ResponseStatus(value = HttpStatus.CREATED)
    public void setMetadataByName(final @PathVariable("name") String name,
                                    final @RequestBody ImageMetadata metadata,
                                    final @RequestParam(value = "project", required = false) String project,
                                    final @RequestParam(value = "overwrite", defaultValue = "false") Boolean overwrite,
                                    final @RequestParam(value = "ignoreBlank", defaultValue = "false") Boolean ignoreBlank)
            throws NoServerPrefException, NotFoundException, ContainerServerException {
        service.setMetadataByName(name, metadata, project, overwrite, ignoreBlank);
    }

    @RequestMapping(value = {"/images/{repo}/{image}", "/images/name/{repo}/{image}"}, method = POST, consumes = JSON)
    @ResponseStatus(value = HttpStatus.CREATED)
    public void setMetadataByNameWithRepo(final @PathVariable("image") String image,
                                            final @PathVariable("repo") String repo,
                                            final @RequestBody ImageMetadata metadata,
                                            final @RequestParam(value = "project", required = false) String project,
                                            final @RequestParam(value = "overwrite", defaultValue = "false") Boolean overwrite,
                                            final @RequestParam(value = "ignoreBlank", defaultValue = "false") Boolean ignoreBlank)
            throws NoServerPrefException, NotFoundException, ContainerServerException {
        final String name = repo != null ? repo + "/" + image : image;
        service.setMetadataByName(name, metadata, project, overwrite, ignoreBlank);
    }

    @RequestMapping(value = "/images/{id:"+ID_REGEX+"}", method = POST, consumes = JSON)
    @ResponseStatus(value = HttpStatus.CREATED)
    public void setMetadataByIdWithNameFallback(final @PathVariable("id") String imageId,
                                    final @RequestBody ImageMetadata metadata,
                                    final @RequestParam(value = "project", required = false) String project,
                                    final @RequestParam(value = "overwrite", defaultValue = "false") Boolean overwrite,
                                    final @RequestParam(value = "ignoreBlank", defaultValue = "false") Boolean ignoreBlank)
            throws NoServerPrefException, NotFoundException, ContainerServerException {
        try {
            service.setMetadataById(imageId, metadata, project, overwrite, ignoreBlank);
        } catch (NotFoundException e) {
            _log.warn("Could not find image with id " + imageId + ". Trying as name.");
            service.setMetadataByName(imageId, metadata, project, overwrite, ignoreBlank);
        }
    }
    @RequestMapping(value = "/images/id/{id}", method = POST, consumes = JSON)
    @ResponseStatus(value = HttpStatus.CREATED)
    public void setMetadataById(final @PathVariable("id") String imageId,
                                    final @RequestBody ImageMetadata metadata,
                                    final @RequestParam(value = "project", required = false) String project,
                                    final @RequestParam(value = "overwrite", defaultValue = "false") Boolean overwrite,
                                    final @RequestParam(value = "ignoreBlank", defaultValue = "false") Boolean ignoreBlank)
            throws NoServerPrefException, NotFoundException, ContainerServerException {
        service.setMetadataById(imageId, metadata, project, overwrite, ignoreBlank);
    }

}
