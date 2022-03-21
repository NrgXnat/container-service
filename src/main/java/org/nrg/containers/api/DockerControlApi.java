package org.nrg.containers.api;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.mandas.docker.client.DockerCertificates;
import org.mandas.docker.client.DockerClient;
import org.mandas.docker.client.DockerClient.ListImagesParam;
import org.mandas.docker.client.EventStream;
import org.mandas.docker.client.LogStream;
import org.mandas.docker.client.auth.ConfigFileRegistryAuthSupplier;
import org.mandas.docker.client.auth.FixedRegistryAuthSupplier;
import org.mandas.docker.client.builder.jersey.JerseyDockerClientBuilder;
import org.mandas.docker.client.exceptions.ContainerNotFoundException;
import org.mandas.docker.client.exceptions.DockerCertificateException;
import org.mandas.docker.client.exceptions.DockerException;
import org.mandas.docker.client.exceptions.ImageNotFoundException;
import org.mandas.docker.client.exceptions.ServiceNotFoundException;
import org.mandas.docker.client.exceptions.TaskNotFoundException;
import org.mandas.docker.client.messages.ContainerConfig;
import org.mandas.docker.client.messages.ContainerCreation;
import org.mandas.docker.client.messages.ContainerInfo;
import org.mandas.docker.client.messages.Event;
import org.mandas.docker.client.messages.HostConfig;
import org.mandas.docker.client.messages.Image;
import org.mandas.docker.client.messages.ImageInfo;
import org.mandas.docker.client.messages.PortBinding;
import org.mandas.docker.client.messages.RegistryAuth;
import org.mandas.docker.client.messages.RegistryConfigs;
import org.mandas.docker.client.messages.ServiceCreateResponse;
import org.mandas.docker.client.messages.mount.Mount;
import org.mandas.docker.client.messages.mount.TmpfsOptions;
import org.mandas.docker.client.messages.swarm.ContainerSpec;
import org.mandas.docker.client.messages.swarm.EndpointSpec;
import org.mandas.docker.client.messages.swarm.NetworkAttachmentConfig;
import org.mandas.docker.client.messages.swarm.Placement;
import org.mandas.docker.client.messages.swarm.PortConfig;
import org.mandas.docker.client.messages.swarm.ReplicatedService;
import org.mandas.docker.client.messages.swarm.Reservations;
import org.mandas.docker.client.messages.swarm.ResourceRequirements;
import org.mandas.docker.client.messages.swarm.ResourceSpec;
import org.mandas.docker.client.messages.swarm.Resources;
import org.mandas.docker.client.messages.swarm.RestartPolicy;
import org.mandas.docker.client.messages.swarm.ServiceMode;
import org.mandas.docker.client.messages.swarm.ServiceSpec;
import org.mandas.docker.client.messages.swarm.Task;
import org.mandas.docker.client.messages.swarm.TaskSpec;
import org.nrg.containers.events.model.DockerContainerEvent;
import org.nrg.containers.events.model.ServiceTaskEvent;
import org.nrg.containers.exceptions.ContainerBackendException;
import org.nrg.containers.exceptions.ContainerException;
import org.nrg.containers.exceptions.DockerServerException;
import org.nrg.containers.exceptions.NoContainerServerException;
import org.nrg.containers.exceptions.NoDockerServerException;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.auto.ResolvedCommand;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.auto.ContainerMessage;
import org.nrg.containers.model.container.auto.ServiceTask;
import org.nrg.containers.model.dockerhub.DockerHubBase.DockerHub;
import org.nrg.containers.model.image.docker.DockerImage;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.services.CommandLabelService;
import org.nrg.containers.services.DockerHubService;
import org.nrg.containers.services.DockerServerService;
import org.nrg.containers.utils.ShellSplitter;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.framework.services.NrgEventService;
import org.nrg.xft.security.UserI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.mandas.docker.client.DockerClient.EventsParam.since;
import static org.mandas.docker.client.DockerClient.EventsParam.type;
import static org.mandas.docker.client.DockerClient.EventsParam.until;
import static org.mandas.docker.client.messages.swarm.TaskStatus.TASK_STATE_FAILED;
import static org.nrg.containers.services.CommandLabelService.LABEL_KEY;
import static org.nrg.containers.utils.ContainerUtils.instanceOrDefault;

@Slf4j
@Service
public class DockerControlApi implements ContainerControlApi {

    private final DockerServerService dockerServerService;
    private final CommandLabelService commandLabelService;
    private final DockerHubService dockerHubService;
    private final NrgEventService eventService;

    @Autowired
    public DockerControlApi(final DockerServerService dockerServerService,
                            final CommandLabelService commandLabelService,
                            final DockerHubService dockerHubService,
                            final NrgEventService eventService) {
        this.dockerServerService = dockerServerService;
        this.commandLabelService = commandLabelService;
        this.dockerHubService = dockerHubService;
        this.eventService = eventService;
    }

    @Nonnull
    private DockerServer getServer() throws NoDockerServerException {
        try {
            return dockerServerService.getServer();
        } catch (NotFoundException e) {
            throw new NoDockerServerException(e);
        }
    }

    @Override
    public String ping() throws NoDockerServerException, DockerServerException {
        return ping(getServer());
    }

    private String ping(final DockerServer dockerServer) throws DockerServerException {
        return dockerServer.swarmMode() ? pingSwarmMaster(dockerServer) : pingServer(dockerServer);
    }

    private String pingServer(final DockerServer dockerServer) throws DockerServerException {
        try (final DockerClient client = getClient(dockerServer)) {
            return client.ping();
        } catch (DockerException | InterruptedException e) {
            log.error("Unable to connect with Docker server {}:\n{}", dockerServer == null ? "" : dockerServer.toString(), e.getMessage());
            throw new DockerServerException(e);
        }
    }

    private String pingSwarmMaster(final DockerServer dockerServer) throws DockerServerException {
        try (final DockerClient client = getClient(dockerServer)) {
            client.inspectSwarm();
            // If we got this far without an exception, then all is well.
        } catch (DockerException | InterruptedException e) {
            log.error(e.getMessage());
            throw new DockerServerException(e);
        }
        return "OK";
    }

    @Override
    public boolean canConnect() {
        try {
            final String pingResult = ping();
            return StringUtils.isNotBlank(pingResult) && pingResult.equals("OK");
        } catch (NoDockerServerException e) {
            log.error(e.getMessage());
        } catch (DockerServerException ignored) {
            // Any actual errors have already been logged. We can safely ignore them here.
        }

        return false;
    }

    @Override
    @Nonnull
    public String pingHub(final @Nonnull DockerHub hub) throws DockerServerException, NoDockerServerException {
        return pingHub(hub, null, null, null, null);
    }

    @Override
    @Nonnull
    public String pingHub(final @Nonnull DockerHub hub, final @Nullable String username, final @Nullable String password,
                          final @Nullable String token, final @Nullable String email)
            throws DockerServerException, NoDockerServerException {
        int status = 500;
        try (final DockerClient client = getClient()) {
            status = client.auth(registryAuth(hub, username, password, token, email, true));
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new DockerServerException(e);
        }
        return status < 400 ? "OK" : "";
    }

    @Nullable
    private RegistryAuth registryAuth(final @Nullable DockerHub hub, final @Nullable String username,
                                      final @Nullable String password, final @Nullable String token,
                                      final @Nullable String email) {
        return registryAuth(hub, username, password, token, email, false);
    }

    @Nullable
    private RegistryAuth registryAuth(final @Nullable DockerHub hub, final @Nullable String username,
                                      final @Nullable String password, final @Nullable String token,
                                      final @Nullable String email, boolean forPing) {
        // TODO "forPing" is a hack. client.auth() needs a RegistryAuth object; it doesn't default to config.json
        //  as client.pull() does. This is because the RegistryAuthSupplier associates RegistryAuth objects with
        //  image names, not hubs
        if (hub == null || !forPing && (username == null || password == null)) {
            return null;
        }
        return RegistryAuth.builder()
                .serverAddress(hub.url())
                .username(username == null ? hub.username() : username)
                .password(password == null ? hub.password() : password)
                .identityToken(token == null ? hub.token() : token)
                .email(email == null ? hub.email() : email)
                .build();
    }

    /**
     * Query Docker server for all images
     *
     * @return Image objects stored on docker server
     **/
    @Override
    @Nonnull
    public List<DockerImage> getAllImages() throws NoDockerServerException, DockerServerException {
        return getImages(null);
    }

    /**
     * Query Docker server for images with parameters
     *
     * @param params Map of query parameters (name = value)
     * @return Image objects stored on docker server meeting the query parameters
     **/
    @Nonnull
    private List<DockerImage> getImages(final Map<String, String> params)
            throws NoDockerServerException, DockerServerException {
        return _getImages(params).stream().map(this::spotifyToNrg).collect(Collectors.toList());
    }

    private List<org.mandas.docker.client.messages.Image> _getImages(final Map<String, String> params)
            throws NoDockerServerException, DockerServerException {
        // Transform param map to ListImagesParam array
        final List<ListImagesParam> dockerParamsList = Lists.newArrayList();
        if (params != null && params.size() > 0) {
            for (final Map.Entry<String, String> param : params.entrySet()) {
                dockerParamsList.add(
                        ListImagesParam.create(param.getKey(), param.getValue())
                );
            }
        }
        final ListImagesParam[] dockerParams = dockerParamsList.toArray(new ListImagesParam[0]);

        try (final DockerClient dockerClient = getClient()) {
            return dockerClient.listImages(dockerParams);
        } catch (DockerException | InterruptedException e) {
            log.error("Failed to list images. {}", e.getMessage(), e);
            throw new DockerServerException(e);
        } catch (Error e) {
            log.error("Failed to list images. {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Query Docker server for image by name
     *
     * @param imageId ID of image
     * @return Image stored on docker server with the given name
     **/
    @Override
    @Nonnull
    public DockerImage getImageById(final String imageId)
        throws NotFoundException, DockerServerException, NoDockerServerException {
        try (final DockerClient client = getClient()) {
            return getImageById(imageId, client);
        }
    }

    private DockerImage getImageById(final String imageId, final DockerClient client)
            throws DockerServerException, NotFoundException {
        final DockerImage image = spotifyToNrg(_getImageById(imageId, client));
        if (image != null) {
            return image;
        }
        throw new NotFoundException(String.format("Could not find image %s", imageId));
    }

    private org.mandas.docker.client.messages.ImageInfo _getImageById(final String imageId, final DockerClient client)
            throws DockerServerException, NotFoundException {
        try {
            return client.inspectImage(imageId);
        } catch (ImageNotFoundException e) {
            throw new NotFoundException(e);
        } catch (DockerException | InterruptedException e) {
            throw new DockerServerException(e);
        }
    }

    @Override
    public void deleteImageById(final String id, final Boolean force) throws NoDockerServerException, DockerServerException {
        try (final DockerClient dockerClient = getClient()) {
            dockerClient.removeImage(id, force, false);
        } catch (DockerException|InterruptedException e) {
            throw new DockerServerException(e);
        }
    }

    @Override
    @Nullable
    public DockerImage pullImage(final String name) throws NoDockerServerException, DockerServerException, NotFoundException {
        return pullImage(name, null);
    }

    @Override
    @Nullable
    public DockerImage pullImage(final String name, final @Nullable DockerHub hub)
            throws NoDockerServerException, DockerServerException, NotFoundException {
        return hub != null ?
                pullImage(name, hub, hub.username(), hub.password(), hub.token(), hub.email()) :
                pullImage(name, hub, null, null, null, null);
    }

    @Override
    @Nullable
    public DockerImage pullImage(final String name, final @Nullable DockerHub hub, final @Nullable String username,
                                 final @Nullable String password, final @Nullable String token, final @Nullable String email)
            throws NoDockerServerException, DockerServerException, NotFoundException {
        final DockerClient client = getClient();
        _pullImage(name, hub != null ? registryAuth(hub, username, password, token, email) : null, client);  // We want to throw NotFoundException here if the image is not found on the hub
        try {
            return getImageById(name, client);  // We don't want to throw NotFoundException from here. If we can't find the image here after it has been pulled, that is a server error.
        } catch (NotFoundException e) {
            final String m = String.format("Image \"%s\" was not found", name);
            log.error(m);
            throw new DockerServerException(e);
        }
    }

    private void _pullImage(final @Nonnull String name, final @Nullable RegistryAuth registryAuth, final @Nonnull DockerClient client) throws DockerServerException, NotFoundException {
        try {
            if (registryAuth == null) {
                client.pull(name);
            } else {
                client.pull(name, registryAuth);
            }
        } catch (ImageNotFoundException e) {
            throw new NotFoundException(e.getMessage());
        } catch (DockerException | InterruptedException e) {
            log.error(e.getMessage());
            throw new DockerServerException(e);
        }
    }

    /**
     * Create (but do not start) a container backend object
     *
     * @param resolvedCommand A Command with all inputs fully resolved
     * @param user The XNAT user launching the container
     * @return A Container representing the backend object
     */
    @Override
    public Container create(ResolvedCommand resolvedCommand, UserI user) throws NoContainerServerException, ContainerBackendException, ContainerException {
        final Container toCreate = Container
                .builderFromResolvedCommand(resolvedCommand)
                .userId(user.getLogin())
                .build();
        return create(toCreate, user);
    }

    /**
     * Create (but do not start) a container backend object
     *
     * @param toCreate A Container object populated with all the information needed to launch
     * @param user The XNAT user launching the container
     * @return A Container representing the backend object
     */
    @Override
    public Container create(Container toCreate, UserI user) throws NoContainerServerException, ContainerBackendException, ContainerException {
        try {
            createDirectoriesForMounts(toCreate);
        } catch (IOException e) {
            throw new ContainerException("Unable to createDirectoriesForMounts", e);
        }

        final DockerServer server = getServer();

        final Container.Builder createdBuilder = toCreate
                .toBuilder()
                .userId(user.getLogin());

        if (server.swarmMode()) {
            final String serviceId = createDockerSwarmService(toCreate, server, NumReplicas.ZERO);
            createdBuilder
                    .serviceId(serviceId)
                    .swarm(true);
        } else {
            final String containerId = createDockerContainer(toCreate, server);
            createdBuilder
                    .containerId(containerId)
                    .swarm(false);
        }

        return createdBuilder
                .userId(user.getLogin())
                .build();

    }

    /**
     * Create (but do not start) a container backend object
     *
     * @param resolvedCommand A ResolvedDockerCommand. All templates are resolved, all mount paths exist.
     * @param userI The XNAT user launching the container
     * @return Created Container or Service
     * @deprecated Use {@link ContainerControlApi#create(ResolvedCommand, UserI)} instead
     **/
    @Override
    @Deprecated
    public Container createContainerOrSwarmService(final ResolvedCommand resolvedCommand, final UserI userI)
            throws NoDockerServerException, DockerServerException, ContainerException {
        try {
            return create(resolvedCommand, userI);
        } catch (NoContainerServerException e) {
            throw (e instanceof NoDockerServerException) ? (NoDockerServerException) e :
                    new NoDockerServerException(e.getMessage(), e.getCause());
        } catch (ContainerBackendException e) {
            throw (e instanceof DockerServerException) ? (DockerServerException) e :
                    new DockerServerException(e.getMessage(), e.getCause());
        }
    }

    /**
     * Create (but do not start) a container backend object
     *
     * @param container A Container object, populated with all the information needed to launch.
     * @param userI The XNAT user launching the container
     * @return Created Container or Service
     * @deprecated Use {@link ContainerControlApi#create(Container, UserI)} instead.
     **/
    @Override
    @Deprecated
    public Container createContainerOrSwarmService(final Container container, final UserI userI) throws NoDockerServerException, ContainerException, DockerServerException {
        try {
            return create(container, userI);
        } catch (NoContainerServerException e) {
            throw (e instanceof NoDockerServerException) ? (NoDockerServerException) e :
                    new NoDockerServerException(e.getMessage(), e.getCause());
        } catch (ContainerBackendException e) {
            throw (e instanceof DockerServerException) ? (DockerServerException) e :
                    new DockerServerException(e.getMessage(), e.getCause());
        }
    }

    private void createDirectoriesForMounts(final Container toCreate) throws IOException {
        final List<Container.ContainerMount> containerMounts = toCreate.mounts() == null ? Collections.emptyList() : toCreate.mounts();
        for (final Container.ContainerMount mount : containerMounts) {
            final Path mountFile = Paths.get(mount.xnatHostPath());
            if (!Files.isRegularFile(mountFile)) {
                Files.createDirectories(mountFile);
            }
        }
    }

    /**
     * Create a container on docker according to the given Container object
     *
     * @param toCreate A Container defining all the attributes of the container to create
     * @param server DockerServer preferences
     * @return Docker container ID
     */
    private String createDockerContainer(final Container toCreate, final DockerServer server)
            throws DockerServerException, ContainerException {

        final Map<String, List<PortBinding>> portBindings = Maps.newHashMap();
        for (final Map.Entry<String, String> portEntry : toCreate.ports().entrySet()) {
            final String containerPort = portEntry.getKey();
            final String hostPort = portEntry.getValue();

            if (StringUtils.isNotBlank(containerPort) && StringUtils.isNotBlank(hostPort)) {
                final PortBinding portBinding = PortBinding.of(null, hostPort);
                portBindings.put(containerPort + "/tcp", Lists.newArrayList(portBinding));

            } else {
                // One or both of hostPost and containerPort is blank.
                final String message;
                if (StringUtils.isBlank(containerPort)) {
                    message = "Container port is blank.";
                } else if (StringUtils.isNotBlank(hostPort)) {
                    message = "Host port is blank";
                } else {
                    message = "Container and host ports are blank";
                }
                log.error(message);
                throw new ContainerException(message);
            }
        }

        final HostConfig.Builder hostConfigBuilder =
                HostConfig.builder()
                        .autoRemove(toCreate.autoRemove())
                        .runtime(instanceOrDefault(toCreate.runtime(), ""))
                        .ipcMode(instanceOrDefault(toCreate.ipcMode(), ""))
                        .binds(toCreate.bindMountStrings())
                        .portBindings(portBindings)
                        .memoryReservation(toCreate.reserveMemoryBytes())
                        .memory(toCreate.limitMemoryBytes())
                        .nanoCpus(toCreate.nanoCpus());

        if (toCreate.shmSize() != null && toCreate.shmSize() >= 0) {
            hostConfigBuilder.shmSize(toCreate.shmSize());
        }
        if (!Strings.isNullOrEmpty(toCreate.network())) {
            hostConfigBuilder.networkMode(toCreate.network());
        }

        final Map<String, String> ulimits = toCreate.ulimits();
        if (ulimits != null && !ulimits.isEmpty()){
            List<HostConfig.Ulimit> hostUlimits = new ArrayList<>(ulimits.size());
            for(Map.Entry<String, String> ulimit : ulimits.entrySet()) {
                final String[] split = ulimit.getValue().split(":");
                final Long softLimit = Long.parseLong(split[0]);
                final Long hardLimit = Long.parseLong(split.length > 1 ? split[1] : split[0]);

                hostUlimits.add(HostConfig.Ulimit.builder()
                        .name(ulimit.getKey())
                        .soft(softLimit)
                        .hard(hardLimit)
                        .build()
                );
            }
            hostConfigBuilder.ulimits(hostUlimits);
        }
        final HostConfig hostConfig = hostConfigBuilder.build();

        final boolean overrideEntrypoint = toCreate.overrideEntrypointNonnull();
        final ContainerConfig containerConfig =
                ContainerConfig.builder()
                        .hostConfig(hostConfig)
                        .image(toCreate.dockerImage())
                        .attachStdout(true)
                        .attachStderr(true)
                        .cmd(overrideEntrypoint ?
                                Lists.newArrayList("/bin/sh", "-c", toCreate.commandLine()) :
                                ShellSplitter.shellSplit(toCreate.commandLine()))
                        .entrypoint(overrideEntrypoint ? Collections.singletonList("") : null)
                        .env(toCreate.environmentVariableStrings())
                        .workingDir(toCreate.workingDirectory())
                        .user(server.containerUser())
                        .labels(toCreate.containerLabels())
                        .build();

        if (log.isDebugEnabled()) {
            final String message = String.format(
                    "Creating container:" +
                            "\n\tserver %s %s" +
                            "\n\timage %s" +
                            "\n\tcommand \"%s\"" +
                            "\n\tworking directory \"%s\"" +
                            "\n\tcontainerUser \"%s\"" +
                            "\n\tvolumes [%s]" +
                            "\n\tenvironment variables [%s]" +
                            "\n\texposed ports: {%s}",
                    server.name(), server.host(),
                    toCreate.dockerImage(),
                    toCreate.commandLine(),
                    toCreate.workingDirectory(),
                    server.containerUser(),
                    StringUtils.join(toCreate.bindMountStrings(), ", "),
                    StringUtils.join(toCreate.environmentVariableStrings(), ", "),
                    StringUtils.join(toCreate.portStrings(), ", ")
            );
            log.debug(message);
        }

        try (final DockerClient client = getClient(server, toCreate.dockerImage())) {
            final ContainerCreation container;
            if(Strings.isNullOrEmpty(toCreate.containerName())){
                container = client.createContainer(containerConfig);
            } else {
                container = client.createContainer(containerConfig, toCreate.containerName());
            }

            final List<String> warnings = container.warnings();
            if (warnings != null) {
                for (String warning : warnings) {
                    log.warn(warning);
                }
            }

            return container.id();
        } catch (DockerException | InterruptedException e) {
            log.error(e.getMessage());
            throw new DockerServerException("Could not create container from image " + toCreate.dockerImage(), e);
        }
    }

    /**
     * Create a service on docker swarm according to the given Container object
     *
     * @param toCreate A Container defining all the attributes of the service to create
     * @param server DockerServer preferences
     * @param numReplicas The desired number of replicas of this service.
     *                 Use 0 to "create" a service with no replicas,
     *                 then later "start" it by updating replicas to 1.
     *                 Use 1 to "create" and "start" at the same time.
     * @return Docker swarm service ID
     */
    private String createDockerSwarmService(final Container toCreate, final DockerServer server, final NumReplicas numReplicas)
            throws DockerServerException, ContainerException {
        log.debug("Creating a swarm service with {} replicas.", numReplicas.value);

        final Map<String, String> ports = toCreate.ports() == null ? Collections.emptyMap() : toCreate.ports();
        final List<PortConfig> portConfigs = new ArrayList<>(ports.size());
        for (final Map.Entry<String, String> port : ports.entrySet()) {
            final String containerPort = port.getKey();
            final String hostPort = port.getValue();

            if (StringUtils.isNotBlank(containerPort) && StringUtils.isNotBlank(hostPort)) {
                try {
                    portConfigs.add(PortConfig.builder()
                            .protocol(PortConfig.PROTOCOL_TCP)
                            .publishedPort(Integer.parseInt(hostPort))
                            .targetPort(Integer.parseInt(containerPort))
                            .build());
                } catch (NumberFormatException e) {
                    final String message = "Error creating port binding.";
                    log.error(message, e);
                    throw new ContainerException(message, e);
                }
            } else {
                // One or both of hostPost and containerPort is blank.
                final String message;
                if (StringUtils.isBlank(containerPort)) {
                    message = "Container port is blank.";
                } else if (StringUtils.isNotBlank(hostPort)) {
                    message = "Host port is blank";
                } else {
                    message = "Container and host ports are blank";
                }
                log.error(message);
                throw new ContainerException(message);
            }
        }

        final List<Mount> mounts = toCreate.mounts().stream().map(containerMount ->
            Mount.builder()
                    .source(containerMount.containerHostPath())
                    .target(containerMount.containerPath())
                    .readOnly(!containerMount.writable())
                    .build()
        ).collect(Collectors.toList());

        //TODO make this configurable from UI
        //add docker socket for 'docker in docker containers'
        mounts.add(Mount.builder().source("/var/run/docker.sock").target("/var/run/docker.sock").readOnly(false).build());

        // Temporary work-around to support configurable shm-size in swarm service
        // https://github.com/moby/moby/issues/26714
        if (toCreate.shmSize() != null ){
            final Mount tmpfs = Mount
                    .builder()
                    .type("tmpfs")
                    .target("/dev/shm")
                    .tmpfsOptions(TmpfsOptions.builder().sizeBytes(toCreate.shmSize()).build())
                    .build();

            log.debug("Creating tmpfs mount to support shm-size in Swarm Service: {}", tmpfs);

            mounts.add(tmpfs);
        }

        final Map<String, String> ulimits = toCreate.ulimits();
        if (!(ulimits == null || ulimits.isEmpty())) {
            log.debug("Ulimits command configuration ignored in service mode. Ulimits should be set at the dockerd node level in Swarm mode. ulimits={}", ulimits);
        }

        final String workingDirectory = StringUtils.isNotBlank(toCreate.workingDirectory()) ?
                toCreate.workingDirectory() :
                null;

        final ContainerSpec.Builder containerSpecBuilder = ContainerSpec.builder()
                .image(toCreate.dockerImage())
                .env(toCreate.environmentVariableStrings())
                .dir(workingDirectory)
                .mounts(mounts)
                .user(server.containerUser())
                .labels(toCreate.containerLabels());
        if (toCreate.overrideEntrypointNonnull()) {
            containerSpecBuilder.command("/bin/sh", "-c", toCreate.commandLine());
        } else {
            containerSpecBuilder.args(ShellSplitter.shellSplit(toCreate.commandLine()));
        }
        final ContainerSpec containerSpec = containerSpecBuilder.build();

        // Build out GPU/generic resources specifications from command definition to swarm/single server spec.
        final List<ResourceSpec> resourceSpecs = instanceOrDefault(toCreate.genericResources(), Collections.<String, String>emptyMap())
                .entrySet()
                .stream()
                .map(entry -> StringUtils.isNumeric(entry.getValue()) ?
                        ResourceSpec.DiscreteResourceSpec.builder().kind(entry.getKey()).value(Integer.parseInt(entry.getValue())).build() :
                        ResourceSpec.NamedResourceSpec.builder().kind(entry.getKey()).value(entry.getValue()).build())
                .collect(Collectors.toList());

        // Build named resources and add them to memory reservation requirements
        // let resource constraints default to 0, so they're ignored by Docker
        final ResourceRequirements resourceRequirements =
                ResourceRequirements.builder()
                        .reservations(Reservations.builder()
                                .memoryBytes(toCreate.reserveMemoryBytes()) // megabytes to bytes
                                .resources(resourceSpecs)
                                .build())
                        .limits(Resources.builder()
                                .memoryBytes(toCreate.limitMemoryBytes()) // megabytes to bytes
                                .nanoCpus(toCreate.nanoCpus())
                                .build())
                        .build();

        final TaskSpec.Builder taskSpecBuilder = TaskSpec
                .builder()
                .containerSpec(containerSpec)
                .placement(Placement.create(toCreate.swarmConstraints()))
                .restartPolicy(RestartPolicy.builder()
                        .condition(RestartPolicy.RESTART_POLICY_NONE)
                        .build())
                .resources(resourceRequirements);
        if (!Strings.isNullOrEmpty(toCreate.network())) {
            taskSpecBuilder.networks(NetworkAttachmentConfig.builder().target(toCreate.network()).build());
        }
        final TaskSpec taskSpec = taskSpecBuilder.build();

        final ServiceSpec serviceSpec = ServiceSpec.builder()
                .taskTemplate(taskSpec)
                .mode(ServiceMode.builder()
                        .replicated(ReplicatedService.builder()
                                .replicas(numReplicas.value)
                                .build())
                        .build())
                .endpointSpec(EndpointSpec.builder().ports(portConfigs).build())
                .name(Strings.isNullOrEmpty(toCreate.containerName()) ? UUID.randomUUID().toString() : toCreate.containerName())
                .labels(toCreate.containerLabels())
                .build();

        if (log.isDebugEnabled()) {
            final String message = String.format(
                    "Creating container:" +
                            "\n\tserver %s %s" +
                            "\n\timage %s" +
                            "\n\tcommand \"%s\"" +
                            "\n\tworking directory \"%s\"" +
                            "\n\tcontainerUser \"%s\"" +
                            "\n\tvolumes [%s]" +
                            "\n\tenvironment variables [%s]" +
                            "\n\texposed ports: {%s}",
                    server.name(), server.host(),
                    toCreate.dockerImage(),
                    toCreate.commandLine(),
                    toCreate.workingDirectory(),
                    server.containerUser(),
                    StringUtils.join(toCreate.bindMountStrings(), ", "),
                    StringUtils.join(toCreate.environmentVariableStrings(), ", "),
                    StringUtils.join(toCreate.portStrings(), ", ")
            );
            log.debug(message);
        }

        try (final DockerClient client = getClient(server, toCreate.dockerImage())) {
            final ServiceCreateResponse serviceCreateResponse = client.createService(serviceSpec);

            final List<String> warnings = serviceCreateResponse.warnings();
            if (warnings != null) {
                for (String warning : warnings) {
                    log.warn(warning);
                }
            }

            return serviceCreateResponse.id();
        } catch (DockerException | InterruptedException e) {
            log.error(e.getMessage());
            throw new DockerServerException("Could not create service: " + e.getMessage(), e);
        }
    }

    /**
     * Start a backend object that had previously been created.
     *
     * If this is a container on a local docker server, there is a dedicated API to start.
     * If this is a service on docker swarm, we update the replicas from 0 to 1.
     *
     * @param toStart A Container that refers to a previously-created backend object
     */
    @Override
    public void start(final Container toStart) throws NoContainerServerException, ContainerBackendException {
        final DockerServer server = getServer();
        if (server.swarmMode()) {
            setSwarmServiceReplicasToOne(toStart.serviceId(), toStart.dockerImage(), server);
        } else {
            startDockerContainer(toStart.containerId(), server);
        }
    }

    /**
     * Start a backend object that had previously been created.
     *
     * If this is a container on a local docker server, there is a dedicated API to start.
     * If this is a service on docker swarm, we update the replicas from 0 to 1.
     *
     * @param containerOrService A Container that refers to a previously-created backend object
     * @deprecated Use {@link ContainerControlApi#start(Container)} instead.
     */
    @Override
    @Deprecated
    public void startContainer(final Container containerOrService) throws DockerServerException, NoDockerServerException {
        try {
            start(containerOrService);
        } catch (NoContainerServerException e) {
            throw (e instanceof NoDockerServerException) ? (NoDockerServerException) e :
                    new NoDockerServerException(e.getMessage(), e.getCause());
        } catch (ContainerBackendException e) {
            throw (e instanceof DockerServerException) ? (DockerServerException) e :
                    new DockerServerException(e.getMessage(), e.getCause());
        }
    }

    private void setSwarmServiceReplicasToOne(final String serviceId, final String image, final DockerServer server)
            throws DockerServerException {
        try (final DockerClient client = getClient(server, image)) {

            log.debug("Inspecting service {}", serviceId);
            final org.mandas.docker.client.messages.swarm.Service service = client.inspectService(serviceId);
            if (service == null || service.spec() == null) {
                throw new DockerServerException("Could not start service " + serviceId + ". Could not inspect service spec.");
            }
            final ServiceSpec originalSpec = service.spec();
            final ServiceSpec updatedSpec = ServiceSpec.builder()
                    .name(originalSpec.name())
                    .labels(originalSpec.labels())
                    .updateConfig(originalSpec.updateConfig())
                    .taskTemplate(originalSpec.taskTemplate())
                    .endpointSpec(originalSpec.endpointSpec())
                    .mode(ServiceMode.builder()
                            .replicated(ReplicatedService.builder()
                                    .replicas(1L)
                                    .build())
                            .build())
                    .build();
            final Long version = service.version() != null && service.version().index() != null ?
                    service.version().index() : null;

            log.info("Setting service replication to 1 for service {}", serviceId);
            client.updateService(serviceId, version, updatedSpec);
        } catch (DockerException | InterruptedException e) {
            log.error(e.getMessage());
            throw new DockerServerException("Could not start service " + serviceId + ": " + e.getMessage(), e);
        }
    }

    private void startDockerContainer(final String containerId, final DockerServer server) throws DockerServerException {
        try (final DockerClient client = getClient(server)) {
            log.info("Starting container {}", containerId);
            client.startContainer(containerId);
        } catch (DockerException | InterruptedException e) {
            log.error(e.getMessage());
            throw new DockerServerException("Could not start container " + containerId + ": " + e.getMessage(), e);
        }
    }

    @Override
    public List<Command> parseLabels(final String imageName)
            throws DockerServerException, NoDockerServerException, NotFoundException {
        final DockerImage image = getImageById(imageName);
        return commandLabelService.parseLabels(imageName, image);
    }

    /**
     * Query Docker server for all containers
     *
     * @return Container objects stored on docker server
     **/
    @Override
    public List<ContainerMessage> getAllContainers() throws NoDockerServerException, DockerServerException {
        return getContainers(null);
    }

    /**
     * Query Docker server for containers with parameters
     *
     * @param params Map of query parameters (name = value)
     * @return Container objects stored on docker server meeting the query parameters
     **/
    @Override
    public List<ContainerMessage> getContainers(final Map<String, String> params)
        throws NoDockerServerException, DockerServerException {
        List<org.mandas.docker.client.messages.Container> containerList;

        // Transform param map to ListImagesParam array
        final List<DockerClient.ListContainersParam> dockerParamsList = Lists.newArrayList();
        if (params != null && params.size() > 0) {
            for (final Map.Entry<String, String> paramEntry : params.entrySet()) {
                dockerParamsList.add(DockerClient.ListContainersParam.create(paramEntry.getKey(), paramEntry.getValue()));
            }
        }
        final DockerClient.ListContainersParam[] dockerParams =
                dockerParamsList.toArray(new DockerClient.ListContainersParam[0]);

        try (final DockerClient dockerClient = getClient()) {
            containerList = dockerClient.listContainers(dockerParams);
        } catch (DockerException | InterruptedException e) {
            log.error(e.getMessage());
            throw new DockerServerException(e);
        }
        return containerList.stream().map(this::spotifyToNrg).collect(Collectors.toList());
    }

    /**
     * Query Docker server for specific container
     *
     * @param id Container ID
     * @return Container object with specified ID
     **/
    @Override
    @Nonnull
    public ContainerMessage getContainer(final String id)
        throws NotFoundException, NoDockerServerException, DockerServerException {
        final ContainerMessage container = spotifyToNrg(_getContainer(id));
        if (container != null) {
            return container;
        }
        throw new NotFoundException(String.format("Could not find container %s", id));
    }

    private ContainerInfo _getContainer(final String id) throws NoDockerServerException, DockerServerException {
        final DockerClient client = getClient();
        try {
            return client.inspectContainer(id);
        } catch (DockerException | InterruptedException e) {
            log.error("Container server error." + e.getMessage());
            throw new DockerServerException(e);
        }
    }

    /**
     * Query Docker server for status of specific container
     *
     * @param id Container ID
     * @return Status of Container object with specified ID
     **/
    @Override
    public String getContainerStatus(final String id)
        throws NotFoundException, NoDockerServerException, DockerServerException {
        final ContainerMessage container = getContainer(id);

        return container.status();
    }

    /**
     * Get log from backend
     *
     * @param backendId Identifier for backend entity: docker container ID or swarm service ID.
     * @param logType Stdout or Stderr
     * @return Container log string
     */
    @Override
    public String getLog(final String backendId, final LogType logType) throws NoContainerServerException, ContainerBackendException {
        return getLog(backendId, logType, null, null);
    }

    /**
     * Get log from backend
     *
     * @param container Container object whose logs you wish to read
     * @param logType Stdout or Stderr
     * @return Container log string
     */
    @Override
    public String getLog(final Container container, final LogType logType) throws ContainerBackendException, NoContainerServerException {
        return getLog(container, logType, null, null);
    }

    /**
     * Get log from backend
     *
     * @param backendId Identifier for backend entity: docker container ID or swarm service ID
     * @param logType Stdout or Stderr
     * @param withTimestamps Whether timestamps should be added to the log records on the backend
     * @param since Read logs produced after this Unix timestamp
     * @return Container log string
     */
    @Override
    public String getLog(final String backendId, final LogType logType, final Boolean withTimestamps, final Integer since) throws ContainerBackendException, NoContainerServerException {
        final DockerServer server = getServer();
        if (server.swarmMode()) {
            return getSwarmServiceLog(backendId, assembleDockerClientLogsParams(logType, withTimestamps, since));
        } else {
            return getDockerContainerLog(backendId, assembleDockerClientLogsParams(logType, withTimestamps, since));
        }
    }

    /**
     * Get log from backend
     *
     * @param container Container object whose logs you wish to read
     * @param logType Stdout or Stderr
     * @param withTimestamps Whether timestamps should be added to the log records on the backend
     * @param since Read logs produced after this Unix timestamp
     * @return Container log string
     */
    @Override
    public String getLog(final Container container, final LogType logType, final Boolean withTimestamps, final Integer since) throws ContainerBackendException, NoContainerServerException {
        final DockerServer server = getServer();
        if (server.swarmMode()) {
            return getSwarmServiceLog(container.serviceId(), assembleDockerClientLogsParams(logType, withTimestamps, since));
        } else {
            return getDockerContainerLog(container.containerId(), assembleDockerClientLogsParams(logType, withTimestamps, since));
        }
    }

    /**
     * Get log from backend
     *
     * @param container Container object whose logs you wish to read
     * @param logType Stdout or Stderr
     * @param withTimestamps Whether timestamps should be added to the log records on the backend
     * @param since Read logs produced after this Unix timestamp
     * @return Container log stream
     */
    @Override
    public InputStream getLogStream(final Container container, final LogType logType, boolean withTimestamps, final Integer since) throws ContainerBackendException, NoContainerServerException {
        // TODO Replace this with backend-specific implementations that attach to the underlying log streams
        //  rather than read the entire stream to a string then wrap an InputStream on top of that.
        return new ByteArrayInputStream(getLog(container, logType, withTimestamps, since).getBytes());
    }

    /**
     * Get log from backend
     *
     * @param container Container object whose logs you wish to read
     * @return Container log string
     * @deprecated Use {@link ContainerControlApi#getLog(Container, LogType)}
     */
    @Deprecated
    @Override
    public String getStdoutLog(final Container container) throws NoDockerServerException, DockerServerException {
        return getLogAndWrapOldExceptions(container, LogType.STDOUT);
    }

    /**
     * Get log from backend
     *
     * The backend logging API to use will be based on the backend server settings
     * retrieved from {@link #getServer()}.
     *
     * @param container Container object whose logs you wish to read
     * @return Container log string
     * @deprecated Use {@link ContainerControlApi#getLog(Container, LogType)}
     */
    @Deprecated
    @Override
    public String getStderrLog(final Container container) throws NoDockerServerException, DockerServerException {
        return getLogAndWrapOldExceptions(container, LogType.STDERR);
    }

    /**
     * Get stdout log for a docker container from a docker server
     *
     * @param containerId Container ID whose logs you wish to read
     * @param logParams Docker client API parameters
     * @return Container log string
     * @deprecated This method is tied to a specific container backend.
     * Use {@link ContainerControlApi#getLog(String, LogType, Boolean, Integer)} instead.
     */
    @Deprecated
    @Override
    public String getContainerStdoutLog(final String containerId, final DockerClient.LogsParam... logParams) throws NoDockerServerException, DockerServerException {
        return getDockerContainerLog(containerId, assembleDockerClientLogsParams(LogType.STDOUT, logParams));
    }

    /**
     * Get stderr log for a single docker container from a docker server
     *
     * @param containerId Container ID whose logs you wish to read
     * @param logParams Docker client API parameters
     * @return Container log string
     * @deprecated This method is tied to a specific container backend.
     * Use {@link ContainerControlApi#getLog(String, LogType, Boolean, Integer)} instead.
     */
    @Deprecated
    @Override
    public String getContainerStderrLog(final String containerId, final DockerClient.LogsParam... logParams) throws NoDockerServerException, DockerServerException {
        return getDockerContainerLog(containerId, assembleDockerClientLogsParams(LogType.STDERR, logParams));
    }

    /**
     * Get stdout log for a docker swarm service
     *
     * @param serviceId Swarm service ID whose logs you wish to read
     * @param logParams Docker client API parameters
     * @return Container log string
     * @deprecated This method is tied to a specific container backend.
     * Use {@link ContainerControlApi#getLog(String, LogType, Boolean, Integer)} instead.
     */
    @Deprecated
    @Override
    public String getServiceStdoutLog(final String serviceId, final DockerClient.LogsParam... logParams) throws NoDockerServerException, DockerServerException {
        return getSwarmServiceLog(serviceId, assembleDockerClientLogsParams(LogType.STDOUT, logParams));
    }

    /**
     * Get stderr log for a docker swarm service
     *
     * @param serviceId Swarm service ID whose logs you wish to read
     * @param logParams Docker client API parameters
     * @return Container log string
     * @deprecated This method is tied to a specific container backend.
     * Use {@link ContainerControlApi#getLog(String, LogType, Boolean, Integer)} instead.
     */
    @Deprecated
    @Override
    public String getServiceStderrLog(final String serviceId, final DockerClient.LogsParam... logParams) throws NoDockerServerException, DockerServerException {
        return getSwarmServiceLog(serviceId, assembleDockerClientLogsParams(LogType.STDERR, logParams));
    }

    private String getDockerContainerLog(final String containerId, final DockerClient.LogsParam... logsParams) throws NoDockerServerException, DockerServerException {
        try (final LogStream logStream = getClient().logs(containerId, logsParams)) {
            return logStream.readFully();
        } catch (NoDockerServerException e) {
            throw e;
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new DockerServerException(e);
        }
    }

    private String getSwarmServiceLog(final String serviceId, final DockerClient.LogsParam... logsParams) throws NoDockerServerException, DockerServerException {
        try (final LogStream logStream = getClient().serviceLogs(serviceId, logsParams)) {
            return logStream.readFully();
        } catch (NoDockerServerException e) {
            throw e;
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new DockerServerException(e);
        }
    }

    private DockerClient.LogsParam[] assembleDockerClientLogsParams(final LogType logType, final Boolean withTimestamp, final Integer since) {

        final DockerClient.LogsParam dockerClientLogType = logType == LogType.STDOUT ?
                DockerClient.LogsParam.stdout() :
                DockerClient.LogsParam.stderr();

        if (withTimestamp == null && since == null) {
            return new DockerClient.LogsParam[] {dockerClientLogType};
        } else if (withTimestamp == null) {
            return new DockerClient.LogsParam[] {
                    dockerClientLogType,
                    DockerClient.LogsParam.since(since)
            };
        } else if (since == null) {
            return new DockerClient.LogsParam[] {
                    dockerClientLogType,
                    DockerClient.LogsParam.timestamps(withTimestamp)
            };
        } else {
            return new DockerClient.LogsParam[] {
                    dockerClientLogType,
                    DockerClient.LogsParam.timestamps(withTimestamp),
                    DockerClient.LogsParam.since(since)
            };
        }
    }

    private DockerClient.LogsParam[] assembleDockerClientLogsParams(final LogType logType, final DockerClient.LogsParam... logsParams) {
        final DockerClient.LogsParam dockerClientLogType = logType == LogType.STDOUT ?
                DockerClient.LogsParam.stdout() :
                DockerClient.LogsParam.stderr();

        final DockerClient.LogsParam[] newLogsParams = new DockerClient.LogsParam[logsParams.length + 1];
        System.arraycopy(logsParams, 0, newLogsParams, 1, logsParams.length);
        newLogsParams[0] = dockerClientLogType;

        return newLogsParams;
    }

    private String getLogAndWrapOldExceptions(final Container container, final LogType logType) throws NoDockerServerException, DockerServerException {
        try {
            return getLog(container, logType);
        } catch (NoContainerServerException e) {
            throw (e instanceof NoDockerServerException) ? (NoDockerServerException) e :
                    new NoDockerServerException(e.getMessage(), e.getCause());
        } catch (ContainerBackendException e) {
            throw (e instanceof DockerServerException) ? (DockerServerException) e :
                    new DockerServerException(e.getMessage(), e.getCause());
        }
    }

    @VisibleForTesting
    @Nonnull
    public DockerClient getClient() throws NoDockerServerException, DockerServerException {
        return getClient(getServer());
    }

    @Nonnull
    private DockerClient getClient(final @Nonnull DockerServer server) throws DockerServerException {
        return getClient(server, null);
    }

    @Nonnull
    private DockerClient getClient(final @Nonnull DockerServer server, final @Nullable String imageName)
            throws DockerServerException {

        JerseyDockerClientBuilder clientBuilder = new JerseyDockerClientBuilder();
        clientBuilder.uri(server.host());

        if (StringUtils.isNotBlank(server.certPath())) {
            try {
                final DockerCertificates certificates =
                    new DockerCertificates(Paths.get(server.certPath()));
                clientBuilder.dockerCertificates(certificates);
            } catch (DockerCertificateException e) {
                log.error("Could not find docker certificates at {}", server.certPath(), e);
            }
        }

        // We only need to add auth when we pull (already added in pullImage methods) and when we start a swarm service;
        // imageName is passed in the latter scenario only, so we can use it as an indicator that auth ought to be added.
        // I am tempted to always add it, but want to minimize the intrusiveness of this change.
        if (StringUtils.isNotBlank(imageName)) {
            try {
                // config file first
                RegistryAuth auth = new ConfigFileRegistryAuthSupplier().authFor(imageName);

                // If no entry in config, see if we have hub credentials
                if (auth == null) {
                    final DockerHub hub = dockerHubService.getHubForImage(imageName);
                    if (hub != null && (StringUtils.isNotBlank(hub.username()) || StringUtils.isNotBlank(hub.token()))) {
                        auth = RegistryAuth.builder()
                                .username(hub.username())
                                .password(hub.password())
                                .identityToken(hub.token())
                                .serverAddress(hub.url())
                                .build();
                    }
                }
                if (auth != null) {
                    clientBuilder.registryAuthSupplier(new FixedRegistryAuthSupplier(auth, RegistryConfigs.empty()));
                }
            } catch(Exception e){
                log.error("Issue with auth for image {}", imageName, e);
            }
        }

        try {
            log.trace("DOCKER CLIENT URI IS: {}", clientBuilder.uri().toString());

        	return clientBuilder.build();
        } catch (Throwable e) {
            log.error("Could not create DockerClient instance. Reason: {}", e.getMessage(), e);
            throw new DockerServerException(e);
        }
    }

    /**
     * Kill a backend entity
     *
     * Note that if the backend is a docker swarm, there is no "kill" concept.
     * This method will remove a swarm service instead.
     *
     * @param container Container object describing backend entity to kill
     */
    @Override
    public void kill(final Container container) throws NoContainerServerException, ContainerBackendException, NotFoundException {
        final DockerServer server = getServer();
        if (server.swarmMode()) {
            log.debug("There is no \"kill\" command for Docker Swarm Services. Calling \"remove\" instead.");
            removeDockerSwarmService(container.serviceId(), server);
        } else {
            killDockerContainer(container.containerId(), server);
        }
    }

    /**
     * Remove the backend entity from the backend server
     * only if the server settings specify autoCleanup=true.
     * To remove regardless of the setting, use {@link ContainerControlApi#remove(Container)}.
     * @param container Container object describing backend entity to remove
     */
    @Override
    public void autoCleanup(final Container container) throws NoContainerServerException, ContainerBackendException, NotFoundException {
        final DockerServer server = getServer();
        if (!server.autoCleanup()) {
            log.debug("Server is set to autoCleanup=false. Skipping remove.");
        } else {
            remove(container, server);
        }
    }

    /**
     * Remove the backend entity from the backend server
     * @param container Container object describing backend entity to remove
     */
    @Override
    public void remove(final Container container) throws NoContainerServerException, ContainerBackendException, NotFoundException {
        remove(container, getServer());
    }

    private void remove(final Container container, final DockerServer server)
            throws ContainerBackendException, NotFoundException {
        if (server.swarmMode()) {
            removeDockerSwarmService(container.serviceId(), server);
        } else {
            removeDockerContainer(container.containerId(), server);
        }
    }

    /**
     * Kill a docker container
     * @param id Docker container ID
     * @deprecated Use {@link ContainerControlApi#kill(Container)} instead
     */
    @Override
    @Deprecated
    public void killContainer(final String id) throws NoDockerServerException, DockerServerException, NotFoundException {
        killDockerContainer(id, getServer());
    }

    /**
     * Kill a docker swarm service
     * @param id Service ID
     * @deprecated Use {@link ContainerControlApi#kill(Container)} instead
     */
    @Override
    @Deprecated
    public void killService(final String id) throws NoDockerServerException, DockerServerException, NotFoundException {
        try(final DockerClient client = getClient()) {
            log.info("Killing service {}", id);
            client.removeService(id);
        } catch (ContainerNotFoundException | ServiceNotFoundException e) {
            log.error(e.getMessage(), e);
            throw new NotFoundException(e);
        } catch (DockerException | InterruptedException e) {
            log.error(e.getMessage(), e);
            throw new DockerServerException(e);
        } catch (DockerServerException e) {
            log.error(e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Remove a docker container or docker swarm service from the backend,
     * but only if the server settings have autoCleanup=true.
     * @param container Container describing backend object
     * @deprecated Use {@link ContainerControlApi#autoCleanup(Container)} instead
     */
    @Override
    @Deprecated
    public void removeContainerOrService(final Container container)
            throws NoDockerServerException, DockerServerException {
        try {
            autoCleanup(container);
        } catch (NoContainerServerException e) {
            throw (e instanceof NoDockerServerException) ? (NoDockerServerException) e :
                    new NoDockerServerException(e.getMessage(), e.getCause());
        } catch (ContainerBackendException e) {
            throw (e instanceof DockerServerException) ? (DockerServerException) e :
                    new DockerServerException(e.getMessage(), e.getCause());
        } catch (NotFoundException e) {
            // This wasn't handled in the original implementation.
            // Have to turn it into a more general exception type.
            throw new DockerServerException(e.getCause());
        }
    }

    private void killDockerContainer(final String backendId, final DockerServer server)
            throws DockerServerException, NotFoundException {
        try(final DockerClient client = getClient(server)) {
            log.info("Killing container {}", backendId);
            client.killContainer(backendId);
        } catch (ContainerNotFoundException e) {
            log.error(e.getMessage(), e);
            throw new NotFoundException(e);
        } catch (DockerException | InterruptedException e) {
            log.error(e.getMessage(), e);
            throw new DockerServerException(e);
        }
    }

    private void removeDockerContainer(final String backendId, final DockerServer server)
            throws DockerServerException, NotFoundException {
        try(final DockerClient client = getClient(server)) {
            log.debug("Removing container {}", backendId);
            client.removeContainer(backendId);
        } catch (ContainerNotFoundException e) {
            log.error(e.getMessage(), e);
            throw new NotFoundException(e);
        } catch (DockerException | InterruptedException e) {
            log.error(e.getMessage(), e);
            throw new DockerServerException(e);
        }
    }

    private void removeDockerSwarmService(final String backendId, final DockerServer server)
            throws DockerServerException, NotFoundException {
        try(final DockerClient client = getClient(server)) {
            log.info("Removing service {}", backendId);
            client.removeService(backendId);
        } catch (ServiceNotFoundException e) {
            log.error(e.getMessage(), e);
            throw new NotFoundException(e);
        } catch (DockerException | InterruptedException e) {
            log.error(e.getMessage(), e);
            throw new DockerServerException(e);
        }
    }

    @Override
    @Nullable
    public ServiceTask getTaskForService(final Container service) throws NoDockerServerException, DockerServerException, ServiceNotFoundException, TaskNotFoundException {
        return getTaskForService(getServer(), service);
    }

    @Override
    @Nullable
    public ServiceTask getTaskForService(final DockerServer dockerServer, final Container service)
            throws DockerServerException, ServiceNotFoundException, TaskNotFoundException {
        log.debug("Getting task for service {} \"{}\".", service.databaseId(), service.serviceId());
        try (final DockerClient client = getClient(dockerServer)) {
            Task task = null;
            final String serviceId = service.serviceId();
            final String taskId = service.taskId();

            if (taskId == null) {
                log.trace("Inspecting swarm service {} \"{}\".", service.databaseId(), service.serviceId());
                final org.mandas.docker.client.messages.swarm.Service serviceResponse = client.inspectService(serviceId);
                final String serviceName = serviceResponse.spec().name();
                if (StringUtils.isBlank(serviceName)) {
                    throw new DockerServerException("Unable to determine service name for serviceId " + serviceId +
                            ". Cannot get taskId without this.");
                }

                log.trace("Service {} \"{}\" has name \"{}\" based on inspection: {}. Querying for task matching this service name.",
                        service.databaseId(), serviceId, serviceName, serviceResponse);

                final List<Task> tasks = client.listTasks(Task.Criteria.builder().serviceName(serviceName).build());

                if (tasks.size() == 1) {
                    task = tasks.get(0);
                    log.trace("Found one task \"{}\" for service {} \"{}\" name \"{}\"", task.id(), service.databaseId(), serviceId, serviceName);
                } else if (tasks.size() == 0) {
                    log.debug("No tasks found for service {} \"{}\" name \"{}\"", service.databaseId(), serviceId, serviceName);
                } else {
                    throw new DockerServerException("Found multiple tasks for service " + service.databaseId() +
                            " \"" + serviceId + "\" name \"" + serviceName +
                            "\", I only know how to handle one. Tasks: " + tasks);
                }
            } else {
                log.trace("Service {} \"{}\" has task \"{}\"", service.databaseId(), serviceId, taskId);
                task = client.inspectTask(taskId);
            }

            if (task != null) {
                final ServiceTask serviceTask = ServiceTask.create(task, serviceId);

                if (serviceTask.isExitStatus() && serviceTask.exitCode() == null) {
                    // The Task is supposed to have the container exit code, but docker doesn't report it where it should.
                    // So go get the container info and get the exit code
                    final String containerId = serviceTask.containerId();
                    log.debug("Looking up exit code for container {}.", containerId);
                    if (containerId != null) {
                        final ContainerInfo containerInfo = client.inspectContainer(containerId);
                        if (containerInfo.state().exitCode() == null) {
                            log.debug("Welp. Container exit code is null on the container too.");
                        } else {
                            return serviceTask.toBuilder().exitCode(containerInfo.state().exitCode()).build();
                        }
                    } else {
                        log.error("Cannot look up exit code. Container ID is null.");
                    }
                }

                return serviceTask;
            }
        } catch (ServiceNotFoundException | TaskNotFoundException e) {
            throw e;
        } catch (DockerException | InterruptedException e) {
            log.trace("INTERRUPTED: {}", e.getMessage());
            log.error(e.getMessage(), e);
            throw new DockerServerException(e);
        } catch (DockerServerException e) {
            log.error(e.getMessage(), e);
            throw e;
        }
        return null;
    }

    @Override
    public List<DockerContainerEvent> getContainerEvents(final Date since, final Date until) throws NoDockerServerException, DockerServerException {
        final List<Event> dockerEventList = getDockerContainerEvents(since, until);

        final List<DockerContainerEvent> events = Lists.newArrayList();
        for (final Event dockerEvent : dockerEventList) {
            final Event.Actor dockerEventActor = dockerEvent.actor();
            final Map<String, String> attributes = Maps.newHashMap();
            if (dockerEventActor != null && dockerEventActor.attributes() != null) {
                attributes.putAll(dockerEventActor.attributes());
            }
            if (attributes.containsKey(LABEL_KEY)) {
                attributes.put(LABEL_KEY, "<elided>");
            }
            final DockerContainerEvent containerEvent =
                    DockerContainerEvent.create(dockerEvent.action(),
                            dockerEventActor != null? dockerEventActor.id() : null,
                            dockerEvent.time(),
                            dockerEvent.timeNano(),
                            attributes);
            events.add(containerEvent);
        }
        return events;
    }

    /**
     * @deprecated Get events using {@link ContainerControlApi#getContainerEvents(Date, Date)} and
     *             trigger them with {@link NrgEventService#triggerEvent(org.nrg.framework.event.EventI)}.
     */
    @Override
    @Deprecated
    public void throwContainerEvents(final Date since, final Date until) throws NoDockerServerException, DockerServerException {
        final List<DockerContainerEvent> events = getContainerEvents(since, until);

        for (final DockerContainerEvent event : events) {
            if (event.isIgnoreStatus()) {
                // This occurs on container cleanup, ignore it, we've already finalized at this point
                log.debug("Skipping docker container event: {}", event);
                continue;
            }
            log.debug("Throwing docker container event: {}", event);
            eventService.triggerEvent(event);
        }
    }

    private List<Event> getDockerContainerEvents(final Date since, final Date until) throws NoDockerServerException, DockerServerException {
        try(final DockerClient client = getClient()) {
            log.trace("Reading all docker container events from {} to {}.", since.getTime(), until.getTime());

            final List<Event> eventList;
            try (final EventStream eventStream =
                         client.events(since(since.getTime() / 1000),
                                 until(until.getTime() / 1000),
                                 type(Event.Type.CONTAINER))) {

                log.trace("Got a stream of docker events.");

                eventList = Lists.newArrayList(eventStream);
            }

            log.trace("Closed docker event stream.");

            return eventList;
        } catch (IOException | InterruptedException | DockerException e) {
            log.error(e.getMessage(), e);
            throw new DockerServerException(e);
        } catch (DockerServerException e) {
            log.error(e.getMessage(), e);
            throw e;
        }
    }

    /**
     * @deprecated Moved to private method in {@link org.nrg.containers.events.ContainerStatusUpdater}.
     */
    @Override
    @Deprecated
    public void throwLostTaskEventForService(@Nonnull final Container service) {
        final ServiceTask task = ServiceTask.builder()
                .serviceId(service.serviceId())
                .taskId(null)
                .nodeId(null)
                .status(TASK_STATE_FAILED)
                .swarmNodeError(true)
                .statusTime(null)
                .message(ServiceTask.swarmNodeErrMsg)
                .err(null)
                .exitCode(null)
                .containerId(service.containerId())
                .build();
            final ServiceTaskEvent serviceTaskEvent = ServiceTaskEvent.create(task, service);
            log.trace("Throwing service task event for service {}.", serviceTaskEvent.service().serviceId());
            eventService.triggerEvent(serviceTaskEvent);
    }

    /**
     * @deprecated Unused
     */
    @Override
    @Deprecated
    public void throwTaskEventForService(final Container service) throws NoDockerServerException, DockerServerException, ServiceNotFoundException, TaskNotFoundException {
        throwTaskEventForService(getServer(), service);
    }

    /**
     * @deprecated Moved to private method in {@link org.nrg.containers.events.ContainerStatusUpdater}.
     */
    @Override
    @Deprecated
    public void throwTaskEventForService(final DockerServer dockerServer, final Container service) throws DockerServerException, ServiceNotFoundException, TaskNotFoundException {
        try {
            final ServiceTask task = getTaskForService(dockerServer, service);
            if (task != null) {
                final ServiceTaskEvent serviceTaskEvent = ServiceTaskEvent.create(task, service);
                log.trace("Throwing service task event for service {}.", serviceTaskEvent.service().serviceId());
                eventService.triggerEvent(serviceTaskEvent);
            } else {
                log.debug("Appears that the task has not been assigned for {} : {}", service.serviceId(), service.status());
            }
        } catch (TaskNotFoundException e) {
            throw e;
        }
    }

    /**
     * @deprecated Moved to private method in {@link org.nrg.containers.events.ContainerStatusUpdater}.
     */
    @Override
    @Deprecated
    public void throwRestartEventForService(final Container service) throws ContainerException {
        log.trace("Throwing restart event for service {}.", service.serviceId());
        ServiceTask lastTask = service.makeTaskFromLastHistoryItem();
        ServiceTask restartTask = lastTask.toBuilder()
                .swarmNodeError(true)
                .message(ServiceTask.swarmNodeErrMsg) //Differentiate from when lastTask went through processEvent
                .build();
        final ServiceTaskEvent restartTaskEvent = ServiceTaskEvent.create(restartTask, service,
                ServiceTaskEvent.EventType.Restart);
        eventService.triggerEvent(restartTaskEvent);
    }

    /**
     * @deprecated Moved to private method in {@link org.nrg.containers.events.ContainerStatusUpdater}.
     */
    @Override
    @Deprecated
    public void throwWaitingEventForService(final Container service) throws ContainerException {
        log.trace("Throwing waiting event for service {}.", service.serviceId());
        final ServiceTaskEvent waitingTaskEvent = ServiceTaskEvent.create(service.makeTaskFromLastHistoryItem(), service,
                ServiceTaskEvent.EventType.Waiting);
        eventService.triggerEvent(waitingTaskEvent);
    }

    @Override
    public Integer getFinalizingThrottle() {
        try {
            DockerServer server = getServer();
            return server.swarmMode() ? server.maxConcurrentFinalizingJobs() : null;
        } catch (NoDockerServerException e) {
            log.error("Unable to find server to determine finalizing queue throttle", e);
            return null;
        }
    }

    @Override
    public boolean isStatusEmailEnabled() {
        try {
            DockerServer server = getServer();
            return server.statusEmailEnabled();
        } catch (NoDockerServerException e) {
            log.error("Unable to find server to determine status email enabled setting", e);
            return true;
        }
    }

    /**
     * Convert spotify-docker Image object to xnat-container Image object
     *
     * @param image Spotify-Docker Image object
     * @return NRG Image object
     **/
    @Nullable
    private DockerImage spotifyToNrg(final @Nullable Image image) {
        return image == null ? null :
                DockerImage.create(image.id(), image.repoTags(), image.labels());
    }

    /**
     * Convert spotify-docker Image object to xnat-container Image object
     *
     * @param image Spotify-Docker Image object
     * @return NRG Image object
     **/
    @Nullable
    private DockerImage spotifyToNrg(final @Nullable ImageInfo image) {
        return image == null ? null :
                DockerImage.builder()
                        .imageId(image.id())
                        .labels(image.config().labels() == null ?
                                Collections.<String, String>emptyMap() :
                                image.config().labels())
                        .build();
    }

    /**
     * Convert spotify-docker Container object to xnat-container Container object
     *
     * @param dockerContainer Spotify-Docker Container object
     * @return NRG Container object
     **/
    @Nullable
    private ContainerMessage spotifyToNrg(final @Nullable org.mandas.docker.client.messages.Container dockerContainer) {
        return dockerContainer == null ? null : ContainerMessage.create(dockerContainer.id(), dockerContainer.status());
    }

    /**
     * Convert spotify-docker Container object to xnat-container Container object
     *
     * @param dockerContainer Spotify-Docker ContainerInfo object
     * @return NRG Container object
     **/
    @Nullable
    private ContainerMessage spotifyToNrg(final @Nullable org.mandas.docker.client.messages.ContainerInfo dockerContainer) {
        return dockerContainer == null ? null : ContainerMessage.create(
                dockerContainer.id(),
                dockerContainer.state().running() ? "Running" :
                        dockerContainer.state().paused() ? "Paused" :
                        dockerContainer.state().restarting() != null && dockerContainer.state().restarting() ? "Restarting" :
                        dockerContainer.state().exitCode() != null ? "Exited" :
                        null
        );
    }

    public enum NumReplicas {
        ZERO(0L),
        ONE(1L);

        public final long value;
        NumReplicas(long value) {
            this.value = value;
        }
    }
}