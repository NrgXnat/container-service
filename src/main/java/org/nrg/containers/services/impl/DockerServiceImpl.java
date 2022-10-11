package org.nrg.containers.services.impl;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.api.ContainerControlApi;
import org.nrg.containers.exceptions.DockerServerException;
import org.nrg.containers.exceptions.InvalidDefinitionException;
import org.nrg.containers.exceptions.NoDockerServerException;
import org.nrg.containers.exceptions.NotUniqueException;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.dockerhub.DockerHubBase;
import org.nrg.containers.model.dockerhub.DockerHubBase.DockerHub;
import org.nrg.containers.model.dockerhub.DockerHubBase.DockerHubWithPing;
import org.nrg.containers.model.image.docker.DockerImage;
import org.nrg.containers.model.image.docker.DockerImageAndCommandSummary;
import org.nrg.containers.model.server.docker.Backend;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServerWithPing;
import org.nrg.containers.services.CommandLabelService;
import org.nrg.containers.services.CommandService;
import org.nrg.containers.services.DockerHubService;
import org.nrg.containers.services.DockerHubService.DockerHubDeleteDefaultException;
import org.nrg.containers.services.DockerServerService;
import org.nrg.containers.services.DockerService;
import org.nrg.framework.exceptions.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DockerServiceImpl implements DockerService {

    private ContainerControlApi controlApi;
    private DockerHubService dockerHubService;
    private CommandService commandService;
    private DockerServerService dockerServerService;
    private final CommandLabelService commandLabelService;

    @Autowired
    public DockerServiceImpl(final ContainerControlApi controlApi,
                             final DockerHubService dockerHubService,
                             final CommandService commandService,
                             final DockerServerService dockerServerService,
                             final CommandLabelService commandLabelService) {
        this.controlApi = controlApi;
        this.dockerHubService = dockerHubService;
        this.commandService = commandService;
        this.dockerServerService = dockerServerService;
        this.commandLabelService = commandLabelService;
    }

    @Override
    public List<DockerHubWithPing> getHubs() {
        return ping(dockerHubService.getHubs());
    }

    @Override
    public DockerHubWithPing getHub(final long id) throws NotFoundException {
        return ping(dockerHubService.getHub(id));
    }

    @Override
    public DockerHubWithPing getHub(final String name) throws NotFoundException, NotUniqueException {
        return ping(dockerHubService.getHub(name));
    }

    @Override
    public DockerHubWithPing createHub(final DockerHub hub) {
        return ping(dockerHubService.create(hub));
    }

    @Override
    public DockerHubWithPing createHubAndSetDefault(final DockerHub hub, final String username, final String reason) {
        return ping(dockerHubService.createAndSetDefault(hub, username, reason));
    }

    @Override
    public void updateHub(final DockerHub hub) {
        dockerHubService.update(hub);
    }

    @Override
    public void updateHubAndSetDefault(final DockerHub hub, final String username, final String reason) {
        dockerHubService.updateAndSetDefault(hub, username, reason);
    }

    @Override
    public void setDefaultHub(final long id, final String username, final String reason) {
        dockerHubService.setDefault(id, username, reason);
    }

    @Override
    public void deleteHub(final long id) throws DockerHubDeleteDefaultException {
        dockerHubService.delete(id);
    }

    @Override
    public void deleteHub(final String name) throws DockerHubDeleteDefaultException, NotUniqueException {
        dockerHubService.delete(name);
    }

    @Override
    public DockerHubBase.DockerHubStatus pingHub(final long hubId) throws DockerServerException, NoDockerServerException, NotFoundException {
        final DockerHub hub = dockerHubService.getHub(hubId);
        return pingHub(hub);
    }

    @Override
    public DockerHubBase.DockerHubStatus pingHub(final long hubId, final String username, final String password, final String token, final String email)
            throws NotFoundException {
        final DockerHub hub = dockerHubService.getHub(hubId);
        return pingHub(hub, username, password, token, email);
    }

    @Override
    public DockerHubBase.DockerHubStatus pingHub(final String hubName)
            throws NotUniqueException, NotFoundException {
        final DockerHub hub = dockerHubService.getHub(hubName);
        return pingHub(hub);
    }

    @Override
    public DockerHubBase.DockerHubStatus pingHub(final String hubName, final String username, final String password, final String token, final String email)
            throws NotUniqueException, NotFoundException {
        final DockerHub hub = dockerHubService.getHub(hubName);
        return pingHub(hub, username, password, token, email);
    }

    private DockerHubBase.DockerHubStatus pingHub(final DockerHub hub) {
        return pingHub(hub, null, null, null, null);
    }

    private DockerHubBase.DockerHubStatus pingHub(final DockerHub hub, final String username, final String password, final String token, final String email) {
        return controlApi.pingHub(hub, username, password, token, email);
    }

    @Nonnull
    private DockerHubWithPing ping(final DockerHub hubBeforePing) {
        return DockerHubWithPing.create(hubBeforePing, pingHub(hubBeforePing));
    }

    @Nonnull
    private List<DockerHubWithPing> ping(final @Nonnull List<DockerHub> hubsBeforePing) {
        final List<DockerHubWithPing> hubsAfterPing = Lists.newArrayList();
        for (final DockerHub hubBeforePing : hubsBeforePing) {
            hubsAfterPing.add(ping(hubBeforePing));
        }
        return hubsAfterPing;
    }

    @Override
    public DockerImage pullFromHub(final long hubId, final String imageName, final boolean saveCommands)
            throws DockerServerException, NoDockerServerException, NotFoundException {
        return pullFromHub(dockerHubService.getHub(hubId), imageName, saveCommands);
    }

    @Override
    public DockerImage pullFromHub(final long hubId, final String imageName, final boolean saveCommands,
                                   final String username, final String password, final String token, final String email)
            throws DockerServerException, NoDockerServerException, NotFoundException {
        return pullFromHub(dockerHubService.getHub(hubId), imageName, saveCommands, username, password, token, email);
    }

    @Override
    public DockerImage pullFromHub(final String hubName, final String imageName, final boolean saveCommands)
            throws DockerServerException, NoDockerServerException, NotFoundException, NotUniqueException {
        return pullFromHub(dockerHubService.getHub(hubName), imageName, saveCommands);
    }

    @Override
    public DockerImage pullFromHub(final String hubName, final String imageName, final boolean saveCommands,
                                   final String username, final String password, final String token, final String email)
            throws DockerServerException, NoDockerServerException, NotFoundException, NotUniqueException {
        return pullFromHub(dockerHubService.getHub(hubName), imageName, saveCommands, username, password, token, email);
    }

    @Override
    public DockerImage pullFromHub(final String imageName, final boolean saveCommands)
            throws DockerServerException, NoDockerServerException, NotFoundException {
        return pullFromHub(dockerHubService.getDefault(), imageName, saveCommands);
    }

    private DockerImage pullFromHub(final DockerHub hub, final String imageName, final boolean saveCommands)
            throws NoDockerServerException, DockerServerException, NotFoundException {
        return pullFromHub(hub, imageName, saveCommands, hub.username(), hub.password(), hub.token(), hub.email());
    }

    private DockerImage pullFromHub(final DockerHub hub,
                                    final String imageName,
                                    final boolean saveCommands,
                                    final String username,
                                    final String password,
                                    final String token,
                                    final String email)
            throws NoDockerServerException, DockerServerException, NotFoundException {
        final DockerImage dockerImage = controlApi.pullImage(imageName, hub, username, password, token, email);
        if (saveCommands) {
            saveFromImageLabels(imageName, dockerImage);
        }
        return dockerImage;
    }

    @Override
    public DockerServerWithPing getServer() throws NotFoundException {
        final DockerServer dockerServer = dockerServerService.getServer();
        final boolean ping = controlApi.canConnect();
        return DockerServerWithPing.create(dockerServer, ping);
    }

    @Override
    public DockerServerWithPing setServer(final DockerServer server) throws InvalidDefinitionException {
        final DockerServer dockerServer = dockerServerService.setServer(server);
        final boolean ping = controlApi.canConnect();
        return DockerServerWithPing.create(dockerServer, ping);
    }

    @Override
    public String ping() throws NoDockerServerException, DockerServerException {
        return controlApi.ping();
    }

    @Override
    public List<DockerImage> getInstalledImages()
            throws NoDockerServerException, DockerServerException {
        return controlApi.getAllImages();

    }

    @Override
    public List<DockerImage> getAllImages()
            throws NoDockerServerException, DockerServerException {
        // Pre-populate list from Docker-installed images
        List<DockerImage> allImages = getInstalledImages();

        // Get set of unique image names referenced in all commands
        Set<String> cmdImageTags = commandService.getAll().stream()
                .filter(command -> StringUtils.isNotBlank(command.image()))
                .map(command -> command.image())
                .collect(Collectors.toCollection(HashSet::new));

        // Create Image objects for command images not already installed
        List<String> installedTags = allImages.stream().map(DockerImage::tags)
                .flatMap(List::stream).collect(Collectors.toList());
        for (final String cmdImageTag : cmdImageTags.stream()
                .filter(tag -> !installedTags.contains(tag))
                .collect(Collectors.toList())) {
            allImages.add(DockerImage.builder().addTag(cmdImageTag).build());
        }
        return allImages;
    }

    @Override
    @Nonnull
    public List<DockerImageAndCommandSummary> getImageSummaries()
            throws NoDockerServerException, DockerServerException {
        // TODO once I have multiple docker servers, I will have to go ask all of them for their images
        final DockerServer dockerServer;
        try {
            dockerServer = dockerServerService.getServer();
        } catch (NotFoundException e) {
            throw new NoDockerServerException(e);
        }
        final String server = dockerServer.name();

        final List<DockerImage> rawImages = controlApi.getAllImages();

        // Store the images by every name that someone might call them: all tags and id
        // final Map<String, DockerImage> imagesByIdUniqueValues = Maps.newHashMap();
        final Map<String, String> imageIdsByNameDuplicateValues = Maps.newHashMap();

        // Store the summaries indexed by image id
        final Map<String, DockerImageAndCommandSummary.Builder> imageSummaryBuildersByImageId = Maps.newHashMap();
        final Map<String, List<Command>> commandListsByImageId = Maps.newHashMap();
        for (final DockerImage image : rawImages) {

            if (StringUtils.isNotBlank(image.imageId())) {
                // Keep track of all the tags that the image uses. This will make the image
                // easier to find if a command uses one of these tags as its "image name".
                if (image.tags() != null && !image.tags().isEmpty()) {
                    for (final String tag : image.tags()) {
                        imageIdsByNameDuplicateValues.put(tag, image.imageId());
                    }
                }

                // Start building the image summary (but leave it partially built for now).
                // The reason for leaving it as a Builder is that we may need to modify the
                // list of commands later (when we have to reconcile the commands that are defined
                // in the image's labels with the commands we read from the database),
                // but if we fully build the image summary then the commands are in an ImmutableList.
                imageSummaryBuildersByImageId.put(image.imageId(),
                        DockerImageAndCommandSummary.builder()
                                .addDockerImage(image)
                                .server(server)
                );
                commandListsByImageId.put(image.imageId(),
                        commandLabelService.parseLabels(image)
                );
            } else {
                // If image has no ID, then we will have problems tracking it uniquely.
                // Just skip it.
            }
        }

        // Go through all commands in the database, update the image summaries we have with
        // any new info about these commands, and add new images summaries if we haven't made one yet.
        for (final Command command : commandService.getAll()) {
            final String imageNameUsedByTheCommand = command.image();
            if (StringUtils.isNotBlank(imageNameUsedByTheCommand)) {
                // The command refers to the image by some name. Let's see if we've already
                // started an image summary with that name...
                if (imageIdsByNameDuplicateValues.containsKey(imageNameUsedByTheCommand)) {

                    // We do recognize the image by this name, so we have already started building a summary.
                    // Merge this command into the list of the image's commands
                    //      i.e. add it if it isn't there, or update it if it already is there.
                    //
                    // Note: if we have the image name in the map imageIdsByNameDuplicateValue, we know that
                    // we also have some list of commands in the map commandListsByImageId, so we can safely
                    // check the former and get an object from the latter.
                    final String dockerImageId = imageIdsByNameDuplicateValues.get(imageNameUsedByTheCommand);
                    addOrUpdateCommand(commandListsByImageId.get(dockerImageId), command);
                } else {
                    // We do *not* recognize the image by this name. However, we may yet have a summary
                    // started for the image, just with different names. We must first check whether
                    //   A. docker recognizes the image by that name, or
                    //   B. docker does not recognize the image by that name
                    DockerImage dockerImage = null;
                    try {
                        dockerImage = controlApi.getImageById(imageNameUsedByTheCommand);
                    } catch (NotFoundException | NoDockerServerException ignored) {
                        // ignored
                    }

                    if (dockerImage != null) {
                        // This means A: we do have the image on the docker server, just not by this name
                        // Since we have already started summaries for all the images docker knows about,
                        // and docker knows about this one, then we are certain we have already started a
                        // summary for this image.
                        final String dockerImageId = dockerImage.imageId();

                        // This is a new name, so add it to the name cache
                        imageIdsByNameDuplicateValues.put(imageNameUsedByTheCommand, dockerImageId);

                        // Now add the command to or update the command in the list
                        addOrUpdateCommand(commandListsByImageId.get(dockerImageId), command);
                    } else {
                        // This means B: the command refers to some image that we do not have on the docker server.
                        // We still want to report a summary, but we have to create a new one with some information
                        // left blank.

                        // We use the command's imageName as a surrogate "id".
                        imageIdsByNameDuplicateValues.put(imageNameUsedByTheCommand, imageNameUsedByTheCommand);

                        imageSummaryBuildersByImageId.put(imageNameUsedByTheCommand,
                                DockerImageAndCommandSummary.builder()
                                        .addImageName(imageNameUsedByTheCommand)
                        );
                        commandListsByImageId.put(imageNameUsedByTheCommand,
                                Lists.newArrayList(command)
                        );
                    }
                }
            } else {
                // command does not refer to an image? Should not be possible...
                log.error("Command " + String.valueOf(command.id()) + " has a blank imageName.");
            }
        }

        // Now we go through the imagesummary builders and build all of them,
        // including the final list of commands.
        final List<DockerImageAndCommandSummary> summaries = Lists.newArrayList();
        for (final String imageId : imageSummaryBuildersByImageId.keySet()) {
            final DockerImageAndCommandSummary.Builder builder = imageSummaryBuildersByImageId.get(imageId);
            final List<Command> commands = commandListsByImageId.get(imageId);
            summaries.add(builder.commands(commands).build());
        }
        return summaries;
    }

    public DockerImage getImage(final String imageId)
            throws NoDockerServerException, NotFoundException {
        try {
            return controlApi.getImageById(imageId);
        } catch (DockerServerException e) {
            throw new NotFoundException(e);
        }
    }

    public void removeImageById(final String imageId, final Boolean force)
            throws NoDockerServerException, DockerServerException {
        final List<DockerImageAndCommandSummary> dockerImageAndCommandSummaries = getImageSummaries();

        controlApi.deleteImageById(imageId, force);

        for (final DockerImageAndCommandSummary dockerImageAndCommandSummary : dockerImageAndCommandSummaries) {
            if (dockerImageAndCommandSummary.imageId() != null &&
                    (dockerImageAndCommandSummary.imageId().equals(imageId) || dockerImageAndCommandSummary.imageId().contains(imageId))) {
                for (final Command command : dockerImageAndCommandSummary.commands()) {
                    // The commands in the command summary list can have ID 0 if they are in the image labels but not saved in DB.
                    if (command.id() != 0L) {
                        commandService.delete(command.id());
                    }
                }
            }
        }

    }

    @Override
    @Nonnull
    public List<Command> saveFromImageLabels(final String imageName) throws DockerServerException, NotFoundException, NoDockerServerException {
        return saveFromImageLabels(imageName, controlApi.getImageById(imageName));
    }

    @Nonnull
    private List<Command> saveFromImageLabels(final String imageName, final DockerImage dockerImage) {
        if (log.isDebugEnabled()) {
            log.debug("Parsing labels for " + imageName);
        }
        final List<Command> parsed = commandLabelService.parseLabels(imageName, dockerImage);

        if (parsed.isEmpty()) {
            log.debug("Did not find any command labels.");
            return parsed;
        }

        log.debug("Saving commands from image labels");
        return commandService.save(parsed);
    }

    private void addOrUpdateCommand(final List<Command> commandsList,
                                    final Command commandToAddOrUpdate) {
        // Check to see if the list of commands already has one with this name.
        // If so, we added the existing command from the labels.
        // It will not have an id, and might not have any xnat wrappers.
        // So we should replace it.
        boolean shouldUpdate = false;
        int updateIndex = -1;
        int numCommands = commandsList.size();
        for (int i = 0; i < numCommands; i++) {
            final Command existingCommand = commandsList.get(i);
            if (existingCommand.name() != null &&
                    existingCommand.name().equals(commandToAddOrUpdate.name())) {
                shouldUpdate = true;
                updateIndex = i;
                break;
            }
        }
        if (shouldUpdate && updateIndex > -1) {
            commandsList.remove(updateIndex);
            commandsList.add(updateIndex, commandToAddOrUpdate);
        } else {
            commandsList.add(commandToAddOrUpdate);
        }
    }

    @Override
    @Nonnull
    public Command getCommandByImage(final String imageWithCommandName) throws NotFoundException {
        final String[] imageSplitOnColon = imageWithCommandName.split(":");
        final String imageWithoutCommandName = imageSplitOnColon.length == 1 ? imageSplitOnColon[0] : imageSplitOnColon[0] + ":" + imageSplitOnColon[1];
        final String commandName;
        if (imageSplitOnColon.length > 3) {
            final StringBuilder sb = new StringBuilder(imageSplitOnColon[2]);
            for (int i = 3; i < imageSplitOnColon.length; i++) {
                sb.append(":");
                sb.append(imageSplitOnColon[i]);
            }
            commandName = sb.toString();
        } else if (imageSplitOnColon.length == 3) {
            commandName = imageSplitOnColon[2];
        } else {
            commandName = null;
        }

        try {
            log.debug("Attempting to pull image {}.", imageWithoutCommandName);
            pullFromHub(imageWithoutCommandName, true);
            log.debug("Successfully pulled image {}.", imageWithoutCommandName);
        } catch (Exception ignored) {
            // This could be a problem or it could not. We ignore it for now.
            // We may have the command saved already anyway. If we don't, we will soon find out.
            // And if we can't connect to docker, we will find that out soon enough too.
            log.debug("Could not pull image {}. Continuing.", imageWithoutCommandName);
        }

        log.debug("Getting all commands for image {}.", imageWithoutCommandName);
        final List<Command> commandsByImage = commandService.getByImage(imageWithoutCommandName);

        if (commandsByImage != null && commandsByImage.size() > 0) {
            if (StringUtils.isNotBlank(commandName)) {
                for (final Command commandByImage : commandsByImage) {
                    if (commandName.equals(commandByImage.name())) {
                        log.debug("Found command \"{}\".", commandByImage.name());
                        return commandByImage;
                    }
                }
            } else {
                if (commandsByImage.size() == 1) {
                    final Command command = commandsByImage.get(0);
                    log.debug("Found only one command: \"{}\".", command.name());
                    return command;
                }

                final String message = String.format("Found multiple commands for image %s. Could not distinguish because I was not given a command name. (Input: %s)", imageWithoutCommandName, imageWithCommandName);
                log.error(message);
                throw new NotFoundException(message);
            }
            final String message = String.format("Could not find a command with name %s for image %s. (Input: %s)", commandName, imageWithoutCommandName, imageWithCommandName);
            log.error(message);
            throw new NotFoundException(message);
        }
        final String message = String.format("Could not find any commands for image %s. (Input: %s)", imageWithoutCommandName, imageWithCommandName);
        log.error(message);
        throw new NotFoundException(message);
    }
}
