package org.nrg.containers.api;

import org.nrg.containers.events.model.DockerContainerEvent;
import org.nrg.containers.exceptions.ContainerException;
import org.nrg.containers.exceptions.DockerServerException;
import org.nrg.containers.exceptions.NoDockerServerException;
import org.nrg.containers.model.command.auto.ResolvedCommand;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.container.auto.ContainerMessage;
import org.nrg.containers.model.dockerhub.DockerHubBase.DockerHub;
import org.nrg.containers.model.image.docker.DockerImage;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServerWithPing;
import org.nrg.framework.exceptions.NotFoundException;

import java.util.Date;
import java.util.List;
import java.util.Map;

public interface ContainerControlApi {
    DockerServerWithPing getServerAndPing() throws NoDockerServerException;
    DockerServerWithPing setServerAndPing(DockerServer server);
    String pingServer() throws NoDockerServerException, DockerServerException;
    boolean canConnect();

    String pingHub(DockerHub hub) throws DockerServerException, NoDockerServerException;
    String pingHub(DockerHub hub, String username, String password) throws DockerServerException, NoDockerServerException;

    List<DockerImage> getAllImages() throws NoDockerServerException, DockerServerException;
    DockerImage getImageById(final String imageId) throws NotFoundException, DockerServerException, NoDockerServerException;
    void deleteImageById(String id, Boolean force) throws NoDockerServerException, DockerServerException;

    DockerImage pullImage(String name) throws NoDockerServerException, DockerServerException, NotFoundException;
    DockerImage pullImage(String name, DockerHub hub) throws NoDockerServerException, DockerServerException, NotFoundException;
    DockerImage pullImage(String name, DockerHub hub, String username, String password) throws NoDockerServerException, DockerServerException, NotFoundException;

    String createContainer(final ResolvedCommand dockerCommand) throws NoDockerServerException, DockerServerException, ContainerException;
    //    String createContainer(final String imageName, final List<String> runCommand, final List <String> volumes) throws NoServerPrefException, DockerServerException;
//    String createContainer(final DockerServer server, final String imageName,
//                       final List<String> runCommand, final List <String> volumes) throws DockerServerException;
//    String createContainer(final DockerServer server, final String imageName,
//                       final List<String> runCommand, final List <String> volumes,
//                       final List<String> environmentVariables) throws DockerServerException;
    void startContainer(final String containerId) throws NoDockerServerException, DockerServerException;

    List<Command> parseLabels(final String imageName)
            throws DockerServerException, NoDockerServerException, NotFoundException;

    List<ContainerMessage> getAllContainers() throws NoDockerServerException, DockerServerException;
    List<ContainerMessage> getContainers(final Map<String, String> params) throws NoDockerServerException, DockerServerException;
    ContainerMessage getContainer(final String id) throws NotFoundException, NoDockerServerException, DockerServerException;
    String getContainerStatus(final String id) throws NotFoundException, NoDockerServerException, DockerServerException;
    String getContainerStdoutLog(String id) throws NoDockerServerException, DockerServerException;
    String getContainerStderrLog(String id) throws NoDockerServerException, DockerServerException;

    List<DockerContainerEvent> getContainerEvents(final Date since, final Date until) throws NoDockerServerException, DockerServerException;
    List<DockerContainerEvent> getContainerEventsAndThrow(final Date since, final Date until) throws NoDockerServerException, DockerServerException;

    void killContainer(final String id) throws NoDockerServerException, DockerServerException, NotFoundException;
}
