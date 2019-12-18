package org.nrg.containers.services.impl;

import com.google.common.base.Function;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.api.ContainerControlApi;
import org.nrg.containers.events.model.ContainerEvent;
import org.nrg.containers.events.model.ServiceTaskEvent;
import org.nrg.containers.exceptions.CommandResolutionException;
import org.nrg.containers.exceptions.ContainerException;
import org.nrg.containers.exceptions.ContainerFinalizationException;
import org.nrg.containers.exceptions.DockerServerException;
import org.nrg.containers.exceptions.NoDockerServerException;
import org.nrg.containers.exceptions.UnauthorizedException;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.auto.ResolvedCommand;
import org.nrg.containers.model.command.auto.ResolvedInputTreeNode;
import org.nrg.containers.model.command.auto.ResolvedInputValue;
import org.nrg.containers.model.configuration.PluginVersionCheck;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.auto.Container.ContainerHistory;
import org.nrg.containers.model.container.auto.ServiceTask;
import org.nrg.containers.model.container.entity.ContainerEntity;
import org.nrg.containers.model.container.entity.ContainerEntityHistory;
import org.nrg.containers.model.xnat.Scan;
import org.nrg.containers.model.xnat.XnatModelObject;
import org.nrg.containers.services.CommandResolutionService;
import org.nrg.containers.services.ContainerEntityService;
import org.nrg.containers.services.ContainerFinalizeService;
import org.nrg.containers.services.ContainerService;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xdat.entities.AliasToken;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.security.helpers.Users;
import org.nrg.xdat.security.user.exceptions.UserInitException;
import org.nrg.xdat.security.user.exceptions.UserNotFoundException;
import org.nrg.xdat.services.AliasTokenService;
import org.nrg.xft.XFTItem;
import org.nrg.xft.event.EventUtils;
import org.nrg.xft.event.persist.PersistentWorkflowI;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.services.XnatAppInfo;
import org.nrg.xnat.utils.WorkflowUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

@Slf4j
@Service
public class ContainerServiceImpl implements ContainerService {
    private static final Pattern exitCodePattern = Pattern.compile("kill|die|oom\\((\\d+|x)\\)");
    private static final String MIN_XNAT_VERSION_REQUIRED = "1.7.5";

    private final ContainerControlApi containerControlApi;
    private final ContainerEntityService containerEntityService;
    private final CommandResolutionService commandResolutionService;
    private final AliasTokenService aliasTokenService;
    private final SiteConfigPreferences siteConfigPreferences;
    private final ContainerFinalizeService containerFinalizeService;
    private final XnatAppInfo xnatAppInfo;

    @Autowired
    public ContainerServiceImpl(final ContainerControlApi containerControlApi,
                                final ContainerEntityService containerEntityService,
                                final CommandResolutionService commandResolutionService,
                                final AliasTokenService aliasTokenService,
                                final SiteConfigPreferences siteConfigPreferences,
                                final ContainerFinalizeService containerFinalizeService,
                                final XnatAppInfo xnatAppInfo) {
        this.containerControlApi = containerControlApi;
        this.containerEntityService = containerEntityService;
        this.commandResolutionService = commandResolutionService;
        this.aliasTokenService = aliasTokenService;
        this.siteConfigPreferences = siteConfigPreferences;
        this.containerFinalizeService = containerFinalizeService;
        this.xnatAppInfo = xnatAppInfo;
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
    @Nonnull
    public List<Container> retrieveServices() {
        return toPojo(containerEntityService.retrieveServices());
    }

    @Override
    @Nonnull
    public List<Container> retrieveNonfinalizedServices() {
        return toPojo(containerEntityService.retrieveNonfinalizedServices());
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
        final ContainerEntityHistory containerEntityHistoryItem = containerEntityService.addContainerHistoryItem(fromPojo(container), fromPojo(history), userI);
        return containerEntityHistoryItem == null ? null : toPojo(containerEntityHistoryItem);
    }

    @Override
    @Nonnull
    public Container resolveCommandAndLaunchContainer(final long wrapperId,
                                                      final Map<String, String> inputValues,
                                                      final UserI userI)
            throws NoDockerServerException, DockerServerException, NotFoundException, CommandResolutionException, ContainerException, UnauthorizedException {
        return launchResolvedCommand(commandResolutionService.resolve(wrapperId, inputValues, userI), userI);
    }

    @Override
    @Nonnull
    public Container resolveCommandAndLaunchContainer(final long commandId,
                                                      final String wrapperName,
                                                      final Map<String, String> inputValues,
                                                      final UserI userI)
            throws NoDockerServerException, DockerServerException, NotFoundException, CommandResolutionException, ContainerException, UnauthorizedException {
        return launchResolvedCommand(commandResolutionService.resolve(commandId, wrapperName, inputValues, userI), userI);

    }

    @Override
    @Nonnull
    public Container resolveCommandAndLaunchContainer(final String project,
                                                      final long wrapperId,
                                                      final Map<String, String> inputValues,
                                                      final UserI userI)
            throws NoDockerServerException, DockerServerException, NotFoundException, CommandResolutionException, ContainerException, UnauthorizedException {
        return launchResolvedCommand(commandResolutionService.resolve(project, wrapperId, inputValues, userI), userI);
    }

    @Override
    @Nonnull
    public Container resolveCommandAndLaunchContainer(final String project,
                                                      final long commandId,
                                                      final String wrapperName,
                                                      final Map<String, String> inputValues,
                                                      final UserI userI)
            throws NoDockerServerException, DockerServerException, NotFoundException, CommandResolutionException, ContainerException, UnauthorizedException {
        return launchResolvedCommand(commandResolutionService.resolve(project, commandId, wrapperName, inputValues, userI), userI);

    }

    @Override
    @Nonnull
    public Container launchResolvedCommand(final ResolvedCommand resolvedCommand,
                                           final UserI userI)
            throws NoDockerServerException, DockerServerException, ContainerException, UnsupportedOperationException {
        return launchResolvedCommand(resolvedCommand, userI, null);
    }

    private Container launchResolvedCommand(final ResolvedCommand resolvedCommand,
                                            final UserI userI,
                                            final Container parent)
            throws NoDockerServerException, DockerServerException, ContainerException, UnsupportedOperationException {
        if (resolvedCommand.type().equals(DOCKER.getName()) ||
                resolvedCommand.type().equals(DOCKER_SETUP.getName()) ||
                resolvedCommand.type().equals(DOCKER_WRAPUP.getName())) {
            return launchResolvedDockerCommand(resolvedCommand, userI, parent);
        } else {
            throw new UnsupportedOperationException("Cannot launch a command of type " + resolvedCommand.type());
        }
    }

    @Nonnull
    private Container launchResolvedDockerCommand(final ResolvedCommand resolvedCommand,
                                                  final UserI userI,
                                                  final Container parent)
            throws NoDockerServerException, DockerServerException, ContainerException {
        log.info("Preparing to launch resolved command.");
        final ResolvedCommand preparedToLaunch = prepareToLaunch(resolvedCommand, parent, userI);

        log.info("Creating container from resolved command.");
        final Container createdContainerOrService = containerControlApi.createContainerOrSwarmService(preparedToLaunch, userI);

        log.info("Recording container launch.");
        final String workflowId = makeWorkflowIfAppropriate(resolvedCommand, createdContainerOrService, userI);
        final Container savedContainerOrService = toPojo(containerEntityService.save(fromPojo(
                createdContainerOrService.toBuilder()
                        .workflowId(workflowId)
                        .parent(parent)
                        .build()
        ), userI));

        if (resolvedCommand.wrapupCommands().size() > 0) {
            log.info("Creating wrapup container objects in database (not creating docker containers).");
            for (final ResolvedCommand resolvedWrapupCommand : resolvedCommand.wrapupCommands()) {
                final Container wrapupContainer = createWrapupContainerInDbFromResolvedCommand(resolvedWrapupCommand, savedContainerOrService, userI);
                log.debug("Created wrapup container {} for parent container {}.", wrapupContainer.databaseId(), savedContainerOrService.databaseId());
            }
        }

        if (resolvedCommand.setupCommands().size() > 0) {
            log.info("Launching setup containers.");
            for (final ResolvedCommand resolvedSetupCommand : resolvedCommand.setupCommands()) {
                launchResolvedCommand(resolvedSetupCommand, userI, savedContainerOrService);
            }
        } else {
            startContainer(userI, savedContainerOrService);
        }

        return savedContainerOrService;
    }

    private void startContainer(final UserI userI, final Container savedContainerOrService) throws NoDockerServerException, ContainerException {
        log.info("Starting container.");
        try {
            containerControlApi.startContainer(savedContainerOrService);
        } catch (DockerServerException e) {
            addContainerHistoryItem(savedContainerOrService, ContainerHistory.fromSystem("Failed", "Did not start." + e.getMessage()), userI);
            handleFailure(savedContainerOrService);
            throw new ContainerException("Failed to start");
        }
    }

    @Nonnull
    private Container createWrapupContainerInDbFromResolvedCommand(final ResolvedCommand resolvedCommand, final Container parent, final UserI userI) {
        final Container toCreate = Container.containerFromResolvedCommand(resolvedCommand, null, userI.getLogin()).toBuilder()
                .parent(parent)
                .subtype(DOCKER_WRAPUP.getName())
                .project(parent != null ? parent.project() : null)
                .build();
        return toPojo(containerEntityService.create(fromPojo(toCreate)));
    }

    @Nonnull
    private Container launchContainerFromDbObject(final Container toLaunch, final UserI userI) throws DockerServerException, NoDockerServerException, ContainerException {
        final Container preparedToLaunch = prepareToLaunch(toLaunch, userI);

        log.info("Creating docker container for wrapup container {}.", toLaunch.databaseId());
        final Container createdContainerOrService = containerControlApi.createContainerOrSwarmService(preparedToLaunch, userI);

        log.info("Updating wrapup container {}.", toLaunch.databaseId());
        containerEntityService.update(fromPojo(createdContainerOrService));

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
        if (container != null ) {
            final String userLogin = container.userId();
            try {
                final UserI userI = Users.getUser(userLogin);

                final Container containerWithAddedEvent = addContainerEventToHistory(event, userI);
                if (event.isExitStatus()) {
                    log.debug("Container is dead. Finalizing.");
                    finalize(containerWithAddedEvent, userI, event.exitCode());
                }
            } catch (UserInitException | UserNotFoundException e) {
                log.error("Could not update container status. Could not get user details for user " + userLogin, e);
            } catch (ContainerException | NoDockerServerException | DockerServerException e) {
                log.error("Container finalization failed.", e);
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
        if (StringUtils.isBlank(event.service().taskId())) {
            log.debug("Service \"{}\" has no task information yet. Setting it now.", task.serviceId());
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

        if (service != null) {
            final String userLogin = service.userId();
            try {
                final UserI userI = Users.getUser(userLogin);
                final ContainerHistory taskHistoryItem = ContainerHistory.fromServiceTask(task);
                final ContainerHistory createdTaskHistoryItem = addContainerHistoryItem(service, taskHistoryItem, userI);
                if (createdTaskHistoryItem == null) {
                    // We have already added this task and can safely skip it.
                    log.debug("Skipping task status we have already seen.");
                } else {
                    if (task.isExitStatus()) {
                        addContainerHistoryItem(service, ContainerHistory.fromSystem("Finalizing","Processing finished. Uploading files." ), userI);
                        log.debug("Service has exited. Finalizing.");
                        final String exitCodeString = task.exitCode() == null ? null : String.valueOf(task.exitCode());
                        final Container serviceWithAddedEvent = retrieve(service.databaseId());
                        finalize(serviceWithAddedEvent, userI, exitCodeString);
                    }
                }
            } catch (UserInitException | UserNotFoundException e) {
                log.error("Could not update container status. Could not get user details for user " + userLogin, e);
            } catch (ContainerException | NoDockerServerException | DockerServerException e) {
                log.error("Container finalization failed.", e);
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("Done processing service task event: " + event);
        }
    }

    @Override
    public void finalize(final String containerId, final UserI userI) throws NotFoundException, ContainerException, NoDockerServerException, DockerServerException {
        finalize(get(containerId), userI);
    }

    @Override
    public void finalize(final Container container, final UserI userI) throws ContainerException, DockerServerException, NoDockerServerException {
        finalize(container, userI, container.exitCode());
    }

    @Override
    public void finalize(final Container notFinalized, final UserI userI, final String exitCode) throws ContainerException, NoDockerServerException, DockerServerException {
        final long databaseId = notFinalized.databaseId();
        log.debug("Beginning finalization for container {}.", databaseId);

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
                    log.debug("Launching wrapup container {}.", wrapupContainer.databaseId());
                    // This wrapup container has not been launched yet. Launch it now.
                    launchedWrapupContainers = true;
                    launchContainerFromDbObject(wrapupContainer, userI);
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
        final String serviceOrContainer = notFinalized.isSwarmService() ? "service" : "container";
        final String serviceOrContainerId = notFinalized.isSwarmService() ? notFinalized.serviceId() : notFinalized.containerId();
        log.info("Finalizing Container {}, {} id {}.", databaseId, serviceOrContainer, serviceOrContainerId);

        final Container finalized = containerFinalizeService.finalizeContainer(notFinalized, userI, exitCodeIsFailed(exitCode), wrapupContainers);

        log.debug("Done uploading for Container {}. Now saving information about created outputs.", databaseId);

        containerEntityService.update(fromPojo(finalized));

        // Now check if this container *is* a setup or wrapup container.
        // If so, we need to re-check the parent.
        // If this is a setup container, parent can (maybe) be launched.
        // If this is a wrapup container, parent can (maybe) be finalized.
        final Container parent = finalized.parent();
        if (parent == null) {
            // Nothing left to do. This container is done.
            log.debug("Done finalizing Container {}, {} id {}.", databaseId, serviceOrContainer, serviceOrContainerId);
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

                checkIfSpecialContainersFailed(setupContainers, parent, startMainContainer, "Setup", userI);
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
                        log.info("All wrapup containers for parent Container {} are finished and not failed. Finalizing container id {}.", parentDatabaseId, parentContainerId);
                        try {
                            ContainerServiceImpl.this.finalize(parent, userI);
                        } catch (NoDockerServerException | DockerServerException | ContainerException e) {
                            log.error("Failed to finalize parent Container {} with container id {}.", parentDatabaseId, parentContainerId);
                        }
                    }
                };

                checkIfSpecialContainersFailed(wrapupContainersForParent, parent, finalizeMainContainer, "Wrapup", userI);
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
                failedContainerMessage = "Failed " + setupOrWrapup + " container: " + String.format(failedContainerMessageTemplate, failed.databaseId(), failed.containerId());
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
            addContainerHistoryItem(parent, ContainerHistory.fromSystem("Failed " + setupOrWrapup, failedContainerMessage), userI);
        } else if (numNull == numSpecial) {
            // This is an error. We know at least one setup container has finished because we have reached this "finalize" method.
            // At least one of the setup containers should have a non-null exit status.
            final String message = "All " + setupOrWrapup + " containers have null statuses, but one of them should be finished.";
            log.error(message);
            log.info("Setting status to \"Failed {}\" for parent container {} with container id {}.", setupOrWrapup, parentDatabaseId, parentContainerId);
            addContainerHistoryItem(parent, ContainerHistory.fromSystem("Failed " + setupOrWrapup, message), userI);
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

    @Override
    public String kill(final String project, final String containerId, final UserI userI)
            throws NoDockerServerException, DockerServerException, NotFoundException {
        // TODO check user permissions. How?

        return kill(get(containerId), userI);
    }
    private String kill(final Container container, final UserI userI)
            throws NoDockerServerException, DockerServerException, NotFoundException {
        addContainerHistoryItem(container, ContainerHistory.fromUserAction("Killed", userI.getLogin()), userI);

        final String containerDockerId = container.containerId();
        containerControlApi.killContainer(containerDockerId);
        return containerDockerId;
    }

    @Override
    @Nonnull
    public Map<String, InputStream> getLogStreams(final long id)
            throws NotFoundException, NoDockerServerException, DockerServerException {
        return getLogStreams(get(id));
    }

    @Override
    @Nonnull
    public Map<String, InputStream> getLogStreams(final String containerId)
            throws NotFoundException, NoDockerServerException, DockerServerException {
        return getLogStreams(get(containerId));
    }

    @Nonnull
    private Map<String, InputStream> getLogStreams(final Container container)
            throws NotFoundException, NoDockerServerException, DockerServerException {
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
            throws NotFoundException, NoDockerServerException, DockerServerException {
        return getLogStream(get(id), logFileName);
    }

    @Override
    @Nullable
    public InputStream getLogStream(final String containerId, final String logFileName)
            throws NotFoundException, NoDockerServerException, DockerServerException {
        return getLogStream(get(containerId), logFileName);
    }

    @Nullable
    private InputStream getLogStream(final Container container, final String logFileName)
            throws NoDockerServerException, DockerServerException {
        final String logPath = container.getLogPath(logFileName);
        if (StringUtils.isBlank(logPath)) {
            // If log path is blank, that means we have not yet saved the logs from docker. Go fetch them now.
            if (ContainerService.STDOUT_LOG_NAME.contains(logFileName)) {
                return new ByteArrayInputStream(containerControlApi.getStdoutLog(container).getBytes());
            } else if (ContainerService.STDERR_LOG_NAME.contains(logFileName)) {
                return new ByteArrayInputStream(containerControlApi.getStderrLog(container).getBytes());
            } else {
                return null;
            }
        } else {
            // If log path is not blank, that means we have saved the logs to a file. Read it now.
            try {
                return new FileInputStream(logPath);
            } catch (FileNotFoundException e) {
                log.error("Container %s log file %s not found. Path: %s", container.databaseId(), logFileName, logPath);
            }
        }

        return null;
    }

    private void handleFailure(final Container container) {
        // TODO handle failure
    }

    /**
     * Creates a workflow if possible and returns its ID.
     *
     * This is a way for us to show the
     * the container execution in the history table and as a workflow alert banner
     * (where enabled) without any custom UI work.
     *
     * It is possible to create a workflow for the execution if the resolved command
     * has one external input which is an XNAT object. If it has zero external inputs,
     * there is no object on which we can "hang" the workflow, so to speak. If it has more
     * than one external input, we don't know which is the one that should display the
     * workflow, so we don't make one.
     *
     * @param resolvedCommand A resolved command that will be used to launch a container
     * @param containerOrService The Container object which refers to either a container launched on
     *                           a single docker machine or a service created on a swarm
     * @param userI The user launching the container
     * @return ID of the created workflow, or null if no workflow was created
     */
    @Nullable
    private String makeWorkflowIfAppropriate(final ResolvedCommand resolvedCommand, final Container containerOrService, final UserI userI) {
        log.debug("Preparing to make workflow.");
        final XFTItem rootInputObject = findRootInputObject(resolvedCommand, userI);
        if (rootInputObject == null) {
            // We didn't find a root input XNAT object, so we can't make a workflow.
            log.debug("Cannot make workflow.");
            return null;
        }

        log.debug("Creating workflow for Wrapper {} - Command {} - Image {}.",
                resolvedCommand.wrapperName(), resolvedCommand.commandName(), resolvedCommand.image());
        try {
            final PersistentWorkflowI workflow = WorkflowUtils.buildOpenWorkflow(userI, rootInputObject,
                    EventUtils.newEventInstance(EventUtils.CATEGORY.DATA, EventUtils.TYPE.PROCESS,
                            resolvedCommand.wrapperName(),
                            "Container launch",
                            StringUtils.isNotBlank(containerOrService.serviceId()) ?
                                    containerOrService.serviceId() :
                                    containerOrService.containerId()));
            WorkflowUtils.save(workflow, workflow.buildEvent());
            log.debug("Created workflow {}.", workflow.getWorkflowId());
            return String.valueOf(workflow.getWorkflowId());
        } catch (Exception e) {
            log.error("Could not create workflow.", e);
        }

        return null;
    }

    @Nullable
    private XFTItem findRootInputObject(final ResolvedCommand resolvedCommand, final UserI userI) {
        log.debug("Checking input values to find root XNAT input object.");
        final List<ResolvedInputTreeNode<? extends Command.Input>> flatInputTrees = resolvedCommand.flattenInputTrees();

        XFTItem rootInputValue = null;
        for (final ResolvedInputTreeNode<? extends Command.Input> node : flatInputTrees) {


            if (rootInputValue != null) {
                // We have already seen one candidate for a root object.
                // Seeing this one means we have more than one, and won't be able to
                // uniquely resolve a root object.


                log.debug("Found another root XNAT input object: {}. I was expecting one. Using first: {}", node.input().name(), rootInputValue.getDBName());
                continue;
            }

            final Command.Input input = node.input();
            log.debug("Input \"{}\".", input.name());
            if (!(input instanceof Command.CommandWrapperExternalInput)) {
                log.debug("Skipping. Not an external wrapper input.");
                continue;
            }

            final String type = input.type();
            if (!(type.equals(PROJECT.getName()) || type.equals(PROJECT_ASSET.getName()) || type.equals(SUBJECT.getName()) || type.equals(SESSION.getName()) || type.equals(SCAN.getName())
                    || type.equals(ASSESSOR.getName()) || type.equals(RESOURCE.getName()))) {
                log.debug("Skipping. Input type \"{}\" is not an XNAT type.", type);
                continue;
            }

            final List<ResolvedInputTreeNode.ResolvedInputTreeValueAndChildren> valuesAndChildren = node.valuesAndChildren();
            if (valuesAndChildren == null || valuesAndChildren.isEmpty() || valuesAndChildren.size() > 1) {
                log.debug("Skipping. {} values.", (valuesAndChildren == null || valuesAndChildren.isEmpty()) ? "No" : "Multiple");
                continue;
            }

            final ResolvedInputValue externalInputValue = valuesAndChildren.get(0).resolvedValue();
            final XnatModelObject inputValueXnatObject = externalInputValue.xnatModelObject();

            if (inputValueXnatObject == null) {
                log.debug("Skipping. XNAT model object is null.");
                continue;
            }

            final XnatModelObject xnatObjectToUseAsRoot;
            if (type.equals(SCAN.getName())) {
                // If the external input is a scan, the workflow will not show up anywhere. So we
                // use its parent session as the root object instead.
                final XnatModelObject parentSession = ((Scan) inputValueXnatObject).getSession(userI);
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
            log.debug("Found a valid root XNAT input object.", input.name());
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
        // Assume that everything is fine unless the exit code is explicitly > 0.
        // So exitCode="0", ="", =null all count as not failed.
        boolean isFailed = false;
        if (StringUtils.isNotBlank(exitCode)) {
            Long exitCodeNumber = null;
            try {
                exitCodeNumber = Long.parseLong(exitCode);
            } catch (NumberFormatException e) {
                // ignored
            }

            isFailed = exitCodeNumber != null && exitCodeNumber > 0;
        }
        return isFailed;
    }
}
