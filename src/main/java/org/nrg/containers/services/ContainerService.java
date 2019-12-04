package org.nrg.containers.services;

import org.nrg.containers.events.model.ContainerEvent;
import org.nrg.containers.events.model.ServiceTaskEvent;
import org.nrg.containers.exceptions.CommandResolutionException;
import org.nrg.containers.exceptions.ContainerException;
import org.nrg.containers.exceptions.DockerServerException;
import org.nrg.containers.exceptions.NoDockerServerException;
import org.nrg.containers.exceptions.UnauthorizedException;
import org.nrg.containers.model.command.auto.ResolvedCommand;
import org.nrg.containers.model.configuration.PluginVersionCheck;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xft.security.UserI;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

public interface ContainerService {
    String STDOUT_LOG_NAME = "stdout.log";
    String STDERR_LOG_NAME = "stderr.log";
    String[] LOG_NAMES = new String[] {STDOUT_LOG_NAME, STDERR_LOG_NAME};

    PluginVersionCheck checkXnatVersion();

    List<Container> getAll();
    Container retrieve(final long id);
    Container retrieve(final String containerId);
    Container get(final long id) throws NotFoundException;
    Container get(final String containerId) throws NotFoundException;
    void delete(final long id);
    void delete(final String containerId);
    void update(Container container);

    List<Container> getAll(final Boolean nonfinalized, String project);
    List<Container> getAll(String project);
    List<Container> getAll(Boolean nonfinalized);

    Container getByName(String project, String name, final Boolean nonfinalized);
    Container getByName(String name, final Boolean nonfinalized);


    List<Container> retrieveServices();
    List<Container> retrieveNonfinalizedServices();

    List<Container> retrieveSetupContainersForParent(long parentId);
    List<Container> retrieveWrapupContainersForParent(long parentId);

    Container addContainerEventToHistory(final ContainerEvent containerEvent, final UserI userI);
    Container.ContainerHistory addContainerHistoryItem(final Container container,
                                                       final Container.ContainerHistory history, final UserI userI);

    Container resolveCommandAndLaunchContainer(long wrapperId,
                                               Map<String, String> inputValues,
                                               UserI userI)
            throws NoDockerServerException, DockerServerException, NotFoundException, CommandResolutionException, ContainerException, UnauthorizedException;
    Container resolveCommandAndLaunchContainer(long commandId,
                                               String wrapperName,
                                               Map<String, String> inputValues,
                                               UserI userI)
            throws NoDockerServerException, DockerServerException, NotFoundException, CommandResolutionException, ContainerException, UnauthorizedException;
    Container resolveCommandAndLaunchContainer(String project,
                                               long wrapperId,
                                               Map<String, String> inputValues,
                                               UserI userI)
            throws NoDockerServerException, DockerServerException, NotFoundException, CommandResolutionException, ContainerException, UnauthorizedException;
    Container resolveCommandAndLaunchContainer(String project,
                                               long commandId,
                                               String wrapperName,
                                               Map<String, String> inputValues,
                                               UserI userI)
            throws NoDockerServerException, DockerServerException, NotFoundException, CommandResolutionException, ContainerException, UnauthorizedException;
    Container launchResolvedCommand(final ResolvedCommand resolvedCommand, final UserI userI)
            throws NoDockerServerException, DockerServerException, ContainerException;

    void processEvent(final ContainerEvent event);
    void processEvent(final ServiceTaskEvent event);

    void finalize(final String containerId, final UserI userI) throws NotFoundException, ContainerException, NoDockerServerException, DockerServerException;
    void finalize(final Container container, final UserI userI) throws ContainerException, DockerServerException, NoDockerServerException;
    void finalize(final Container container, final UserI userI, final String exitCode) throws ContainerException, NoDockerServerException, DockerServerException;

    String kill(final String containerId, final UserI userI)
            throws NoDockerServerException, DockerServerException, NotFoundException;

    String kill(final String project, final String containerId, final UserI userI)
            throws NoDockerServerException, DockerServerException, NotFoundException;

    Map<String, InputStream> getLogStreams(long id) throws NotFoundException, NoDockerServerException, DockerServerException;
    Map<String, InputStream> getLogStreams(String containerId) throws NotFoundException, NoDockerServerException, DockerServerException;
    InputStream getLogStream(long id, String logFileName) throws NotFoundException, NoDockerServerException, DockerServerException;
    InputStream getLogStream(String containerId, String logFileName) throws NotFoundException, NoDockerServerException, DockerServerException;
}
