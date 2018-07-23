package org.nrg.containers.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.auto.CommandSummaryForContext;
import org.nrg.containers.model.configuration.CommandConfiguration;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.xnat.Assessor;
import org.nrg.containers.model.xnat.Project;
import org.nrg.containers.model.xnat.Resource;
import org.nrg.containers.model.xnat.Scan;
import org.nrg.containers.model.xnat.Session;
import org.nrg.containers.model.xnat.Subject;
import org.nrg.containers.model.xnat.XnatModelObject;
import org.nrg.xdat.model.XnatImageassessordataI;
import org.nrg.xdat.model.XnatImagescandataI;
import org.nrg.xdat.model.XnatImagesessiondataI;
import org.nrg.xdat.model.XnatSubjectdataI;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xdat.om.XnatResourcecatalog;
import org.nrg.xft.exception.ElementNotFoundException;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.eventservice.actions.MultiActionProvider;
import org.nrg.xnat.eventservice.events.EventServiceEvent;
import org.nrg.xnat.eventservice.model.Action;
import org.nrg.xnat.eventservice.model.ActionAttributeConfiguration;
import org.nrg.xnat.eventservice.model.Subscription;
import org.nrg.xnat.eventservice.services.SubscriptionDeliveryEntityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.nrg.xnat.eventservice.entities.TimedEventStatusEntity.Status.ACTION_FAILED;
import static org.nrg.xnat.eventservice.entities.TimedEventStatusEntity.Status.ACTION_STEP;

@Service
public class CommandActionProvider extends MultiActionProvider {
    private final String DISPLAY_NAME = "Container Service";
    private final String DESCRIPTION = "This Action Provider facilitates linking Event Service events to Container Service commands.";

    private static final Logger log = LoggerFactory.getLogger(CommandActionProvider.class);

    private final ContainerService containerService;
    private final CommandService commandService;
    private final ContainerConfigService containerConfigService;
    private final ObjectMapper mapper;
    private SubscriptionDeliveryEntityService subscriptionDeliveryEntityService;

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
        return DISPLAY_NAME;
    }

    @Override
    public String getDescription() {
        return DESCRIPTION;
    }

    @Override
    public void processEvent(EventServiceEvent event, Subscription subscription, UserI user, Long deliveryId) {
        final Object eventObject = event.getObject();
        final long wrapperId;
        try {
            wrapperId = Long.parseLong(actionKeyToActionId(subscription.actionKey()));
        }catch(Exception e){
            log.error("Could not extract WrapperId from actionKey:" + subscription.actionKey());
            log.error("Aborting subscription: " + subscription.name());
            subscriptionDeliveryEntityService.addStatus(deliveryId, ACTION_FAILED, new Date(), "Could not extract WrapperId from actionKey:" + subscription.actionKey());
            return;
        }
        final Map<String,String> inputValues = subscription.attributes() != null ? subscription.attributes() : Maps.<String,String>newHashMap();

        // Setup XNAT Object for Container
        XnatModelObject modelObject = null;
        String objectLabel = "";
        if(eventObject instanceof XnatProjectdata){
            modelObject = new Project(((XnatProjectdata) eventObject));
            objectLabel = "project";
        } else if(eventObject instanceof XnatSubjectdataI){
            modelObject = new Subject((XnatSubjectdataI) eventObject);
            objectLabel = "subject";
        } else if(eventObject instanceof XnatImagesessiondataI){
            modelObject = new Session((XnatImagesessiondataI) eventObject);
            objectLabel = "session";
        } else if(eventObject instanceof XnatImagescandataI){
            Session session = new Session(((XnatImagescandataI)eventObject).getImageSessionId(), user);
            String sessionUri = session.getUri();
            modelObject = new Scan((XnatImagescandataI) eventObject, sessionUri, null);
            objectLabel = "scan";
        } else if(eventObject instanceof XnatImageassessordataI){
            modelObject = new Assessor((XnatImageassessordataI) eventObject);
            objectLabel = "assessor";
        } else if(eventObject instanceof XnatResourcecatalog){
            modelObject = new Resource((XnatResourcecatalog) eventObject);
            objectLabel = "resource";
        } else {
            log.error(String.format("Container Service does not support Event Object."));
        }
        String objectString = modelObject != null ? modelObject.getUri() : "";
        try {
            objectString = mapper.writeValueAsString(modelObject);
        } catch (JsonProcessingException e) {
            log.error(String.format("Could not serialize ModelObject %s to json.", objectLabel), e);
        }
        inputValues.put(objectLabel, objectString);
        Container container = null;
        try {
            container = containerService.resolveCommandAndLaunchContainer(wrapperId, inputValues, user);
        }catch (Throwable e){
            log.error("Error launching command wrapper {}\n{}", wrapperId, e.getMessage(), e);
            subscriptionDeliveryEntityService.addStatus(deliveryId, ACTION_FAILED, new Date(), "Error launching command wrapper" + e.getMessage());
        }
        if(container != null) {
            subscriptionDeliveryEntityService.addStatus(deliveryId, ACTION_STEP, new Date(), "Container " + container.containerId() + " launched.");
        }


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
    public List<Action> getActions(String projectId, String xsiType, UserI user) {
        List<Action> actions = new ArrayList<>();
        try {
            List<CommandSummaryForContext> available;
            if(projectId != null) {
                // Project configured Commands
                available = commandService.available(projectId, xsiType, user);
            } else {
                // Site configured Commands
                available = commandService.available(xsiType, user);
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
                        if(entry.getValue() != null && entry.getValue().userSettable() != null && entry.getValue().type() != null &&
                                (entry.getValue().type().equalsIgnoreCase("string") || entry.getValue().type().equalsIgnoreCase("boolean") || entry.getValue().type().equalsIgnoreCase("integer"))
                                ) {
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
        } catch (ElementNotFoundException e) {
            log.error(e.getMessage());
            e.printStackTrace();
        }
        return actions;
    }

    @Override
    public Boolean isActionAvailable(String actionKey, String projectId, String xnatType, UserI user) {
        for (Command command : commandService.getAll()) {
            for(Command.CommandWrapper wrapper : command.xnatCommandWrappers()){
                if(Long.toString(wrapper.id()).contentEquals(actionKeyToActionId(actionKey))){
                    if(Strings.isNullOrEmpty(xnatType) || wrapper.contexts().contains(xnatType)){
                        if( (Strings.isNullOrEmpty(projectId) && containerConfigService.isEnabledForSite(wrapper.id())) ||
                                (!Strings.isNullOrEmpty(projectId) && containerConfigService.isEnabledForProject(projectId, wrapper.id())) ){
                            return true;
                        }
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
