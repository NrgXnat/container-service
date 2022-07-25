package org.nrg.containers.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.auto.CommandSummaryForContext;
import org.nrg.containers.model.command.entity.CommandWrapperInputType;
import org.nrg.containers.model.configuration.CommandConfiguration;
import org.nrg.containers.utils.ContainerServicePermissionUtils;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xdat.model.XnatImagesessiondataI;
import org.nrg.xdat.om.*;
import org.nrg.xdat.om.base.auto.AutoXnatProjectdata;
import org.nrg.xdat.om.base.auto.AutoXnatSubjectdata;
import org.nrg.xft.ItemI;
import org.nrg.xft.exception.ElementNotFoundException;
import org.nrg.xft.exception.FieldNotFoundException;
import org.nrg.xft.exception.XFTInitException;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.eventservice.actions.MultiActionProvider;
import org.nrg.xnat.eventservice.events.EventServiceEvent;
import org.nrg.xnat.eventservice.events.ScheduledEvent;
import org.nrg.xnat.eventservice.model.Action;
import org.nrg.xnat.eventservice.model.ActionAttributeConfiguration;
import org.nrg.xnat.eventservice.model.Subscription;
import org.nrg.xnat.eventservice.model.xnat.XnatModelObject;
import org.nrg.xnat.eventservice.services.EventService;
import org.nrg.xnat.eventservice.services.EventServiceComponentManager;
import org.nrg.xnat.eventservice.services.SubscriptionDeliveryEntityService;
import org.nrg.xnat.helpers.uri.UriParserUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static org.nrg.xnat.eventservice.entities.TimedEventStatusEntity.Status.*;

@Service
public class CommandActionProvider extends MultiActionProvider {

    private static final Logger log = LoggerFactory.getLogger(CommandActionProvider.class);

    private final ContainerService containerService;
    private final CommandService commandService;
    private final ContainerConfigService containerConfigService;
    private final ObjectMapper mapper;
    private final SubscriptionDeliveryEntityService subscriptionDeliveryEntityService;
    private final EventServiceComponentManager componentManager;
    private final EventService eventService;

    private final static List<CommandWrapperInputType> SCHEDULED_EVENT_SUPPORTED_TYPES
            = Arrays.asList(CommandWrapperInputType.PROJECT,
            CommandWrapperInputType.SUBJECT,
            CommandWrapperInputType.SESSION,
            CommandWrapperInputType.ASSESSOR,
            CommandWrapperInputType.SCAN,
            CommandWrapperInputType.PROJECT_ASSET,
            CommandWrapperInputType.SUBJECT_ASSESSOR);

    @Autowired
    public CommandActionProvider(final ContainerService containerService,
                                 final CommandService commandService,
                                 final ContainerConfigService containerConfigService,
                                 final ObjectMapper mapper,
                                 final SubscriptionDeliveryEntityService subscriptionDeliveryEntityService,
                                 final EventServiceComponentManager componentManager,
                                 final EventService eventService) {
        this.containerService = containerService;
        this.commandService = commandService;
        this.containerConfigService = containerConfigService;
        this.mapper = mapper;
        this.subscriptionDeliveryEntityService = subscriptionDeliveryEntityService;
        this.componentManager = componentManager;
        this.eventService = eventService;
    }

    @Override
    public String getDisplayName() {
        return "Container Service";
    }

    @Override
    public String getDescription() {
        return "This Action Provider facilitates linking Event Service events to Container Service commands.";
    }

    @Override
    public void processEvent(EventServiceEvent event, Subscription subscription, UserI user, Long deliveryId) {
        final long wrapperId;
        final Command.CommandWrapper wrapper;
        final CommandWrapperInputType externalInputType;
        final String externalInputName;

        try {
            wrapperId = Long.parseLong(actionKeyToActionId(subscription.actionKey()));
        } catch (Exception e) {
            log.error("Could not extract WrapperId from actionKey: {}", subscription.actionKey());
            log.error("Aborting subscription: {}", subscription.name());
            subscriptionDeliveryEntityService.addStatus(deliveryId, ACTION_FAILED, new Date(), "Could not extract WrapperId from actionKey:" + subscription.actionKey());
            return;
        }

        try {
            wrapper = commandService.getWrapper(wrapperId);
        } catch (NotFoundException e) {
            log.error("Wrapper not found with id: {} ", wrapperId);
            log.error("Aborting subscription: {}", subscription.name());
            subscriptionDeliveryEntityService.addStatus(deliveryId, ACTION_FAILED, new Date(), "Wrapper not found with id: " + wrapperId);
            return;
        }

        try {
            final Command.CommandWrapperExternalInput externalInput = getExternalInput(wrapper);
            externalInputName = externalInput.name();
            externalInputType = CommandWrapperInputType.fromName(externalInput.type());
            if (null == externalInputType) {
                throw new Exception("External input type is null.");
            }
        } catch (Exception e) {
            log.error("Failed to determine external input type for command wrapper with id: {}. Aborting subscription: {}", wrapperId, subscription.name());
            subscriptionDeliveryEntityService.addStatus(deliveryId, ACTION_FAILED, new Date(), "Failed to determine external input type for command wrapper with id:" + wrapperId);
            return;
        }

        if (!(event instanceof ScheduledEvent)) {
            try {
                final String projectId = (subscription.eventFilter().projectIds() == null || subscription.eventFilter().projectIds().isEmpty()) ? null :
                        (subscription.eventFilter().projectIds().size() == 1 ? subscription.eventFilter().projectIds().get(0) : event.getProjectId());
                final Map<String, String> inputValues = subscription.attributes() != null ? subscription.attributes() : new HashMap<>();
                final Object eventObject = event.getObject(user);

                final String inputUri;
                if (XnatResourcecatalog.class.isAssignableFrom(eventObject.getClass())) {
                    // TODO XNAT-7129: UriParserUtils::getArchiveUri returns null for XnatResourcecatalog types
                    //  so we need to build it relative to the parent URI. Remove after XNAT-7129 is fixed.
                    final XnatResourcecatalog resource = (XnatResourcecatalog) eventObject;
                    inputUri = UriParserUtils.getArchiveUri(resource.getParent()) + "/resources/" + resource.getLabel();
                } else {
                    inputUri = UriParserUtils.getArchiveUri(eventObject);
                }

                inputValues.put(externalInputName, inputUri);
                containerService.launchContainer(Strings.isNullOrEmpty(projectId) ? null : projectId, 0L, null, wrapperId, externalInputName, inputValues, user);
                subscriptionDeliveryEntityService.addStatus(deliveryId, ACTION_STEP, new Date(), "Container queued.");
            } catch (Exception e) {
                log.error(e.getMessage(), e);
                log.error("Aborting subscription: {}", subscription.name());
                subscriptionDeliveryEntityService.addStatus(deliveryId, ACTION_FAILED, new Date(), e.getMessage());
            }
            return;
        }

        if (!SCHEDULED_EVENT_SUPPORTED_TYPES.contains(externalInputType)) {
            final String msg = "External Input type:" + externalInputType + " is not supported by scheduled events.";
            log.error(msg);
            subscriptionDeliveryEntityService.addStatus(deliveryId, ACTION_FAILED, new Date(), msg);
            return;
        }

        final List<String> projectIds =
                (subscription.eventFilter().projectIds() != null && !subscription.eventFilter().projectIds().isEmpty())
                        ? subscription.eventFilter().projectIds() :
                        AutoXnatProjectdata.getAllXnatProjectdatas(user, false)
                                .stream().map(AutoXnatProjectdata::getId).collect(Collectors.toList());

        if (projectIds == null || projectIds.isEmpty()) {
            // Subscribed to all projects but there are no projects in XNAT.
            log.debug("No projects subscribed to event. Nothing to do.");
            subscriptionDeliveryEntityService.addStatus(deliveryId, ACTION_COMPLETE, new Date(), "No projects subscribed to event. Nothing to do.");
            return;
        }

        final Map<String, String> inputValues = subscription.attributes() != null ? subscription.attributes() : new HashMap<>();
        for (String projectId : projectIds) {
            final List<String> inputUris = getInputUrisForContainer(externalInputType, projectId, wrapper, user, deliveryId, subscription);

            if (inputUris.isEmpty()) {
                final String msg = "No inputs of type: " + externalInputType + " found in project: " + projectId + ". Nothing to do.";
                subscriptionDeliveryEntityService.addStatus(deliveryId, ACTION_STEP, new Date(), msg);
                log.debug(msg);
                continue;
            }

            try {
                final String inputStr = mapper.writeValueAsString(inputUris);
                inputValues.put(externalInputName, inputStr);
                containerService.bulkLaunch(projectId, 0L, null, wrapperId, externalInputName, inputValues, user);
                final String msg = "Containers queued for " + inputUris.size() + " "
                        + externalInputType.getName().toLowerCase() + "(s) in project: " + projectId;
                subscriptionDeliveryEntityService.addStatus(deliveryId, ACTION_STEP, new Date(), msg);
            } catch (IOException e) {
                final String msg = "Failed to queue containers for project: " + projectId;
                log.error("{} - Command Wrapper: {}", msg, wrapper.id(), e);
                subscriptionDeliveryEntityService.addStatus(deliveryId, ACTION_ERROR, new Date(), msg);
            }
        }
        subscriptionDeliveryEntityService.addStatus(deliveryId, ACTION_COMPLETE, new Date(), "Done");
    }

    /**
     * Retrieve a list of archive uris
     *
     * @param inputType    - the external input type of the container
     * @param projectId    - the projectId
     * @param wrapper      - the command wrapper
     * @param user         - the user
     * @param deliveryId   - the subscription delivery id
     * @param subscription - the subscription
     * @return A set containing the input uris in the project for the given input type
     */
    private List<String> getInputUrisForContainer(final CommandWrapperInputType inputType,
                                                  final String projectId,
                                                  final Command.CommandWrapper wrapper,
                                                  final UserI user,
                                                  final Long deliveryId, final Subscription subscription) {
        final List<String> inputUris = new ArrayList<>();
        final Set<String> contexts = wrapper.contexts();
        final XnatProjectdata projectData = AutoXnatProjectdata.getXnatProjectdatasById(projectId, user, false);

        if (projectData == null) {
            final String msg = "Project " + projectId + " not found. Skipping.";
            log.debug(msg);
            subscriptionDeliveryEntityService.addStatus(deliveryId, ACTION_ERROR, new Date(), msg);
            return Collections.emptyList();
        }

        switch (inputType) {
            case PROJECT:
                if (xsiTypesMatch(projectData.getXSIType(), contexts) && filterJsonForItem(projectData, subscription, user)) {
                    inputUris.add(UriParserUtils.getArchiveUri(projectData));
                }
                break;
            case SUBJECT:
                inputUris.addAll(projectData.getParticipants_participant()
                        .stream()
                        .filter(subj -> xsiTypesMatch(subj.getXSIType(), contexts))
                        .filter(subj -> filterJsonForItem(subj, subscription, user))
                        .filter(subj -> isNotShared(subj, projectId)) // TODO Remove for CS-754
                        .filter(subj -> ContainerServicePermissionUtils.userHasRequiredPermissions(user, projectId, subj, wrapper))
                        .map(UriParserUtils::getArchiveUri).collect(Collectors.toSet()));
                break;
            case SUBJECT_ASSESSOR:
                inputUris.addAll(projectData.getParticipants_participant()
                        .stream()
                        .map(AutoXnatSubjectdata::getExperiments_experiment)
                        .flatMap(List::stream)
                        .filter(XnatSubjectassessordata.class::isInstance)
                        .map(XnatSubjectassessordata.class::cast)
                        .filter(exp -> xsiTypesMatch(exp.getXSIType(), contexts))
                        .filter(exp -> filterJsonForItem(exp, subscription, user))
                        .filter(exp -> exp instanceof XnatImagesessiondata || isNotShared(exp, projectId)) // TODO Remove for CS-754
                        .filter(exp -> ContainerServicePermissionUtils.userHasRequiredPermissions(user, projectId, exp, wrapper))
                        .map(UriParserUtils::getArchiveUri).collect(Collectors.toSet()));
                break;
            case SESSION:
                inputUris.addAll(projectData.getExperiments()
                        .stream()
                        .filter(exp -> xsiTypesMatch(exp.getXSIType(), contexts))
                        .filter(XnatImagesessiondataI.class::isInstance)
                        .filter(exp -> filterJsonForItem(exp, subscription, user))
                        .filter(exp -> ContainerServicePermissionUtils.userHasRequiredPermissions(user, projectId, exp, wrapper))
                        .map(UriParserUtils::getArchiveUri).collect(Collectors.toSet()));
                break;
            case SCAN:
                inputUris.addAll(projectData.getExperiments().stream()
                        .filter(XnatImagesessiondataI.class::isInstance)
                        .map(XnatImagesessiondataI.class::cast)
                        .map(XnatImagesessiondataI::getScans_scan)
                        .flatMap(List::stream)
                        .filter(scan -> xsiTypesMatch(scan.getXSIType(), contexts))
                        .filter(scan -> filterJsonForItem(scan, subscription, user))
                        .filter(XnatImagescandata.class::isInstance)
                        .map(XnatImagescandata.class::cast)
                        .filter(scan -> isNotShared(scan, projectId)) // TODO Remove for CS-754
                        .filter(scan -> ContainerServicePermissionUtils.userHasRequiredPermissions(user, projectId, scan, wrapper))
                        .map(UriParserUtils::getArchiveUri).collect(Collectors.toSet()));
                break;
            case ASSESSOR:
                inputUris.addAll(projectData.getExperiments()
                        .stream()
                        .filter(XnatImagesessiondataI.class::isInstance)
                        .map(XnatImagesessiondataI.class::cast)
                        .map(XnatImagesessiondataI::getAssessors_assessor)
                        .flatMap(List::stream)
                        .filter(XnatImageassessordata.class::isInstance)
                        .map(XnatImageassessordata.class::cast)
                        .filter(imgAsses -> xsiTypesMatch(imgAsses.getXSIType(), contexts))
                        .filter(imgAsses -> filterJsonForItem(imgAsses, subscription, user))
                        .filter(imgAsses -> isNotShared(imgAsses, projectId)) // TODO Remove for CS-754
                        .filter(imgAsses -> ContainerServicePermissionUtils.userHasRequiredPermissions(user, projectId, imgAsses, wrapper))
                        .map(UriParserUtils::getArchiveUri).collect(Collectors.toSet()));
                break;
            case PROJECT_ASSET:
                inputUris.addAll(getProjectAssets(user, projectId)
                        .stream().filter(asset -> xsiTypesMatch(asset.getXSIType(), contexts))
                        .filter(asset -> filterJsonForItem(asset, subscription, user))
                        .filter(asset -> isNotShared(asset, projectId)) // TODO Remove for CS-754
                        .filter(asset -> ContainerServicePermissionUtils.userHasRequiredPermissions(user, projectId, asset, wrapper))
                        .map(UriParserUtils::getArchiveUri).collect(Collectors.toSet()));
                break;
            default:
                log.error("External input type: {} not supported by scheduled events.", inputType);
                break;
        }
        return inputUris;
    }

    private List<XnatAbstractprojectasset> getProjectAssets(UserI user, String projectId) {
        final String xmlPath = XnatAbstractprojectasset.SCHEMA_ELEMENT_NAME + "/project";
        return XnatAbstractprojectasset.getXnatAbstractprojectassetsByField(xmlPath, projectId, user, false);
    }

    private boolean filterJsonForItem(Object item, Subscription subscription, UserI user) {
        final String jsonItem;
        final XnatModelObject modelObject;
        try {
            modelObject = componentManager.getModelObject(item, user);
            if (modelObject != null) {
                jsonItem = mapper.writeValueAsString(modelObject);
            } else {
                log.debug("Could not serialize event object: {}", item);
                return false;
            }
        } catch (JsonProcessingException e) {
            log.error("Exception attempting to serialize: {}", item != null ? item.getClass().getCanonicalName() : "null", e);
            return false;
        }

        try {
            if (subscription.eventFilter() != null && !Strings.isNullOrEmpty(subscription.eventFilter().jsonPathFilter())) {
                if (!Strings.isNullOrEmpty(jsonItem)) {
                    List<String> filterResult = eventService.performJsonFilter(subscription, jsonItem);
                    if (!filterResult.isEmpty()) {
                        if (log.isDebugEnabled()) {
                            int substringLen = 200;
                            String jsonItemToLog = jsonItem.length() > substringLen ? jsonItem.substring(0, substringLen - 3) + "..." : jsonItem;
                            log.debug("JSONPath Filter Match - Serialized event:\n{}\nJSONPath Filter:\n{}", jsonItemToLog, subscription.eventFilter().jsonPathFilter());
                        }
                        return true;
                    }
                }
            } else {
                return true;
            }
        } catch (Throwable e) {
            log.error("Aborting Event Service object filtering ", e);
        }

        return false;
    }

    private boolean isNotShared(final ItemI item, final String projectId) {
        return StringUtils.equals(projectId, getPrimaryProjectId(item));
    }

    private String getPrimaryProjectId(ItemI item) {
        try {
            return item.getStringProperty("project");
        } catch (XFTInitException | ElementNotFoundException | FieldNotFoundException e) {
            return null;
        }
    }

    private boolean xsiTypesMatch(String xsiType, Set<String> types) {
        try {
            return commandService.xsiTypesMatch(xsiType, types);
        } catch (ElementNotFoundException ex) {
            log.error(ex.getMessage(), ex);
            return false;
        }
    }

    private Command.CommandWrapperExternalInput getExternalInput(Command.CommandWrapper wrapper) throws Exception {
        return wrapper.externalInputs()
                .stream()
                .findFirst()
                .orElseThrow(() -> new Exception("Failed to determine external input type for command wrapper with id: " + wrapper.id()));
    }

    @Override
    public List<Action> getAllActions() {
        List<Action> actions = new ArrayList<>();
        List<Command> commands = commandService.getAll();
        for (Command command : commands) {
            for (Command.CommandWrapper wrapper : command.xnatCommandWrappers()) {
                actions.add(Action.builder()
                        .id(String.valueOf(wrapper.id()))
                        .displayName(wrapper.name())
                        .description(wrapper.description())
                        .provider(this)
                        .actionKey(actionIdToActionKey(Long.toString(wrapper.id())))
                        .build());
            }
        }
        return actions;
    }


    @Override
    public List<Action> getActions(String projectId, List<String> xsiTypes, UserI user) {
        List<Action> actions = new ArrayList<>();
        if (xsiTypes == null || xsiTypes.isEmpty()) {
            xsiTypes = new ArrayList<>();
            xsiTypes.add(null);
        }
        try {
            Set<CommandSummaryForContext> available = new HashSet<>();
            if (projectId != null) {
                // Project configured Commands
                xsiTypes.forEach(xsiType ->
                {
                    try {
                        available.addAll(commandService.available(projectId, xsiType, user));
                    } catch (ElementNotFoundException e) {
                        log.error(e.getMessage());
                    }
                });
            } else {
                // Site configured Commands
                xsiTypes.forEach(xsiType ->
                {
                    try {
                        available.addAll(commandService.available(xsiType, user));
                    } catch (ElementNotFoundException e) {
                        log.error(e.getMessage());
                    }
                });
            }

            for (CommandSummaryForContext command : available) {
                if (!command.enabled()) continue;
                Map<String, ActionAttributeConfiguration> attributes = new HashMap<>();
                try {
                    ImmutableMap<String, CommandConfiguration.CommandInputConfiguration> inputs;
                    if (!Strings.isNullOrEmpty(projectId)) {
                        inputs = commandService.getProjectConfiguration(projectId, command.wrapperId()).inputs();
                    } else {
                        inputs = commandService.getSiteConfiguration(command.wrapperId()).inputs();
                    }
                    for (Map.Entry<String, CommandConfiguration.CommandInputConfiguration> entry : inputs.entrySet()) {
                        if (entry.getValue() != null && entry.getValue().userSettable() != null && entry.getValue().userSettable() && entry.getValue().type() != null) {
                            attributes.put(entry.getKey(), CommandInputConfig2ActionAttributeConfig(entry.getValue()));
                        }
                    }
                } catch (Exception e) {
                    log.error("Exception getting Command Configuration for command: " + command.commandName() + "\n" + e.getMessage());
                    e.printStackTrace();
                }

                actions.add(Action.builder()
                        .id(String.valueOf(command.wrapperId()))
                        .displayName(command.wrapperName())
                        .description(command.wrapperDescription())
                        .provider(this)
                        .actionKey(actionIdToActionKey(Long.toString(command.wrapperId())))
                        .attributes(attributes.isEmpty() ? null : attributes)
                        .build());
            }
        } catch (Throwable e) {
            log.error(e.getMessage());
            e.printStackTrace();
        }
        return actions;
    }

    @Override
    public Boolean isActionAvailable(final String actionKey, final String projectId, final UserI user) {
        for (Command command : commandService.getAll()) {
            for (Command.CommandWrapper wrapper : command.xnatCommandWrappers()) {
                if (Long.toString(wrapper.id()).contentEquals(actionKeyToActionId(actionKey))) {
                    if ((Strings.isNullOrEmpty(projectId) && containerConfigService.isEnabledForSite(wrapper.id())) ||
                            (!Strings.isNullOrEmpty(projectId) && containerConfigService.isEnabledForProject(projectId, wrapper.id()))) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    ActionAttributeConfiguration CommandInputConfig2ActionAttributeConfig(CommandConfiguration.CommandInputConfiguration commandInputConfiguration) {
        return ActionAttributeConfiguration.builder()
                .description(commandInputConfiguration.description())
                .type(commandInputConfiguration.type())
                .defaultValue(commandInputConfiguration.defaultValue())
                .required(commandInputConfiguration.required())
                .build();
    }

}
