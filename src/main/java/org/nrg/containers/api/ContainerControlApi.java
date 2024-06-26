package org.nrg.containers.api;

import org.mandas.docker.client.DockerClient;
import org.mandas.docker.client.exceptions.ServiceNotFoundException;
import org.mandas.docker.client.exceptions.TaskNotFoundException;
import org.nrg.containers.events.model.DockerContainerEvent;
import org.nrg.containers.exceptions.ContainerBackendException;
import org.nrg.containers.exceptions.ContainerException;
import org.nrg.containers.exceptions.DockerServerException;
import org.nrg.containers.exceptions.NoContainerServerException;
import org.nrg.containers.exceptions.NoDockerServerException;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.auto.ResolvedCommand;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.auto.ContainerMessage;
import org.nrg.containers.model.container.auto.ServiceTask;
import org.nrg.containers.model.dockerhub.DockerHubBase;
import org.nrg.containers.model.dockerhub.DockerHubBase.DockerHub;
import org.nrg.containers.model.image.docker.DockerImage;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xft.security.UserI;

import java.time.OffsetDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;

public interface ContainerControlApi {

    String ping() throws NoDockerServerException, DockerServerException;
    boolean canConnect();

    DockerHubBase.DockerHubStatus pingHub(DockerHub hub);
    DockerHubBase.DockerHubStatus pingHub(DockerHub hub, String username, String password, String token, String email);

    List<DockerImage> getAllImages() throws NoDockerServerException, DockerServerException;
    DockerImage getImageById(final String imageId) throws NotFoundException, DockerServerException, NoDockerServerException;
    void deleteImageById(String id, Boolean force) throws NoDockerServerException, DockerServerException;

    DockerImage pullImage(String name) throws NoDockerServerException, DockerServerException, NotFoundException;
    DockerImage pullImage(String name, DockerHub hub) throws NoDockerServerException, DockerServerException, NotFoundException;
    DockerImage pullImage(String name, DockerHub hub, String username, String password, String token, String email) throws NoDockerServerException, DockerServerException, NotFoundException;

    Container create(ResolvedCommand resolvedCommand, UserI user) throws NoContainerServerException, ContainerBackendException, ContainerException;
    Container create(Container toCreate, UserI user) throws NoContainerServerException, ContainerBackendException, ContainerException;
    void start(final Container toStart) throws NoContainerServerException, ContainerBackendException;
    @Deprecated Container createContainerOrSwarmService(final ResolvedCommand dockerCommand, final UserI userI) throws NoDockerServerException, DockerServerException, ContainerException;
    @Deprecated Container createContainerOrSwarmService(final Container container, final UserI userI) throws NoDockerServerException, DockerServerException, ContainerException;
    @Deprecated void startContainer(final Container containerOrService) throws NoDockerServerException, DockerServerException;

    List<Command> parseLabels(final String imageName)
            throws DockerServerException, NoDockerServerException, NotFoundException;

    List<ContainerMessage> getAllContainers() throws NoDockerServerException, DockerServerException;
    List<ContainerMessage> getContainers(final Map<String, String> params) throws NoDockerServerException, DockerServerException;
    ContainerMessage getContainer(final String id) throws NotFoundException, NoDockerServerException, DockerServerException;
    String getContainerStatus(final String id) throws NotFoundException, NoDockerServerException, DockerServerException;

    String getLog(Container container, LogType logType) throws ContainerBackendException, NoContainerServerException;
    String getLog(Container container, LogType logType, Boolean withTimestamps, OffsetDateTime since) throws ContainerBackendException, NoContainerServerException;
    @Deprecated String getStdoutLog(Container container) throws NoDockerServerException, DockerServerException;
    @Deprecated String getStderrLog(Container container) throws NoDockerServerException, DockerServerException;
    @Deprecated String getContainerStdoutLog(String containerId, DockerClient.LogsParam... logParams) throws NoDockerServerException, DockerServerException;
    @Deprecated String getContainerStderrLog(String containerId, DockerClient.LogsParam... logParams) throws NoDockerServerException, DockerServerException;
    @Deprecated String getServiceStdoutLog(String serviceId, DockerClient.LogsParam... logParams) throws NoDockerServerException, DockerServerException;
    @Deprecated String getServiceStderrLog(String serviceId, DockerClient.LogsParam... logParams) throws NoDockerServerException, DockerServerException;
    
    void kill(Container container) throws NoContainerServerException, ContainerBackendException, NotFoundException;
    void autoCleanup(Container container) throws NoContainerServerException, ContainerBackendException, NotFoundException;
    void remove(Container container) throws NoContainerServerException, ContainerBackendException, NotFoundException;
    @Deprecated void killContainer(final String id) throws NoDockerServerException, DockerServerException, NotFoundException;
    @Deprecated void killService(String id) throws NoDockerServerException, DockerServerException, NotFoundException;
    @Deprecated void removeContainerOrService(final Container container) throws NoDockerServerException, DockerServerException;

    ServiceTask getTaskForService(Container service) throws NoDockerServerException, DockerServerException, ServiceNotFoundException, TaskNotFoundException;
    ServiceTask getTaskForService(DockerServer dockerServer, Container service) throws DockerServerException, ServiceNotFoundException, TaskNotFoundException;

    List<DockerContainerEvent> getContainerEvents(final Date since, final Date until) throws NoDockerServerException, DockerServerException;
    @Deprecated void throwContainerEvents(final Date since, final Date until) throws NoDockerServerException, DockerServerException;
    @Deprecated void throwLostTaskEventForService(Container service);
    @Deprecated void throwTaskEventForService(Container service) throws NoDockerServerException, DockerServerException, ServiceNotFoundException, TaskNotFoundException;
    @Deprecated void throwTaskEventForService(DockerServer dockerServer, Container service) throws DockerServerException, ServiceNotFoundException, TaskNotFoundException;
    @Deprecated void throwRestartEventForService(Container service) throws ContainerException;
    @Deprecated void throwWaitingEventForService(Container service) throws ContainerException;

    Integer getFinalizingThrottle();
    boolean isStatusEmailEnabled();
}
