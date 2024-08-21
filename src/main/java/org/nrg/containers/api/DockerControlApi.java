package org.nrg.containers.api;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallbackTemplate;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.EventsCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.command.InspectServiceCmd;
import com.github.dockerjava.api.command.KillContainerCmd;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.RemoveServiceCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.command.UpdateServiceCmd;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.AuthConfig;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ContainerSpec;
import com.github.dockerjava.api.model.DiscreteResourceSpec;
import com.github.dockerjava.api.model.EndpointSpec;
import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.EventActor;
import com.github.dockerjava.api.model.EventType;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.GenericResource;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import com.github.dockerjava.api.model.NamedResourceSpec;
import com.github.dockerjava.api.model.NetworkAttachmentConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.PortConfig;
import com.github.dockerjava.api.model.PortConfigProtocol;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.ResourceRequirements;
import com.github.dockerjava.api.model.ResourceSpecs;
import com.github.dockerjava.api.model.ServiceModeConfig;
import com.github.dockerjava.api.model.ServicePlacement;
import com.github.dockerjava.api.model.ServiceReplicatedModeOptions;
import com.github.dockerjava.api.model.ServiceRestartCondition;
import com.github.dockerjava.api.model.ServiceRestartPolicy;
import com.github.dockerjava.api.model.ServiceSpec;
import com.github.dockerjava.api.model.Task;
import com.github.dockerjava.api.model.TaskSpec;
import com.github.dockerjava.api.model.TmpfsOptions;
import com.github.dockerjava.api.model.Ulimit;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.core.NameParser;
import com.github.dockerjava.okhttp.OkDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.events.model.DockerContainerEvent;
import org.nrg.containers.exceptions.ContainerBackendException;
import org.nrg.containers.exceptions.ContainerException;
import org.nrg.containers.exceptions.DockerServerException;
import org.nrg.containers.exceptions.NoContainerServerException;
import org.nrg.containers.exceptions.NoDockerServerException;
import org.nrg.containers.exceptions.ServiceNotFoundException;
import org.nrg.containers.exceptions.TaskNotFoundException;
import org.nrg.containers.model.command.auto.ResolvedCommand;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.auto.ServiceTask;
import org.nrg.containers.model.dockerhub.DockerHubBase;
import org.nrg.containers.model.dockerhub.DockerHubBase.DockerHub;
import org.nrg.containers.model.image.docker.DockerImage;
import org.nrg.containers.model.server.docker.Backend;
import org.nrg.containers.model.server.docker.DockerClientCacheKey;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.secrets.ContainerPropertiesWithSecretValues;
import org.nrg.containers.services.DockerHubService;
import org.nrg.containers.services.DockerServerService;
import org.nrg.containers.utils.ShellSplitter;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xft.security.UserI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.nrg.containers.services.CommandLabelService.LABEL_KEY;
import static org.nrg.containers.utils.ContainerUtils.instanceOrDefault;

@Slf4j
@Service
public class DockerControlApi implements ContainerControlApi {
    private static final String HUB_AUTH_SUCCESS = "Login Succeeded";

    private final DockerServerService dockerServerService;
    private final DockerHubService dockerHubService;
    private final KubernetesClientFactory kubernetesClientFactory;

    private static final Object CACHED_DOCKER_CLIENT_MUTEX = new Object();
    private static DockerClientCacheKey CACHED_DOCKER_CLIENT_KEY = null;
    private static volatile DockerClient CACHED_DOCKER_CLIENT = null;

    @Autowired
    public DockerControlApi(final DockerServerService dockerServerService,
                            final DockerHubService dockerHubService,
                            final KubernetesClientFactory kubernetesClientFactory) {
        this.dockerServerService = dockerServerService;
        this.dockerHubService = dockerHubService;
        this.kubernetesClientFactory = kubernetesClientFactory;
    }

    @Nonnull
    private DockerServer getServer() throws NoDockerServerException {
        try {
            return dockerServerService.getServer();
        } catch (NotFoundException e) {
            throw new NoDockerServerException(e);
        }
    }

    private KubernetesClient getKubernetesClient() throws NoContainerServerException {
        clearDockerClientCache();
        return kubernetesClientFactory.getKubernetesClient();
    }

    @Override
    public String ping() throws NoDockerServerException, DockerServerException {
        final DockerServer dockerServer = getServer();

        switch (dockerServer.backend()) {
            case SWARM:
                return pingSwarmMaster(dockerServer);
            case DOCKER:
                return pingServer(dockerServer);
            case KUBERNETES:
                try {
                    return getKubernetesClient().ping();
                } catch (ContainerBackendException e) {
                    throw (e instanceof DockerServerException) ? (DockerServerException) e : new DockerServerException(e);
                } catch (NoContainerServerException e) {
                    throw (e instanceof NoDockerServerException) ? (NoDockerServerException) e : new NoDockerServerException(e);
                }
            default:
                throw new NoDockerServerException("Not implemented");
        }
    }

    private String pingServer(final DockerServer dockerServer) throws DockerServerException {
        final DockerClient client = getDockerClient(dockerServer);
        try {
            client.pingCmd().exec();
            // If we got this far without an exception, then all is well.
        } catch (DockerException e) {
            log.trace("Failed to ping", e);
            throw new DockerServerException(e);
        }
        return "OK";
    }

    private String pingSwarmMaster(final DockerServer dockerServer) throws DockerServerException {
        final DockerClient client = getDockerClient(dockerServer);
        try {
            client.inspectSwarmCmd().exec();
            // If we got this far without an exception, then all is well.
        } catch (DockerException e) {
            log.trace("Failed to inspect swarm", e);
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
            log.error("Cannot check connection. No docker server defined.");
        } catch (DockerServerException ignored) {
            // Any actual errors have already been logged. We can safely ignore them here.
        }

        return false;
    }

    @Override
    @Nonnull
    public DockerHubBase.DockerHubStatus pingHub(final @Nonnull DockerHub hub) {
        return pingHub(hub, null, null, null, null);
    }

    @Override
    @Nonnull
    public DockerHubBase.DockerHubStatus pingHub(final @Nonnull DockerHub hub, final @Nullable String username, final @Nullable String password,
                                                 final @Nullable String token, final @Nullable String email) {
        DockerHubBase.DockerHubStatus.Builder hubStatusBuilder = DockerHubBase.DockerHubStatus.create(false).toBuilder();
        Backend backend = null;
        try {
            backend = getServer().backend();
        } catch (NoDockerServerException e) {
            // ignore
        }
        if (backend == null) {
            return hubStatusBuilder.ping(false)
                    .response("Error")
                    .message("Docker server unavailable.")
                    .build();
        }
        switch (backend) {
            case DOCKER:
            case SWARM:
                try {
                    final DockerClient client = getDockerClient();
                    final String statusStr = client.authCmd().withAuthConfig(authConfig(hub, username, password, token, email)).exec().getStatus();
                    final boolean success = statusStr.equals(HUB_AUTH_SUCCESS);
                    hubStatusBuilder.ping(success)
                            .response(success ? "OK" : "Down")
                            .message("Hub response: " + statusStr);
                } catch (Exception e) {
                    log.error("Hub status check created exception.", e);
                    hubStatusBuilder.ping(false)
                            .response("Error")
                            .message("Hub status check created exception. Check Docker server status.");
                }
                break;
            case KUBERNETES:
                // TODO: CS-746 - Use Docker registry APIs to ping image host, regardless of server mode
                hubStatusBuilder.ping(false)
                        .response("Unknown")
                        .message("Cannot ping image host in Kubernetes mode.");
                break;
            default:
                hubStatusBuilder.ping(false)
                        .response("Error")
                        .message("Docker server unavailable.");
        }
        return hubStatusBuilder.build();
    }

    @Nullable
    private AuthConfig authConfig(final String dockerImage, final DockerClientConfig config) {
        // Resolve registry url from image
        NameParser.ReposTag reposTag = NameParser.parseRepositoryTag(dockerImage);
        final String registryUrl = NameParser.resolveRepositoryName(reposTag.repos).hostname;

        // See if we have a DockerHub object for this registry url
        final DockerHub hub = dockerHubService.getByUrl(registryUrl);
        AuthConfig authConfig = authConfig(hub, null, null, null, null);

        if (authConfig == null) {
            // Get AuthConfig from docker config file
            authConfig = config.effectiveAuthConfig(dockerImage);
        }

        return authConfig;
    }

    @Nullable
    private AuthConfig authConfig(final @Nullable DockerHub hub,
                                  final @Nullable String username,
                                  final @Nullable String password,
                                  final @Nullable String token,
                                  final @Nullable String email) {
        return hub == null ? null :
                new AuthConfig()
                        .withRegistryAddress(hub.url())
                        .withUsername(username == null ? hub.username() : username)
                        .withPassword(password == null ? hub.password() : password)
                        .withIdentityToken(token == null ? hub.token() : token)
                        .withEmail(email == null ? hub.email() : email);
    }

    /**
     * Query Docker server for all images
     *
     * @return Image objects stored on docker server
     **/
    @Override
    @Nonnull
    public List<DockerImage> getAllImages() throws NoDockerServerException, DockerServerException {
        return getAllImages(getServer());
    }

    @Nonnull
    private List<DockerImage> getAllImages(final DockerServer server) throws DockerServerException {
        if (server.backend() == Backend.KUBERNETES) {
            return Collections.emptyList();
        }

        final DockerClient client = getDockerClient(server);
        try {
            return client.listImagesCmd()
                    .exec()
                    .stream()
                    .map(image -> DockerImage.builder()
                            .imageId(image.getId())
                            .tags(image.getRepoTags() == null ? Collections.emptyList() : Arrays.asList(image.getRepoTags()))
                            .labels(image.getLabels() == null ? Collections.emptyMap() : image.getLabels())
                            .build())
                    .collect(Collectors.toList());
        } catch (DockerException e) {
            throw new DockerServerException("Could not list images", e);
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
        if (getServer().backend() == Backend.KUBERNETES) {
            throw new NoDockerServerException("Docker server images not available in Kubernetes mode.");
        }

        return getImageById(imageId, getDockerClient());
    }

    private DockerImage getImageById(final String imageId, final DockerClient client)
            throws DockerServerException, NotFoundException {
        try {
            final InspectImageResponse resp = client.inspectImageCmd(imageId).exec();
            if (resp == null) {
                throw new NotFoundException("Could not find image \"" + imageId + "\"");
            }
            return DockerImage.builder()
                    .imageId(resp.getId())
                    .labels(resp.getConfig() == null || resp.getConfig().getLabels() == null ?
                            Collections.emptyMap() :
                            resp.getConfig().getLabels())
                    .build();
        } catch (com.github.dockerjava.api.exception.NotFoundException e) {
            throw new NotFoundException(e);
        }  catch (DockerException e) {
            throw new DockerServerException("Could not delete image", e);
        }
    }

    @Override
    public void deleteImageById(final String id, final Boolean force) throws NoDockerServerException, DockerServerException {
        final DockerClient client = getDockerClient();
        try {
            client.removeImageCmd(id).withForce(force).withNoPrune(false).exec();
        } catch (com.github.dockerjava.api.exception.NotFoundException e) {
            throw new DockerServerException("Image not found", e);
        } catch (DockerException e) {
            throw new DockerServerException("Could not delete image", e);
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
                pullImage(name, null, null, null, null, null);
    }

    @Override
    @Nullable
    public DockerImage pullImage(final String name, final @Nullable DockerHub hub, final @Nullable String username,
                                 final @Nullable String password, final @Nullable String token, final @Nullable String email)
            throws NoDockerServerException, DockerServerException, NotFoundException {
        final DockerClient client = getDockerClient();

        _pullImage(client, name, authConfig(hub, username, password, token, email));  // We want to throw NotFoundException here if the image is not found on the hub
        try {
            return getImageById(name, client);  // We don't want to throw NotFoundException from here. If we can't find the image here after it has been pulled, that is a server error.
        } catch (NotFoundException e) {
            final String m = String.format("Image \"%s\" was not found", name);
            log.error(m);
            throw new DockerServerException(e);
        }
    }

    private void _pullImage(final DockerClient client,
                            final @Nonnull String name,
                            final @Nullable AuthConfig authConfig) throws DockerServerException, NotFoundException {
            final PullImageCmd cmd = client.pullImageCmd(name);
            if (authConfig != null) {
                cmd.withAuthConfig(authConfig);
            }

            try {
                cmd.start().awaitCompletion();
            } catch (com.github.dockerjava.api.exception.NotFoundException e) {
                throw new NotFoundException(e.getMessage());
            } catch (DockerException | InterruptedException e) {
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
                .userId(user.getLogin())
                .backend(server.backend());

        switch (server.backend()) {
            case SWARM:
                final String serviceId = createDockerSwarmService(toCreate, server, NumReplicas.ZERO);
                createdBuilder.serviceId(serviceId);
                break;
            case DOCKER:
                final String containerId = createDockerContainer(toCreate, server);
                createdBuilder.containerId(containerId);
                break;
            case KUBERNETES:
                final String kubernetesJobId = getKubernetesClient().createJob(toCreate, NumReplicas.ZERO, server.containerUser(), server.gpuVendor());
                createdBuilder.serviceId(kubernetesJobId);
                break;
            default:
                throw new NoContainerServerException("Not implemented");
        }

        return createdBuilder.build();

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

        final List<PortBinding> portBindings = new ArrayList<>();
        for (final Map.Entry<String, String> portEntry : toCreate.ports().entrySet()) {
            final String containerPort = portEntry.getKey();
            final String hostPort = portEntry.getValue();

            if (StringUtils.isNotBlank(containerPort) && StringUtils.isNotBlank(hostPort)) {
                portBindings.add(new PortBinding(
                        Ports.Binding.bindPortSpec(hostPort),
                        ExposedPort.tcp(Integer.parseInt(containerPort))
                ));

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

        // Secrets
        final ContainerPropertiesWithSecretValues containerPropertiesWithSecretValues =
                ContainerPropertiesWithSecretValues.prepareSecretsForLaunch(toCreate);

        // Environment variables
        final Map<String, String> environmentVariables = containerPropertiesWithSecretValues.environmentVariables();

        final HostConfig hostConfig = new HostConfig()
                .withAutoRemove(toCreate.autoRemove())
                .withRuntime(instanceOrDefault(toCreate.runtime(), ""))
                .withIpcMode(instanceOrDefault(toCreate.ipcMode(), ""))
                .withBinds(toCreate.bindMountStrings().stream().map(Bind::parse).collect(Collectors.toList()))
                .withPortBindings(portBindings)
                .withMemoryReservation(toCreate.reserveMemoryBytes())
                .withMemory(toCreate.limitMemoryBytes())
                .withNanoCPUs(toCreate.nanoCpus());

        if (toCreate.shmSize() != null && toCreate.shmSize() >= 0) {
            hostConfig.withShmSize(toCreate.shmSize());
        }
        if (!Strings.isNullOrEmpty(toCreate.network())) {
            hostConfig.withNetworkMode(toCreate.network());
        }

        final Map<String, String> ulimits = toCreate.ulimits();
        if (ulimits != null && !ulimits.isEmpty()) {
            List<Ulimit> hostUlimits = ulimits.entrySet().stream()
                    .map(ulimit -> {
                        final String[] split = ulimit.getValue().split(":");
                        return new Ulimit(ulimit.getKey(), Long.parseLong(split[0]), Long.parseLong(split.length > 1 ? split[1] : split[0]));
                    })
                    .collect(Collectors.toList());

            hostConfig.withUlimits(hostUlimits);
        }

        final boolean overrideEntrypoint = toCreate.overrideEntrypointNonnull();

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
        final DockerClient client = getDockerClient(server);

        // Pull image before creating container
        try {
            if (getAllImages(server).stream()
                    .noneMatch(img -> img.tags().contains(toCreate.dockerImage()))) {
                pullImage(toCreate.dockerImage());
            }
        } catch (NoDockerServerException | NotFoundException e) { // TODO make a new version of get and pull that take a server
            log.error("Failed to pull image", e);
            throw new DockerServerException("Could not pull image " + toCreate.dockerImage() + " from repository.", e);
        }

        try {
            // TODO this does some auth config stuff with the image that we should be aware of
            final CreateContainerCmd cmd = client.createContainerCmd(toCreate.dockerImage())
                    .withName(StringUtils.defaultIfBlank(toCreate.containerName(), ""))
                    .withHostConfig(hostConfig)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .withCmd(overrideEntrypoint ?
                            Arrays.asList("/bin/sh", "-c", toCreate.commandLine()) :
                            ShellSplitter.shellSplit(toCreate.commandLine()))
                    .withEnv(environmentVariables.entrySet()
                            .stream()
                            .map(entry -> entry.getKey() + "=" + entry.getValue())
                            .collect(Collectors.toList()))
                    .withWorkingDir(StringUtils.defaultIfBlank(toCreate.workingDirectory(), ""))
                    .withUser(StringUtils.defaultIfBlank(server.containerUser(), ""))
                    .withLabels(instanceOrDefault(toCreate.containerLabels(), Collections.emptyMap()));
            if (overrideEntrypoint) {
                cmd.withEntrypoint("");
            }
            final CreateContainerResponse resp = cmd.exec();
            for (String warning : resp.getWarnings()) {
                log.warn(warning);
            }
            return resp.getId();
        } catch (DockerException e) {
            log.error("Failed to create container", e);
            throw new DockerServerException("Could not create container", e);
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
                    portConfigs.add(new PortConfig()
                            .withProtocol(PortConfigProtocol.TCP)
                            .withPublishedPort(Integer.parseInt(hostPort))
                            .withTargetPort(Integer.parseInt(containerPort))
                    );
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
            new Mount()
                    .withSource(containerMount.containerHostPath())
                    .withTarget(containerMount.containerPath())
                    .withReadOnly(!containerMount.writable())
        ).collect(Collectors.toList());

        //TODO make this configurable from UI
        //add docker socket for 'docker in docker containers'
        // mounts.add(Mount.builder().source("/var/run/docker.sock").target("/var/run/docker.sock").readOnly(false).build());

        // Temporary work-around to support configurable shm-size in swarm service
        // https://github.com/moby/moby/issues/26714
        if (toCreate.shmSize() != null) {
            final Mount tmpfs = new Mount()
                    .withType(MountType.TMPFS)
                    .withTarget("/dev/shm")
                    .withTmpfsOptions(new TmpfsOptions().withSizeBytes(toCreate.shmSize()));

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

        // Secrets
        final ContainerPropertiesWithSecretValues containerPropertiesWithSecretValues =
                ContainerPropertiesWithSecretValues.prepareSecretsForLaunch(toCreate);

        // Environment variables
        final Map<String, String> environmentVariables = containerPropertiesWithSecretValues.environmentVariables();

        final ContainerSpec containerSpec = new ContainerSpec()
                .withImage(toCreate.dockerImage())
                .withDir(workingDirectory)
                .withMounts(mounts)
                .withUser(server.containerUser())
                .withLabels(toCreate.containerLabels())
                .withEnv(environmentVariables.entrySet()
                        .stream()
                        .map(entry -> entry.getKey() + "=" + entry.getValue())
                        .collect(Collectors.toList()));
        if (toCreate.overrideEntrypointNonnull()) {
            containerSpec.withCommand(Arrays.asList("/bin/sh", "-c", toCreate.commandLine()));
        } else {
            containerSpec.withArgs(ShellSplitter.shellSplit(toCreate.commandLine()));
        }

        // Build out GPU/generic resources specifications from command definition to swarm/single server spec.
        // TODO We currently cannot add generic resources to resource requirements in official docker-java
        //   See https://github.com/docker-java/docker-java/issues/2320 for issue and
        //   https://github.com/docker-java/docker-java/pull/2327 for a PR that would fix it.
        //   To support this we had to fork docker-java and make a custom build.
        final List<GenericResource<?>> resourceSpecs = instanceOrDefault(toCreate.genericResources(), Collections.<String, String>emptyMap())
                .entrySet()
                .stream()
                .map(entry -> StringUtils.isNumeric(entry.getValue()) ?
                        new DiscreteResourceSpec().withKind(entry.getKey()).withValue(Integer.parseInt(entry.getValue())) :
                        new NamedResourceSpec().withKind(entry.getKey()).withValue(entry.getValue()))
                .collect(Collectors.toList());

        final ResourceRequirements resourceRequirements = new ResourceRequirements()
                .withReservations(new ResourceSpecs()
                        .withMemoryBytes(toCreate.reserveMemoryBytes())
                        .withGenericResources(resourceSpecs)
                )
                .withLimits(new ResourceSpecs()
                        .withMemoryBytes(toCreate.limitMemoryBytes())
                        .withNanoCPUs(toCreate.nanoCpus()));


        final TaskSpec taskSpec = new TaskSpec()
                .withContainerSpec(containerSpec)
                .withPlacement(new ServicePlacement().withConstraints(toCreate.swarmConstraints()))
                .withRestartPolicy(new ServiceRestartPolicy().withCondition(ServiceRestartCondition.NONE))
                .withResources(resourceRequirements);
        if (!Strings.isNullOrEmpty(toCreate.network())) {
            taskSpec.withNetworks(Collections.singletonList(new NetworkAttachmentConfig().withTarget(toCreate.network())));
        }

        final ServiceSpec serviceSpec = new ServiceSpec()
                .withName(toCreate.containerNameOrRandom())
                .withTaskTemplate(taskSpec)
                .withEndpointSpec(new EndpointSpec().withPorts(portConfigs))
                .withLabels(toCreate.containerLabels())
                .withMode(new ServiceModeConfig()
                        .withReplicated(new ServiceReplicatedModeOptions().withReplicas(numReplicas.value)));

        final DockerClientConfig config = createDockerClientConfig(server);
        final AuthConfig authConfig = authConfig(toCreate.dockerImage(), config);

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

        final DockerClient client = getDockerClient(server);
        try {
            return client.createServiceCmd(serviceSpec)
                    .withAuthConfig(authConfig)
                    .exec()
                    .getId();
        } catch (DockerException e) {
            log.error("Failed to create service", e);
            throw new DockerServerException("Could not create service", e);
        }
    }

    /**
     * Start a backend object that had previously been created.
     * <p>
     * If this is a container on a local docker server, there is a dedicated API to start.
     * If this is a service on docker swarm, we update the replicas from 0 to 1.
     *
     * @param toStart A Container that refers to a previously-created backend object
     */
    @Override
    public void start(final Container toStart) throws NoContainerServerException, ContainerBackendException {
        final DockerServer server = getServer();
        switch (server.backend()) {
            case SWARM:
                setSwarmServiceReplicasToOne(toStart.serviceId(), server);
                break;
            case DOCKER:
                startDockerContainer(toStart.containerId(), server);
                break;
            case KUBERNETES:
                getKubernetesClient().unsuspendJob(toStart.jobName());
                break;
            default:
                throw new NoContainerServerException("Not implemented");
        }
    }

    private void setSwarmServiceReplicasToOne(final String serviceId, final DockerServer server)
            throws DockerServerException {
        final DockerClient client = getDockerClient(server);

        final com.github.dockerjava.api.model.Service service;
        try (final InspectServiceCmd cmd = client.inspectServiceCmd(serviceId)) {
            log.debug("Inspecting service {}", serviceId);
            service = cmd.exec();
        } catch (com.github.dockerjava.api.exception.NotFoundException e) {
            log.error("Could not inspect service {}", serviceId, e);
            throw new DockerServerException("Could not inspect service " + serviceId, e);
        }

        final ServiceSpec originalSpec = service != null ? service.getSpec() : null;
        if (originalSpec == null) {
            throw new DockerServerException("Could not start service " + serviceId + ". Could not inspect service spec.");
        }
        final ServiceSpec updatedSpec = originalSpec
                .withMode(new ServiceModeConfig()
                        .withReplicated(new ServiceReplicatedModeOptions().withReplicas(1))
                );

        final Long version = service.getVersion() != null ? service.getVersion().getIndex() : null;

        try (final UpdateServiceCmd cmd = client.updateServiceCmd(serviceId, updatedSpec)
                .withVersion(version)) {
            log.debug("Updating service {}", serviceId);
            cmd.exec();
        }  catch (DockerException e) {
            throw new DockerServerException("Could not update service", e);
        }
    }

    private void startDockerContainer(final String containerId, final DockerServer server) throws DockerServerException {
        final DockerClient client = getDockerClient(server);
        try (final StartContainerCmd cmd = client.startContainerCmd(containerId)) {
            log.info("Starting container {}", containerId);
            cmd.exec();
        } catch (com.github.dockerjava.api.exception.NotFoundException | NotModifiedException e) {
            log.error("Could not start container {}", containerId, e);
            throw new DockerServerException("Could not start container " + containerId, e);
        }
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
     * @param container Container object whose logs you wish to read
     * @param logType Stdout or Stderr
     * @param withTimestamps Whether timestamps should be added to the log records on the backend
     * @param since Read logs produced after this timestamp
     * @return Container log string
     */
    public String getLog(final Container container, final LogType logType, final Boolean withTimestamps, final OffsetDateTime since) throws ContainerBackendException, NoContainerServerException {
        final DockerServer server = getServer();
        switch (server.backend()) {
            case SWARM:
                return getSwarmServiceLog(server, container.serviceId(), logType, withTimestamps, since);
            case DOCKER:
                return getDockerContainerLog(server, container.containerId(), logType, withTimestamps, since);
            case KUBERNETES:
                return getKubernetesClient().getLog(container.podName(), logType, withTimestamps, since);
            default:
                throw new NoContainerServerException("Not implemented");
        }
    }

    private String getDockerContainerLog(final DockerServer server, final String containerId, final LogType logType, final Boolean withTimestamps, final OffsetDateTime since) throws DockerServerException {
        final DockerClient client = getDockerClient(server);

        final GetLogCallback callback = client.logContainerCmd(containerId)
                .withStdOut(logType == LogType.STDOUT)
                .withStdErr(logType == LogType.STDERR)
                .withFollowStream(false)
                .withTimestamps(withTimestamps)
                .withSince(since == null ? null : Math.toIntExact(since.toEpochSecond()))
                .exec(new GetLogCallback());
        try {
            callback.awaitCompletion();
        } catch (InterruptedException | DockerException e) {
            log.error("Could not get container log", e);
            throw new DockerServerException(e);
        }
        return callback.getLog();
    }

    private String getSwarmServiceLog(final DockerServer server, final String serviceId, final LogType logType, final Boolean withTimestamps, final OffsetDateTime since) throws DockerServerException {
        final DockerClient client = getDockerClient(server);

        final GetLogCallback callback = client.logServiceCmd(serviceId)
                .withStdout(logType == LogType.STDOUT)
                .withStderr(logType == LogType.STDERR)
                .withFollow(false)
                .withTimestamps(withTimestamps)
                .withSince(since == null ? null : Math.toIntExact(since.toEpochSecond()))
                .exec(new GetLogCallback());
        try {
            callback.awaitCompletion();
        } catch (InterruptedException | DockerException e) {
            log.error("Could not get service log", e);
            throw new DockerServerException(e);
        }
        return callback.getLog();
    }

    @VisibleForTesting
    @Nonnull
    public DockerClient getDockerClient() throws NoDockerServerException {
        return getDockerClient(getServer());
    }

    @VisibleForTesting
    @Nonnull
    public DockerClient getDockerClient(final DockerServer server) {
        final DockerClientCacheKey key = new DockerClientCacheKey(server);
        if (CACHED_DOCKER_CLIENT == null || !key.equals(CACHED_DOCKER_CLIENT_KEY)) {
            synchronized (CACHED_DOCKER_CLIENT_MUTEX) {
                if (CACHED_DOCKER_CLIENT == null || !key.equals(CACHED_DOCKER_CLIENT_KEY)) {
                    clearDockerClientCache_onlyCallFromWithinSyncBlock();

                    log.debug("Creating new docker client instance with key {}", key);
                    CACHED_DOCKER_CLIENT_KEY = key;
                    CACHED_DOCKER_CLIENT = createDockerClient(server);
                }
            }
        }
        return CACHED_DOCKER_CLIENT;
    }

    private void clearDockerClientCache() {
        if (CACHED_DOCKER_CLIENT != null) {
            synchronized (CACHED_DOCKER_CLIENT_MUTEX) {
                clearDockerClientCache_onlyCallFromWithinSyncBlock();
            }
        }
    }

    private void clearDockerClientCache_onlyCallFromWithinSyncBlock() {
        if (CACHED_DOCKER_CLIENT != null) {
            final DockerClient clientToClose = CACHED_DOCKER_CLIENT;
            log.debug("Closing docker client instance");
            try {
                clientToClose.close();
            } catch (Exception e) {
                log.error("Error closing docker client", e);
            }
        }
        CACHED_DOCKER_CLIENT_KEY = null;
        CACHED_DOCKER_CLIENT = null;
    }

    private DockerClient createDockerClient(final @Nonnull DockerServer server) {
        final DockerClientConfig config = createDockerClientConfig(server);

        final DockerHttpClient httpClient = new OkDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .connectTimeout(3000)
                .readTimeout(4500)
                .build();

        return DockerClientImpl.getInstance(config, httpClient);
    }

    private DockerClientConfig createDockerClientConfig(final @Nonnull DockerServer server) {
        DefaultDockerClientConfig.Builder configBuilder = DefaultDockerClientConfig.createDefaultConfigBuilder();

        final String host = server.host();
        if (StringUtils.isNotBlank(host)) {
            configBuilder.withDockerHost(host);
        }
        final String certPath = server.certPath();
        if (StringUtils.isNotBlank(certPath)) {
            configBuilder.withDockerCertPath(certPath);
        }

        // TODO are there other things that need to be configured here?

        return configBuilder.build();
    }
    /**
     * Kill a backend entity
     * <p>
     * Note that if the backend is a docker swarm, there is no "kill" concept.
     * This method will remove a swarm service instead.
     *
     * @param container Container object describing backend entity to kill
     */
    @Override
    public void kill(final Container container) throws NoContainerServerException, ContainerBackendException, NotFoundException {
        final DockerServer server = getServer();
        switch (server.backend()) {
            case SWARM:
                removeDockerSwarmService(container.serviceId(), server);
                break;
            case DOCKER:
                killDockerContainer(container.containerId(), server);
                break;
            case KUBERNETES:
                getKubernetesClient().removeJob(container.jobName());
                break;
            default:
                throw new NoContainerServerException("Not implemented");
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
            throws ContainerBackendException, NoContainerServerException, NotFoundException {
        switch (server.backend()) {
            case SWARM:
                removeDockerSwarmService(container.serviceId(), server);
                break;
            case DOCKER:
                removeDockerContainer(container.containerId(), server);
                break;
            case KUBERNETES:
                getKubernetesClient().removeJob(container.jobName());
                break;
            default:
                throw new NoContainerServerException("Not implemented");
        }
    }

    private void killDockerContainer(final String backendId, final DockerServer server)
            throws DockerServerException, NotFoundException {
        final DockerClient client = getDockerClient(server);
        try (final KillContainerCmd cmd = client.killContainerCmd(backendId)) {
            log.debug("Killing container {}", backendId);
            cmd.exec();
        } catch (com.github.dockerjava.api.exception.NotFoundException e) {
            log.error("Could not kill container {}: Not found.", backendId, e);
            throw new NotFoundException(e);
        } catch (DockerException e) {
            log.error("Could not kill container {}", backendId, e);
            throw new DockerServerException(e);
        }
    }

    private void removeDockerContainer(final String backendId, final DockerServer server)
            throws DockerServerException, NotFoundException {
        final DockerClient client = getDockerClient(server);
        try (final RemoveContainerCmd cmd = client.removeContainerCmd(backendId)) {
            log.debug("Removing container {}", backendId);
            cmd.exec();
        } catch (com.github.dockerjava.api.exception.NotFoundException e) {
            log.error("Could not remove container {}: Not found", backendId, e);
            throw new NotFoundException(e);
        } catch (DockerException e) {
            log.error("Could not remove container {}", backendId, e);
            throw new DockerServerException(e);
        }
    }

    private void removeDockerSwarmService(final String backendId, final DockerServer server)
            throws DockerServerException, NotFoundException {
        final DockerClient client = getDockerClient(server);
        try (final RemoveServiceCmd cmd = client.removeServiceCmd(backendId)) {
            log.debug("Removing service {}", backendId);
            cmd.exec();
        } catch (com.github.dockerjava.api.exception.NotFoundException e) {
            log.error("Could not remove service {}: Not found", backendId, e);
            throw new NotFoundException(e);
        } catch (DockerException e) {
            log.error("Could not remove service {}", backendId, e);
            throw new DockerServerException(e);
        }
    }

    @Override
    @Nullable
    public ServiceTask getTaskForService(final DockerServer server, final Container service)
            throws DockerServerException, ServiceNotFoundException, TaskNotFoundException {
        final DockerClient client = getDockerClient(server);

        final String serviceId = service.serviceId();
        final String taskId = service.taskId();
        log.debug("Getting task for service {} \"{}\".", service.databaseId(), serviceId);

        final List<Task> tasks;
        if (taskId == null) {
            log.trace("Inspecting swarm service {} \"{}\".", service.databaseId(), serviceId);
            final String serviceName;
            try {
                final com.github.dockerjava.api.model.Service serviceResponse = client.inspectServiceCmd(serviceId).exec();
                serviceName = serviceResponse.getSpec() != null ? serviceResponse.getSpec().getName() : null;
                log.trace("Service {} \"{}\" has name \"{}\" based on inspection: {}",
                        service.databaseId(), serviceId, serviceName, serviceResponse);
            } catch (com.github.dockerjava.api.exception.NotFoundException e) {
                throw new ServiceNotFoundException(e);
            }

            if (StringUtils.isBlank(serviceName)) {
                throw new DockerServerException("Unable to determine service name for serviceId " + serviceId +
                        ". Cannot get taskId without this.");
            }

            try {
                tasks = client.listTasksCmd().withServiceFilter(serviceName).exec();
            } catch (com.github.dockerjava.api.exception.NotFoundException e) {
                throw new TaskNotFoundException(e);
            } catch (DockerException e) {
                log.error("Could not get task for service {} \"{}\".", service.databaseId(), serviceId, e);
                throw new DockerServerException(e);
            }
        } else {
            log.trace("Service {} \"{}\" has task \"{}\"", service.databaseId(), serviceId, taskId);
            try {
                tasks = client.listTasksCmd().withIdFilter(taskId).exec();
            } catch (com.github.dockerjava.api.exception.NotFoundException e) {
                throw new TaskNotFoundException(e);
            } catch (DockerException e) {
                log.error("Could not get task for service {} \"{}\".", service.databaseId(), serviceId, e);
                throw new DockerServerException(e);
            }
        }

        final Task task;
        if (tasks.size() == 1) {
            task = tasks.get(0);
            log.trace("Found one task \"{}\" for service {} \"{}\"", task.getId(), service.databaseId(), serviceId);
        } else if (tasks.isEmpty()) {
            log.debug("No tasks found for service {} \"{}\"", service.databaseId(), serviceId);
            return null;
        } else {
            throw new DockerServerException("Found multiple tasks for service " + service.databaseId() +
                    " \"" + serviceId + "\", I only know how to handle one. Tasks: " + tasks);
        }

        final ServiceTask serviceTask = ServiceTask.create(task, serviceId);

        if (serviceTask.isExitStatus() && serviceTask.exitCode() == null) {
            // The Task is supposed to have the container exit code, but docker doesn't report it where it should.
            // So go get the container info and get the exit code
            final String containerId = serviceTask.containerId();
            log.debug("Looking up exit code for container {}.", containerId);
            if (containerId != null) {
                final InspectContainerResponse containerInfo = client.inspectContainerCmd(containerId).exec();
                final Long containerExitCode = containerInfo.getState().getExitCodeLong();
                if (containerExitCode == null) {
                    log.debug("Welp. Container exit code is null on the container too.");
                } else {
                    return serviceTask.toBuilder().exitCode(containerExitCode).build();
                }
            } else {
                log.error("Cannot look up exit code. Container ID is null.");
            }
        }

        return serviceTask;
    }

    @Override
    public List<DockerContainerEvent> getContainerEvents(final Date since, final Date until) throws NoDockerServerException, DockerServerException {
        final DockerClient client = getDockerClient(getServer());
        try (final EventsCmd cmd = client.eventsCmd()
                .withSince(String.valueOf(since.getTime() / 1000))
                .withUntil(String.valueOf(until.getTime() / 1000))
                .withEventTypeFilter(EventType.CONTAINER);
             final GetContainerEventsCallback callback = new GetContainerEventsCallback()) {

            // Execute the command and get the events
            log.info("Requesting events from {} to {}", since.getTime() / 1000, until.getTime() / 1000);
            cmd.exec(callback);
            callback.awaitCompletion();
            log.debug("Completed requesting events from {} to {}", since.getTime() / 1000, until.getTime() / 1000);

            return callback.events;
        } catch (IOException | InterruptedException | DockerException e) {
            log.error("Error getting container events", e);
            throw new DockerServerException(e);
        }
    }

    @Override
    public Integer getFinalizingThrottle() {
        try {
            DockerServer server = getServer();
            if (server.backend() == Backend.SWARM) {
                return server.maxConcurrentFinalizingJobs();
            }
            return null;
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

    public enum NumReplicas {
        ZERO(0),
        ONE(1);

        public final int value;
        NumReplicas(int value) {
            this.value = value;
        }
    }

    private static final class GetContainerEventsCallback extends ResultCallbackTemplate<GetContainerEventsCallback, Event> {
        private final List<DockerContainerEvent> events = new ArrayList<>();

        @Override
        public void onNext(Event event) {
            log.debug("Received event: {}", event);
            final Map<String, String> attributes = new HashMap<>();
            final EventActor actor = event.getActor();
            if (actor != null && actor.getAttributes() != null) {
                attributes.putAll(actor.getAttributes());
            }
            if (attributes.containsKey(LABEL_KEY)) {
                attributes.put(LABEL_KEY, "<elided>");
            }
            this.events.add(
                    DockerContainerEvent.create(event.getAction(),
                            actor != null? actor.getId() : null,
                            new Date(event.getTime()),
                            event.getTimeNano(),
                            attributes)
            );
        }
    }

    @VisibleForTesting
    public static final class GetLogCallback extends ResultCallbackTemplate<GetLogCallback, Frame> {
        final ByteArrayOutputStream logBuilder = new ByteArrayOutputStream();

        @Override
        public void onNext(Frame frame) {
            try {
                logBuilder.write(frame.getPayload());
            } catch (IOException e) {
                log.error("Error writing container log", e);
                onError(e);
            }
        }

        public String getLog() {
            return logBuilder.toString();
        }
    }
}