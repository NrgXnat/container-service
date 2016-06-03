package org.nrg.containers.api.impl;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.spotify.docker.client.DefaultDockerClient;
import com.spotify.docker.client.exceptions.DockerCertificateException;
import com.spotify.docker.client.exceptions.DockerException;
import com.spotify.docker.client.exceptions.ImageNotFoundException;
import com.spotify.docker.client.DockerCertificates;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.DockerClient.LogsParam;
import com.spotify.docker.client.LogStream;
import com.spotify.docker.client.messages.*;
import org.apache.commons.lang.StringUtils;
import org.nrg.containers.api.ContainerControlApi;
import org.nrg.containers.exceptions.ContainerServerException;
import org.nrg.containers.exceptions.NoServerPrefException;
import org.nrg.containers.exceptions.NotFoundException;
import org.nrg.containers.model.Container;
import org.nrg.containers.model.ContainerHub;
import org.nrg.containers.model.ContainerServer;
import org.nrg.containers.model.ContainerServerPrefsBean;
import org.nrg.containers.image.Image;
import org.nrg.prefs.exceptions.InvalidPreferenceName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

@Service
public class DockerControlApi implements ContainerControlApi {
    private static final Logger _log = LoggerFactory.getLogger(DockerControlApi.class);

    @Autowired
    @SuppressWarnings("SpringJavaAutowiringInspection") // IntelliJ does not process the excludeFilter in ContainerServiceConfig @ComponentScan, erroneously marks this red
    private ContainerServerPrefsBean containerServerPref;

    /**
     * Query Docker server for all images
     *
     * @return Image objects stored on docker server
     **/
    @Override
    public List<org.nrg.containers.image.Image> getAllImages() throws NoServerPrefException, ContainerServerException {
        return getImages(null);
    }

    /**
     * Query Docker server for images with parameters
     *
     * @param params Map of query parameters (name = value)
     * @return Image objects stored on docker server meeting the query parameters
     **/
    public List<Image> getImages(final Map<String, String> params)
        throws NoServerPrefException, ContainerServerException {
        return DockerImageToNrgImage(_getImages(params));
    }

    private List<com.spotify.docker.client.messages.Image> _getImages(final Map<String, String> params)
        throws NoServerPrefException, ContainerServerException {
        // Transform param map to ListImagesParam array
        DockerClient.ListImagesParam[] dockerParams;
        if (params != null && params.size() > 0) {
            List<DockerClient.ListImagesParam> dockerParamsList =
                    Lists.transform(Lists.newArrayList(params.entrySet()), imageParamTransformer);
            dockerParams = dockerParamsList.toArray(new DockerClient.ListImagesParam[dockerParamsList.size()]);
        } else {
            dockerParams = new DockerClient.ListImagesParam[] {};
        }

        try (final DockerClient dockerClient = getClient()) {
            return dockerClient.listImages(dockerParams);
        } catch (DockerException | InterruptedException e) {
            _log.error("Failed to list images. " + e.getMessage());
            throw new ContainerServerException(e);
        } catch (Error e) {
            _log.error("Failed to list images. " + e.getMessage());
            throw e;
        }
    }

    public ContainerServer getServer() throws NoServerPrefException {
        if (containerServerPref == null || containerServerPref.getHost() == null) {
            throw new NoServerPrefException("No container server URI defined in preferences.");
        }
        return containerServerPref.toBean();
    }

    public void setServer(final String host) throws InvalidPreferenceName {
        setServer(host, null);
    }

    public void setServer(final String host, final String certPath) throws InvalidPreferenceName {
        containerServerPref.setHost(host);
        containerServerPref.setCertPath(certPath);
    }

    public void setServer(final ContainerServer serverBean) throws InvalidPreferenceName {
        containerServerPref.setFromBean(serverBean);
    }

    @Override
    public String pingServer() throws NoServerPrefException, ContainerServerException {
        try (final DockerClient client = getClient()) {
            return client.ping();
        } catch (DockerException | InterruptedException e) {
            _log.error(e.getMessage());
            throw new ContainerServerException(e);
        }
    }

    @Override
    public String pingHub(ContainerHub hub) throws ContainerServerException, NoServerPrefException {
        final DockerClient client = getClient();
        AuthConfig authConfig = AuthConfig.builder().email(hub.email()).username(hub.username())
                .password(hub.password()).serverAddress(hub.url()).build();
        try {
            client.pull("connectioncheckonly", authConfig);
        }
        catch (ImageNotFoundException imageNotFoundException){
            // Expected result: Hub found, bogus image not found
            return "OK";
        }
        catch (Exception e) {
            _log.error(e.getMessage());
            throw new ContainerServerException(e);
        }
        return null;
    }

    /**
     * Query Docker server for image by name
     *
     * @param imageName Name of image
     * @return Image stored on docker server with the given name
     **/
    @Override
    public Image getImageByName(final String imageName)
        throws ContainerServerException, NotFoundException, NoServerPrefException {
        final Image image = DockerImageToNrgImage(_getImageByName(imageName));
        if (image != null) {
            return image;
        }
        throw new NotFoundException(String.format("Could not find image %s", imageName));
    }

    private com.spotify.docker.client.messages.Image _getImageByName(final String imageName)
        throws ContainerServerException, NoServerPrefException {
        List<com.spotify.docker.client.messages.Image> images;
        try (final DockerClient client = getClient()) {
            images = client.listImages(DockerClient.ListImagesParam.byName(imageName));
        } catch (DockerException | InterruptedException e) {
            throw new ContainerServerException(e);
        }

        return oneImage(imageName, images);
    }

    /**
     * Query Docker server for image by name
     *
     * @param imageId ID of image
     * @return Image stored on docker server with the given name
     **/
    @Override
    public Image getImageById(final String imageId)
        throws NotFoundException, ContainerServerException, NoServerPrefException {
        final Image image = DockerImageToNrgImage(_getImageById(imageId));
        if (image != null) {
            return image;
        }
        throw new NotFoundException(String.format("Could not find image %s", imageId));
    }

    private com.spotify.docker.client.messages.Image _getImageById(final String imageId)
        throws ContainerServerException, NoServerPrefException {
//        TODO: Make this work

        List<com.spotify.docker.client.messages.Image> images;
        try (final DockerClient client = getClient()) {
            images = client.listImages(DockerClient.ListImagesParam.byName(imageId));
        } catch (DockerException | InterruptedException e) {
            throw new ContainerServerException(e);
        }

        return oneImage(imageId, images);
    }

    private com.spotify.docker.client.messages.Image
            oneImage(final String nameOrId,
                     final List<com.spotify.docker.client.messages.Image> images) {

        if (images != null && !images.isEmpty()) {
            if (images.size() > 1) {
                String warn = "Found multiple images matching "+ nameOrId + ": ";
                for (final com.spotify.docker.client.messages.Image image : images) {
                    warn += image.id() + " ";
                }
                warn += ". Returning "+images.get(0).id()+".";

                _log.warn(warn);
            }
            return images.get(0);
        }
        return null;
    }


    /**
     * Query Docker server for all containers
     *
     * @return Container objects stored on docker server
     **/
    @Override
    public List<Container> getAllContainers() throws NoServerPrefException, ContainerServerException {
        return getContainers(null);
    }

    /**
     * Query Docker server for containers with parameters
     *
     * @param params Map of query parameters (name = value)
     * @return Container objects stored on docker server meeting the query parameters
     **/
    @Override
    public List<Container> getContainers(final Map<String, String> params)
        throws NoServerPrefException, ContainerServerException {
        List<com.spotify.docker.client.messages.Container> containerList;

        // Transform param map to ListImagesParam array
        DockerClient.ListContainersParam[] dockerParams;
        if (params != null && params.size() > 0) {
            List<DockerClient.ListContainersParam> dockerParamsList =
                    Lists.transform(Lists.newArrayList(params.entrySet()), containerParamTransformer);
            dockerParams = dockerParamsList.toArray(new DockerClient.ListContainersParam[dockerParamsList.size()]);
        } else {
            dockerParams = new DockerClient.ListContainersParam[] {};
        }

        try (final DockerClient dockerClient = getClient()) {
            containerList = dockerClient.listContainers(dockerParams);
        } catch (DockerException | InterruptedException e) {
            _log.error(e.getMessage());
            throw new ContainerServerException(e);
        }
        return DockerContainerToNrgContainer(containerList);
    }

    /**
     * Query Docker server for specific container
     *
     * @param id Container ID
     * @return Container object with specified ID
     **/
    @Override
    public Container getContainer(final String id)
        throws NotFoundException, NoServerPrefException, ContainerServerException {
        final Container container = DockerContainerToNrgContainer(_getContainer(id));
        if (container != null) {
            return container;
        }
        throw new NotFoundException(String.format("Could not find container %s", id));
    }

    private ContainerInfo _getContainer(final String id) throws NoServerPrefException, ContainerServerException {
        final DockerClient client = getClient();
        try {
            return client.inspectContainer(id);
        } catch (DockerException | InterruptedException e) {
            _log.error("Container server error." + e.getMessage());
            throw new ContainerServerException(e);
        }
    }

    /**
     * Query Docker server for status of specific container
     *
     * @param id Container ID
     * @return Status of Container object with specified ID
     **/
    @Override
    public String getContainerStatus(final String id)
        throws NotFoundException, NoServerPrefException, ContainerServerException {
        final Container container = getContainer(id);

        return container != null ? container.status() : null;
    }

    /**
     * Launch image on Docker server
     *
     * @param imageName name of image to launch
     * @param runCommand Command string to execute
     * @param volumes Volume mounts, in the form "/path/on/server:/path/in/container"
     * @return ID of created Container
     **/
    @Override
    public String launchImage(final String imageName, final List<String> runCommand, final List<String> volumes)
        throws NoServerPrefException {

         final HostConfig hostConfig =
                HostConfig.builder()
                .binds(volumes)
                .build();
        final ContainerConfig containerConfig =
                ContainerConfig.builder()
                .hostConfig(hostConfig)
                .image(imageName)
                .attachStdout(true)
                .attachStderr(true)
                .cmd(runCommand)
                .build();

        if (_log.isDebugEnabled()) {
            final String message = String.format(
                    "Starting container: server %s, image %s, command \"%s\", volumes [%s]",
                    getServer(),
                    imageName,
                    StringUtils.join(runCommand, " "),
                    StringUtils.join(volumes, ", ")
            );
            _log.debug(message);
        }

        final ContainerCreation container;
        try (final DockerClient client = getClient()) {
            container = client.createContainer(containerConfig);

            _log.info("Starting container: id "+container.id());
            if (container.getWarnings() != null) {
                for (String warning : container.getWarnings()) {
                    _log.warn(warning);
                }
            }
            client.startContainer(container.id());

            return container.id();
        } catch (DockerException | InterruptedException e) {
            _log.error(e.getMessage());
        }

        return "";
    }

    @Override
    public String getContainerLogs(String id) throws NoServerPrefException, ContainerServerException {
        String logs;
        try (final LogStream logStream = getClient().logs(id, LogsParam.stdout())) {
            logs = logStream.readFully();
        } catch (DockerException | InterruptedException e) {
            _log.error(e.getMessage());
            throw new ContainerServerException(e);
        }

        return logs;
    }

    @Override
    public void deleteImageByName(String name) throws NoServerPrefException, ContainerServerException {
        com.spotify.docker.client.messages.Image image = _getImageByName(name);
        deleteImageById(image.id());
    }

    @Override
    public void deleteImageById(String id) throws NoServerPrefException {
        try (final DockerClient dockerClient = getClient()) {
            dockerClient.removeImage(id);
        } catch (DockerException|InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Create a client connection to a Docker server using default image repository configuration
     *
     * @return DockerClient object using default authConfig
     **/
    public DockerClient getClient() throws NoServerPrefException {
        final ContainerServer server = getServer();

        if (_log.isDebugEnabled()) {
            _log.debug("method getClient, Create server connection, server " + server.host());
        }

        DefaultDockerClient.Builder clientBuilder =
            DefaultDockerClient.builder()
                .uri(server.host());

        if (server.certPath() != null && !server.certPath().equals("")) {
            try {
                final DockerCertificates certificates =
                    new DockerCertificates(Paths.get(server.certPath()));
                clientBuilder = clientBuilder.dockerCertificates(certificates);
            } catch (DockerCertificateException e) {
                _log.error("Could not find docker certificates at " + server.certPath(), e);
            }
        }

        return clientBuilder.build();
    }

    public DockerClient getClientFromEnv() throws DockerCertificateException {

        return DefaultDockerClient.fromEnv().build();
    }

//    /**
//     * Search docker server for images
//     *
//     * @param searchString string to match with image names.
//     * @return List of NRG Image objects
//     **/
//    public List<ImageSearchResult> searchImages(String searchString) throws Exception {
//        return getClient().searchImages(searchString);
//
//    }

    /**
     * Pull image from default hub onto docker server
     *
     **/
    @Override
    public void pullImage(String name) throws NoServerPrefException, ContainerServerException {
        try (final DockerClient client = getClient()) {
            client.pull(name);
        } catch (DockerException | InterruptedException e) {
            _log.error(e.getMessage());
            throw new ContainerServerException(e);
        }
    }

    /**
     * Pull image from specified hub onto docker server
     *
     **/
    @Override
    public void pullImage(String name, ContainerHub hub) throws NoServerPrefException, ContainerServerException {
        if (hub == null) {
            pullImage(name);
        } else {
            try (final DockerClient client = getClient()) {
                final AuthConfig authConfig = AuthConfig.builder()
                    .email(hub.email())
                    .username(hub.username())
                    .password(hub.password())
                    .serverAddress(hub.url())
                    .build();
                client.pull(name, authConfig);
            } catch (DockerException | InterruptedException e) {
                _log.error(e.getMessage());
                throw new ContainerServerException(e);
            }
        }
    }



    // TODO Move everything below to a DAO class
    /**
     * Function to transform image query parameters from key/value to DockerClient.ListImagesParam
     **/
    private static Function<Map.Entry<String, String>, DockerClient.ListImagesParam> imageParamTransformer =
            new Function<Map.Entry<String, String>, DockerClient.ListImagesParam>() {
                @Override
                public DockerClient.ListImagesParam apply(Map.Entry<String, String> stringStringEntry) {
                    return new DockerClient.ListImagesParam(stringStringEntry.getKey(), stringStringEntry.getValue());
                }
            };

    /**
     * Function to transform container query parameters from key/value to DockerClient.ListContainersParam
     **/
    private static Function<Map.Entry<String, String>, DockerClient.ListContainersParam> containerParamTransformer =
            new Function<Map.Entry<String, String>, DockerClient.ListContainersParam>() {
                @Override
                public DockerClient.ListContainersParam apply(Map.Entry<String, String> stringStringEntry) {
                    return new DockerClient.ListContainersParam(stringStringEntry.getKey(), stringStringEntry.getValue());
                }
            };

    /**
     * Convert spotify-docker Image object to xnat-container Image object
     *
     * @param dockerImage Spotify-Docker Image object
     * @return NRG Image object
     **/
    private static org.nrg.containers.image.Image DockerImageToNrgImage(final com.spotify.docker.client.messages.Image dockerImage) {
        org.nrg.containers.image.Image genericImage = null;
        if (dockerImage != null) {
            genericImage =
                    new org.nrg.containers.image.Image(
                            dockerImage.repoTags() != null && dockerImage.repoTags().size() > 0 ? dockerImage.repoTags().get(0) : "null",
                            dockerImage.id(),
                            dockerImage.size(),
                            dockerImage.repoTags(),
                            dockerImage.labels()
                    );
        }
        return genericImage;
    }

    /**
     * Convert list of spotify-docker Image objects to list of xnat-container Image objects
     *
     * @param dockerImageList List of Spotify-Docker Image objects
     * @return List of NRG Image objects
     **/
    private static List<org.nrg.containers.image.Image> DockerImageToNrgImage(final List<com.spotify.docker.client.messages.Image> dockerImageList) {
        return Lists.transform(dockerImageList, DockerImageToNrgImage);
    }

    /**
     * Function to convert list of spotify-docker Image objects to list of xnat-container Image objects
     **/
    private static Function<com.spotify.docker.client.messages.Image, org.nrg.containers.image.Image> DockerImageToNrgImage =
            new Function<com.spotify.docker.client.messages.Image, Image>() {
                @Override
                public Image apply(com.spotify.docker.client.messages.Image image) {
                    return DockerImageToNrgImage(image);
                }
            };

    /**
     * Convert spotify-docker Container object to xnat-container Container object
     *
     * @param dockerContainer Spotify-Docker Container object
     * @return NRG Container object
     **/
    private static Container DockerContainerToNrgContainer(final com.spotify.docker.client.messages.Container dockerContainer) {
        Container genericContainer = null;
        if (dockerContainer != null) {
            genericContainer =
                    new Container(dockerContainer.id(), dockerContainer.status());
        }
        return genericContainer;
    }

    /**
     * Convert spotify-docker Container object to xnat-container Container object
     *
     * @param dockerContainer Spotify-Docker ContainerInfo object
     * @return NRG Container object
     **/
    private static Container DockerContainerToNrgContainer(final com.spotify.docker.client.messages.ContainerInfo dockerContainer) {
        org.nrg.containers.model.Container genericContainer = null;
        if (dockerContainer != null) {
            genericContainer =
                    new Container(
                            dockerContainer.id(),
                            dockerContainer.state().running() ? "Running" :
                                dockerContainer.state().paused() ? "Paused" :
                                dockerContainer.state().restarting() ? "Restarting" :
                                dockerContainer.state().exitCode() != null ? "Exited" :
                                null
                    );
        }
        return genericContainer;
    }

    /**
     * Convert list of spotify-docker Container objects to list of xnat-container Container objects
     *
     * @param dockerContainerList List of Spotify-Docker Container objects
     * @return List of NRG Container objects
     **/
    private static List<org.nrg.containers.model.Container> DockerContainerToNrgContainer(final List<com.spotify.docker.client.messages.Container> dockerContainerList) {
        return Lists.transform(dockerContainerList, DockerContainerToNrgContainer);
    }

    /**
     * Function to convert list of spotify-docker Container objects to xnat-container Container objects
     **/
    private static Function<com.spotify.docker.client.messages.Container, org.nrg.containers.model.Container> DockerContainerToNrgContainer =
            new Function<com.spotify.docker.client.messages.Container, Container>() {
                @Override
                public Container apply(com.spotify.docker.client.messages.Container container) {
                    return DockerContainerToNrgContainer(container);
                }
            };
}