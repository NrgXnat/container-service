package org.nrg.containers.services.impl;


import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.mandas.docker.client.messages.swarm.TaskStatus;
import org.nrg.action.ClientException;
import org.nrg.containers.api.ContainerControlApi;
import org.nrg.containers.events.model.ContainerEvent;
import org.nrg.containers.events.model.KubernetesStatusChangeEvent;
import org.nrg.containers.events.model.ServiceTaskEvent;
import org.nrg.containers.exceptions.CommandResolutionException;
import org.nrg.containers.exceptions.ContainerBackendException;
import org.nrg.containers.exceptions.ContainerException;
import org.nrg.containers.exceptions.ContainerFinalizationException;
import org.nrg.containers.exceptions.DockerServerException;
import org.nrg.containers.exceptions.NoContainerServerException;
import org.nrg.containers.exceptions.NoDockerServerException;
import org.nrg.containers.exceptions.UnauthorizedException;
import org.nrg.containers.jms.requests.ContainerFinalizingRequest;
import org.nrg.containers.jms.requests.ContainerRequest;
import org.nrg.containers.jms.requests.ContainerStagingRequest;
import org.nrg.containers.jms.utils.QueueUtils;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.auto.Command.CommandWrapper;
import org.nrg.containers.model.command.auto.Command.ConfiguredCommand;
import org.nrg.containers.model.command.auto.LaunchReport;
import org.nrg.containers.model.command.auto.ResolvedCommand;
import org.nrg.containers.model.command.auto.ResolvedInputTreeNode;
import org.nrg.containers.model.command.auto.ResolvedInputValue;
import org.nrg.containers.model.command.entity.CommandWrapperInputType;
import org.nrg.containers.model.configuration.PluginVersionCheck;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.auto.Container.ContainerHistory;
import org.nrg.containers.model.container.auto.ContainerPaginatedRequest;
import org.nrg.containers.model.container.auto.ServiceTask;
import org.nrg.containers.model.container.entity.ContainerEntity;
import org.nrg.containers.model.container.entity.ContainerEntityHistory;
import org.nrg.containers.model.orchestration.auto.Orchestration;
import org.nrg.containers.model.orchestration.auto.Orchestration.OrchestrationIdentifier;
import org.nrg.containers.model.server.docker.Backend;
import org.nrg.containers.model.xnat.Scan;
import org.nrg.containers.model.xnat.XnatModelObject;
import org.nrg.containers.services.CommandResolutionService;
import org.nrg.containers.services.CommandService;
import org.nrg.containers.services.ContainerEntityService;
import org.nrg.containers.services.ContainerFinalizeService;
import org.nrg.containers.services.ContainerService;
import org.nrg.containers.services.OrchestrationService;
import org.nrg.containers.utils.ContainerUtils;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.framework.services.NrgEventServiceI;
import org.nrg.xdat.XDAT;
import org.nrg.xdat.entities.AliasToken;
import org.nrg.xdat.om.WrkWorkflowdata;
import org.nrg.xdat.om.XnatAbstractprojectasset;
import org.nrg.xdat.om.XnatImageassessordata;
import org.nrg.xdat.om.XnatImagescandata;
import org.nrg.xdat.om.XnatImagesessiondata;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xdat.om.XnatResource;
import org.nrg.xdat.om.XnatSubjectassessordata;
import org.nrg.xdat.om.XnatSubjectdata;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.security.helpers.Groups;
import org.nrg.xdat.security.helpers.Permissions;
import org.nrg.xdat.security.helpers.Users;
import org.nrg.xdat.security.user.exceptions.UserInitException;
import org.nrg.xdat.security.user.exceptions.UserNotFoundException;
import org.nrg.xdat.services.AliasTokenService;
import org.nrg.xft.XFTItem;
import org.nrg.xft.event.EventUtils;
import org.nrg.xft.event.persist.PersistentWorkflowI;
import org.nrg.xft.event.persist.PersistentWorkflowUtils;
import org.nrg.xft.exception.ElementNotFoundException;
import org.nrg.xft.exception.FieldNotFoundException;
import org.nrg.xft.exception.XFTInitException;
import org.nrg.xft.search.CriteriaCollection;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.archive.ResourceData;
import org.nrg.xnat.event.model.BulkLaunchEvent;
import org.nrg.xnat.helpers.uri.URIManager;
import org.nrg.xnat.helpers.uri.archive.impl.ExptScanURI;
import org.nrg.xnat.services.XnatAppInfo;
import org.nrg.xnat.services.archive.CatalogService;
import org.nrg.xnat.turbine.utils.ArchivableItem;
import org.nrg.xnat.utils.WorkflowUtils;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolExecutorFactoryBean;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.nrg.containers.model.command.entity.CommandType.DOCKER;
import static org.nrg.containers.model.command.entity.CommandType.DOCKER_SETUP;
import static org.nrg.containers.model.command.entity.CommandType.DOCKER_WRAPUP;
import static org.nrg.containers.model.command.entity.CommandWrapperInputType.ASSESSOR;
import static org.nrg.containers.model.command.entity.CommandWrapperInputType.PROJECT;
import static org.nrg.containers.model.command.entity.CommandWrapperInputType.PROJECT_ASSET;
import static org.nrg.containers.model.command.entity.CommandWrapperInputType.RESOURCE;
import static org.nrg.containers.model.command.entity.CommandWrapperInputType.SCAN;
import static org.nrg.containers.model.command.entity.CommandWrapperInputType.SESSION;
import static org.nrg.containers.model.command.entity.CommandWrapperInputType.SUBJECT;
import static org.nrg.containers.model.command.entity.CommandWrapperInputType.SUBJECT_ASSESSOR;

@Slf4j
@Service
public class ContainerServiceImpl implements ContainerService {
    private static final String MIN_XNAT_VERSION_REQUIRED = "1.8.5";
    public static final String WAITING = "Waiting";
    public static final String _WAITING = "_Waiting";
    public static final String FINALIZING = "Finalizing";
    public static final String STAGING = "Staging";
    public static final String CREATED = "Created";
    public static final String setupStr = "Setup";
    public static final String wrapupStr = "Wrapup";
    public static final String containerLaunchJustification = "Container launch";
    public static final String TO_BE_ASSIGNED = "To be assigned";

    private final ContainerControlApi containerControlApi;
    private final ContainerEntityService containerEntityService;
    private final CommandResolutionService commandResolutionService;
    private final CommandService commandService;
    private final AliasTokenService aliasTokenService;
    private final SiteConfigPreferences siteConfigPreferences;
    private final ContainerFinalizeService containerFinalizeService;
    private final XnatAppInfo xnatAppInfo;
    private final CatalogService catalogService;
    private final OrchestrationService orchestrationService;
    private final ObjectMapper mapper;
    private final ExecutorService executorService;
    private final NrgEventServiceI eventService;


    private LoadingCache<OrchestrationIdentifier, Optional<Orchestration>> orchestrationCache;

    @Autowired
    public ContainerServiceImpl(final ContainerControlApi containerControlApi,
                                final ContainerEntityService containerEntityService,
                                final CommandResolutionService commandResolutionService,
                                final CommandService commandService,
                                final AliasTokenService aliasTokenService,
                                final SiteConfigPreferences siteConfigPreferences,
                                final ContainerFinalizeService containerFinalizeService,
                                final XnatAppInfo xnatAppInfo,
                                final CatalogService catalogService,
                                final OrchestrationService orchestrationService,
                                final NrgEventServiceI eventService,
                                final ObjectMapper mapper,
                                @Qualifier("containerServiceThreadPoolExecutorFactoryBean")
                                    final ThreadPoolExecutorFactoryBean containerServiceThreadPoolExecutorFactoryBean) {
        this.containerControlApi = containerControlApi;
        this.containerEntityService = containerEntityService;
        this.commandResolutionService = commandResolutionService;
        this.commandService = commandService;
        this.aliasTokenService = aliasTokenService;
        this.siteConfigPreferences = siteConfigPreferences;
        this.containerFinalizeService = containerFinalizeService;
        this.xnatAppInfo = xnatAppInfo;
        this.catalogService = catalogService;
        this.orchestrationService = orchestrationService;
        this.eventService = eventService;
        this.mapper = mapper;
        this.executorService = containerServiceThreadPoolExecutorFactoryBean.getObject();

        buildCache();
    }

    private void buildCache() {
        CacheLoader<OrchestrationIdentifier, Optional<Orchestration>> loader = new CacheLoader<OrchestrationIdentifier, Optional<Orchestration>>() {
            @Override
            public Optional<Orchestration> load(@Nonnull OrchestrationIdentifier oi) {
                return Optional.ofNullable(orchestrationService.findWhereWrapperIsFirst(oi));
            }
        };
        orchestrationCache = CacheBuilder.newBuilder().expireAfterAccess(10, TimeUnit.SECONDS).build(loader);
    }

    @Override
    public PluginVersionCheck checkXnatVersion(){
        String xnatVersion = getXnatVersion();
        Boolean compatible = isVersionCompatible(xnatVersion, MIN_XNAT_VERSION_REQUIRED);
        return PluginVersionCheck.builder()
                .compatible(compatible)
                .xnatVersionDetected(xnatVersion)
                .xnatVersionRequired(MIN_XNAT_VERSION_REQUIRED)
                 .message(compatible ? null : "This version of Container Service requires XNAT " + MIN_XNAT_VERSION_REQUIRED + " or above. Some features may not function as expected.")
                .build();

    }

    private String getXnatVersion(){
        try{
            return xnatAppInfo != null ? xnatAppInfo.getVersion() : null;
        } catch (Throwable e){
            log.error("Could not detect XNAT Version.");
        }
        return null;
    }

    private Boolean isVersionCompatible(String currentVersion, String minRequiredVersion){
        // TODO need some tests for this - JF 2022-03-09
        try{
            if(Strings.isNullOrEmpty(currentVersion)){
                log.error("Unknown XNAT version.");
                return false;
            }
            log.debug("XNAT Version " + currentVersion + " found.");
            Pattern pattern = Pattern.compile("([0-9]+)[.]([0-9]+)[.]?([0-9]*)");
            Matcher reqMatcher =        pattern.matcher(minRequiredVersion);
            Matcher curMatcher =        pattern.matcher(currentVersion);
            if(reqMatcher.find() && curMatcher.find()) {
                Integer requiredMajor = Integer.valueOf(reqMatcher.group(1) != null ? reqMatcher.group(1) : "0");
                Integer requiredFeature = Integer.valueOf(reqMatcher.group(2) != null ? reqMatcher.group(2) : "0");
                Integer requiredBug = Integer.valueOf(reqMatcher.group(1) != null ? reqMatcher.group(3) : "0");

                Integer currentMajor = Integer.valueOf(curMatcher.group(1) != null ? curMatcher.group(1) : "0");
                Integer currentFeature = Integer.valueOf(curMatcher.group(2) != null ? curMatcher.group(2) : "0");
                Integer currentBug = Integer.valueOf(curMatcher.group(1) != null ? curMatcher.group(3) : "0");

                if (currentMajor < requiredMajor) {
                    log.error("Required XNAT Version: " + minRequiredVersion + "+.  Found XNAT Version: " + currentVersion + ".");
                    return false;
                } else if (currentMajor > requiredMajor) {
                    return true;
                } else {
                    if (currentFeature < requiredFeature) {
                        log.error("Required XNAT Version: " + minRequiredVersion + "+.  Found XNAT Version: " + currentVersion + ".");
                        return false;
                    } else if (currentFeature > requiredFeature) {
                        return true;
                    } else {
                        if (currentBug < requiredBug) {
                            log.error("Required XNAT Version: " + minRequiredVersion + "+.  Found XNAT Version: " + currentVersion + ".");
                            return false;
                        } else {
                            return true;
                        }
                    }
                }
            } else {
                log.debug("This is a non-numbered version of XNAT or CS plugin. Skipping compatibility check.");
                return true;
            }
        } catch (Throwable e){
            e.printStackTrace();
        }
        log.error("Failed to parse current (" + currentVersion + ") or required (" + minRequiredVersion + ") version tags.");
        return false;
    }

    @Override
    public List<Container> getAll() {
        return toPojo(containerEntityService.getAll());
    }

    @Override
    @Nullable
    public Container retrieve(final String containerId) {
        final ContainerEntity containerEntity = containerEntityService.retrieve(containerId);
        return containerEntity == null ? null : toPojo(containerEntity);
    }

    @Override
    @Nullable
    public Container retrieve(final long id) {
        final ContainerEntity containerEntity = containerEntityService.retrieve(id);
        return containerEntity == null ? null : toPojo(containerEntity);
    }

    @Override
    @Nonnull
    public Container get(final long id) throws NotFoundException {
        return toPojo(containerEntityService.get(id));
    }

    @Override
    @Nonnull
    public Container get(final String containerId) throws NotFoundException {
        return toPojo(containerEntityService.get(containerId));
    }

    @Override
    public void delete(final long id) {
        containerEntityService.delete(id);
    }

    @Override
    public void delete(final String containerId) {
        containerEntityService.delete(containerId);
    }

    @Override
    public void update(final Container container) {
        containerEntityService.update(fromPojo(container));
    }

    @Override
    public List<Container> getAll(final Boolean nonfinalized, final String project) {
        return toPojo(containerEntityService.getAll(nonfinalized, project));
    }

    @Override
    public List<Container> getAll(final String project) {
        return getAll(null, project);
    }

    @Override
    public List<Container> getAll(final Boolean nonfinalized) {
        return toPojo(containerEntityService.getAll(nonfinalized));
    }

    @Override
    public Container getByName(String project, String name, Boolean nonfinalized) {
        List<Container> all = getAll(nonfinalized, project);
        for(Container container : all){
            if (container.containerName() != null && container.containerName().contentEquals(name)){
                return container;
            }
        }
        return null;
    }

    @Override
    public Container getByName(String name, Boolean nonfinalized) {
        List<Container> all = getAll(nonfinalized);
        for(Container container : all){
            if (container.containerName() != null && container.containerName().contentEquals(name)){
                return container;
            }
        }
        return null;
    }


    @Override
    public List<Container> getPaginated(ContainerPaginatedRequest containerPaginatedRequest) {
        return toPojo(containerEntityService.getPaginated(containerPaginatedRequest));
    }

    @Override
    @Nonnull
    public List<Container> retrieveServices() {
        return toPojo(containerEntityService.retrieveServices());
    }

    @Override
    @Nonnull
    public List<Container> retrieveNonfinalizedServices() {
        return toPojo(containerEntityService.retrieveNonfinalizedServices());
    }

    @Nullable
    private List<WrkWorkflowdata> getContainerWorkflowsByStatus(String status, UserI user) {
        final CriteriaCollection cc = new CriteriaCollection("AND");
        cc.addClause("wrk:workFlowData.justification", containerLaunchJustification);
        cc.addClause("wrk:workFlowData.status", status);
        List<WrkWorkflowdata> workflows = WrkWorkflowdata.getWrkWorkflowdatasByField(cc, user, false);
        if (workflows == null || workflows.size() == 0) {
            log.info("No containers are in {} state", status);
            return null;
        }
        return workflows;
    }

    @Nullable
    private List<WrkWorkflowdata> getContainerWorkflowsByStatuses(List<String> statuses, UserI user) {
        final CriteriaCollection cc = new CriteriaCollection("AND");
        cc.addClause("wrk:workFlowData.justification", containerLaunchJustification);
        final CriteriaCollection cco = new CriteriaCollection("OR");
        for (String status : statuses) {
            cco.addClause("wrk:workFlowData.status", status);
        }
        cc.add(cco);
        List<WrkWorkflowdata> workflows = WrkWorkflowdata.getWrkWorkflowdatasByField(cc, user, false);
        if (workflows == null || workflows.size() == 0) {
            log.info("No containers are in {} state", statuses);
            return null;
        }
        return workflows;
    }

    private long getTimeSinceWorkflowMod(final WrkWorkflowdata wrk)
            throws ElementNotFoundException, FieldNotFoundException, XFTInitException, ParseException {
        Date now = new Date();
        Date modTime = wrk.getItem().getMeta().getDateProperty("last_modified");
        return (now.getTime() - modTime.getTime()) / (60 * 60 * 1000 * 24);
    }

    @Override
    public void checkQueuedContainerJobs(UserI user) {
        List<WrkWorkflowdata> workflows = getContainerWorkflowsByStatus(
                ContainerRequest.inQueueStatusPrefix + PersistentWorkflowUtils.QUEUED, user);
        if (workflows == null) return;
        for (final WrkWorkflowdata wrk : workflows) {
            try {
                long diffHours = getTimeSinceWorkflowMod(wrk);
                log.trace("Checking workflow {}", wrk.getWorkflowId());
                if (diffHours < 5 || QueueUtils.count(ContainerStagingRequest.destination) > 0) {
                    continue;
                }
                // TODO ultimately we should re-queue this, but for now just fail it
                log.info("Failing container workflow wfid {} because it was queued for more than 5 hours " +
                        "and nothing remains in the staging queue", wrk.getWorkflowId());
                updateWorkflow(wrk, PersistentWorkflowUtils.FAILED + " (Queue)",
                        "Workflow queued for more than 5 hours, needs relaunch");
            } catch (XFTInitException | ElementNotFoundException | FieldNotFoundException | ParseException e) {
                log.error("Unable to determine mod time for wfid {}", wrk.getWorkflowId());
            }

            containerFinalizeService.sendContainerStatusUpdateEmail(user, false, wrk.getPipelineName(),
                    wrk.getId(), null, wrk.getExternalid(), null);
        }
    }

    @Override
    public void checkWaitingContainerJobs(UserI user) {
        List<WrkWorkflowdata> workflows = getContainerWorkflowsByStatus(ContainerRequest.inQueueStatusPrefix +
                WAITING, user);
        if (workflows == null) return;
        for (final WrkWorkflowdata wrk : workflows) {
            String containerId = null;
            try {
                long diffHours = getTimeSinceWorkflowMod(wrk);
                if (diffHours < 5 || QueueUtils.count(ContainerFinalizingRequest.destination) > 0) {
                    continue;
                }
                containerId = wrk.getComments();
                Container containerOrService = get(containerId);
                log.info("Re-queuing waiting container workflow wfid {} containerId {}", wrk.getWorkflowId(),
                        containerId);
                addContainerHistoryItem(containerOrService, ContainerHistory.fromSystem(WAITING,
                        "Reset status from " + wrk.getStatus() + " to " + WAITING), user);
            } catch (XFTInitException | ElementNotFoundException| FieldNotFoundException | ParseException e) {
                log.error("Unable to determine mod time for wfid {}", wrk.getWorkflowId());
            } catch (NotFoundException e) {
                log.error("Unable to find container with service or container id {}", containerId);
            }
        }
    }

    @Override
    @Nonnull
    public List<Container> retrieveSetupContainersForParent(final long parentId) {
        return toPojo(containerEntityService.retrieveSetupContainersForParent(parentId));
    }

    @Override
    @Nonnull
    public List<Container> retrieveWrapupContainersForParent(final long parentId) {
        return toPojo(containerEntityService.retrieveWrapupContainersForParent(parentId));
    }

    @Override
    @Nullable
    public Container addContainerEventToHistory(final ContainerEvent containerEvent, final UserI userI) {
        final ContainerEntity containerEntity = containerEntityService.addContainerEventToHistory(containerEvent, userI);
        return containerEntity == null ? null : toPojo(containerEntity);
    }

    @Override
    @Nullable
    public ContainerHistory addContainerHistoryItem(final Container container, final ContainerHistory history, final UserI userI) {
        final ContainerEntityHistory containerEntityHistoryItem = containerEntityService.addContainerHistoryItem(fromPojo(container),
                fromPojo(history), userI);
        return containerEntityHistoryItem == null ? null : ContainerHistory.create(containerEntityHistoryItem);
    }

    @Override
    public void queueResolveCommandAndLaunchContainer(@Nullable String project,
                                                      final long wrapperId,
                                                      final long commandId,
                                                      @Nullable final String wrapperName,
                                                      final Map<String, String> inputValues,
                                                      final UserI userI,
                                                      @Nullable PersistentWorkflowI workflow) {

        // Workflow shouldn't be null unless container launched without a root element
        // (I think the only way to do so would be through the REST API)
        String workflowid = null;
        if (workflow != null) {
            workflowid = workflow.getWorkflowId().toString();
            if (project == null) {
                project = XnatProjectdata.SCHEMA_ELEMENT_NAME.equals(workflow.getDataType())
                        ? workflow.getId()
                        : workflow.getExternalid();
            }
        }

        ContainerStagingRequest request = new ContainerStagingRequest(project, wrapperId, commandId, wrapperName,
                inputValues, userI.getLogin(), workflowid);

        String count = "[not computed]";
        if (log.isTraceEnabled()) {
            count = Integer.toString(QueueUtils.count(request.getDestination()));
        }
        log.debug("Adding to staging queue: count {}, project {}, wrapperId {}, commandId {}, wrapperName {}, " +
                        "inputValues {}, username {}, workflowId {}", count, request.getProject(),
                request.getWrapperId(), request.getCommandId(), request.getWrapperName(),
                request.getInputValues(), request.getUsername(), request.getWorkflowid());

        try {
            updateWorkflow(workflow, ContainerRequest.inQueueStatusPrefix + PersistentWorkflowUtils.QUEUED);
            XDAT.sendJmsRequest(request);
        } catch (Exception e) {
            handleFailure(workflow, e, "JMS");
            String pipelineName = workflow != null ? workflow.getPipelineName() : "Unknown";
            String xnatId = workflow != null ? workflow.getId() : "Unknown";
            String wfProject = workflow != null ? workflow.getExternalid() : "Unknown";
            containerFinalizeService.sendContainerStatusUpdateEmail(userI, false, pipelineName,
                    xnatId, null, StringUtils.defaultIfBlank(project, wfProject), null);
        }
    }

    @Override
    public void consumeResolveCommandAndLaunchContainer(@Nullable final String project,
                                                        final long wrapperId,
                                                        final long commandId,
                                                        @Nullable final String wrapperName,
                                                        final Map<String, String> inputValues,
                                                        final UserI userI,
                                                        @Nullable final String workflowid) {

        log.debug("consumeResolveCommandAndLaunchContainer wfid {}", workflowid);

        PersistentWorkflowI workflow = null;
        if (workflowid != null) {
            workflow = WorkflowUtils.getUniqueWorkflow(userI, workflowid);
            updateWorkflow(workflow, STAGING, "Command resolution");
        }

        try {
            log.debug("Configuring command for wfid {}", workflowid);
            ConfiguredCommand configuredCommand = commandService.getAndConfigure(project, commandId, wrapperName, wrapperId);

            log.debug("Resolving command for wfid {}", workflowid);
            ResolvedCommand resolvedCommand = commandResolutionService.resolve(configuredCommand, inputValues, project, userI);

            // Launch resolvedCommand
            log.debug("Launching command for wfid {}", workflowid);
            Container container = launchResolvedCommand(resolvedCommand, userI, workflow);
            if (log.isInfoEnabled()) {
                CommandWrapper wrapper = configuredCommand.wrapper();
                log.info("Launched command for wfid {}: command {}, wrapper {} {}. Produced container {}.", workflowid,
                        configuredCommand.id(), wrapper.id(), wrapper.name(), container.databaseId());
                log.debug("Container for wfid {}: {}", workflowid, container);
            }
        } catch (NotFoundException | CommandResolutionException | UnauthorizedException e) {
            handleFailure(workflow, e, "Command resolution");
            log.error("Container command resolution failed for wfid {}.", workflowid, e);
        } catch (NoDockerServerException | DockerServerException | ContainerException | UnsupportedOperationException e) {
            handleFailure(workflow, e, "Container launch");
            log.error("Container launch failed for wfid {}.", workflowid, e);
        } catch (Exception e) {
            handleFailure(workflow, e, "Staging");
            log.error("consumeResolveCommandAndLaunchContainer failed for wfid {}.", workflowid, e);
        }
    }

    @Override
    @Nonnull
    public Container launchResolvedCommand(final ResolvedCommand resolvedCommand,
                                           final UserI userI,
                                           @Nullable PersistentWorkflowI workflow)
            throws NoDockerServerException, DockerServerException, ContainerException, UnsupportedOperationException {
        return launchResolvedCommand(resolvedCommand, userI, workflow,null);
    }

    @Nonnull
    private Container launchResolvedCommand(final ResolvedCommand resolvedCommand,
                                            final UserI userI,
                                            @Nullable PersistentWorkflowI workflow,
                                            @Nullable final Container parent)
            throws NoDockerServerException, DockerServerException, ContainerException, UnsupportedOperationException {
        if (resolvedCommand.type().equals(DOCKER.getName()) ||
                resolvedCommand.type().equals(DOCKER_SETUP.getName()) ||
                resolvedCommand.type().equals(DOCKER_WRAPUP.getName())) {
            // In the future we will re-work the launch* api methods to throw new-style exceptions
            // For now we catch the new-style exceptions here and rethrow them as old-style
            try {
                return launchResolvedDockerCommand(resolvedCommand, userI, workflow, parent);
            } catch (NoContainerServerException e) {
                throw (e instanceof NoDockerServerException) ? (NoDockerServerException) e :
                        new NoDockerServerException(e.getMessage(), e.getCause());
            } catch (ContainerBackendException e) {
                throw (e instanceof DockerServerException) ? (DockerServerException) e :
                        new DockerServerException(e.getMessage(), e.getCause());
            }
        } else {
            throw new UnsupportedOperationException("Cannot launch a command of type " + resolvedCommand.type());
        }
    }

    private Container launchResolvedDockerCommand(final ResolvedCommand resolvedCommand,
                                                  final UserI userI,
                                                  @Nullable PersistentWorkflowI workflow,
                                                  @Nullable final Container parent)
            throws NoContainerServerException, ContainerBackendException, ContainerException {

        log.info("Preparing to launch resolved command.");
        final ResolvedCommand preparedToLaunch = prepareToLaunch(resolvedCommand, parent, userI, workflow);

        if (resolvedCommand.type().equals(DOCKER_SETUP.getName()) && workflow != null) {
            // Create a new workflow for setup (wrapup doesn't come through this method, gets its workflow
            // in createWrapupContainerInDbFromResolvedCommand)
            workflow = createContainerWorkflow(workflow.getId(), workflow.getDataType(),
                    workflow.getPipelineName() + "-setup", workflow.getExternalid(), userI);
        }

        // Update workflow with resolved command (or try to create it if null)
        workflow = updateWorkflowWithResolvedCommand(workflow, resolvedCommand, userI);

		try {
            log.info("Creating container from resolved command.");
        	final Container created = containerControlApi.create(preparedToLaunch, userI);

            if (workflow != null) {
                // Update workflow with container information
                log.info("Recording container launch.");
                updateWorkflowWithContainer(workflow, created);
            }

            // Save container in db.
			final Container saved = toPojo(containerEntityService.save(fromPojo(
	                created.toBuilder()
	                        .workflowId(workflow != null ? workflow.getWorkflowId().toString() : null)
	                        .parent(parent)
	                        .build()
	        ), userI));
	
	        if (resolvedCommand.wrapupCommands().size() > 0) {
	            // Save wrapup containers in db
	            log.info("Creating wrapup container objects in database (not creating docker containers).");
	            for (final ResolvedCommand resolvedWrapupCommand : resolvedCommand.wrapupCommands()) {
	                final Container wrapupContainer = createWrapupContainerInDbFromResolvedCommand(resolvedWrapupCommand,
                            saved, userI, workflow);
	                log.debug("Created wrapup container {} for parent container {}.", wrapupContainer.databaseId(),
                            saved.databaseId());
	            }
	        }
	
	        if (resolvedCommand.setupCommands().size() > 0) {
	            log.info("Launching setup containers.");
	            for (final ResolvedCommand resolvedSetupCommand : resolvedCommand.setupCommands()) {
	                launchResolvedCommand(resolvedSetupCommand, userI, workflow, saved);
	            }
	        } else {
	            start(userI, saved);
	        }
	
	        return saved;
        } catch (Exception e) {
        	handleFailure(workflow, e);
        	throw e;
        }
    }

    private void start(final UserI userI, final Container toStart) throws NoContainerServerException, ContainerException {
        log.info("Starting container.");
        try {
            containerControlApi.start(toStart);
        } catch (ContainerBackendException e) {
            addContainerHistoryItem(toStart, ContainerHistory.fromSystem(PersistentWorkflowUtils.FAILED, "Did not start." + e.getMessage()), userI);
            handleFailure(userI, toStart);
            throw new ContainerException("Failed to start");
        }
    }

    @Nonnull
    private Container createWrapupContainerInDbFromResolvedCommand(final ResolvedCommand resolvedCommand, final Container parent,
                                                                   final UserI userI, PersistentWorkflowI parentWorkflow) {

        PersistentWorkflowI workflow = null;
        if (parentWorkflow != null) {
            workflow = createContainerWorkflow(parentWorkflow.getId(), parentWorkflow.getDataType(),
                    parentWorkflow.getPipelineName() + "-wrapup", parentWorkflow.getExternalid(), userI);
        }
        String workflowid = workflow == null ? null : workflow.getWorkflowId().toString();

        List<String> swarmConstraints;
        if (parent != null) {
            swarmConstraints = parent.swarmConstraints() != null ? new ArrayList<>(parent.swarmConstraints()) : null;
        } else {
            swarmConstraints = resolvedCommand.swarmConstraints();
        }
        final Container toCreate = Container.builderFromResolvedCommand(resolvedCommand)
                .userId(userI.getLogin())
                .parent(parent)
                .workflowId(workflowid)
                .subtype(DOCKER_WRAPUP.getName())
                .project(parent != null ? parent.project() : null)
                .swarmConstraints(swarmConstraints)
                .status(CREATED) //Needs non-empty status to be picked up by containerService.retrieveNonfinalizedServices()
                .build();
        return toPojo(containerEntityService.create(fromPojo(toCreate)));
    }
    @Nonnull
    private Container launchContainerFromDbObject(final Container toLaunch, final UserI userI)
            throws ContainerBackendException, NoContainerServerException, ContainerException {
        return launchContainerFromDbObject(toLaunch, userI, false);
    }

    @Nonnull
    private Container launchContainerFromDbObject(final Container toLaunch, final UserI userI, final boolean restart)
            throws ContainerBackendException, NoContainerServerException, ContainerException {

        final String workflowId = toLaunch.workflowId();
        PersistentWorkflowI workflow = workflowId != null ? WorkflowUtils.getUniqueWorkflow(userI, workflowId) : null;

        final Container preparedToLaunch = restart ? toLaunch : prepareToLaunch(toLaunch, userI, workflow);

        log.info("Creating backend container for {} container {}.", toLaunch.subtype(), toLaunch.databaseId());
        final Container created = containerControlApi.create(preparedToLaunch, userI);

        log.info("Updating {} container {}.", toLaunch.subtype(), toLaunch.databaseId());
        containerEntityService.update(fromPojo(created));

        // Update workflow if we have one
        if (workflow != null) {
            updateWorkflowWithContainer(workflow, created);
        }

        start(userI, created);

        return created;
    }

    @Nonnull
    private ResolvedCommand prepareToLaunch(final ResolvedCommand resolvedCommand,
                                            final Container parent,
                                            final UserI userI,
                                            final PersistentWorkflowI workflow) {

        ResolvedCommand.Builder builder = resolvedCommand.toBuilder()
                .addEnvironmentVariables(getDefaultEnvironmentVariablesForLaunch(userI, workflow));

        if (parent != null) {
            if (resolvedCommand.project() == null) {
                builder.project(parent.project());
            }
            builder.swarmConstraints(parent.swarmConstraints() != null ? new ArrayList<>(parent.swarmConstraints()) : null);
        }

        return builder.build();
    }

    @Nonnull
    private Container prepareToLaunch(final Container toLaunch,
                                      final UserI userI,
                                      final PersistentWorkflowI workflow) {
        return toLaunch.toBuilder()
                .addEnvironmentVariables(getDefaultEnvironmentVariablesForLaunch(userI, workflow))
                .build();
    }

    private Map<String, String> getDefaultEnvironmentVariablesForLaunch(final UserI userI, final PersistentWorkflowI workflow) {
        final AliasToken token = aliasTokenService.issueTokenForUser(userI);
        final String processingUrl = (String)siteConfigPreferences.getProperty("processingUrl");
        final String xnatHostUrl = StringUtils.isBlank(processingUrl) ? siteConfigPreferences.getSiteUrl() : processingUrl;
        final String workflowId = workflow != null ? workflow.getWorkflowId().toString() : "";
        final String eventId = workflow != null ? workflow.buildEvent().getEventId().toString() : "";

        final Map<String, String> defaultEnvironmentVariables = new HashMap<>();
        defaultEnvironmentVariables.put("XNAT_USER", token.getAlias());
        defaultEnvironmentVariables.put("XNAT_PASS", token.getSecret());
        defaultEnvironmentVariables.put("XNAT_HOST", xnatHostUrl);
        defaultEnvironmentVariables.put("XNAT_WORKFLOW_ID", workflowId);
        defaultEnvironmentVariables.put("XNAT_EVENT_ID", eventId);

        return defaultEnvironmentVariables;
    }

    @Override
    public void processEvent(final ContainerEvent event) {
        log.debug("Processing container event: {}", event);
        final Container container = retrieve(event.backendId());

        // container will be null if either we aren't tracking the container
        // that this event is about, or if we have already recorded the event
        if (container == null) {
            log.debug("Nothing to do. Container was null after retrieving by id {}.", event.backendId());
            return;
        }

        if (isFinalizing(container)) {
            log.debug("Container {} finalizing, skipping addl event {}", container.containerOrServiceId(), event);
            return;
        }

        final String userLogin = container.userId();
        final UserI userI;
        try {
            userI = Users.getUser(userLogin);
        } catch (UserInitException | UserNotFoundException e) {
            log.error("Could not get user details for user {}. Done processing container event: {}", userLogin, event, e);
            return;
        }

        Container containerWithAddedEvent = addContainerEventToHistory(event, userI);
        if (containerWithAddedEvent == null) {
            // Ignore this issue?
            containerWithAddedEvent = container;
        }

        if (containerWithAddedEvent.backend() == Backend.KUBERNETES && event instanceof KubernetesStatusChangeEvent) {
            KubernetesStatusChangeEvent kEvent = ((KubernetesStatusChangeEvent) event);

            // Check if we need to set additional ids
            boolean shouldUpdatePodName = containerWithAddedEvent.podName() == null && kEvent.podName() != null;
            boolean shouldUpdateContainerId = containerWithAddedEvent.containerId() == null && kEvent.containerId() != null;
            if (shouldUpdatePodName || shouldUpdateContainerId) {
                Container.Builder builder = containerWithAddedEvent.toBuilder();

                if (shouldUpdatePodName) {
                    log.debug("Container {} for job {}: setting taskId to pod name {}",
                            container.databaseId(), container.jobName(), kEvent.podName()
                    );
                    builder.taskId(kEvent.podName());
                }
                if (shouldUpdateContainerId) {
                    log.debug("Container {} for job {}: setting containerId to container id {}",
                            container.databaseId(), container.jobName(), kEvent.containerId()
                    );
                    builder.containerId(kEvent.containerId());
                }
                containerEntityService.update(fromPojo(builder.build()));
                containerWithAddedEvent = retrieve(container.databaseId());
            }
        }

        if (event.isExitStatus()) {
            log.debug("Container is dead. Finalizing.");

            queueFinalize(event.exitCode(),
                    ContainerUtils.statusIsSuccessful(containerWithAddedEvent.status(), containerWithAddedEvent.backend()),
                    containerWithAddedEvent, userI);
        }

        log.debug("Done processing container event: {}", event);
    }

    @Override
    public void processEvent(final ServiceTaskEvent event) {
        final ServiceTask task = event.task();
        Container service = event.service();

	    log.debug("Processing service task event for service \"{}\" status \"{}\" exit code {}.",
                task.serviceId(), task.status(), task.exitCode());

        if (service == null) {
            log.error("Could not find service corresponding to event {}", event);
            return;
        }

        if (StringUtils.isBlank(service.taskId()) || StringUtils.isBlank(service.nodeId()) || StringUtils.isBlank(service.containerId())) {
            // When we create the service, we don't know all the IDs.
            // We may be able to update some or all of them now.
            final Container.Builder serviceToUpdateBuilder = service.toBuilder();
            boolean shouldUpdate = false;

            if (StringUtils.isBlank(service.taskId()) && StringUtils.isNotBlank(task.taskId())) {
                log.debug("Service {} \"{}\" setting task ID to \"{}\".",
                        service.databaseId(), service.serviceId(), task.taskId());
                serviceToUpdateBuilder.taskId(task.taskId());
                shouldUpdate = true;
            }
            if (StringUtils.isBlank(service.nodeId()) && StringUtils.isNotBlank(task.nodeId())) {
                log.debug("Service {} \"{}\" setting node ID to \"{}\".",
                        service.databaseId(), service.serviceId(), task.nodeId());
                serviceToUpdateBuilder.nodeId(task.nodeId());
                shouldUpdate = true;
            }
            if (StringUtils.isBlank(service.containerId()) && StringUtils.isNotBlank(task.containerId())) {
                log.debug("Service {} \"{}\" setting container ID to \"{}\".",
                        service.databaseId(), service.serviceId(), task.containerId());
                serviceToUpdateBuilder.containerId(task.containerId());
                shouldUpdate = true;
            }
            if (shouldUpdate) {
                containerEntityService.update(fromPojo(serviceToUpdateBuilder.build()));
                service = retrieve(service.databaseId());
            }
        }

        // The service won't be null here even though the IDE thinks it might be
        if (isFinalizing(service)) {
            log.error("Service is already finalizing. service \"{}\" status \"{}\" exit code {}.",
                    task.serviceId(), task.status(), task.exitCode());
            return;
        }

        final String userLogin = service.userId();
        try {
            final UserI userI = Users.getUser(userLogin);
            final ContainerHistory taskHistoryItem = ContainerHistory.fromServiceTask(task);

            // Process new and waiting events (duplicate docker events are skipped)
            if (!isWaiting(service) && addContainerHistoryItem(service, taskHistoryItem, userI) == null) {
                // We have already added this task and can safely skip it.
                log.debug("Skipping task status we have already seen: service \"{}\" status \"{}\" exit code {}.",
                        task.serviceId(), task.status(), task.exitCode());
            } else {
                log.debug("Checking service \"{}\" status \"{}\" exit code {}.",
                        task.serviceId(), task.status(), task.exitCode());

                if (!isWaiting(service) && task.swarmNodeError()) {
                    // Attempt to restart the service and fail the workflow if we cannot;
                    // either way, don't proceed to finalize.
                    restartService(service, userI);
                    return;
                }

                if (isWaiting(service) || task.isExitStatus()) {
                    final String exitCodeString = task.exitCode() == null ? null : String.valueOf(task.exitCode());
                    final Container serviceWithAddedEvent = retrieve(service.databaseId());

                    if (serviceWithAddedEvent == null) {
                        // Shouldn't ever happen
                        log.error("Could not retrieve updated service {}", service);
                        return;
                    }
                    log.debug("Can finalize service \"{}\" status \"{}\" exit code {}.",
                            task.serviceId(), task.status(), task.exitCode());
                    queueFinalize(exitCodeString, task.isSuccessfulStatus(), serviceWithAddedEvent, userI);
                } else {
                    log.debug("Service is still running. service \"{}\" status \"{}\" exit code {}.",
                            task.serviceId(), task.status(), task.exitCode());
                }
            }
        } catch (UserInitException | UserNotFoundException e) {
            log.error("Could not update container status. Could not get user details for user {}", userLogin, e);
        }

        log.debug("Done processing service task event for service \"{}\" status \"{}\" exit code {}.",
                task.serviceId(), task.status(), task.exitCode());
    }

    private void doRestart(Container service, UserI userI)
            throws ContainerBackendException, NoContainerServerException, ContainerException {

        if (!service.isSwarmService()) {
            throw new ContainerException("Cannot restart non-swarm container");
        }

        String serviceId = service.serviceId();
        String restartMessage = "Restarting serviceId "+ serviceId + " due to apparent swarm node error " +
                "(likely node " + service.nodeId() + " went down)";

        // Rebuild service, emptying ids (serviceId = null keeps it from being updated until a new service is assigned),
        // and save it to db
        service = service.toBuilder()
                .serviceId(null)
                .taskId(null)
                .containerId(null)
                .nodeId(null)
                .build();
        containerEntityService.update(fromPojo(service));

        // Log the restart history
        ContainerHistory restartHistory = ContainerHistory.fromSystem(ContainerHistory.restartStatus,
                restartMessage);
        addContainerHistoryItem(service, restartHistory, userI);

        // Relaunch container in new service
        launchContainerFromDbObject(service, userI, true);
    }

    @Override
    public boolean restartService(Container service) {
        try {
            return restartService(service, Users.getUser(service.userId()));
        } catch (UserNotFoundException | UserInitException e) {
            log.error("Could not get user from service {} \"{}\" userId \"{}\"",
                    service.databaseId(), service.serviceId(), service.userId(), e);
        }
        return false;
    }

    @Override
    public boolean restartService(Container service, UserI userI) {
        final int maxRestarts = 5;
        int nrun = maxRestarts + 1;

        log.info("Restarting service {} \"{}\"", service.databaseId(), service.serviceId());

        if (!service.isSwarmService()) {
            // Refuse to restart a non-swarm container
            return false;
        }

        // Remove the errant service
        try {
            containerControlApi.remove(service);
        } catch (ContainerBackendException | NotFoundException | NoContainerServerException e) {
            log.info("Ignoring exceptions, continuing restart for service {} \"{}\"",
                    service.databaseId(), service.serviceId());
            // It may already be gone
        }

        String failureMessage = "Service not found on swarm OR state='shutdown' OR " +
                "apparently active current state with exit status of -1 or desired state='shutdown' occurred " +
                "in all " + nrun + " attempts)";
        // Node killed or something, try to restart
        if (service.countRestarts() < maxRestarts) {
            try {
                doRestart(service, userI);
                log.debug("Restarted service {} \"{}\"", service.databaseId(), service.serviceId());
                return true;
            } catch (Exception e) {
                log.error("Unable to restart service {} \"{}\"", service.databaseId(), service.serviceId(), e);
                failureMessage = "Unable to restart";
            }
        }

        // Already restarted or unable to restart, fail it
        final Date now = new Date();
        final ContainerHistory newHistoryItem = ContainerHistory.builder()
                .entityType("service")
                .entityId(null)
                .status(TaskStatus.TASK_STATE_FAILED)
                .exitCode("126")
                .timeRecorded(now)
                .externalTimestamp(String.valueOf(now.getTime()))
                .message(ServiceTask.swarmNodeErrMsg + ": " + failureMessage)
                .build();
        addContainerHistoryItem(service, newHistoryItem, userI);

        // Update workflow again so we get the "Failed (Swarm)" status
        ContainerUtils.updateWorkflowStatus(service.workflowId(), PersistentWorkflowUtils.FAILED + " (Swarm)",
                userI, newHistoryItem.message());

        return false;
    }

    @Override
	public void queueFinalize(final String exitCodeString, final boolean isSuccessfulStatus,
                              final Container containerOrService, final UserI userI) {

		ContainerFinalizingRequest request = new ContainerFinalizingRequest(exitCodeString, isSuccessfulStatus,
                containerOrService.containerOrServiceId(), userI.getLogin());

		Integer count = null;
        if (log.isDebugEnabled()){
            count = QueueUtils.count(request.getDestination());
        }

		if (!request.inJMSQueue(getWorkflowStatus(userI, containerOrService))) {
            Integer limit = containerControlApi.getFinalizingThrottle();
            if (canFinalize(limit, userI, containerOrService, request)) {
                log.debug("Added to finalizing queue: count {}, exitcode {}, issuccessfull {}, id {}, username {}, status {}",
                        count, request.getExitCodeString(), request.isSuccessful(), request.getId(), request.getUsername(),
                        containerOrService.status());
                try {
                    XDAT.sendJmsRequest(request);
                } catch (Exception e) {
                    recoverFromQueueingFailureFinalizing(e, containerOrService, userI);
                }
            } else {
                log.debug("Throttling finalizing queue container id {} must wait", request.getId());
            }
		} else {
			log.debug("Already in finalizing queue: count {}, exitcode {}, issuccessfull {}, id {}, username {}, status {}",
                    count, request.getExitCodeString(), request.isSuccessful(), request.getId(), request.getUsername(),
                    containerOrService.status());
		}
	}

    private synchronized boolean canFinalize(Integer limit, UserI user, Container containerOrService,
                                             ContainerFinalizingRequest request) {
        List<WrkWorkflowdata> wfs = getContainerWorkflowsByStatuses(Arrays.asList(FINALIZING, _WAITING), user);
        boolean canFinalize = limit == null || wfs == null || wfs.size() < limit;
        if (canFinalize) {
            addContainerHistoryItem(containerOrService, ContainerHistory.fromSystem(
                    request.makeJMSQueuedStatus(containerOrService.status()), "Queued for finalizing"),
                    user);
        }
        return canFinalize;
    }

    private void recoverFromQueueingFailureFinalizing(Exception e,
                                                      final Container containerOrService,
                                                      final UserI userI) {

        final Container.ContainerHistory failedHistoryItem = Container.ContainerHistory
                .fromSystem(PersistentWorkflowUtils.FAILED + " (JMS)", e.getMessage());
        addContainerHistoryItem(containerOrService, failedHistoryItem, userI);
        cleanupContainers(containerOrService);

        // email user
        PersistentWorkflowI workflow = getContainerWorkflow(userI, containerOrService);
        String pipelineName;
        String xnatId;
        String project;
        if (workflow != null) {
            pipelineName = workflow.getPipelineName();
            xnatId = workflow.getId();
            project = workflow.getExternalid();
        } else {
            xnatId = "Unknown";
            project = "Unknown";
            try {
                pipelineName = commandService.get(containerOrService.wrapperId()).name();
            } catch (Exception x) {
                pipelineName = "Unknown";
            }
        }

        containerFinalizeService.sendContainerStatusUpdateEmail(userI, false, pipelineName,
                xnatId, null, project, null);
    }
	
    @Override
	public void consumeFinalize(final String exitCodeString, final boolean isSuccessfulStatus,
                                final Container container, final UserI userI)
            throws ContainerException, NotFoundException {
        try {
            addContainerHistoryItem(container, ContainerHistory.fromSystem(FINALIZING,
                    "Processing finished. Uploading files."), userI);
            log.debug("Finalizing containerOrService {}", container);
            final Container containerOrServiceWithAddedEvent = get(container.databaseId());
                ContainerServiceImpl.this.finalize(containerOrServiceWithAddedEvent, userI, exitCodeString,
                        isSuccessfulStatus);

            if (log.isDebugEnabled()) {
                int countOfContainersWaiting = containerEntityService.howManyContainersAreWaiting();
                int countOfContainersBeingFinalized = containerEntityService.howManyContainersAreBeingFinalized();
                log.debug("There are {} being finalized at present with {} waiting", countOfContainersBeingFinalized,
                        countOfContainersWaiting);
            }
        } catch (Exception e) {
            log.error("Finalization failed on container {}", container, e);
            throw e;
        }
	}

    @Override
    public boolean isWaiting(Container containerOrService){
        String status = containerOrService.status();
    	return status != null && status.startsWith(WAITING);
    }

    @Override
    public boolean isFinalizing(Container containerOrService){
        String status = containerOrService.status();
    	return status != null && (status.startsWith(ContainerRequest.inQueueStatusPrefix + WAITING) || status.equals(FINALIZING));
    }

    @Override
    public boolean containerStatusIsTerminal(Container containerOrService){
        return containerOrService.statusIsTerminal();
    }

    /**
     * If workflow thinks the container is "done" (failed, complete, whatever), but container thinks it is active,
     * update container status to match workflow
     *
     * @param containerOrService the container
     * @param user the user
     * @return true if status modified
     */
    @Override
    public boolean fixWorkflowContainerStatusMismatch(Container containerOrService, UserI user) {
        final String status = containerOrService.getWorkflowStatus(user);
        // if null or not a terminal workflow status, don't change
        if (status == null ||
                (!status.startsWith(PersistentWorkflowUtils.FAILED) && !status.startsWith(PersistentWorkflowUtils.COMPLETE))) {
            return false;
        }
        if (status.equals(containerOrService.status()) || containerStatusIsTerminal(containerOrService)) {
            // statuses are the same or at least both terminal
            return false;
        }

        // container still thinks it is active, but workflow is terminal
        try {
            killWithoutHistory(containerOrService);
        } catch (NoContainerServerException | ContainerBackendException | NotFoundException e) {
            log.error("Attempted to kill container {} due to workflow in status {}",
                    containerOrService.containerOrServiceId(), status, e);
        }
        log.info("Setting container {} status to \"{}\" to match workflow.", containerOrService.databaseId(), status);
        ContainerHistory failureHist = ContainerHistory.fromSystem(status,
                "Manual update to match workflow status");
        addContainerHistoryItem(containerOrService, failureHist, user);
        return true;
    }


    @Override
    public void finalize(final String containerId, final UserI userI)
            throws NotFoundException, ContainerException {
        finalize(get(containerId), userI);
    }

    @Override
    public void finalize(final Container container, final UserI userI)
            throws ContainerException {
        String status = container.lastHistoryStatus();
        boolean isSuccessfulStatus = status == null || status.equals(FINALIZING) ||
                ContainerUtils.statusIsSuccessful(status, container.backend());
        finalize(container, userI, container.exitCode(), isSuccessfulStatus);
    }

    @Override
    public void finalize(final Container notFinalized, final UserI userI, final String exitCode, boolean isSuccessfulStatus)
            throws ContainerException {
        final long databaseId = notFinalized.databaseId();
        log.debug("Beginning finalization for container {}.", databaseId);
        final boolean failed = exitCodeIsFailed(exitCode) || !isSuccessfulStatus;

        // Check if this container is the parent to any wrapup containers that haven't been launched.
        // If we find any, launch them.
        boolean launchedWrapupContainers = false;
        final List<Container> wrapupContainers = retrieveWrapupContainersForParent(databaseId);
        if (wrapupContainers.size() > 0) {
            log.debug("Container {} is parent to {} wrapup containers.", databaseId, wrapupContainers.size());
            // Have these wrapup containers already been launched?
            // If they have container or service IDs, then we know they have been launched.
            // If they have been launched, we assume they have also been completed. That's how we get back here.
            for (final Container wrapupContainer : wrapupContainers) {
                if (StringUtils.isBlank(wrapupContainer.containerId()) && StringUtils.isBlank(wrapupContainer.serviceId())) {
                    if (failed) {
                        // Don't launch them, just fail them
                        String status = PersistentWorkflowUtils.FAILED + " (Parent)";
                        log.info("Setting wrapup container {} status to \"{}\".", wrapupContainer.databaseId(), status);
                        ContainerHistory failureHist = ContainerHistory.fromSystem(status,
                                "Parent container failed (exit code=" + exitCode + ")");
                        addContainerHistoryItem(wrapupContainer, failureHist, userI);
                    } else {
                        log.debug("Launching wrapup container {}.", wrapupContainer.databaseId());
                        // This wrapup container has not been launched yet. Launch it now.
                        try {
                            launchContainerFromDbObject(wrapupContainer, userI);
                            launchedWrapupContainers = true;
                        } catch (ContainerBackendException | NoContainerServerException | ContainerException e) {
                            log.error("Launching wrapup container {} failed", wrapupContainer, e);
                            //finalize to kill any other wrapup containers, set parent status to failed, and email user
                            ContainerHistory failureHist = ContainerHistory.fromSystem(PersistentWorkflowUtils.FAILED,
                                    "Unable to launch: " + e.getMessage(), "1");
                            addContainerHistoryItem(wrapupContainer, failureHist, userI);
                            finalize(wrapupContainer, userI, "1", false);
                            return;
                        }
                    }
                }
            }
        }

        if (launchedWrapupContainers) {
            log.debug("Pausing finalization for container {} to wait for wrapup containers to finish.", databaseId);
            return;
        } else if (wrapupContainers.size() > 0) {
            log.debug("All wrapup containers are complete.");
        }  // Nothing to log in the else clause where there aren't any wrapup containers

        // Once we are sure there are no wrapup containers left to launch, finalize
        final String containerOrService = notFinalized.isSwarmService() ? "service" : "container";
        final String containerOrServiceId = notFinalized.containerOrServiceId();
        log.info("Finalizing Container {}, {} id {}.", databaseId, containerOrService, containerOrServiceId);

        final Container finalized = containerFinalizeService.finalizeContainer(notFinalized, userI,
                failed, wrapupContainers);

        log.debug("Done uploading files for container {}. Now saving information about outputs.", finalized);

        containerEntityService.update(fromPojo(finalized));

        // Now check if this container *is* a setup or wrapup container.
        // If so, we need to re-check the parent.
        // If this is a setup container, parent can (maybe) be launched.
        // If this is a wrapup container, parent can (maybe) be finalized.
        final Container parent = finalized.parent();
        if (parent == null) {
            // Nothing left to do. This container is done.
            log.debug("Done finalizing container {}, {} id {}.", databaseId, containerOrService, containerOrServiceId);
            cleanupContainers(finalized);
            return;
        }
        final long parentDatabaseId = parent.databaseId();
        final String parentContainerId = parent.containerId();

        final String subtype = finalized.subtype();
        if (subtype == null) {
            throw new ContainerFinalizationException(finalized,
                    String.format("Can't finalize container %d. It has a non-null parent with ID %d, but a null subtype. I don't know what to do with that.", databaseId, parentDatabaseId)
            );
        }

        if (subtype.equals(DOCKER_SETUP.getName())) {
            log.debug("Container {} is a setup container for parent container {}. Checking whether parent needs a status change.", databaseId, parentDatabaseId);
            final List<Container> setupContainers = retrieveSetupContainersForParent(parentDatabaseId);
            if (setupContainers.size() > 0) {
                final Runnable startMainContainer = () -> {
                    // If none of the setup containers have failed and none of the exit codes are null,
                    // that means all the setup containers have succeeded.
                    // We should start the parent container.
                    log.info("All setup containers for parent Container {} are finished and not failed. Launching container.", parentDatabaseId);
                    try {
                        start(userI, parent);
                    } catch (NoContainerServerException | ContainerException e) {
                        log.error("Failed to start parent Container {} with container id {}.", parentDatabaseId, parentContainerId);
                    }
                };

                runActionIfNoSpecialContainersFailed(startMainContainer, setupContainers, parent, userI, setupStr);
            }
        } else if (subtype.equals(DOCKER_WRAPUP.getName())) {
            // This is a wrapup container.
            // Did this container succeed or fail?
            // If it failed, go mark all the other wrapup containers failed and also the parent.
            // If it succeeded, then finalize the parent.

            log.debug("Container {} is a wrapup container for parent container {}.", databaseId, parentDatabaseId);

            final List<Container> wrapupContainersForParent = retrieveWrapupContainersForParent(parentDatabaseId);
            if (wrapupContainersForParent.size() > 0) {
                final Runnable finalizeMainContainer = () -> {
                    // If none of the wrapup containers have failed and none of the exit codes are null,
                    // that means all the wrapup containers have succeeded.
                    // We should finalize the parent container.
                    log.info("All wrapup containers for parent Container {} are finished and not failed. Finalizing container id {}.",
                            parentDatabaseId, parentContainerId);
                    try {
                        ContainerServiceImpl.this.finalize(parent, userI);
                    } catch (ContainerException e) {
                        log.error("Failed to finalize parent Container {} with container id {}.",
                                parentDatabaseId, parentContainerId, e);
                    }
                };

                runActionIfNoSpecialContainersFailed(finalizeMainContainer, wrapupContainersForParent, parent, userI, wrapupStr);
            }
        }
    }

    private void cleanupContainers(Container finalized) {
        long databaseId = finalized.databaseId();
        List<Container> toCleanup = new ArrayList<>();
        toCleanup.add(finalized);
        toCleanup.addAll(retrieveSetupContainersForParent(databaseId));
        toCleanup.addAll(retrieveWrapupContainersForParent(databaseId));
        for (Container container : toCleanup) {
            if (StringUtils.isNotBlank(container.containerOrServiceId())) {
                try {
                    containerControlApi.autoCleanup(container);
                } catch (NoContainerServerException | ContainerBackendException | NotFoundException e) {
                    log.warn("Unable to remove service or container {}", container, e);
                }
            }
        }
    }

    private void runActionIfNoSpecialContainersFailed(final Runnable successAction,
                                                      final List<Container> specialContainers,
                                                      final Container parent,
                                                      final UserI userI,
                                                      final String setupOrWrapup) {
        final List<Container> failedExitCode = new ArrayList<>();
        final List<Container> nullExitCode = new ArrayList<>();
        for (final Container specialContainer : specialContainers) {
            if (exitCodeIsFailed(specialContainer.exitCode())) {
                failedExitCode.add(specialContainer);
            } else if (specialContainer.exitCode() == null) {
                nullExitCode.add(specialContainer);
            }
        }

        final int numSpecial = specialContainers.size();
        final int numFailed = failedExitCode.size();
        final int numNull = nullExitCode.size();

        final String failedContainerStatus = PersistentWorkflowUtils.FAILED + " (" + setupOrWrapup + ")";
        if (numFailed > 0) {
            // If any of the special containers failed, we must kill the rest and fail the main container.
            log.debug("One or more {} containers have failed. Killing the rest and failing the parent.", setupOrWrapup);
            for (final Container specialContainer : specialContainers) {
                if (failedExitCode.contains(specialContainer)) {
                    log.debug("{} container failed {}", setupOrWrapup, specialContainer);
                } else if (nullExitCode.contains(specialContainer)) {
                    log.debug("{} container has no exit code. Attempting to kill it. {}", setupOrWrapup, specialContainer);
                    try {
                        kill(specialContainer, userI);
                    } catch (NoContainerServerException | ContainerBackendException | NotFoundException e) {
                        log.error("Failed to kill {} container {}", setupOrWrapup, specialContainer.databaseId(), e);
                    }
                } else {
                    log.debug("{} container succeeded. {}", setupOrWrapup, specialContainer);
                }
            }

            final String failedContainerMessageTemplate = "db id %d, %s id %s";
            final String failedContainerMessage;
            if (failedExitCode.size() == 1) {
                final Container failed = failedExitCode.get(0);
                failedContainerMessage = "Failed " + setupOrWrapup + " container " +
                        String.format(failedContainerMessageTemplate,
                                failed.databaseId(), failed.isSwarmService() ? "service" : "container",
                                failed.isSwarmService() ? failed.serviceId() : failed.containerId());
            } else {
                final StringBuilder sb = new StringBuilder();
                sb.append("Failed ");
                sb.append(setupOrWrapup);
                sb.append("containers: ");
                sb.append(String.format(failedContainerMessageTemplate,
                        failedExitCode.get(0).databaseId(),
                        failedExitCode.get(0).isSwarmService() ? "service" : "container",
                        failedExitCode.get(0).isSwarmService() ? failedExitCode.get(0).serviceId() : failedExitCode.get(0).containerId()));
                for (int i = 1; i < failedExitCode.size(); i++) {
                    final Container failed = failedExitCode.get(i);
                    sb.append("; ");
                    sb.append(String.format(failedContainerMessageTemplate,
                            failed.databaseId(), failed.isSwarmService() ? "service" : "container",
                            failed.isSwarmService() ? failed.serviceId() : failed.containerId()));
                }
                sb.append(".");
                failedContainerMessage = sb.toString();
            }

            log.info("Setting status to \"{}\" for parent {}.", failedContainerStatus, parent.databaseId());
            ContainerHistory failureHist = ContainerHistory.fromSystem(failedContainerStatus, failedContainerMessage);
            addContainerHistoryItem(parent, failureHist, userI);

            // If specialContainers are setup containers and there are also wrapup containers, we need to update their
            // statuses in the db (since they haven't been created or started, they don't need to be killed)
            if (setupOrWrapup.equals(setupStr)) {
                final List<Container> wrapupContainersForParent = retrieveWrapupContainersForParent(parent.databaseId());
                for (Container wrapupContainer : wrapupContainersForParent) {
                    addContainerHistoryItem(wrapupContainer, failureHist, userI);
                }
            }
            cleanupContainers(parent);
        } else if (numNull == numSpecial) {
            // This is an error. We know at least one setup container has finished because we have reached this "finalize" method.
            // At least one of the setup containers should have a non-null exit status.
            final String message = "All " + setupOrWrapup + " containers have null statuses, but one of them should be finished.";
            log.error(message);
            log.info("Setting status to \"{}\" for parent container {}.", failedContainerStatus, parent.databaseId());
            addContainerHistoryItem(parent, ContainerHistory.fromSystem(failedContainerStatus, message), userI);
            cleanupContainers(parent);
        } else if (numNull > 0) {
            final int numLeft = numSpecial - numNull;
            log.debug("Not changing parent status. {} {} containers left to finish.", numLeft, setupOrWrapup);
        } else {
            successAction.run();
        }
    }

    public boolean canKill(String containerId, UserI userI) {
        try {
            Container containerOrService = get(containerId);
            verifyKillPermission(null, containerOrService, userI);
            // if verifyKillPermission doesn't throw UnauthorizedException, user has permission to kill
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void verifyKillPermission(@Nullable String project, Container containerOrService, UserI userI)
            throws UnauthorizedException {
        if (!(userI.getLogin().equals(containerOrService.userId()) || Groups.hasAllDataAdmin(userI))) {
            try {
                project = StringUtils.firstNonBlank(containerOrService.project(), project);
                if (!Permissions.isProjectOwner(userI, project)) {
                    throw new UnauthorizedException("User cannot terminate this container or service");
                }
            } catch (Exception e) {
                throw new UnauthorizedException("Unable to determine user permissions", e);
            }
        }
    }

    @Override
    public String kill(final String containerId, final UserI userI)
            throws NoDockerServerException, DockerServerException, NotFoundException, UnauthorizedException {
        return kill(null, containerId, userI);
    }

    @Override
    public String kill(@Nullable String project, String containerId, UserI userI)
            throws NoDockerServerException, DockerServerException, NotFoundException, UnauthorizedException {
        // User who launched the container, all data admins, and project owners can terminate
        Container containerOrService = get(containerId);
        verifyKillPermission(project, containerOrService, userI);
        try {
            kill(containerOrService, userI);
            return containerOrService.containerOrServiceId();
        } catch (NoContainerServerException e) {
            throw (e instanceof NoDockerServerException) ? (NoDockerServerException) e :
                    new NoDockerServerException(e.getMessage(), e.getCause());
        } catch (ContainerBackendException e) {
            throw (e instanceof DockerServerException) ? (DockerServerException) e :
                    new DockerServerException(e.getMessage(), e.getCause());
        }
    }

    private void kill(final Container container, final UserI userI)
            throws NoContainerServerException, ContainerBackendException, NotFoundException {
        addContainerHistoryItem(container, ContainerHistory.fromUserAction(ContainerEntity.KILL_STATUS,
                userI.getLogin()), userI);
        killWithoutHistory(container);
    }

    private void killWithoutHistory(final Container container)
            throws NoContainerServerException, ContainerBackendException, NotFoundException {
        containerControlApi.kill(container);
    }

    @Override
    @Nonnull
    public Map<String, InputStream> getLogStreams(final long id)
            throws NotFoundException {
        return getLogStreams(get(id));
    }

    @Override
    @Nonnull
    public Map<String, InputStream> getLogStreams(final String containerId)
            throws NotFoundException {
        return getLogStreams(get(containerId));
    }

    @Nonnull
    private Map<String, InputStream> getLogStreams(final Container container) {
        final Map<String, InputStream> logStreams = new HashMap<>(ContainerService.LOG_NAMES.length);
        for (final String logName : ContainerService.LOG_NAMES) {
            final InputStream logStream = getLogStream(container, logName);
            if (logStream != null) {
                logStreams.put(logName, logStream);
            }
        }
        return logStreams;
    }

    @Override
    @Nullable
    public InputStream getLogStream(final long id, final String logFileName)
            throws NotFoundException {
        return getLogStream(get(id), logFileName);
    }

    @Nullable
    private InputStream getLogStream(final Container container, final String logFileName) {
        return getLogStream(container, logFileName, false, null);
    }

    @Override
    @Nullable
    public InputStream getLogStream(final String containerId, final String logFileName)
            throws NotFoundException {
        return getLogStream(containerId, logFileName, false, null);
    }

    @Override
    @Nullable
    public InputStream getLogStream(final String containerId, final String logFileName,
                                    boolean withTimestamps, final Integer since)
            throws NotFoundException {
        return getLogStream(get(containerId), logFileName, withTimestamps, since);
    }

    @Nullable
    private InputStream getLogStream(final Container container, final String logFileName,
                                     boolean withTimestamps, final Integer since) {
        final boolean containerDone = containerStatusIsTerminal(container);
        final String logPath = container.getLogPath(logFileName);
        if (!containerDone) {
            try {
                // If log path is blank, that means we have not yet saved the logs from docker. Go fetch them now.
                final ContainerControlApi.LogType logType = ContainerService.STDOUT_LOG_NAME.contains(logFileName) ?
                        ContainerControlApi.LogType.STDOUT :
                        ContainerControlApi.LogType.STDERR;
                return containerControlApi.getLogStream(container, logType, withTimestamps, since);
            } catch (NoContainerServerException | ContainerBackendException e) {
                log.debug("No {} log for {}", logFileName, container.databaseId());
            }
        } else if (!StringUtils.isBlank(logPath)) {
            // If log path is not blank, that means we have saved the logs to a file (processing has completed). Read it now.
            try {
                return new FileInputStream(logPath);
            } catch (FileNotFoundException e) {
                log.error("Container {} log file {} not found. Path: {}", container.databaseId(), logFileName, logPath);
            }
        }

        return null;
    }

    private PersistentWorkflowI getContainerWorkflow(UserI userI, final Container container) {
        String workFlowId = container.workflowId();
        return WorkflowUtils.getUniqueWorkflow(userI, workFlowId);
    }

    private String getWorkflowStatus(UserI userI, final Container container) {
     	   return getContainerWorkflow(userI, container).getStatus();
    }

	private void handleFailure(UserI userI, final Container container) {
       try {
    	   String workFlowId = container.workflowId();
    	   PersistentWorkflowI workflow = WorkflowUtils.getUniqueWorkflow(userI,workFlowId);
    	   handleFailure(workflow);
    	}catch(Exception e) {
        	log.error("Unable to update workflow and set it to FAILED status for container", e);
    	}
    }
    
    private void handleFailure(@Nullable PersistentWorkflowI workflow) {
        handleFailure(workflow, null, null);
    }

    private void handleFailure(@Nullable PersistentWorkflowI workflow, @Nullable final Exception source) {
        handleFailure(workflow, source, null);
    }

    /**
     * Updates workflow status to Failed based on the exception if provided, appends ' (statusSuffix)' if provided or
     * discernible from exception class
     * @param workflow the workflow
     * @param source the exception source
     * @param statusSuffix optional suffix (will try to determine from exception class if not provided)
     */
    private void handleFailure(@Nullable PersistentWorkflowI workflow,
                               @Nullable final Exception source,
                               @Nullable String statusSuffix) {
        if (workflow == null) return;

        String details = "";
        if (source != null) {
            String exceptionName = source.getClass().getName().replace("Exception$", "");
            statusSuffix = StringUtils.defaultIfBlank(statusSuffix, exceptionName);
            details = StringUtils.defaultIfBlank(source.getMessage(), exceptionName);
        }
        statusSuffix = StringUtils.isNotBlank(statusSuffix) ?  " (" + statusSuffix + ")" : "";

        String status = PersistentWorkflowUtils.FAILED + statusSuffix;
        updateWorkflow(workflow, status, details);
    }

    /**
     * Update workflow status and details and save
     * @param workflow the workflow
     * @param status the status
     */
    private void updateWorkflow(@Nullable PersistentWorkflowI workflow, @Nonnull String status) {
        updateWorkflow(workflow, status, null);
    }

    /**
     * Update workflow status and details and save
     * @param workflow the workflow
     * @param status the status
     * @param details the details (optional)
     */
    private void updateWorkflow(@Nullable PersistentWorkflowI workflow, @Nonnull String status, @Nullable String details) {
        if (workflow == null) return;
        try {
            workflow.setStatus(status);
            workflow.setDetails(details);
            WorkflowUtils.save(workflow, workflow.buildEvent());
        } catch(Exception e) {
            log.error("Unable to update workflow {} and set it to {} status", workflow.getWorkflowId(), status, e);
        }
    }

    /**
     * Creates a workflow object to be used with container service
     * @param xnatIdOrUri the xnat ID or URI string of the container's root element
     * @param containerInputType the container input type of the container's root element
     * @param wrapperName the wrapper name or id as a string
     * @param projectId the project ID
     * @param user the user
     * @return the workflow
     */
    @Nullable
    @Override
    public PersistentWorkflowI createContainerWorkflow(String xnatIdOrUri, String containerInputType,
                                                       String wrapperName, String projectId, UserI user) {
        return createContainerWorkflow(xnatIdOrUri, containerInputType, wrapperName, projectId, user, null);
    }

    /**
     * Creates a workflow object to be used with container service
     * @param xnatIdOrUri the xnat ID or URI string of the container's root element
     * @param containerInputType the container input type of the container's root element
     * @param wrapperName the wrapper name or id as a string
     * @param projectId the project ID
     * @param user the user
     * @return the workflow
     */
    @Nullable
    @Override
    public PersistentWorkflowI createContainerWorkflow(String xnatIdOrUri, String containerInputType,
                                                       String wrapperName, String projectId, UserI user,
                                                       String bulkLaunchId) {
        return createContainerWorkflow(xnatIdOrUri, containerInputType, wrapperName, projectId, user, bulkLaunchId, null, 0);
    }

    @Nullable
    @Override
    public PersistentWorkflowI createContainerWorkflow(String xnatIdOrUri, String containerInputType,
                                                       String wrapperName, String projectId, UserI user,
                                                       @Nullable String bulkLaunchId, @Nullable Long orchestrationId,
                                                       int orchestrationOrder) {
        if (xnatIdOrUri == null) {
            return null;
        }

        String xsiType;
        String xnatId;
        String scanId = null;
        try {
            // Attempt to parse xnatIdOrUri as URI, from this, get archivable item for workflow
            ResourceData resourceData = catalogService.getResourceDataFromUri(xnatIdOrUri);
            URIManager.DataURIA uri = resourceData.getUri();
            if (uri instanceof ExptScanURI) {
                scanId = ((ExptScanURI) resourceData.getUri()).getScan().getXnatImagescandataId().toString();
            }
            ArchivableItem item = resourceData.getItem();
            xnatId = item.getId();
            xsiType = item.getXSIType();
            projectId = StringUtils.defaultIfBlank(projectId, item.getProject());
        } catch (ClientException e) {
            // Fall back on id as string, determine xsiType from container input type
            xnatId = xnatIdOrUri;
            xsiType = containerInputType;
            try {
                switch (CommandWrapperInputType.valueOf(containerInputType.toUpperCase())) {
                    case PROJECT:
                        xsiType = XnatProjectdata.SCHEMA_ELEMENT_NAME;
                        break;
                    case PROJECT_ASSET:
                        xsiType = XnatAbstractprojectasset.SCHEMA_ELEMENT_NAME;
                        break;
                    case SUBJECT:
                        xsiType = XnatSubjectdata.SCHEMA_ELEMENT_NAME;
                        break;
                    case SUBJECT_ASSESSOR:
                        xsiType = XnatSubjectassessordata.SCHEMA_ELEMENT_NAME;
                        break;
                    case SESSION:
                        xsiType = XnatImagesessiondata.SCHEMA_ELEMENT_NAME;
                        break;
                    case SCAN:
                        xsiType = XnatImagescandata.SCHEMA_ELEMENT_NAME;
                        break;
                    case RESOURCE:
                        xsiType = XnatResource.SCHEMA_ELEMENT_NAME;
                        break;
                    case ASSESSOR:
                        xsiType = XnatImageassessordata.SCHEMA_ELEMENT_NAME;
                        break;
                }
            } catch (IllegalArgumentException | NullPointerException ex) {
                // Not what we're expecting, but just go with it (it'll be updated after command is resolved)
                xsiType = containerInputType;
            }
        }

        PersistentWorkflowI workflow = null;
        try {
            workflow = WorkflowUtils.buildOpenWorkflow(user, xsiType, xnatId, projectId,
                    EventUtils.newEventInstance(EventUtils.CATEGORY.DATA, EventUtils.TYPE.PROCESS,
                            wrapperName,
                            containerLaunchJustification,
                            ""));
            workflow.setStatus(PersistentWorkflowUtils.QUEUED);
            if (scanId != null) {
                workflow.setScanId(scanId);
            }
            if (bulkLaunchId != null) {
                workflow.setJobid(bulkLaunchId);
            }
            if (orchestrationId != null) {
                workflow.setNextStepId(orchestrationId.toString());
                workflow.setCurrentStepId(Integer.toString(orchestrationOrder));
            }
            try {
                WorkflowUtils.save(workflow, workflow.buildEvent());
            } catch (PSQLException e) {
                // Note: for scans, this can fail with a duplicate key value violates unique constraint
                // (id, pipeline_name, launch_time) since they'll share a root element.
                // XNAT-6606 added scanId to the uniqueness constraint, but this doesn't auto-update, so we'll leave
                // this check just in case
                // Let's try to re-save with a new time
                if (e.getMessage().contains("duplicate key value violates unique constraint \"wrk_workflowdata_u_true\"")) {
                    workflow.setLaunchTime(Calendar.getInstance().getTime());
                    WorkflowUtils.save(workflow, workflow.buildEvent());
                } else {
                    throw e;
                }
            }
            log.debug("Created workflow {}.", workflow.getWorkflowId());
        } catch (Exception e) {
            log.error("Issue creating workflow for {} {}", xnatId, wrapperName, e);
        }
        return workflow;
    }

    /**
     * Updates a workflow with info from resolved command, creating the workflow if null
     *
     * This is a way for us to show the the container execution in the history table
     * and as a workflow alert banner (where enabled) without any custom UI work.
     *
     * It is possible to create a workflow for the execution if the resolved command
     * has one external input which is an XNAT object. If it has zero external inputs,
     * there is no object on which we can "hang" the workflow, so to speak. If it has more
     * than one external input, we don't know which is the one that should display the
     * workflow, so we don't make one.
     *
     * @param workflow  the initial workflow
     * @param resolvedCommand A resolved command that will be used to launch a container
     * @param userI The user launching the container
     * @return the updated or created workflow
     */
    private PersistentWorkflowI updateWorkflowWithResolvedCommand(@Nullable PersistentWorkflowI workflow,
                                                                  final ResolvedCommand resolvedCommand,
                                                                  final UserI userI) {
        final RootInputObject rootInputObject = findRootInputObject(resolvedCommand, userI);
        if (rootInputObject == null) {
            // We didn't find a root input XNAT object, so we can't make a workflow.
            log.debug("Cannot update workflow, no root input.");
            return workflow;
        }

        try {
            if (workflow == null) {
                // Create it
                log.debug("Create workflow for Wrapper {} - Command {} - Image {}.",
                        resolvedCommand.wrapperName(), resolvedCommand.commandName(), resolvedCommand.image());
                workflow = WorkflowUtils.buildOpenWorkflow(userI, rootInputObject.rootObject,
                        EventUtils.newEventInstance(EventUtils.CATEGORY.DATA, EventUtils.TYPE.PROCESS,
                                resolvedCommand.wrapperName(),
                                ContainerServiceImpl.containerLaunchJustification,
                                ""));
                if (rootInputObject.scanId != null) {
                    workflow.setSrc(rootInputObject.scanId);
                }
            } else {
                // Update it
                log.debug("Update workflow for Wrapper {} - Command {} - Image {}.",
                        resolvedCommand.wrapperName(), resolvedCommand.commandName(), resolvedCommand.image());
                // Update workflow fields
                workflow.setType(EventUtils.TYPE.PROCESS);
                workflow.setPipelineName(resolvedCommand.wrapperName());
                workflow.setJustification(ContainerServiceImpl.containerLaunchJustification);
                workflow.setId(rootInputObject.rootObject.getIDValue());
                workflow.setDataType(rootInputObject.rootObject.getXSIType());
                workflow.setPipelineName(resolvedCommand.wrapperName());
                if (StringUtils.isBlank(workflow.getExternalid())) {
                    // Only reset external ID if we don't have one, otherwise this can change from shared to primary project
                    workflow.setExternalid(PersistentWorkflowUtils.getExternalId(rootInputObject.rootObject));
                }
            }

            String project = workflow.getExternalid();
            if (StringUtils.isNotBlank(project) && workflow.getNextStepId() == null) {
                // check if this is the start of an orchestrated sequence, WorkflowStatusEventOrchestrationListener will handle later steps
                final Orchestration orchestration = getOrchestrationWhereWrapperIsFirst(project, resolvedCommand.wrapperId());
                if (orchestration != null) {
                    workflow.setNextStepId(Long.toString(orchestration.getId()));
                    workflow.setCurrentStepId("0");
                }
            }

            workflow.setStatus(PersistentWorkflowUtils.QUEUED);
            WorkflowUtils.save(workflow, workflow.buildEvent());
            log.debug("Updated workflow {}.", workflow.getWorkflowId());
        } catch (Exception e) {
            log.error("Could not create/update workflow.", e);
        }
        return workflow;
    }

    @Nullable
    private Orchestration getOrchestrationWhereWrapperIsFirst(@Nullable final String project, long wrapperId)
            throws ExecutionException {
        return getOrchestrationWhereWrapperIsFirst(project, wrapperId, 0L, null);
    }

    @Override
    @Nullable
    public Orchestration getOrchestrationWhereWrapperIsFirst(@Nullable final String project,
                                                             long wrapperId,
                                                             final long commandId,
                                                             @Nullable final String wrapperName)
            throws ExecutionException {
        if (project == null) {
            // No orchestration without project context
            return null;
        }
        OrchestrationIdentifier oi = new OrchestrationIdentifier(project, wrapperId, commandId, wrapperName);
        return orchestrationCache.get(oi).orElse(null);
    }

    /**
     * Updates a workflow with info from container object
     *
     * @param workflow  the initial workflow
     * @param containerOrService the container
     */
    private void updateWorkflowWithContainer(@Nonnull PersistentWorkflowI workflow, Container containerOrService) {
        String wrkFlowComment = containerOrService.containerOrServiceId();
        log.debug("Updating workflow for Container {}", wrkFlowComment);

    	try {
            workflow.setComments(wrkFlowComment);
            WorkflowUtils.save(workflow, workflow.buildEvent());
            log.debug("Updated workflow {}.", workflow.getWorkflowId());
        } catch (Exception e) {
            log.error("Could not update workflow.", e);
        }
    }

    private static class RootInputObject{
        public XFTItem rootObject = null;
        public String scanId = null;
    }

    @Nullable
    private RootInputObject findRootInputObject(final ResolvedCommand resolvedCommand, final UserI userI) {
        log.debug("Checking input values to find root XNAT input object.");
        final List<ResolvedInputTreeNode<? extends Command.Input>> flatInputTrees = resolvedCommand.flattenInputTrees();

        RootInputObject rootInputObject = new RootInputObject();
        for (final ResolvedInputTreeNode<? extends Command.Input> node : flatInputTrees) {
            final Command.Input input = node.input();
            log.debug("Input \"{}\".", input.name());
            if (!(input instanceof Command.CommandWrapperExternalInput)) {
                log.debug("Skipping. Not an external wrapper input.");
                continue;
            }

            final String type = input.type();
            if (!(type.equals(PROJECT.getName()) || type.equals(PROJECT_ASSET.getName()) || type.equals(SUBJECT.getName()) || 
                    type.equals(SESSION.getName()) || type.equals(SCAN.getName()) || type.equals(ASSESSOR.getName()) || 
                    type.equals(RESOURCE.getName()) || type.equals(SUBJECT_ASSESSOR.getName()))) {
                log.debug("Skipping. Input type \"{}\" is not an XNAT type.", type);
                continue;
            }

            final List<ResolvedInputTreeNode.ResolvedInputTreeValueAndChildren> valuesAndChildren = node.valuesAndChildren();
            if (valuesAndChildren == null || valuesAndChildren.size() != 1) {
                log.debug("Skipping. {} values.", (valuesAndChildren == null || valuesAndChildren.isEmpty()) ? "No" : "Multiple");
                continue;
            }

            final ResolvedInputValue externalInputValue = valuesAndChildren.get(0).resolvedValue();
            final XnatModelObject inputValueXnatObject = externalInputValue.xnatModelObject();

            if (inputValueXnatObject == null) {
                log.debug("Skipping. XNAT model object is null.");
                continue;
            }

            if (rootInputObject.rootObject != null) {
                // We have already seen one candidate for a root object.
                // Seeing this one means we have more than one, and won't be able to
                // uniquely resolve a root object.
                // We won't be able to make a workflow. We can bail out now.
                log.debug("Found another root XNAT input object: {}. I was expecting one. Bailing out.", input.name());
                return null;
            }

            final XnatModelObject xnatObjectToUseAsRoot;
            if (type.equals(SCAN.getName())) {
                // If the external input is a scan, the workflow will not show up anywhere. So we
                // use its parent session as the root object instead.
                final Scan scan = (Scan) inputValueXnatObject;
                final XnatModelObject parentSession = scan.getSession(userI, false, new HashSet<>());
                if (parentSession != null) {
                    xnatObjectToUseAsRoot = parentSession;
                    rootInputObject.scanId = scan.getIntegerId().toString();
                } else {
                    // Ok, nevermind, use the scan anyway. It's not a huge thing.
                    xnatObjectToUseAsRoot = inputValueXnatObject;
                }
            } else {
                xnatObjectToUseAsRoot = inputValueXnatObject;
            }

            try {
                log.debug("Getting input value as XFTItem.");
                rootInputObject.rootObject = xnatObjectToUseAsRoot.getXftItem(userI);
            } catch (Throwable t) {
                // If anything goes wrong, bail out. No workflow.
                log.error("That didn't work.", t);
                continue;
            }

            if (rootInputObject.rootObject == null) {
                // I don't know if this is even possible
                log.debug("XFTItem is null.");
                continue;
            }

            // This is the first good input value.
            log.debug("Found a valid root XNAT input object: {}.", input.name());
        }

        if (rootInputObject.rootObject == null) {
            // Didn't find any candidates
            log.debug("Found no valid root XNAT input object candidates.");
            return null;
        }

        // At this point, we know we found a single valid external input value.
        // We can declare the object in that value to be the root object.
        return rootInputObject;
    }

    @Nonnull
    private Container toPojo(@Nonnull final ContainerEntity containerEntity) {
        return Container.create(containerEntity);
    }

    @Nonnull
    private List<Container> toPojo(@Nonnull final List<ContainerEntity> containerEntityList) {
        return containerEntityList.stream().map(this::toPojo).collect(Collectors.toList());
    }

    @Nonnull
    private ContainerEntity fromPojo(@Nonnull final Container container) {
        final ContainerEntity template = containerEntityService.retrieve(container.databaseId());
        return template == null ? ContainerEntity.fromPojo(container) : template.update(container);
    }

    @Nonnull
    private ContainerEntityHistory fromPojo(@Nonnull final ContainerHistory containerHistory) {
        return ContainerEntityHistory.fromPojo(containerHistory);
    }

    private boolean exitCodeIsFailed(final String exitCode) {
        // Assume that everything is fine unless the exit code is explicitly != 0.
        // So exitCode="0", ="", =null all count as not failed.
        boolean isFailed = false;
        if (StringUtils.isNotBlank(exitCode)) {
            Long exitCodeNumber = null;
            try {
                exitCodeNumber = Long.parseLong(exitCode);
            } catch (NumberFormatException e) {
                // ignored
            }

            isFailed = exitCodeNumber != null && exitCodeNumber != 0;
        }
        return isFailed;
    }

	@Override
	public List<Container> retrieveServicesInWaitingState() {
        return toPojo(containerEntityService.retrieveServicesInWaitingState());
	}

    @Override
    @Nonnull
    public LaunchReport launchContainer(@Nullable final String project,
                                         final long commandId,
                                         @Nullable final String wrapperName,
                                         final long wrapperId,
                                         @Nullable final String rootElement,
                                         final Map<String, String> allRequestParams,
                                         final UserI userI) {
        return launchContainer(project, commandId, wrapperName, wrapperId, rootElement, allRequestParams,
                userI, null);
    }

    @Override
    @Nonnull
    public LaunchReport launchContainer(@Nullable final String project,
                                         final long commandId,
                                         @Nullable final String wrapperName,
                                         final long wrapperId,
                                         @Nullable final String rootElement,
                                         final Map<String, String> allRequestParams,
                                         final UserI userI,
                                         @Nullable final String bulkLaunchId) {
        return launchContainer(project, commandId, wrapperName, wrapperId, rootElement, allRequestParams,
                userI, bulkLaunchId, null);
    }

    @Override
    @Nonnull
    public LaunchReport launchContainer(@Nullable final String project,
                                         final long commandId,
                                         @Nullable final String wrapperName,
                                         final long wrapperId,
                                         @Nullable final String rootElement,
                                         final Map<String, String> allRequestParams,
                                         final UserI userI,
                                         @Nullable final String bulkLaunchId,
                                         @Nullable final Long orchestrationId) {

        PersistentWorkflowI workflow = null;
        String workflowid = "";

        try {
            // Create workflow first
            String xnatIdOrUri;
            if (rootElement != null && (xnatIdOrUri = allRequestParams.get(rootElement)) != null) {
                // Note: for scans, this can fail with a duplicate key value violates unique constraint
                // (id, pipeline_name, launch_time) since they'll share a root element. But, we try again later, so no biggie
                String wrapperNameUse = StringUtils.isBlank(wrapperName) && wrapperId != 0 ?
                        commandService.retrieveWrapper(wrapperId).name() : wrapperName;
                workflow = createContainerWorkflow(xnatIdOrUri, rootElement,
                        wrapperNameUse, StringUtils.defaultString(project, ""), userI,
                        bulkLaunchId, orchestrationId, 0);
                workflowid = workflow.getWorkflowId().toString();
            }

            // Queue command resolution and container launch
            queueResolveCommandAndLaunchContainer(project, wrapperId, commandId,
                    wrapperName, allRequestParams, userI, workflow);

            String msg = StringUtils.isNotBlank(workflowid) ? workflowid : TO_BE_ASSIGNED;
            return LaunchReport.Success.create(msg, allRequestParams, null, commandId, wrapperId);

        } catch (Throwable t) {
            if (workflow != null) {
                String failedStatus = PersistentWorkflowUtils.FAILED + " (Staging)";
                workflow.setStatus(failedStatus);
                workflow.setDetails(t.getMessage());
                try {
                    WorkflowUtils.save(workflow, workflow.buildEvent());
                } catch (Exception we) {
                    log.error("Unable to set workflow status to {} for wfid={}", failedStatus, workflow.getWorkflowId(), we);
                }
            }
            if (log.isInfoEnabled()) {
                log.error("Unable to queue container launch for command wrapper name {}.", wrapperName);
                log.error(mapLogString("Params: ", allRequestParams));
                log.error("Exception: ", t);
            }
            return LaunchReport.Failure.create(t.getMessage() != null ? t.getMessage() : "Unable to queue container launch",
                    allRequestParams, commandId, wrapperId);
        }
    }

    @Override
    public LaunchReport.BulkLaunchReport bulkLaunch(final String project,
                                                    final long commandId,
                                                    final String wrapperName,
                                                    final long wrapperId,
                                                    final String rootElement,
                                                    final Map<String, String> allRequestParams,
                                                    final UserI userI) throws IOException {
        final String bulkLaunchId = generateBulkLaunchId(userI);
        List<String> targets = mapper.readValue(allRequestParams.get(rootElement), new TypeReference<List<String>>() {});

        Orchestration orchestration = null;
        try {
            orchestration = getOrchestrationWhereWrapperIsFirst(project, wrapperId,
                    commandId, wrapperName);
        } catch (ExecutionException e) {
            log.error("Unable to query for orchestration");
        }

        final Long orchestrationId;
        final String pipelineName;
        int steps;
        if (orchestration != null) {
            orchestrationId = orchestration.getId();
            pipelineName = orchestration.getName() + " orchestration";
            steps = orchestration.getWrapperIds().size();
        } else {
            orchestrationId = null;
            pipelineName = StringUtils.defaultIfBlank(wrapperName, commandService.retrieveWrapper(wrapperId).name());
            steps = 1;
        }
        eventService.triggerEvent(BulkLaunchEvent.initial(bulkLaunchId, userI.getID(), targets.size(), steps));
        log.debug("Bulk launching on {} targets", targets.size());

        final LaunchReport.BulkLaunchReport.Builder reportBuilder = LaunchReport.BulkLaunchReport.builder()
                .bulkLaunchId(bulkLaunchId).pipelineName(pipelineName);
        int failures = 0;
        for (final String target : targets) {
            final Map<String, String> paramsSet = new HashMap<>(allRequestParams);
            paramsSet.put(rootElement, target);
            try {
                executorService.submit(() -> {
                    launchContainer(project, commandId, wrapperName, wrapperId, rootElement, paramsSet, userI,
                            bulkLaunchId, orchestrationId);
                });
                reportBuilder.addSuccess(LaunchReport.Success.create(ContainerServiceImpl.TO_BE_ASSIGNED,
                        paramsSet, null, commandId, wrapperId));
            } catch (Exception e) {
                // Most exceptions should be "logged" to the workflow but this is meant to catch
                // issues submitting to the executorService
                reportBuilder.addFailure(LaunchReport.Failure.create(e.getMessage() != null ?
                                e.getMessage() : "Unable to queue container launch",
                        paramsSet, commandId, wrapperId));
                failures++;
            }
        }

        if (failures > 0) {
            // this should be super uncommon
            eventService.triggerEvent(BulkLaunchEvent.executorServiceFailureCount(bulkLaunchId, userI.getID(), failures));
        }

        return reportBuilder.build();
    }

    private String generateBulkLaunchId(final UserI userI) {
        return "bulk-" + userI.getLogin() + System.currentTimeMillis() + new Random().nextInt(1000);
    }

    private String mapLogString(final String title, final Map<String, String> map) {
        final StringBuilder messageBuilder = new StringBuilder(title);
        for (Map.Entry<String, String> entry : map.entrySet()) {
            messageBuilder.append(entry.getKey());
            messageBuilder.append(": ");
            messageBuilder.append(entry.getValue());
            messageBuilder.append(", ");
        }
        return messageBuilder.substring(0, messageBuilder.length() - 2);
    }
}
