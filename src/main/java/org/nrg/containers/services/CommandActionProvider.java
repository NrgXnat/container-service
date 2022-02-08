package org.nrg.containers.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.auto.CommandSummaryForContext;
import org.nrg.containers.model.configuration.CommandConfiguration;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xdat.model.XnatImagesessiondataI;
import org.nrg.xdat.model.XnatSubjectassessordataI;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xdat.om.XnatSubjectdata;
import org.nrg.xdat.om.base.BaseXnatProjectdata;
import org.nrg.xdat.om.base.auto.AutoXnatProjectdata;
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
                                 final CommandEntityService commandEntityService,
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

        for(String projectId : projectIds) {
            final Map<String, String> inputValues = subscription.attributes() != null ? subscription.attributes() : Maps.newHashMap();
            final XnatProjectdata projectData = BaseXnatProjectdata.getXnatProjectdatasById(projectId, user, false);
            if(projectData == null){
                final String msg = String.format("Project %s not found. Skipping. ", projectId);
                log.debug(msg);
                subscriptionDeliveryEntityService.addStatus(deliveryId, ACTION_ERROR, new Date(), msg);
                continue;
            }

            final List<String> uris = getUrisForInputType(externalInputType, projectData);
            if(null == uris || uris.isEmpty()){
                final String msg = String.format("No inputs of type %s found in project %s. Skipping.", externalInputType, projectId);
                log.debug(msg);
                subscriptionDeliveryEntityService.addStatus(deliveryId, ACTION_STEP, new Date(), msg);
                continue;
            }

            try{
                final String inputStr = mapper.writeValueAsString(uris);
                inputValues.put(externalInputName, inputStr);
                containerService.bulkLaunch(projectId, 0L, null, wrapperId, externalInputName, inputValues, user);
                subscriptionDeliveryEntityService.addStatus(deliveryId, ACTION_COMPLETE, new Date(), "Container(s) queued for project: " + projectId);
            }catch(IOException e){
                log.error("Failed to execute Bulk Launch for wrapper: {} on project: {}", wrapper.name(), projectId, e);
                subscriptionDeliveryEntityService.addStatus(deliveryId, ACTION_ERROR, new Date(), "Failed to queue containers for project: " + projectId);
            }
        }
    }

    /**
     * Retrieve a list of archive uris
     * @param inputType - the external input type of the container
     * @param projectData - the project
     * @return
     * @throws Exception
     */
    private List<String> getUrisForInputType(String inputType, XnatProjectdata projectData) {
        final List<String> uris = new ArrayList<>();
        if(inputType.equalsIgnoreCase("project")){
            uris.add(UriParserUtils.getArchiveUri(projectData));
        }else if(inputType.equalsIgnoreCase("subject")){
            uris.addAll(projectData.getParticipants_participant().stream()
                    .map(UriParserUtils::getArchiveUri).collect(Collectors.toList()));
        }else if(inputType.equalsIgnoreCase("session")){
            projectData.getParticipants_participant()
                        .forEach(subject -> uris.addAll(subject.getExperiments_experiment().stream()
                            .filter(e -> e instanceof XnatImagesessiondataI)
                            .map(UriParserUtils::getArchiveUri).collect(Collectors.toList())));
        } else if(inputType.equalsIgnoreCase("subject-assessor")){
            projectData.getParticipants_participant()
                        .forEach(subject -> uris.addAll(subject.getExperiments_experiment().stream()
                            .map(UriParserUtils::getArchiveUri).collect(Collectors.toList())));
        }else if(inputType.equalsIgnoreCase(("scan"))){
            projectData.getExperiments().stream().filter(i -> i instanceof XnatImagesessiondataI)
                        .forEach(experiment ->
                            uris.addAll(((XnatImagesessiondataI)experiment).getScans_scan().stream()
                                .map(UriParserUtils::getArchiveUri).collect(Collectors.toList())));
        }else if(inputType.equalsIgnoreCase("assessor")){
            projectData.getExperiments().stream().filter(i -> i instanceof XnatImagesessiondataI)
                        .forEach(experiment ->
                            uris.addAll(((XnatImagesessiondataI)experiment).getAssessors_assessor().stream()
                                .map(UriParserUtils::getArchiveUri).collect(Collectors.toList())));
        }else{
            log.error("Input type: {} is not supported by Scheduled Events.", inputType);
        }
        return uris;
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
                    ImmutableMap<String, CommandConfiguration.CommandInputConfiguration> inputs = null;
                    if(!Strings.isNullOrEmpty(projectId)) {
                        inputs = commandService.getProjectConfiguration(projectId, command.wrapperId()).inputs();
                    } else {
                        inputs = commandService.getSiteConfiguration(command.wrapperId()).inputs();
                    }
                    for(Map.Entry<String, CommandConfiguration.CommandInputConfiguration> entry : inputs.entrySet()){
                        if ( entry.getValue() != null && entry.getValue().userSettable() != null && entry.getValue().userSettable() != false && entry.getValue().type() != null ) {
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
