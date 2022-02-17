package org.nrg.containers.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.auto.CommandSummaryForContext;
import org.nrg.containers.model.configuration.CommandConfiguration;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xdat.model.XnatImagesessiondataI;
import org.nrg.xdat.om.XnatExperimentdata;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xdat.om.XnatSubjectdata;
import org.nrg.xdat.om.base.BaseXnatProjectdata;
import org.nrg.xdat.om.base.auto.AutoXnatProjectdata;
import org.nrg.xdat.om.base.auto.AutoXnatSubjectdata;
import org.nrg.xft.exception.ElementNotFoundException;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.eventservice.actions.MultiActionProvider;
import org.nrg.xnat.eventservice.events.EventServiceEvent;
import org.nrg.xnat.eventservice.events.ScheduledEvent;
import org.nrg.xnat.eventservice.model.Action;
import org.nrg.xnat.eventservice.model.ActionAttributeConfiguration;
import org.nrg.xnat.eventservice.model.Subscription;
import org.nrg.xnat.eventservice.services.SubscriptionDeliveryEntityService;
import org.nrg.xnat.helpers.uri.UriParserUtils;
import org.nrg.xnat.turbine.utils.ArchivableItem;
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

    @Autowired
    public CommandActionProvider(final ContainerService containerService,
                                 final CommandService commandService,
                                 final ContainerConfigService containerConfigService,
                                 final ObjectMapper mapper,
                                 final SubscriptionDeliveryEntityService subscriptionDeliveryEntityService) {
        this.containerService = containerService;
        this.commandService = commandService;
        this.containerConfigService = containerConfigService;
        this.mapper = mapper;
        this.subscriptionDeliveryEntityService = subscriptionDeliveryEntityService;
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
        final String externalInputType;
        final String externalInputName;

        try {
            wrapperId = Long.parseLong(actionKeyToActionId(subscription.actionKey()));
        }catch(Exception e){
            log.error("Could not extract WrapperId from actionKey: {}", subscription.actionKey());
            log.error("Aborting subscription: {}", subscription.name());
            subscriptionDeliveryEntityService.addStatus(deliveryId, ACTION_FAILED, new Date(), "Could not extract WrapperId from actionKey:" + subscription.actionKey());
            return;
        }

        try{
            wrapper = commandService.getWrapper(wrapperId);
        } catch (NotFoundException e) {
            log.error("Wrapper not found with id: {} ", wrapperId);
            log.error("Aborting subscription: {}", subscription.name());
            subscriptionDeliveryEntityService.addStatus(deliveryId, ACTION_FAILED, new Date(), "Wrapper not found with id: " + wrapperId);
            return;
        }

        try{
            final Command.CommandWrapperExternalInput externalInput = getExternalInput(wrapper);
            externalInputName = externalInput.name();
            externalInputType = externalInput.type();
        } catch (Exception e) {
            log.error("Failed to determine external input type for command wrapper with id: {}", wrapperId);
            log.error("Aborting subscription: {}", subscription.name());
            subscriptionDeliveryEntityService.addStatus(deliveryId, ACTION_FAILED, new Date(), "Failed to determine external input type for command wrapper with id:" + wrapperId);
            return;
        }

        if( !(event instanceof ScheduledEvent) ) {
            try {
                final String projectId = (subscription.eventFilter().projectIds() == null || subscription.eventFilter().projectIds().isEmpty()) ? null :
                        (subscription.eventFilter().projectIds().size() == 1 ? subscription.eventFilter().projectIds().get(0) : event.getProjectId());
                final Map<String, String> inputValues = subscription.attributes() != null ? subscription.attributes() : Maps.newHashMap();
                inputValues.put(externalInputName, UriParserUtils.getArchiveUri(event.getObject(user)));
                containerService.launchContainer(Strings.isNullOrEmpty(projectId) ? null : projectId, 0L, null, wrapperId, externalInputName, inputValues, user);
                subscriptionDeliveryEntityService.addStatus(deliveryId, ACTION_STEP, new Date(), "Container queued.");
            } catch (Exception e) {
                log.error(e.getMessage(), e);
                log.error("Aborting subscription: {}", subscription.name());
                subscriptionDeliveryEntityService.addStatus(deliveryId, ACTION_FAILED, new Date(), e.getMessage());
            }
            return;
        }

        final List<String> projectIds =
                (subscription.eventFilter().projectIds() != null && !subscription.eventFilter().projectIds().isEmpty())
                            ? subscription.eventFilter().projectIds() :
                                    BaseXnatProjectdata.getAllXnatProjectdatas(user, false)
                                                        .stream().map(AutoXnatProjectdata::getId).collect(Collectors.toList());

        if(projectIds == null || projectIds.isEmpty()) {
            // Subscribed to all projects but there are no projects in XNAT.
            log.debug("No projects subscribed to event. Nothing to do.");
            subscriptionDeliveryEntityService.addStatus(deliveryId, ACTION_COMPLETE, new Date(), "No projects subscribed to event. Nothing to do.");
            return;
        }

        final Map<String, String> inputValues = subscription.attributes() != null ? subscription.attributes() : Maps.newHashMap();
        final Set<String> uris = projectIds.stream().map(pId -> getInputUrisForContainer(externalInputType, pId, wrapper.contexts(), user, deliveryId))
                                                    .flatMap(Set::stream).collect(Collectors.toSet());

        if(uris.isEmpty()){
            final String msg = String.format("No inputs of type: %s found. Nothing to do.", externalInputType);
            subscriptionDeliveryEntityService.addStatus(deliveryId, ACTION_COMPLETE, new Date(), msg);
            log.debug(msg);
            return;
        }

        try{
            final String inputStr = mapper.writeValueAsString(uris);
            inputValues.put(externalInputName, inputStr);
            containerService.bulkLaunch(projectIds.size() == 1 ? projectIds.get(0) : null, 0L, null, wrapperId, externalInputName, inputValues, user);
            subscriptionDeliveryEntityService.addStatus(deliveryId, ACTION_COMPLETE, new Date(), "Container(s) queued for " + (projectIds.size() == 1 ? ("project: " + projectIds.get(0)) : "multiple projects."));
        }catch(IOException e){
            log.error("Failed to execute Bulk Launch for wrapper: {}", wrapper.name(), e);
            subscriptionDeliveryEntityService.addStatus(deliveryId, ACTION_ERROR, new Date(), "Failed to queue containers for Scheduled Event");
        }
    }

    /**
     * Retrieve a list of archive uris
     * @param inputType - the external input type of the container
     * @param projectId - the projectId
     * @param contexts  - the wrapper contexts
     * @param user - the user
     * @param deliveryId - the subscription delivery id
     * @return A set containing the input uris in the project for the given input type
     */
    private Set<String> getInputUrisForContainer(String inputType, String projectId, Set<String> contexts, UserI user, Long deliveryId) {
        final XnatProjectdata projectData = BaseXnatProjectdata.getXnatProjectdatasById(projectId, user, false);
        if(projectData == null){
            final String msg = String.format("Project %s not found. Skipping. ", projectId);
            log.debug(msg);
            subscriptionDeliveryEntityService.addStatus(deliveryId, ACTION_ERROR, new Date(), msg);
            return Collections.emptySet();
        }

        final Set<String> inputUris = Sets.newHashSet();
        if(inputType.equalsIgnoreCase("project") && xsiTypesMatch(projectData.getXSIType(), contexts)){
            inputUris.add(UriParserUtils.getArchiveUri(projectData));
        }else if(inputType.equalsIgnoreCase("subject")){
            inputUris.addAll(projectData.getParticipants_participant()
                                        .stream()
                                        .filter(subj -> xsiTypesMatch(subj.getXSIType(), contexts))
                                        .filter(subj -> canUserEditPrimaryProject(user, subj, deliveryId))
                                        .map(UriParserUtils::getArchiveUri).collect(Collectors.toSet()));
        }else if(inputType.equalsIgnoreCase("session")){
            inputUris.addAll(projectData.getExperiments()
                                        .stream()
                                        .filter(exp -> xsiTypesMatch(exp.getXSIType(), contexts))
                                        .filter(exp -> exp instanceof XnatImagesessiondataI)
                                        .filter(exp -> canUserEditPrimaryProject(user, exp, deliveryId))
                                        .map(UriParserUtils::getArchiveUri).collect(Collectors.toSet()));
        } else if(inputType.equalsIgnoreCase("subject-assessor")){
            inputUris.addAll(projectData.getParticipants_participant()
                                        .stream()
                                        .filter(subj -> canUserEditPrimaryProject(user, subj, deliveryId))
                                        .map(AutoXnatSubjectdata::getExperiments_experiment)
                                        .flatMap(List::stream)
                                        .filter(exp -> xsiTypesMatch(exp.getXSIType(), contexts))
                                        .map(UriParserUtils::getArchiveUri).collect(Collectors.toSet()));
        }else if(inputType.equalsIgnoreCase(("scan"))){
            inputUris.addAll(projectData.getExperiments().stream()
                                        .filter(exp -> exp instanceof XnatImagesessiondataI)
                                        .filter(exp -> canUserEditPrimaryProject(user, exp, deliveryId))
                                        .map(XnatImagesessiondataI.class::cast)
                                        .map(XnatImagesessiondataI::getScans_scan)
                                        .flatMap(List::stream)
                                        .filter(scan -> xsiTypesMatch(scan.getXSIType(), contexts))
                                        .map(UriParserUtils::getArchiveUri).collect(Collectors.toSet()));
        }else if(inputType.equalsIgnoreCase("assessor")){
            inputUris.addAll(projectData.getExperiments()
                                        .stream()
                                        .filter(exp -> exp instanceof XnatImagesessiondataI)
                                        .filter(exp -> canUserEditPrimaryProject(user, exp, deliveryId))
                                        .map(XnatImagesessiondataI.class::cast)
                                        .map(XnatImagesessiondataI::getAssessors_assessor)
                                        .flatMap(List::stream)
                                        .filter(imgAsses -> xsiTypesMatch(imgAsses.getXSIType(), contexts))
                                        .map(UriParserUtils::getArchiveUri).collect(Collectors.toSet()));
        }
        return inputUris;
    }

    private boolean canUserEditPrimaryProject(UserI user, ArchivableItem item, Long deliveryId){
        XnatProjectdata primaryProject = null;
        if(item instanceof XnatExperimentdata){
            primaryProject = ((XnatExperimentdata) item).getPrimaryProject(false);
        }else if (item instanceof XnatSubjectdata){
            primaryProject = ((XnatSubjectdata) item).getPrimaryProject(false);
        }

        try{
            if(primaryProject != null && primaryProject.canEdit(user)){
                return true;
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }

        final String msg = String.format("User: %s doesn't have permission to run containers on the primary project. Skipping item: %s.", user.getUsername(), item.getId());
        subscriptionDeliveryEntityService.addStatus(deliveryId, ACTION_STEP, new Date(), msg);
        log.debug(msg);
        return false;
    }

    private boolean xsiTypesMatch(String xsiType, Set<String> types){
        try {
            return commandService.xsiTypesMatch(xsiType, types);
        } catch (ElementNotFoundException ex) {
            log.error(ex.getMessage(), ex);
            return false;
        }
    }

    private Command.CommandWrapperExternalInput getExternalInput(Command.CommandWrapper wrapper) throws Exception{
        final ImmutableList<Command.CommandWrapperExternalInput> externalInputs = wrapper.externalInputs();
        final List<String> types = Arrays.asList("project","subject","session","assessor","resource","scan","subject-assessor");
        for( Command.CommandWrapperExternalInput externalInput : externalInputs){
            if(types.contains(externalInput.type().toLowerCase())){
                return externalInput;
            }
        }
        throw new Exception("Failed to determine external input type for command wrapper with id: " + wrapper.id());
    }

    @Override
    public List<Action> getAllActions() {
        List<Action> actions = new ArrayList<>();
        List<Command> commands = commandService.getAll();
        for(Command command : commands){
            for(Command.CommandWrapper wrapper : command.xnatCommandWrappers()) {
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
        if(xsiTypes == null || xsiTypes.isEmpty()){
            xsiTypes = new ArrayList<>();
            xsiTypes.add(null);
        }
        try {
            Set<CommandSummaryForContext> available = new HashSet<>();
            if(projectId != null) {
                // Project configured Commands
                xsiTypes.forEach(xsiType ->
                {
                    try { available.addAll(commandService.available(projectId, xsiType, user));
                    } catch (ElementNotFoundException e) { log.error(e.getMessage()); }
                });
            } else {
                // Site configured Commands
                xsiTypes.forEach(xsiType ->
                {
                    try { available.addAll(commandService.available(xsiType, user));
                    } catch (ElementNotFoundException e) { log.error(e.getMessage()); }
                });
            }

            for(CommandSummaryForContext command : available){
                if(!command.enabled()) continue;
                Map<String, ActionAttributeConfiguration> attributes = new HashMap<>();
                try {
                    ImmutableMap<String, CommandConfiguration.CommandInputConfiguration> inputs;
                    if(!Strings.isNullOrEmpty(projectId)) {
                        inputs = commandService.getProjectConfiguration(projectId, command.wrapperId()).inputs();
                    } else {
                        inputs = commandService.getSiteConfiguration(command.wrapperId()).inputs();
                    }
                    for(Map.Entry<String, CommandConfiguration.CommandInputConfiguration> entry : inputs.entrySet()){
                        if ( entry.getValue() != null && entry.getValue().userSettable() != null && entry.getValue().userSettable() && entry.getValue().type() != null ) {
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
            for(Command.CommandWrapper wrapper : command.xnatCommandWrappers()){
                if(Long.toString(wrapper.id()).contentEquals(actionKeyToActionId(actionKey))){
                    if( (Strings.isNullOrEmpty(projectId) && containerConfigService.isEnabledForSite(wrapper.id())) ||
                            (!Strings.isNullOrEmpty(projectId) && containerConfigService.isEnabledForProject(projectId, wrapper.id())) ){
                        return true;
                    }
                }
            }
        }

        return false;
    }

    ActionAttributeConfiguration CommandInputConfig2ActionAttributeConfig(CommandConfiguration.CommandInputConfiguration commandInputConfiguration){
        return ActionAttributeConfiguration.builder()
                                    .description(commandInputConfiguration.description())
                                    .type(commandInputConfiguration.type())
                                    .defaultValue(commandInputConfiguration.defaultValue())
                                    .required(commandInputConfiguration.required())
                                    .build();
    }

}
