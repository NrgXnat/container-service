package org.nrg.containers.services;

import org.nrg.containers.events.model.ContainerEvent;
import org.nrg.containers.events.model.ServiceTaskEvent;
import org.nrg.containers.exceptions.ContainerException;
import org.nrg.containers.exceptions.DockerServerException;
import org.nrg.containers.exceptions.NoDockerServerException;
import org.nrg.containers.model.command.auto.ResolvedCommand;
import org.nrg.containers.model.configuration.PluginVersionCheck;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xft.event.persist.PersistentWorkflowI;
import org.nrg.xft.security.UserI;

import java.io.InputStream;
import java.util.Date;
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


    List<Container> retrieveServices();
    List<Container> retrieveServicesInWaitingState();
    List<Container> retrieveNonfinalizedServices();

    void checkQueuedContainerJobs(UserI user);
    void checkWaitingContainerJobs(UserI user);
    void resetFinalizingStatusToWaitingOrFailed();

    List<Container> retrieveSetupContainersForParent(long parentId);
    List<Container> retrieveWrapupContainersForParent(long parentId);

    Container addContainerEventToHistory(final ContainerEvent containerEvent, final UserI userI);
    Container.ContainerHistory addContainerHistoryItem(final Container container,
                                                       final Container.ContainerHistory history, final UserI userI);

    PersistentWorkflowI createContainerWorkflow(String xnatId, String xsiType,
                                                String wrapperName, String projectId, UserI user)
            throws Exception;

    void queueResolveCommandAndLaunchContainer(String project,
                                               long wrapperId,
                                               long commandId,
                                               String wrapperName,
                                               Map<String, String> inputValues,
                                               UserI userI, PersistentWorkflowI workflow) throws Exception;

    void consumeResolveCommandAndLaunchContainer(String project,
                                                 long wrapperId,
                                                 long commandId,
                                                 String wrapperName,
                                                 Map<String, String> inputValues,
                                                 UserI userI, String workflowid);

    Container launchResolvedCommand(final ResolvedCommand resolvedCommand, final UserI userI, PersistentWorkflowI workflow)
            throws NoDockerServerException, DockerServerException, ContainerException;

    void processEvent(final ContainerEvent event);
    void processEvent(final ServiceTaskEvent event);

    void finalize(final String containerId, final UserI userI) throws NotFoundException, ContainerException, NoDockerServerException, DockerServerException;
    void finalize(final Container container, final UserI userI) throws ContainerException, DockerServerException, NoDockerServerException;
    void finalize(Container notFinalized, UserI userI, String exitCode, boolean isSuccessfulStatus)	throws ContainerException, NoDockerServerException, DockerServerException;
    
    String kill(final String containerId, final UserI userI)
            throws NoDockerServerException, DockerServerException, NotFoundException;

    Map<String, InputStream> getLogStreams(long id) throws NotFoundException;
    Map<String, InputStream> getLogStreams(String containerId) throws NotFoundException;
    InputStream getLogStream(long id, String logFileName) throws NotFoundException;
    InputStream getLogStream(String containerId, String logFileName) throws NotFoundException;
    InputStream getLogStream(String containerId, String logFileName, boolean withTimestamps, Integer since) throws NotFoundException;
	boolean isWaiting(Container service);
	boolean isFinalizing(Container service);
    boolean isFailedOrComplete(Container service, UserI user);
	void queueFinalize(final String exitCodeString, final boolean isSuccessful, final Container service, final UserI userI);
    void consumeFinalize(final String exitCodeString, final boolean isSuccessful, final Container service, final UserI userI)
            throws NoDockerServerException, ContainerException, NotFoundException, DockerServerException;

    /**
     * Restart a service through swarm
     * @param service the service to restart
     * @param user the user
     * @return true is successfully restarted, false otherwise
     */
    boolean restartService(Container service, UserI user);
}
