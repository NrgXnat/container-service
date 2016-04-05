package org.nrg.containers.services;

import org.nrg.containers.exceptions.ContainerServerException;
import org.nrg.containers.exceptions.NoHubException;
import org.nrg.containers.exceptions.NoServerPrefException;
import org.nrg.containers.exceptions.NotFoundException;
import org.nrg.containers.metadata.ImageMetadata;
import org.nrg.containers.model.Container;
import org.nrg.containers.model.ContainerHub;
import org.nrg.containers.model.ContainerServer;
import org.nrg.containers.model.Image;
import org.nrg.prefs.exceptions.InvalidPreferenceName;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public interface ContainerService {

    List<Image> getAllImages() throws NoServerPrefException, ContainerServerException;

    Image getImageByName(String name) throws NoServerPrefException, NotFoundException, ContainerServerException;

    Image getImageById(String id) throws NoServerPrefException, NotFoundException, ContainerServerException;

    String deleteImageById(String id, Boolean onServer) throws NoServerPrefException, NotFoundException, ContainerServerException;

    String deleteImageByName(String name, Boolean onServer) throws NoServerPrefException, NotFoundException, ContainerServerException;

    List<Container> getContainers(Map<String, List<String>> queryParams) throws NoServerPrefException, ContainerServerException;

    String getContainerStatus(String id) throws NoServerPrefException, NotFoundException, ContainerServerException;

    Container getContainer(String id) throws NoServerPrefException, NotFoundException, ContainerServerException;

    String launch(String imageName, Map<String, String> params, Boolean wait)
            throws NoServerPrefException, NotFoundException, ContainerServerException;

    String launchOn(String imageName, String xnatId, String type, Map<String, String> launchArguments, Boolean wait)
            throws NoServerPrefException, NotFoundException, ContainerServerException;


    String launchFromScript(String scriptId, Map<String, String> launchArguments, Boolean wait) throws Exception;

    String getContainerLogs(String id) throws NoServerPrefException, NotFoundException, ContainerServerException;

    String verbContainer(String id, String status) throws NoServerPrefException, NotFoundException, ContainerServerException;

    List<ContainerHub> getHubs();

    void setHub(ContainerHub hub) throws IOException;

//    String search(String term) throws NoHubException;

    void pullByName(String image, String hub, String hubUsername, String hubPassword)
        throws NoHubException, NotFoundException, ContainerServerException, IOException, NoServerPrefException;

    void pullByName(String image, String hub)
        throws NoHubException, NotFoundException, ContainerServerException, IOException, NoServerPrefException;

    Image pullFromSource(String source, String name) throws NoHubException, NotFoundException, ContainerServerException;

    void setMetadataByName(String name, ImageMetadata metadata, String project, Boolean overwrite, Boolean ignoreBlank)
            throws NoServerPrefException, NotFoundException, ContainerServerException;

    void setMetadataById(String id, ImageMetadata metadata, String project, Boolean overwrite, Boolean ignoreBlank)
            throws NoServerPrefException, NotFoundException, ContainerServerException;

    ContainerServer getServer() throws NoServerPrefException, NotFoundException;

    void setServer(ContainerServer server) throws InvalidPreferenceName;

    String pingServer() throws NoServerPrefException, ContainerServerException;

    String pingHub(ContainerHub hub) throws ContainerServerException, NoServerPrefException;

    String setMetadataById(String id, Map<String, String> metadata, String project, Boolean overwrite, Boolean ignoreBlank)
            throws NoServerPrefException, NotFoundException;
}
