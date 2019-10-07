package org.nrg.containers.services.impl;


import static org.nrg.containers.model.command.entity.CommandType.DOCKER;
import static org.nrg.containers.model.command.entity.CommandType.DOCKER_SETUP;
import static org.nrg.containers.model.command.entity.CommandType.DOCKER_WRAPUP;
import static org.nrg.containers.model.command.entity.CommandWrapperInputType.ASSESSOR;
import static org.nrg.containers.model.command.entity.CommandWrapperInputType.PROJECT;
import static org.nrg.containers.model.command.entity.CommandWrapperInputType.RESOURCE;
import static org.nrg.containers.model.command.entity.CommandWrapperInputType.SCAN;
import static org.nrg.containers.model.command.entity.CommandWrapperInputType.SESSION;
import static org.nrg.containers.model.command.entity.CommandWrapperInputType.SUBJECT;

import java.io.*;
import java.text.ParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.messages.swarm.TaskStatus;
import org.apache.commons.lang3.StringUtils;
import org.nrg.action.ClientException;
import org.nrg.containers.api.ContainerControlApi;
import org.nrg.containers.events.model.ContainerEvent;
import org.nrg.containers.events.model.DockerContainerEvent;
import org.nrg.containers.events.model.ServiceTaskEvent;
import org.nrg.containers.exceptions.*;
import org.nrg.containers.jms.requests.ContainerFinalizingRequest;
import org.nrg.containers.jms.requests.ContainerRequest;
import org.nrg.containers.jms.requests.ContainerStagingRequest;
import org.nrg.containers.jms.utils.QueueUtils;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.auto.Command.ConfiguredCommand;
import org.nrg.containers.model.command.auto.Command.CommandWrapper;
import org.nrg.containers.model.command.auto.ResolvedCommand;
import org.nrg.containers.model.command.auto.ResolvedInputTreeNode;
import org.nrg.containers.model.command.auto.ResolvedInputValue;
import org.nrg.containers.model.command.entity.CommandWrapperInputType;
import org.nrg.containers.model.configuration.PluginVersionCheck;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.auto.Container.ContainerHistory;
import org.nrg.containers.model.container.auto.ServiceTask;
import org.nrg.containers.model.container.entity.ContainerEntity;
import org.nrg.containers.model.container.entity.ContainerEntityHistory;
import org.nrg.containers.model.xnat.Scan;
import org.nrg.containers.model.xnat.XnatModelObject;
import org.nrg.containers.services.*;
import org.nrg.containers.utils.ContainerUtils;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xdat.XDAT;
import org.nrg.xdat.entities.AliasToken;
import org.nrg.xdat.om.*;
import org.nrg.xdat.preferences.SiteConfigPreferences;
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
import org.nrg.xnat.services.XnatAppInfo;
import org.nrg.xnat.services.archive.CatalogService;
import org.nrg.xnat.turbine.utils.ArchivableItem;
import org.nrg.xnat.utils.WorkflowUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.google.common.base.Function;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ContainerServiceImpl implements ContainerService {
    private static final String MIN_XNAT_VERSION_REQUIRED = "1.7.5";
    public static final String WAITING = "Waiting";
    public static final String FINALIZING = "Finalizing";
    public static final String CREATED = "Created";
    public static final String setupStr = "Setup";
    public static final String wrapupStr = "Wrapup";
    public static final String containerLaunchJustification = "Container launch";

    private final ContainerControlApi containerControlApi;
    private final ContainerEntityService containerEntityService;
    private final CommandResolutionService commandResolutionService;
    private final CommandService commandService;
    private final AliasTokenService aliasTokenService;
    private final SiteConfigPreferences siteConfigPreferences;
    private final ContainerFinalizeService containerFinalizeService;
    private final XnatAppInfo xnatAppInfo;
    private final CatalogService catalogService;

    @Autowired
    public ContainerServiceImpl(final ContainerControlApi containerControlApi,
                                final ContainerEntityService containerEntityService,
                                final CommandResolutionService commandResolutionService,
                                final CommandService commandService,
                                final AliasTokenService aliasTokenService,
                                final SiteConfigPreferences siteConfigPreferences,
                                final ContainerFinalizeService containerFinalizeService,
                                final XnatAppInfo xnatAppInfo,
                                final CatalogService catalogService) {
        this.containerControlApi = containerControlApi;
        this.containerEntityService = containerEntityService;
        this.commandResolutionService = commandResolutionService;
        this.commandService = commandService;
        this.aliasTokenService = aliasTokenService;
        this.siteConfigPreferences = siteConfigPreferences;
        this.containerFinalizeService = containerFinalizeService;
        this.xnatAppInfo = xnatAppInfo;
        this.catalogService = catalogService;
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

    private long getTimeSinceWorkflowMod(final WrkWorkflowdata wrk)
            throws ElementNotFoundException, FieldNotFoundException, XFTInitException, ParseException {
        Date now = new Date();
        Date modTime = wrk.getItem().getMeta().getDateProperty("last_modified");
        return (now.getTime() - modTime.getTime()) / (60 * 60 * 1000) % 24;
    }

    @Override
    public void checkQueuedContainerJobs(UserI user) {
        List<WrkWorkflowdata> workflows = getContainerWorkflowsByStatus(PersistentWorkflowUtils.QUEUED, user);
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
                wrk.setStatus(PersistentWorkflowUtils.FAILED + " (Queue)");
                wrk.setDetails("Workflow queued for more than 5 hours, needs relaunch");
                WorkflowUtils.save(wrk, wrk.buildEvent());

            } catch (XFTInitException | ElementNotFoundException| FieldNotFoundException | ParseException e) {
                log.error("Unable to determine mod time for wfid {}", wrk.getWorkflowId());
            } catch (Exception e) {
                log.error("Unable to save updated workflow {}", wrk.getWorkflowId());
            }
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
    public void resetFinalizingStatusToWaitingOrFailed() {
    	List<ContainerEntity> finalizingContainerEntities =  containerEntityService.retrieveServicesInFinalizingState();
    	if (finalizingContainerEntities == null || finalizingContainerEntities.size() == 0) {
    		log.info("Appears that no containers are in orphaned {} state", FINALIZING);
    		return;
    	}
        List<Container> finalizingContainers  = toPojo(finalizingContainerEntities);
        //Now update the finalizing state to Waiting or Failed
        for (final Container s : finalizingContainers) {
            Container service = retrieve(s.databaseId());
            if (service == null) {
                continue;
            }
            log.info("Found service {} task {} workflow {} in possibly abandoned {} state", service.serviceId(),
                    service.taskId(), service.workflowId(), FINALIZING);
            final String userLogin = service.userId();
            try {
                final UserI userI = Users.getUser(userLogin);
                Date now = new Date();
                Date lastStatusTime = service.statusTime();
                Long diffHours = lastStatusTime == null ? null :
                        (now.getTime() - lastStatusTime.getTime()) / (60 * 60 * 1000) % 24;
                if (diffHours != null && diffHours < 72) {
                    addContainerHistoryItem(service, ContainerHistory.fromSystem(WAITING,
                            "Reset status from Finalizing to Waiting." ), userI);
                    log.info("Updated service {} to Waiting state", service);
                } else {
                    addContainerHistoryItem(service, ContainerHistory.fromSystem(PersistentWorkflowUtils.FAILED +
                            "("+FINALIZING+")", FINALIZING + " for more than 72 Hours"), userI);
                    log.info("Updated service {} to FAILED state", service);
                }
            } catch(UserNotFoundException | UserInitException e) {
                log.error("Could not update container status. Could not get user details for user " + userLogin, e);
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
        return containerEntityHistoryItem == null ? null : toPojo(containerEntityHistoryItem);
    }

    @Override
    public void queueResolveCommandAndLaunchContainer(@Nullable String project,
                                                      final long wrapperId,
                                                      final long commandId,
                                                      @Nullable final String wrapperName,
                                                      final Map<String, String> inputValues,
                                                      final UserI userI,
                                                      @Nullable PersistentWorkflowI workflow)
            throws Exception {

        // Workflow shouldn't be null unless container launched without a root element
        // (I think the only way to do so would be through the REST API)
        String workflowid = null;
        //String status = null;
        if (workflow != null) {
            workflowid = workflow.getWorkflowId().toString();
            //status = workflow.getStatus();
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

        // Update: don't use JMS indicator for staging queue since staging tasks are added to the JMS queue manually
        //
        // Workflow will only retain the JMS indicator for command resolution, once launching process begins, status is
        // updated without JMS indicator. This is probably okay since staging tasks are added to the JMS queue manually
        // upon launch, rather than automatically by an event.
        //if (status != null && request.inJMSQueue(status)) {
        //    log.debug("{} appears to already be in JMS queue", workflowid);
        //    // Throw exception?
        //    return;
        //}
        //
        //if (workflow != null) {
        //    workflow.setStatus(request.makeJMSQueuedStatus(status));
        //    WorkflowUtils.save(workflow, workflow.buildEvent());
        //}

        XDAT.sendJmsRequest(request);
    }


    @Override
    public void consumeResolveCommandAndLaunchContainer(@Nullable final String project,
                                                        final long wrapperId,
                                                        final long commandId,
                                                        @Nullable final String wrapperName,
                                                        final Map<String, String> inputValues,
                                                        final UserI userI,
                                                        @Nullable final String workflowid) {

        log.trace("consumeResolveCommandAndLaunchContainer wfid {}", workflowid);

        PersistentWorkflowI workflow = null;
        if (workflowid != null) {
            workflow = WorkflowUtils.getUniqueWorkflow(userI, workflowid);
        }

        try {
            log.trace("Configuring command for wfid {}", workflowid);
            ConfiguredCommand configuredCommand = commandService.getAndConfigure(project, commandId, wrapperName, wrapperId);

            log.trace("Resolving command for wfid {}", workflowid);
            ResolvedCommand resolvedCommand = commandResolutionService.resolve(configuredCommand, inputValues, userI);
            if (StringUtils.isNotBlank(project)) {
                resolvedCommand = resolvedCommand.toBuilder().project(project).build();
            }

            // Launch resolvedCommand
            log.trace("Launching command for wfid {}", workflowid);
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
            return launchResolvedDockerCommand(resolvedCommand, userI, workflow, parent);
        } else {
            throw new UnsupportedOperationException("Cannot launch a command of type " + resolvedCommand.type());
        }
    }

    private Container launchResolvedDockerCommand(final ResolvedCommand resolvedCommand,
                                                  final UserI userI,
                                                  @Nullable PersistentWorkflowI workflow,
                                                  @Nullable final Container parent)
            throws NoDockerServerException, DockerServerException, ContainerException {

        log.info("Preparing to launch resolved command.");
        final ResolvedCommand preparedToLaunch = prepareToLaunch(resolvedCommand, parent, userI);

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
        	final Container createdContainerOrService =
                    containerControlApi.createContainerOrSwarmService(preparedToLaunch, userI);

            if (workflow != null) {
                // Update workflow with container information
                log.info("Recording container launch.");
                updateWorkflowWithContainer(workflow, createdContainerOrService);
            }

            // Save container in db.
			final Container savedContainerOrService = toPojo(containerEntityService.save(fromPojo(
	                createdContainerOrService.toBuilder()
	                        .workflowId(workflow != null ? workflow.getWorkflowId().toString() : null)
	                        .parent(parent)
	                        .build()
	        ), userI));
	
	        if (resolvedCommand.wrapupCommands().size() > 0) {
	            // Save wrapup containers in db
	            log.info("Creating wrapup container objects in database (not creating docker containers).");
	            for (final ResolvedCommand resolvedWrapupCommand : resolvedCommand.wrapupCommands()) {
	                final Container wrapupContainer = createWrapupContainerInDbFromResolvedCommand(resolvedWrapupCommand,
                            savedContainerOrService, userI, workflow);
	                log.debug("Created wrapup container {} for parent container {}.", wrapupContainer.databaseId(),
                            savedContainerOrService.databaseId());
	            }
	        }
	
	        if (resolvedCommand.setupCommands().size() > 0) {
	            log.info("Launching setup containers.");
	            for (final ResolvedCommand resolvedSetupCommand : resolvedCommand.setupCommands()) {
	                launchResolvedCommand(resolvedSetupCommand, userI, workflow, savedContainerOrService);
	            }
	        } else {
	            startContainer(userI, savedContainerOrService);
	        }
	
	        return savedContainerOrService;
        } catch (Exception e) {
        	handleFailure(workflow, e);
        	throw e;
        }
    }

    private void startContainer(final UserI userI, final Container savedContainerOrService) throws NoDockerServerException, ContainerException {
        log.info("Starting container.");
        try {
            containerControlApi.startContainer(savedContainerOrService);
        } catch (DockerServerException e) {
            addContainerHistoryItem(savedContainerOrService, ContainerHistory.fromSystem(PersistentWorkflowUtils.FAILED, "Did not start." + e.getMessage()), userI);
            handleFailure(userI,savedContainerOrService);
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

        final Container toCreate = Container.containerFromResolvedCommand(resolvedCommand, null, userI.getLogin()).toBuilder()
                .parent(parent)
                .workflowId(workflowid)
                .subtype(DOCKER_WRAPUP.getName())
                .project(parent != null ? parent.project() : null)
                .status(CREATED) //Needs non-empty status to be picked up by containerService.retrieveNonfinalizedServices()
                .build();
        return toPojo(containerEntityService.create(fromPojo(toCreate)));
    }
    @Nonnull
    private Container launchContainerFromDbObject(final Container toLaunch, final UserI userI)
            throws DockerServerException, NoDockerServerException, ContainerException {
        return launchContainerFromDbObject(toLaunch, userI, false);
    }

    @Nonnull
    private Container launchContainerFromDbObject(final Container toLaunch, final UserI userI, final boolean restart)
            throws DockerServerException, NoDockerServerException, ContainerException {

        final Container preparedToLaunch = restart ? toLaunch : prepareToLaunch(toLaunch, userI);

        log.info("Creating docker container for {} container {}.", toLaunch.subtype(), toLaunch.databaseId());
        final Container createdContainerOrService = containerControlApi.createContainerOrSwarmService(preparedToLaunch, userI);

        log.info("Updating {} container {}.", toLaunch.subtype(), toLaunch.databaseId());
        containerEntityService.update(fromPojo(createdContainerOrService));

        // Update workflow if we have one
        String workflowid = toLaunch.workflowId();
        if (StringUtils.isNotBlank(workflowid)) {
            PersistentWorkflowI workflow = WorkflowUtils.getUniqueWorkflow(userI, workflowid);
            updateWorkflowWithContainer(workflow, createdContainerOrService);
        }

        startContainer(userI, createdContainerOrService);

        return createdContainerOrService;
    }

    @Nonnull
    private ResolvedCommand prepareToLaunch(final ResolvedCommand resolvedCommand,
                                            final Container parent,
                                            final UserI userI) {
        return resolvedCommand.toBuilder()
                .addEnvironmentVariables(getDefaultEnvironmentVariablesForLaunch(userI))
                .project(resolvedCommand.project() == null && parent != null ? parent.project() : resolvedCommand.project())
                .build();
    }

    @Nonnull
    private Container prepareToLaunch(final Container toLaunch,
                                      final UserI userI) {
        return toLaunch.toBuilder()
                .addEnvironmentVariables(getDefaultEnvironmentVariablesForLaunch(userI))
                .build();
    }

    private Map<String, String> getDefaultEnvironmentVariablesForLaunch(final UserI userI) {
        final AliasToken token = aliasTokenService.issueTokenForUser(userI);
        final String processingUrl = (String)siteConfigPreferences.getProperty("processingUrl");
        final String xnatHostUrl = StringUtils.isBlank(processingUrl) ? siteConfigPreferences.getSiteUrl() : processingUrl;

        final Map<String, String> defaultEnvironmentVariables = new HashMap<>();
        defaultEnvironmentVariables.put("XNAT_USER", token.getAlias());
        defaultEnvironmentVariables.put("XNAT_PASS", token.getSecret());
        defaultEnvironmentVariables.put("XNAT_HOST", xnatHostUrl);

        return defaultEnvironmentVariables;
    }

    @Override
    public void processEvent(final ContainerEvent event) {
        log.debug("Processing container event");
        final Container container = retrieve(event.containerId());

        // container will be null if either we aren't tracking the container
        // that this event is about, or if we have already recorded the event
        if (container != null) {
            final String userLogin = container.userId();
            try {
                final UserI userI = Users.getUser(userLogin);

                Container containerWithAddedEvent = addContainerEventToHistory(event, userI);
                if (containerWithAddedEvent == null) {
                    // Ignore this issue?
                    containerWithAddedEvent = container;
                }
                if (event.isExitStatus()) {
                    log.debug("Container is dead. Finalizing.");
                    String status = containerWithAddedEvent.status();

                    // If we don't have a status, assume success
                    queueFinalize(event.exitCode(), status == null || DockerContainerEvent.isSuccessfulStatus(status),
                            containerWithAddedEvent, userI);
                }
            } catch (UserInitException | UserNotFoundException e) {
                log.error("Could not update container status. Could not get user details for user " + userLogin, e);
            }
        } else {
            log.debug("Nothing to do. Container was null after retrieving by id {}.", event.containerId());
        }

        log.debug("Done processing docker container event: {}", event);
    }

    @Override
    public void processEvent(final ServiceTaskEvent event) {
        final ServiceTask task = event.task();
        final Container service;

	    log.debug("Processing service task. Task id \"{}\" for service \"{}\".",
	           task.taskId(), task.serviceId());

        // When we create the service, we don't know all the IDs. If this is the first time we
        // have seen a task for this service, we can set those IDs now.
        if (StringUtils.isBlank(event.service().taskId()) || StringUtils.isBlank(event.service().nodeId())) {
            log.debug("Service \"{}\" has no task and/or node information stored. Setting it now.", task.serviceId());
            final Container serviceToUpdate = event.service().toBuilder()
                    .taskId(task.taskId())
                    .containerId(task.containerId())
                    .nodeId(task.nodeId())
                    .build();
            containerEntityService.update(fromPojo(serviceToUpdate));
            service = retrieve(serviceToUpdate.databaseId());
        } else {
            service = event.service();
        }

        if (service == null) {
            log.error("Could not find service corresponding to event {}", event);
            return;
        }

        if (isFinalizing(service)) {
            log.error("Service already finalizing {}", service);
            return;
        }

        final String userLogin = service.userId();
        try {
            final UserI userI = Users.getUser(userLogin);
            final ContainerHistory taskHistoryItem = ContainerHistory.fromServiceTask(task);

            // Process new and waiting events (duplicate docker events are skipped)
            if (!isWaiting(service) && addContainerHistoryItem(service, taskHistoryItem, userI) == null) {
                // We have already added this task and can safely skip it.
                log.debug("Skipping task status we have already seen: service {} task status {}",
                        service.serviceId(), task.status());
            } else {
                log.debug("Checking service {} task.status {} task.exitCode {} task.isExitStatus {}",
                        service.serviceId(), task.status(), task.exitCode(), task.isExitStatus());

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

                    queueFinalize(exitCodeString, task.isSuccessfulStatus(), serviceWithAddedEvent, userI);
                } else {
                    log.debug("Docker event has not exited yet. Service: {} Workflow: {} Status: {}",
                            service.serviceId(), service.workflowId(), service.status());
                }
            }
        } catch (UserInitException | UserNotFoundException e) {
            log.error("Could not update container status. Could not get user details for user {}", userLogin, e);
        }

        log.debug("Done processing service task event: {}", event);
    }

    private void doRestart(Container service, UserI userI)
            throws DockerServerException, NoDockerServerException, ContainerException {

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
                .status(ContainerHistory.restartStatus)
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
    public boolean restartService(Container service, UserI userI) {
        final int maxRestarts = 5;
        int nrun = maxRestarts + 1;

        if (!service.isSwarmService()) {
            // Refuse to restart a non-swarm container
            return false;
        }

        // Remove the errant service
        try {
            containerControlApi.killService(service.serviceId());
        } catch (DockerServerException | NotFoundException | NoDockerServerException e) {
            // It may already be gone
        }

        String failureMessage = "Service not found on swarm OR state='shutdown' OR " +
                "apparently active current state with exit status of -1 or desired state='shutdown' occurred " +
                "in all " + nrun + " attempts)";
        // Node killed or something, try to restart
        if (service.countRestarts() < maxRestarts) {
            try {
                doRestart(service, userI);
                return true;
            } catch (Exception e) {
                log.error("Unable to restart service {}", service.serviceId(), e);
                failureMessage = "Unable to restart";
            }
        }

        // Already restarted or unable to restart, fail it
        ServiceTask task = ServiceTask.builder()
                .serviceId(service.serviceId())
                .status(TaskStatus.TASK_STATE_FAILED)
                .exitCode(126L) // Docker code for "the contained command cannot be invoked"
                .message(ServiceTask.swarmNodeErrMsg)
                .err(failureMessage)
                .statusTime(new Date())
                .taskId("")
                .swarmNodeError(true)
                .build();
        ContainerHistory newHistoryItem = ContainerHistory.fromServiceTask(task);
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

		if (!request.inJMSQueue(this.getWorkflowStatus(userI, containerOrService))) {
			log.debug("Adding to finalizing queue: count {}, exitcode {}, issuccessfull {}, id {}, username {}, status {}",
                    count, request.getExitCodeString(), request.isSuccessful(), request.getId(), request.getUsername(),
                    containerOrService.status());
			addContainerHistoryItem(containerOrService, ContainerHistory.fromSystem(
			        request.makeJMSQueuedStatus(containerOrService.status()), "Queued for finalizing"),
                    userI);
			try {
				XDAT.sendJmsRequest(request);
			} catch (Exception e) {
			    String msg = "Finalizing message queue failed. Throw back into waiting and try again later.";
				addContainerHistoryItem(containerOrService, ContainerHistory.fromSystem(WAITING, msg), userI);
				log.error(msg, e);
			}
		} else {
			log.debug("Already in finalizing queue: count {}, exitcode {}, issuccessfull {}, id {}, username {}, status {}",
                    count, request.getExitCodeString(), request.isSuccessful(), request.getId(), request.getUsername(),
                    containerOrService.status());
		}
	}
	
    @Override
	public void consumeFinalize(final String exitCodeString, final boolean isSuccessfulStatus,
                                final Container containerOrService, final UserI userI) throws
            NoDockerServerException, ContainerException, NotFoundException, DockerServerException {
        try {
            addContainerHistoryItem(containerOrService, ContainerHistory.fromSystem(FINALIZING,
                    "Processing finished. Uploading files."), userI);
            log.debug("Finalizing containerOrService {}", containerOrService);
            final Container containerOrServiceWithAddedEvent = get(containerOrService.databaseId());
                ContainerServiceImpl.this.finalize(containerOrServiceWithAddedEvent, userI, exitCodeString,
                        isSuccessfulStatus);

            if (log.isDebugEnabled()) {
                int countOfContainersWaiting = containerEntityService.howManyContainersAreWaiting();
                int countOfContainersBeingFinalized = containerEntityService.howManyContainersAreBeingFinalized();
                log.debug("There are {} being finalized at present with {} waiting", countOfContainersBeingFinalized,
                        countOfContainersWaiting);
            }
        } catch (Exception e) {
            log.error("Finalization failed on containerOrService {}", containerOrService, e);
            throw e;
        }
	}

    @Override
    public boolean isWaiting(Container containerOrService){
    	return WAITING.equals(containerOrService.status());
    }
    @Override
    public boolean isFinalizing(Container containerOrService){
    	return FINALIZING.equals(containerOrService.status());
    }
    @Override
    public boolean isFailedOrComplete(Container containerOrService, UserI user){
        ContainerEntity entity = ContainerEntity.fromPojo(containerOrService);
        if (entity.statusIsTerminal()) {
            return true;
        }
        final String status = containerOrService.getWorkflowStatus(user);
        return status != null &&
                (status.contains(PersistentWorkflowUtils.FAILED) || status.contains(PersistentWorkflowUtils.COMPLETE));
    }

    @Override
    public void finalize(final String containerId, final UserI userI)
            throws NotFoundException, ContainerException, NoDockerServerException, DockerServerException {
        finalize(get(containerId), userI);
    }

    @Override
    public void finalize(final Container container, final UserI userI)
            throws ContainerException, DockerServerException, NoDockerServerException {
        String status = container.lastHistoryStatus();
        boolean isSuccessfulStatus = status == null || status.equals(FINALIZING) ||
                (container.isSwarmService() ?
                    ServiceTask.isSuccessfulStatus(status) :
                    DockerContainerEvent.isSuccessfulStatus(status));
        finalize(container, userI, container.exitCode(), isSuccessfulStatus);
    }

    @Override
    public void finalize(final Container notFinalized, final UserI userI, final String exitCode, boolean isSuccessfulStatus)
            throws ContainerException, NoDockerServerException, DockerServerException {
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
                        launchedWrapupContainers = true;
                        launchContainerFromDbObject(wrapupContainer, userI);
                    }
                }
            }
        }

        if (launchedWrapupContainers) {
            log.debug("Pausing finalization for container {} to wait for wrapup containers to finish.", databaseId);
            return;
        } else {
            log.debug("All wrapup containers are complete.");
        }

        // Once we are sure there are no wrapup containers left to launch, finalize
        final String containerOrService = notFinalized.isSwarmService() ? "service" : "container";
        final String containerOrServiceId = notFinalized.containerOrServiceId();
        log.info("Finalizing Container {}, {} id {}.", databaseId, containerOrService, containerOrServiceId);

        final Container finalized = containerFinalizeService.finalizeContainer(notFinalized, userI,
                failed, wrapupContainers);

        log.debug("Done uploading files for container {}. Now saving information about outputs.", databaseId);

        containerEntityService.update(fromPojo(finalized));

        // Now check if this container *is* a setup or wrapup container.
        // If so, we need to re-check the parent.
        // If this is a setup container, parent can (maybe) be launched.
        // If this is a wrapup container, parent can (maybe) be finalized.
        final Container parent = finalized.parent();
        if (parent == null) {
            // Nothing left to do. This container is done.
            log.debug("Done finalizing container {}, {} id {}.", databaseId, containerOrService, containerOrServiceId);
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
                final Runnable startMainContainer = new Runnable() {
                    @Override
                    public void run() {
                        // If none of the setup containers have failed and none of the exit codes are null,
                        // that means all the setup containers have succeeded.
                        // We should start the parent container.
                        log.info("All setup containers for parent Container {} are finished and not failed. Starting container id {}.", parentDatabaseId, parentContainerId);
                        try {
                            startContainer(userI, parent);
                        } catch (NoDockerServerException | ContainerException e) {
                            log.error("Failed to start parent Container {} with container id {}.", parentDatabaseId, parentContainerId);
                        }
                    }
                };

                checkIfSpecialContainersFailed(setupContainers, parent, startMainContainer, setupStr, userI);
            }
        } else if (subtype.equals(DOCKER_WRAPUP.getName())) {
            // This is a wrapup container.
            // Did this container succeed or fail?
            // If it failed, go mark all the other wrapup containers failed and also the parent.
            // If it succeeded, then finalize the parent.

            log.debug("Container {} is a wrapup container for parent container {}.", databaseId, parentDatabaseId);

            final List<Container> wrapupContainersForParent = retrieveWrapupContainersForParent(parentDatabaseId);
            if (wrapupContainersForParent.size() > 0) {
                final Runnable finalizeMainContainer = new Runnable() {
                    @Override
                    public void run() {
                        // If none of the wrapup containers have failed and none of the exit codes are null,
                        // that means all the wrapup containers have succeeded.
                        // We should finalize the parent container.
                        log.info("All wrapup containers for parent Ccntainer {} are finished and not failed. Finalizing container id {}.", parentDatabaseId, parentContainerId);
                        try {
                            ContainerServiceImpl.this.finalize(parent, userI);
                        } catch (NoDockerServerException | DockerServerException | ContainerException e) {
                            log.error("Failed to finalize parent Container {} with container id {}.", parentDatabaseId, parentContainerId);
                        }
                    }
                };

                checkIfSpecialContainersFailed(wrapupContainersForParent, parent, finalizeMainContainer, wrapupStr, userI);
            }
        }
    }

    private void checkIfSpecialContainersFailed(final List<Container> specialContainers,
                                                final Container parent,
                                                final Runnable successAction,
                                                final String setupOrWrapup,
                                                final UserI userI) {
        final long parentDatabaseId = parent.databaseId();
        final String parentContainerId = parent.containerId();

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

        if (numFailed > 0) {
            // If any of the special containers failed, we must kill the rest and fail the main container.
            log.debug("One or more {} containers have failed. Killing the rest and failing the parent.", setupOrWrapup);
            for (final Container specialContainer : specialContainers) {
                final long databaseId = specialContainer.databaseId();
                final String containerId = specialContainer.containerId();
                if (failedExitCode.contains(specialContainer)) {
                    log.debug("{} container {} with container id {} failed.", setupOrWrapup, databaseId, containerId);
                } else if (nullExitCode.contains(specialContainer)) {
                    log.debug("{} container {} with container id {} has no exit code. Attempting to kill it.", setupOrWrapup, databaseId, containerId);
                    try {
                        kill(specialContainer, userI);
                    } catch (NoDockerServerException | DockerServerException | NotFoundException e) {
                        log.error(String.format("Failed to kill %s container %d.", setupOrWrapup, databaseId), e);
                    }
                } else {
                    log.debug("{} container {} with container id {} succeeded.", setupOrWrapup, databaseId, containerId);
                }
            }

            final String failedContainerMessageTemplate = "ID %d, container id %s";
            final String failedContainerMessage;
            if (failedExitCode.size() == 1) {
                final Container failed = failedExitCode.get(0);
                failedContainerMessage = "Failed " + setupOrWrapup + " container: " +
                        String.format(failedContainerMessageTemplate, failed.databaseId(), failed.containerId());
            } else {
                final StringBuilder sb = new StringBuilder();
                sb.append("Failed ");
                sb.append(setupOrWrapup);
                sb.append("containers: ");
                sb.append(String.format(failedContainerMessageTemplate, failedExitCode.get(0).databaseId(), failedExitCode.get(0).containerId()));
                for (int i = 1; i < failedExitCode.size(); i++) {
                    sb.append("; ");
                    sb.append(String.format(failedContainerMessageTemplate, failedExitCode.get(i).databaseId(), failedExitCode.get(i).containerId()));
                }
                sb.append(".");
                failedContainerMessage = sb.toString();
            }

            log.info("Setting status to \"Failed {}\" for parent container {} with container id {}.", setupOrWrapup, parentDatabaseId, parentContainerId);
            ContainerHistory failureHist = ContainerHistory.fromSystem("Failed " + setupOrWrapup, failedContainerMessage);
            addContainerHistoryItem(parent, failureHist, userI);

            // If specialContainers are setup containers and there are also wrapup containers, we need to update their
            // statuses in the db (since they haven't been created or started, they don't need to be killed)
            if (setupOrWrapup.equals(setupStr)) {
                final List<Container> wrapupContainersForParent = retrieveWrapupContainersForParent(parentDatabaseId);
                for (Container wrapupContainer : wrapupContainersForParent) {
                    addContainerHistoryItem(wrapupContainer, failureHist, userI);
                }
            }

        } else if (numNull == numSpecial) {
            // This is an error. We know at least one setup container has finished because we have reached this "finalize" method.
            // At least one of the setup containers should have a non-null exit status.
            final String message = "All " + setupOrWrapup + " containers have null statuses, but one of them should be finished.";
            log.error(message);
            log.info("Setting status to \"Failed {}\" for parent container {} with container id {}.", setupOrWrapup, parentDatabaseId, parentContainerId);
            addContainerHistoryItem(parent, ContainerHistory.fromSystem(PersistentWorkflowUtils.FAILED + " " + setupOrWrapup, message), userI);
        } else if (numNull > 0) {
            final int numLeft = numSpecial - numNull;
            log.debug("Not changing parent status. {} {} containers left to finish.", numLeft, setupOrWrapup);
        } else {
            successAction.run();
        }
    }

    @Override
    public String kill(final String containerId, final UserI userI)
            throws NoDockerServerException, DockerServerException, NotFoundException {
        // TODO check user permissions. How?
        return kill(get(containerId), userI);
    }

    private String kill(final Container container, final UserI userI)
            throws NoDockerServerException, DockerServerException, NotFoundException {
        addContainerHistoryItem(container, ContainerHistory.fromUserAction(ContainerEntity.KILL_STATUS,
                userI.getLogin()), userI);

        String containerDockerId;
        if(container.isSwarmService()){
        	containerDockerId = container.serviceId();
            containerControlApi.killService(containerDockerId);
        }else{
        	containerDockerId = container.containerId();
        	containerControlApi.killContainer(containerDockerId);
        }
        return containerDockerId;
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
        final Map<String, InputStream> logStreams = Maps.newHashMap();
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
        final String logPath = container.getLogPath(logFileName);
        if (StringUtils.isBlank(logPath)) {
            try {
                DockerClient.LogsParam sincePrm = since != null ? DockerClient.LogsParam.since(since) : null;
                DockerClient.LogsParam timestampPrm =  DockerClient.LogsParam.timestamps(withTimestamps);
                // If log path is blank, that means we have not yet saved the logs from docker. Go fetch them now.
                if (ContainerService.STDOUT_LOG_NAME.contains(logFileName)) {
                    if (container.isSwarmService()) {
                        return new ByteArrayInputStream(containerControlApi.getServiceStdoutLog(container.serviceId(),
                                timestampPrm, sincePrm).getBytes());
                    } else {
                        return new ByteArrayInputStream(containerControlApi.getContainerStdoutLog(container.containerId(),
                                timestampPrm, sincePrm).getBytes());
                    }
                } else if (ContainerService.STDERR_LOG_NAME.contains(logFileName)) {
                    if (container.isSwarmService()) {
                        return new ByteArrayInputStream(containerControlApi.getServiceStderrLog(container.serviceId(),
                                timestampPrm, sincePrm).getBytes());
                    } else {
                        return new ByteArrayInputStream(containerControlApi.getContainerStderrLog(container.containerId(),
                                timestampPrm, sincePrm).getBytes());
                    }
                } else {
                    return null;
                }
            } catch (NoDockerServerException | DockerServerException e) {
                log.debug("No {} log for {}", logFileName, container.databaseId());
            }
        } else {
            // If log path is not blank, that means we have saved the logs to a file (processing has completed). Read it now.
            try {
                return new FileInputStream(logPath);
            } catch (FileNotFoundException e) {
                log.error("Container {} log file {} not found. Path: {}", container.databaseId(), logFileName, logPath);
            }
        }

        return null;
    }

    private String getWorkflowStatus(UserI userI, final Container container) {
     	   String workFlowId = container.workflowId();
     	   PersistentWorkflowI workflow = WorkflowUtils.getUniqueWorkflow(userI,workFlowId);
     	   return workflow.getStatus();
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
    private void handleFailure(@Nullable PersistentWorkflowI workflow, @Nullable final Exception source,
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
    public PersistentWorkflowI createContainerWorkflow(String xnatIdOrUri, String containerInputType,
                                                       String wrapperName, String projectId, UserI user) {
        if (xnatIdOrUri == null) {
            return null;
        }

        String xsiType;
        String xnatId;
        try {
            // Attempt to parse xnatIdOrUri as URI, from this, get archivable item for workflow
            ResourceData resourceData = catalogService.getResourceDataFromUri(xnatIdOrUri);
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
                    case SUBJECT:
                        xsiType = XnatSubjectdata.SCHEMA_ELEMENT_NAME;
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
            WorkflowUtils.save(workflow, workflow.buildEvent());
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
        final XFTItem rootInputObject = findRootInputObject(resolvedCommand, userI);
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
                workflow = WorkflowUtils.buildOpenWorkflow(userI, rootInputObject,
                        EventUtils.newEventInstance(EventUtils.CATEGORY.DATA, EventUtils.TYPE.PROCESS,
                                resolvedCommand.wrapperName(),
                                ContainerServiceImpl.containerLaunchJustification,
                                ""));
            } else {
                // Update it
                log.debug("Update workflow for Wrapper {} - Command {} - Image {}.",
                        resolvedCommand.wrapperName(), resolvedCommand.commandName(), resolvedCommand.image());
                // Update workflow fields
                workflow.setId(rootInputObject.getIDValue());
                workflow.setExternalid(PersistentWorkflowUtils.getExternalId(rootInputObject));
                workflow.setDataType(rootInputObject.getXSIType());
                workflow.setPipelineName(resolvedCommand.wrapperName());
            }
            workflow.setStatus(PersistentWorkflowUtils.QUEUED);
            WorkflowUtils.save(workflow, workflow.buildEvent());
            log.debug("Updated workflow {}.", workflow.getWorkflowId());
        } catch (Exception e) {
            log.error("Could not create/update workflow.", e);
        }
        return workflow;
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

    @Nullable
    private XFTItem findRootInputObject(final ResolvedCommand resolvedCommand, final UserI userI) {
        log.debug("Checking input values to find root XNAT input object.");
        final List<ResolvedInputTreeNode<? extends Command.Input>> flatInputTrees = resolvedCommand.flattenInputTrees();

        XFTItem rootInputValue = null;
        for (final ResolvedInputTreeNode<? extends Command.Input> node : flatInputTrees) {
            final Command.Input input = node.input();
            log.debug("Input \"{}\".", input.name());
            if (!(input instanceof Command.CommandWrapperExternalInput)) {
                log.debug("Skipping. Not an external wrapper input.");
                continue;
            }

            final String type = input.type();
            if (!(type.equals(PROJECT.getName()) || type.equals(SUBJECT.getName()) || type.equals(SESSION.getName()) || type.equals(SCAN.getName())
                    || type.equals(ASSESSOR.getName()) || type.equals(RESOURCE.getName()))) {
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

            if (rootInputValue != null) {
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
                final XnatModelObject parentSession = ((Scan) inputValueXnatObject).getSession(userI, false,
                        new HashSet<>());
                if (parentSession != null) {
                    xnatObjectToUseAsRoot = parentSession;
                } else {
                    // Ok, nevermind, use the scan anyway. It's not a huge thing.
                    xnatObjectToUseAsRoot = inputValueXnatObject;
                }
            } else {
                xnatObjectToUseAsRoot = inputValueXnatObject;
            }

            try {
                log.debug("Getting input value as XFTItem.");
                rootInputValue = xnatObjectToUseAsRoot.getXftItem(userI);
            } catch (Throwable t) {
                // If anything goes wrong, bail out. No workflow.
                log.error("That didn't work.", t);
                continue;
            }

            if (rootInputValue == null) {
                // I don't know if this is even possible
                log.debug("XFTItem is null.");
                continue;
            }

            // This is the first good input value.
            log.debug("Found a valid root XNAT input object: {}.", input.name());
        }

        if (rootInputValue == null) {
            // Didn't find any candidates
            log.debug("Found no valid root XNAT input object candidates.");
            return null;
        }

        // At this point, we know we found a single valid external input value.
        // We can declare the object in that value to be the root object.

        return rootInputValue;
    }

    @Nonnull
    private Container toPojo(@Nonnull final ContainerEntity containerEntity) {
        return Container.create(containerEntity);
    }

    @Nonnull
    private List<Container> toPojo(@Nonnull final List<ContainerEntity> containerEntityList) {
        return Lists.newArrayList(Lists.transform(containerEntityList, new Function<ContainerEntity, Container>() {
            @Override
            public Container apply(final ContainerEntity input) {
                return toPojo(input);
            }
        }));
    }

    @Nonnull
    private ContainerHistory toPojo(@Nonnull final ContainerEntityHistory containerEntityHistory) {
        return ContainerHistory.create(containerEntityHistory);
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
}
