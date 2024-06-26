package org.nrg.containers.services;

import org.nrg.containers.api.LogType;
import org.nrg.containers.events.model.ContainerEvent;
import org.nrg.containers.events.model.ServiceTaskEvent;
import org.nrg.containers.exceptions.BadRequestException;
import org.nrg.containers.exceptions.ContainerException;
import org.nrg.containers.exceptions.DockerServerException;
import org.nrg.containers.exceptions.NoDockerServerException;
import org.nrg.containers.exceptions.UnauthorizedException;
import org.nrg.containers.model.command.auto.LaunchReport;
import org.nrg.containers.model.command.auto.ResolvedCommand;
import org.nrg.containers.model.configuration.PluginVersionCheck;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.auto.ContainerPaginatedRequest;
import org.nrg.containers.model.orchestration.auto.Orchestration;
import org.nrg.containers.rest.ContainerLogPollResponse;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xft.event.persist.PersistentWorkflowI;
import org.nrg.xft.security.UserI;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.OutputStream;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public interface ContainerService {
    String   XNAT_USER        = "XNAT_USER";
    String   XNAT_PASS        = "XNAT_PASS";
    String   XNAT_HOST        = "XNAT_HOST";
    String   XNAT_WORKFLOW_ID = "XNAT_WORKFLOW_ID";
    String   XNAT_EVENT_ID    = "XNAT_EVENT_ID";
    String   STDOUT_LOG_NAME  = "stdout.log";
    String   STDERR_LOG_NAME  = "stderr.log";
    Set<String> RESERVED_ENV  = Stream.of(XNAT_USER, XNAT_PASS, XNAT_HOST)
            .collect(Collectors.collectingAndThen(Collectors.toSet(), Collections::unmodifiableSet));

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

    List<Container> getPaginated(ContainerPaginatedRequest containerPaginatedRequest);

    List<Container> retrieveServices();
    List<Container> retrieveServicesInWaitingState();
    List<Container> retrieveNonfinalizedServices();

    void checkQueuedContainerJobs(UserI user);
    void checkWaitingContainerJobs(UserI user);

    List<Container> retrieveSetupContainersForParent(long parentId);
    List<Container> retrieveWrapupContainersForParent(long parentId);

    Container addContainerEventToHistory(final ContainerEvent containerEvent, final UserI userI);
    Container.ContainerHistory addContainerHistoryItem(final Container container,
                                                       final Container.ContainerHistory history, final UserI userI);

    PersistentWorkflowI createContainerWorkflow(String xnatId, String xsiType,
                                                String wrapperName, String projectId, UserI user);
    PersistentWorkflowI createContainerWorkflow(String xnatId, String xsiType,
                                                String wrapperName, String projectId, UserI user,
                                                @Nullable String bulkLaunchId);
    PersistentWorkflowI createContainerWorkflow(String xnatId, String xsiType,
                                                String wrapperName, String projectId, UserI user,
                                                @Nullable String bulkLaunchId, @Nullable Long orchestrationId,
                                                int orchestrationOrder);


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

    void finalize(final String containerId, final UserI userI) throws NotFoundException, ContainerException;
    void finalize(final Container container, final UserI userI) throws ContainerException;
    void finalize(Container notFinalized, UserI userI, String exitCode, boolean isSuccessfulStatus) throws ContainerException;

    boolean canKill(final String containerId, final UserI userI);
    String kill(final String containerId, final UserI userI)
            throws NoDockerServerException, DockerServerException, NotFoundException, UnauthorizedException;

    void writeLogsToZipStream(String containerId, OutputStream outputStream) throws NotFoundException, IOException;
    ContainerLogPollResponse getLog(String containerId, LogType logType, String sinceTimestamp)
            throws NotFoundException, IOException, BadRequestException;
    ContainerLogPollResponse getLog(String containerId, LogType logType, OffsetDateTime since)
            throws NotFoundException, IOException;

	boolean isWaiting(Container containerOrService);
	boolean isFinalizing(Container containerOrService);
    boolean containerStatusIsTerminal(Container containerOrService);

    boolean fixWorkflowContainerStatusMismatch(Container containerOrService, UserI user);

    void queueFinalize(final String exitCodeString,
                       final boolean isSuccessful,
                       final Container service,
                       final UserI userI);

    void consumeFinalize(final String exitCodeString,
                         final boolean isSuccessful,
                         final Container service,
                         final UserI userI)
            throws ContainerException, NotFoundException;

    /**
     * Restart a service through swarm
     * @param service the service to restart
     * @return true is successfully restarted, false otherwise
     */
    boolean restartService(Container service);
    /**
     * Restart a service through swarm
     * @param service the service to restart
     * @param user the user
     * @return true is successfully restarted, false otherwise
     */
    boolean restartService(Container service, UserI user);

    String kill(final String project, final String containerId, final UserI userI)
            throws NoDockerServerException, DockerServerException, NotFoundException, UnauthorizedException;

    /**
     * Check if wrapper is start of orchestration, null means no orchestration
     * @param project the project
     * @param wrapperId the wrapper id
     * @param commandId the command id
     * @param wrapperName the wrapper name
     * @return orchestration or null if none
     * @throws ExecutionException for issues querying the orchestration entity
     */
    @Nullable
    Orchestration getOrchestrationWhereWrapperIsFirst(final String project,
                                                      long wrapperId,
                                                      final long commandId,
                                                      @Nullable final String wrapperName)
            throws ExecutionException;

    @Nonnull
    LaunchReport launchContainer(@Nullable String project,
                                 long commandId,
                                 @Nullable String wrapperName,
                                 long wrapperId,
                                 @Nullable String rootElement,
                                 Map<String, String> allRequestParams,
                                 UserI userI);

    @Nonnull
    LaunchReport launchContainer(@Nullable String project,
                                 long commandId,
                                 @Nullable String wrapperName,
                                 long wrapperId,
                                 @Nullable String rootElement,
                                 Map<String, String> allRequestParams,
                                 UserI userI,
                                 @Nullable String bulkLaunchId);

    @Nonnull
    LaunchReport launchContainer(@Nullable String project,
                                 long commandId,
                                 @Nullable String wrapperName,
                                 long wrapperId,
                                 @Nullable String rootElement,
                                 Map<String, String> allRequestParams,
                                 UserI userI,
                                 @Nullable String bulkLaunchId,
                                 @Nullable Long orchestrationId);

    LaunchReport.BulkLaunchReport bulkLaunch(@Nullable String project,
                                             long commandId,
                                             @Nullable String wrapperName,
                                             long wrapperId,
                                             @Nullable String rootElement,
                                             Map<String, String> allRequestParams,
                                             UserI userI) throws IOException;
}
