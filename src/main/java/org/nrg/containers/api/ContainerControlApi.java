package org.nrg.containers.api;

import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.exceptions.ServiceNotFoundException;
import org.nrg.containers.events.model.DockerContainerEvent;
import org.nrg.containers.exceptions.ContainerException;
import org.nrg.containers.exceptions.DockerServerException;
import org.nrg.containers.exceptions.NoDockerServerException;
import org.nrg.containers.model.command.auto.ResolvedCommand;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.auto.ContainerMessage;
import org.nrg.containers.model.container.auto.ServiceTask;
import org.nrg.containers.model.dockerhub.DockerHubBase.DockerHub;
import org.nrg.containers.model.image.docker.DockerImage;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xft.security.UserI;

import java.util.Date;
import java.util.List;
import java.util.Map;

public interface ContainerControlApi {
    String ping() throws NoDockerServerException, DockerServerException;
    boolean canConnect();

    String pingHub(DockerHub hub) throws DockerServerException, NoDockerServerException;
    String pingHub(DockerHub hub, String username, String password) throws DockerServerException, NoDockerServerException;

    List<DockerImage> getAllImages() throws NoDockerServerException, DockerServerException;
    DockerImage getImageById(final String imageId) throws NotFoundException, DockerServerException, NoDockerServerException;
    void deleteImageById(String id, Boolean force) throws NoDockerServerException, DockerServerException;

    DockerImage pullImage(String name) throws NoDockerServerException, DockerServerException, NotFoundException;
    DockerImage pullImage(String name, DockerHub hub) throws NoDockerServerException, DockerServerException, NotFoundException;
    DockerImage pullImage(String name, DockerHub hub, String username, String password) throws NoDockerServerException, DockerServerException, NotFoundException;

    Container createContainerOrSwarmService(final ResolvedCommand dockerCommand, final UserI userI) throws NoDockerServerException, DockerServerException, ContainerException;
    Container createContainerOrSwarmService(final Container container, final UserI userI) throws NoDockerServerException, DockerServerException, ContainerException;
    //    String createContainer(final String imageName, final List<String> runCommand, final List <String> volumes) throws NoServerPrefException, DockerServerException;
//    String createContainer(final DockerServer server, final String imageName,
//                       final List<String> runCommand, final List <String> volumes) throws DockerServerException;
//    String createContainer(final DockerServer server, final String imageName,
//                       final List<String> runCommand, final List <String> volumes,
//                       final List<String> environmentVariables) throws DockerServerException;
    void startContainer(final Container containerOrService) throws NoDockerServerException, DockerServerException;

    List<Command> parseLabels(final String imageName)
            throws DockerServerException, NoDockerServerException, NotFoundException;

    List<ContainerMessage> getAllContainers() throws NoDockerServerException, DockerServerException;
    List<ContainerMessage> getContainers(final Map<String, String> params) throws NoDockerServerException, DockerServerException;
    ContainerMessage getContainer(final String id) throws NotFoundException, NoDockerServerException, DockerServerException;
    String getContainerStatus(final String id) throws NotFoundException, NoDockerServerException, DockerServerException;

    String getStdoutLog(Container container) throws NoDockerServerException, DockerServerException;
    String getStderrLog(Container container) throws NoDockerServerException, DockerServerException;

    String getContainerStdoutLog(String containerId) throws NoDockerServerException, DockerServerException;
    String getContainerStderrLog(String containerId) throws NoDockerServerException, DockerServerException;
    String getServiceStdoutLog(String serviceId) throws NoDockerServerException, DockerServerException;
    String getServiceStderrLog(String serviceId) throws NoDockerServerException, DockerServerException;
    String getContainerStdoutLog(String containerId, DockerClient.LogsParam... logParams) throws NoDockerServerException, DockerServerException;
    String getContainerStderrLog(String containerId, DockerClient.LogsParam... logParams) throws NoDockerServerException, DockerServerException;
    String getServiceStdoutLog(String serviceId, DockerClient.LogsParam... logParams) throws NoDockerServerException, DockerServerException;
    String getServiceStderrLog(String serviceId, DockerClient.LogsParam... logParams) throws NoDockerServerException, DockerServerException;
    
    List<DockerContainerEvent> getContainerEvents(final Date since, final Date until) throws NoDockerServerException, DockerServerException;
    void throwContainerEvents(final Date since, final Date until) throws NoDockerServerException, DockerServerException;

    void killContainer(final String id) throws NoDockerServerException, DockerServerException, NotFoundException;
    void killService(String id) throws NoDockerServerException, DockerServerException, NotFoundException;

    ServiceTask getTaskForService(Container service) throws NoDockerServerException, DockerServerException, ServiceNotFoundException;
    ServiceTask getTaskForService(DockerServer dockerServer, Container service) throws DockerServerException, ServiceNotFoundException;
    void throwTaskEventForService(Container service) throws NoDockerServerException, DockerServerException, ServiceNotFoundException;
    void throwTaskEventForService(DockerServer dockerServer, Container service) throws DockerServerException, ServiceNotFoundException;
    void throwRestartEventForService(Container service) throws ContainerException;
    void throwWaitingEventForService(Container service) throws ContainerException;

}
