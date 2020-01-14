package org.nrg.containers.api;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.spotify.docker.client.DefaultDockerClient;
import com.spotify.docker.client.DockerCertificates;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.DockerClient.ListImagesParam;
import com.spotify.docker.client.DockerClient.LogsParam;
import com.spotify.docker.client.EventStream;
import com.spotify.docker.client.LogStream;
import com.spotify.docker.client.exceptions.ContainerNotFoundException;
import com.spotify.docker.client.exceptions.DockerCertificateException;
import com.spotify.docker.client.exceptions.DockerException;
import com.spotify.docker.client.exceptions.ImageNotFoundException;
import com.spotify.docker.client.exceptions.ServiceNotFoundException;
import com.spotify.docker.client.messages.ContainerConfig;
import com.spotify.docker.client.messages.ContainerCreation;
import com.spotify.docker.client.messages.ContainerInfo;
import com.spotify.docker.client.messages.Event;
import com.spotify.docker.client.messages.HostConfig;
import com.spotify.docker.client.messages.Image;
import com.spotify.docker.client.messages.ImageInfo;
import com.spotify.docker.client.messages.PortBinding;
import com.spotify.docker.client.messages.RegistryAuth;
import com.spotify.docker.client.messages.ServiceCreateResponse;
import com.spotify.docker.client.messages.mount.Mount;
import com.spotify.docker.client.messages.swarm.ContainerSpec;
import com.spotify.docker.client.messages.swarm.EndpointSpec;
import com.spotify.docker.client.messages.swarm.PortConfig;
import com.spotify.docker.client.messages.swarm.ReplicatedService;
import com.spotify.docker.client.messages.swarm.RestartPolicy;
import com.spotify.docker.client.messages.swarm.ServiceMode;
import com.spotify.docker.client.messages.swarm.ServiceSpec;
import com.spotify.docker.client.messages.swarm.Task;
import com.spotify.docker.client.messages.swarm.TaskSpec;
import com.spotify.docker.client.messages.swarm.ResourceRequirements;
import com.spotify.docker.client.messages.swarm.Resources;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.events.model.DockerContainerEvent;
import org.nrg.containers.events.model.ServiceTaskEvent;
import org.nrg.containers.exceptions.ContainerException;
import org.nrg.containers.exceptions.DockerServerException;
import org.nrg.containers.exceptions.NoDockerServerException;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.auto.ResolvedCommand;
import org.nrg.containers.model.command.auto.ResolvedCommandMount;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.auto.ContainerMessage;
import org.nrg.containers.model.container.auto.ServiceTask;
import org.nrg.containers.model.dockerhub.DockerHubBase.DockerHub;
import org.nrg.containers.model.image.docker.DockerImage;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.services.CommandLabelService;
import org.nrg.containers.services.DockerServerService;
import org.nrg.containers.utils.ShellSplitter;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.framework.services.NrgEventService;
import org.nrg.xft.security.UserI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static com.spotify.docker.client.DockerClient.EventsParam.since;
import static com.spotify.docker.client.DockerClient.EventsParam.type;
import static com.spotify.docker.client.DockerClient.EventsParam.until;
import static org.nrg.containers.services.CommandLabelService.LABEL_KEY;

@Slf4j
@Service
public class DockerControlApi implements ContainerControlApi {

    private final DockerServerService dockerServerService;
    private final CommandLabelService commandLabelService;
    private final NrgEventService eventService;

    @Autowired
    public DockerControlApi(final DockerServerService dockerServerService,
                            final CommandLabelService commandLabelService,
                            final NrgEventService eventService) {
        this.dockerServerService = dockerServerService;
        this.commandLabelService = commandLabelService;
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
            log.error("Unable to connect with Docker server:\n" + (dockerServer == null ? "" : dockerServer.toString()), e.getMessage());
            throw new DockerServerException(e);
        }
    }

    private String pingSwarmMaster(final DockerServer dockerServer) throws DockerServerException {
        try (final DockerClient client = getClient(dockerServer)) {
            client.listNodes();
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
        return pingHub(hub, null, null);
    }

    @Override
    @Nonnull
    public String pingHub(final @Nonnull DockerHub hub, final @Nullable String username, final @Nullable String password)
            throws DockerServerException, NoDockerServerException {
        int status = 500;
        try (final DockerClient client = getClient()) {
            status = client.auth(registryAuth(hub, username, password));
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new DockerServerException(e);
        }
        return status < 400 ? "OK" : "";
    }

    @Nullable
    private RegistryAuth registryAuth(final @Nullable DockerHub hub, final @Nullable String username, final @Nullable String password) {
        if (hub == null) {
            return null;
        }
        return RegistryAuth.builder()
                .serverAddress(hub.url())
                .username(username == null ? "" : username)
                .password(password == null ? "" : password)
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
        return Lists.newArrayList(
                Lists.transform(_getImages(params),
                        new Function<Image, DockerImage>() {
                            @Override
                            @Nullable
                            public DockerImage apply(final @Nullable Image image) {
                                return spotifyToNrg(image);
                            }
                        }
                )
        );
    }

    private List<com.spotify.docker.client.messages.Image> _getImages(final Map<String, String> params)
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
        final ListImagesParam[] dockerParams =
                dockerParamsList.toArray(new ListImagesParam[dockerParamsList.size()]);

        try (final DockerClient dockerClient = getClient()) {
            return dockerClient.listImages(dockerParams);
        } catch (DockerException | InterruptedException e) {
            log.error("Failed to list images. " + e.getMessage());
            throw new DockerServerException(e);
        } catch (Error e) {
            log.error("Failed to list images. " + e.getMessage());
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
            throws NoDockerServerException, DockerServerException, NotFoundException {
        final DockerImage image = spotifyToNrg(_getImageById(imageId, client));
        if (image != null) {
            return image;
        }
        throw new NotFoundException(String.format("Could not find image %s", imageId));
    }

    private com.spotify.docker.client.messages.ImageInfo _getImageById(final String imageId, final DockerClient client)
        throws DockerServerException, NoDockerServerException, NotFoundException {
        try {
            return client.inspectImage(imageId);
        } catch (ImageNotFoundException e) {
            throw new NotFoundException(e);
        } catch (DockerException | InterruptedException e) {
            throw new DockerServerException(e);
        }
    }

    /**
     * Launch image on Docker server, either by scheduling a swarm service or directly creating a container
     *
     * @param resolvedCommand A ResolvedDockerCommand. All templates are resolved, all mount paths exist.
     * @param userI The XNAT user launching the container
     * @return Created Container or Service
     **/
    @Override
    public Container createContainerOrSwarmService(final ResolvedCommand resolvedCommand, final UserI userI)
            throws NoDockerServerException, DockerServerException, ContainerException {

        // CS-403 We need to make sure everything exists before we mount it, else
        // bad stuff can happen.
        for (final ResolvedCommandMount mount : resolvedCommand.mounts()) {
            final File mountFile = Paths.get(mount.xnatHostPath()).toFile();
            if (!mountFile.exists()) {
                if (!mountFile.getParentFile().exists()){
                    mountFile.getParentFile().mkdirs();
                }
                if (!mountFile.isFile()) {
                    mountFile.mkdirs();
                }
            }
        }

        final List<String> environmentVariables = Lists.newArrayList();
        for (final Map.Entry<String, String> env : resolvedCommand.environmentVariables().entrySet()) {
            environmentVariables.add(StringUtils.join(new String[] {env.getKey(), env.getValue()}, "="));
        }
        final String workingDirectory = StringUtils.isNotBlank(resolvedCommand.workingDirectory()) ?
                resolvedCommand.workingDirectory() :
                null;

        // let resource constraints default to 0, so they're ignored by Docker
        final Long reserveMemory = resolvedCommand.reserveMemory() == null ?
                0L :
                resolvedCommand.reserveMemory();
        final Long limitMemory = resolvedCommand.limitMemory() == null ?
                0L :
                resolvedCommand.limitMemory();
        final Double limitCpu = resolvedCommand.limitCpu() == null ?
                0D :
                resolvedCommand.limitCpu();

        final List<ResolvedCommandMount> resolvedCommandMounts = resolvedCommand.mounts();
        final DockerServer server = getServer();
        if (server.swarmMode()) {
            final List<Mount> mounts = new ArrayList<>(resolvedCommandMounts.size());
            for (final ResolvedCommandMount resolvedCommandMount : resolvedCommandMounts) {
                mounts.add(Mount.builder()
                        .source(resolvedCommandMount.containerHostPath())
                        .target(resolvedCommandMount.containerPath())
                        .readOnly(!resolvedCommandMount.writable())
                        .build());
            }

            return Container.serviceFromResolvedCommand(resolvedCommand,
                    createService(server,
                            resolvedCommand.image(),
                            resolvedCommand.commandLine(),
                            resolvedCommand.overrideEntrypoint(),
                            mounts,
                            environmentVariables,
                            resolvedCommand.ports(),
                            workingDirectory,
                            reserveMemory,
                            limitMemory,
                            limitCpu),
                    userI.getLogin()
            );
        } else {
            final List<String> bindMounts = new ArrayList<>(resolvedCommandMounts.size());
            for (final ResolvedCommandMount mount : resolvedCommandMounts) {
                bindMounts.add(mount.toBindMountString());
            }

            return Container.containerFromResolvedCommand(resolvedCommand,
                    createContainer(server,
                            resolvedCommand.image(),
                            resolvedCommand.commandLine(),
                            resolvedCommand.overrideEntrypoint(),
                            bindMounts,
                            environmentVariables,
                            resolvedCommand.ports(),
                            workingDirectory,
                            reserveMemory,
                            limitMemory,
                            limitCpu),
                    userI.getLogin()
            );
        }
    }

    /**
     * Launch image on Docker server, either by scheduling a swarm service or directly creating a container
     *
     * @param container A Container object, populated with all the information needed to launch.
     * @param userI The XNAT user launching the container
     * @return Created Container or Service
     **/
    @Override
    public Container createContainerOrSwarmService(final Container container, final UserI userI) throws NoDockerServerException, ContainerException, DockerServerException {
        for (final Container.ContainerMount mount : container.mounts()) {
            final File mountFile = Paths.get(mount.xnatHostPath()).toFile();
            if (!mountFile.exists()) {
                if (mountFile.isDirectory()) {
                    mountFile.mkdirs();
                } else {
                    mountFile.getParentFile().mkdirs();
                }
            }
        }

        final List<String> environmentVariables = Lists.newArrayList();
        for (final Map.Entry<String, String> env : container.environmentVariables().entrySet()) {
            environmentVariables.add(StringUtils.join(new String[] {env.getKey(), env.getValue()}, "="));
        }
        final String workingDirectory = StringUtils.isNotBlank(container.workingDirectory()) ?
                container.workingDirectory() :
                null;

        // let resource constraints default to 0, so they're ignored by Docker
        final Long reserveMemory = container.reserveMemory() == null ?
                0L :
                container.reserveMemory();
        final Long limitMemory = container.limitMemory() == null ?
                0L :
                container.limitMemory();
        final Double limitCpu = container.limitCpu() == null ?
                0D :
                container.limitCpu();

        final DockerServer server = getServer();

        final List<Container.ContainerMount> containerMounts = container.mounts();
        final Boolean overrideEntrypointMayBeNull = container.overrideEntrypoint();
        final boolean overrideEntrypoint = overrideEntrypointMayBeNull != null && overrideEntrypointMayBeNull;
        if (server.swarmMode()) {
            final List<Mount> mounts = new ArrayList<>(containerMounts.size());
            for (final Container.ContainerMount containerMount : containerMounts) {
                mounts.add(Mount.builder()
                        .source(containerMount.containerHostPath())
                        .target(containerMount.containerPath())
                        .readOnly(!containerMount.writable())
                        .build());
            }

            final String serviceId = createService(server,
                    container.dockerImage(),
                    container.commandLine(),
                    overrideEntrypoint,
                    mounts,
                    environmentVariables,
                    container.ports(),
                    workingDirectory,
                    reserveMemory,
                    limitMemory,
                    limitCpu);
            return container.toBuilder()
                    .serviceId(serviceId)
                    .swarm(true)
                    .userId(userI.getLogin())
                    .build();
        } else {
            final List<String> bindMounts = new ArrayList<>(containerMounts.size());
            for (final Container.ContainerMount mount : containerMounts) {
                bindMounts.add(mount.toBindMountString());
            }

            final String containerId = createContainer(server,
                    container.dockerImage(),
                    container.commandLine(),
                    overrideEntrypoint,
                    bindMounts,
                    environmentVariables,
                    container.ports(),
                    workingDirectory,
                    reserveMemory,
                    limitMemory,
                    limitCpu);

            return container.toBuilder()
                    .containerId(containerId)
                    .userId(userI.getLogin())
                    .build();
        }
    }

    private String createContainer(final DockerServer server,
                                   final String imageName,
                                   final String runCommand,
                                   final boolean overrideEntrypoint,
                                   final List<String> bindMounts,
                                   final List<String> environmentVariables,
                                   final Map<String, String> ports,
                                   final String workingDirectory,
                                   final Long reserveMemory,
                                   final Long limitMemory,
                                   final Double limitCpu)
            throws DockerServerException, ContainerException {

        final Map<String, List<PortBinding>> portBindings = Maps.newHashMap();
        final List<String> portStringList = Lists.newArrayList();
        if (!(ports == null || ports.isEmpty())) {
            for (final Map.Entry<String, String> portEntry : ports.entrySet()) {
                final String containerPort = portEntry.getKey();
                final String hostPort = portEntry.getValue();

                if (StringUtils.isNotBlank(containerPort) && StringUtils.isNotBlank(hostPort)) {
                    final PortBinding portBinding = PortBinding.create(null, hostPort);
                    portBindings.put(containerPort + "/tcp", Lists.newArrayList(portBinding));

                    portStringList.add("host" + hostPort + "->" + "container" + containerPort);
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
        }

        final String user = server.containerUser();

        final HostConfig hostConfig =
                HostConfig.builder()
                        .binds(bindMounts)
                        .portBindings(portBindings)
                        .memoryReservation(1024 * 1024 * reserveMemory) // megabytes to bytes
                        .memory(1024 * 1024 * limitMemory) // megabytes to bytes
                        .nanoCpus((new Double(1e9 * limitCpu)).longValue()) // number of cpus (double) to nano-cpus (long, = cpu / 10^9)
                        .build();
        final ContainerConfig containerConfig =
                ContainerConfig.builder()
                        .hostConfig(hostConfig)
                        .image(imageName)
                        .attachStdout(true)
                        .attachStderr(true)
                        .cmd(overrideEntrypoint ? Lists.newArrayList("/bin/sh", "-c", runCommand) : ShellSplitter.shellSplit(runCommand))
                        .entrypoint(overrideEntrypoint ? Collections.singletonList("") : null)
                        .env(environmentVariables)
                        .workingDir(workingDirectory)
                        .user(user)
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
                    imageName,
                    runCommand,
                    workingDirectory,
                    user,
                    StringUtils.join(bindMounts, ", "),
                    StringUtils.join(environmentVariables, ", "),
                    StringUtils.join(portStringList, ", ")
            );
            log.debug(message);
        }

        try (final DockerClient client = getClient(server)) {
            final ContainerCreation container = client.createContainer(containerConfig);

            final List<String> warnings = container.warnings();
            if (warnings != null) {
                for (String warning : warnings) {
                    log.warn(warning);
                }
            }

            return container.id();
        } catch (DockerException | InterruptedException e) {
            log.error(e.getMessage());
            throw new DockerServerException("Could not create container from image " + imageName, e);
        }
    }

    private String createService(final DockerServer server,
                                 final String imageName,
                                 final String runCommand,
                                 final boolean overrideEntrypoint,
                                 final List<Mount> mounts,
                                 final List<String> environmentVariables,
                                 final Map<String, String> ports,
                                 final String workingDirectory,
                                 final Long reserveMemory,
                                 final Long limitMemory,
                                 final Double limitCpu)
            throws DockerServerException, ContainerException {

        final List<PortConfig> portConfigs = Lists.newArrayList();
        final List<String> portStringList = Lists.newArrayList();
        if (!(ports == null || ports.isEmpty())) {
            for (final Map.Entry<String, String> portEntry : ports.entrySet()) {
                final String containerPort = portEntry.getKey();
                final String hostPort = portEntry.getValue();

                if (StringUtils.isNotBlank(containerPort) && StringUtils.isNotBlank(hostPort)) {
                    try {
                        portConfigs.add(PortConfig.builder()
                                .protocol(PortConfig.PROTOCOL_TCP)
                                .publishedPort(Integer.parseInt(hostPort))
                                .targetPort(Integer.parseInt(containerPort))
                                .build());

                        portStringList.add("host" + hostPort + "->" + "container" + containerPort);
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
        }

        // We get the bind mounts strings here not to use for creating the service,
        // but simply for the debug log
        // The Mount objects are what we need for the service
        final List<String> bindMounts = new ArrayList<>(mounts.size());
        for (final Mount mount : mounts) {
            final Boolean ro = mount.readOnly();
            bindMounts.add(mount.source() + ":" + mount.target() + (ro != null && ro ? ":ro" : ""));
        }

        final String user = server.containerUser();

        final ContainerSpec.Builder containerSpecBuilder = ContainerSpec.builder()
                .image(imageName)
                .env(environmentVariables)
                .dir(workingDirectory)
                .mounts(mounts)
                .user(user);
        if (overrideEntrypoint) {
            containerSpecBuilder.command("/bin/sh", "-c", runCommand);
        } else {
            containerSpecBuilder.args(ShellSplitter.shellSplit(runCommand));
        }

        final TaskSpec taskSpec = TaskSpec.builder()
                .containerSpec(containerSpecBuilder.build())
                .restartPolicy(RestartPolicy.builder()
                        .condition("none")
                        .build())
                .resources(ResourceRequirements.builder()
                        .reservations(Resources.builder()
                            .memoryBytes(1024 * 1024 * reserveMemory) // megabytes to bytes
                            .build())
                        .limits(Resources.builder()
                            .memoryBytes(1024 * 1024 * limitMemory) // megabytes to bytes
                            .nanoCpus((new Double(1e9 * limitCpu)).longValue()) // number of cpus (double) to nano-cpus (long, = cpu / 10^9)
                            .build())
                        .build())
                .build();
        final ServiceSpec serviceSpec =
                ServiceSpec.builder()
                        .taskTemplate(taskSpec)
                        .mode(ServiceMode.builder()
                                .replicated(ReplicatedService.builder()
                                        .replicas(0L) // We initially want zero replicas. We will modify this later when it is time to start.
                                        .build())
                                .build())
                        .endpointSpec(EndpointSpec.builder()
                                .ports(portConfigs)
                                .build())
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
                    imageName,
                    runCommand,
                    workingDirectory,
                    user,
                    StringUtils.join(bindMounts, ", "),
                    StringUtils.join(environmentVariables, ", "),
                    StringUtils.join(portStringList, ", ")
            );
            log.debug(message);
        }

        try (final DockerClient client = getClient(server)) {
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
            throw new DockerServerException("Could not create service", e);
        }
    }

    @Override
    public void startContainer(final Container containerOrService) throws DockerServerException, NoDockerServerException {
        startContainer(containerOrService, getServer());
    }

    private void startContainer(final Container containerOrService,
                                final DockerServer server) throws DockerServerException {
        final boolean swarmMode = server.swarmMode();
        final String containerOrServiceId = swarmMode ? containerOrService.serviceId() : containerOrService.containerId();
        try (final DockerClient client = getClient(server)) {
            if (swarmMode) {
                log.debug("Inspecting service " + containerOrServiceId);
                final com.spotify.docker.client.messages.swarm.Service service = client.inspectService(containerOrServiceId);
                if (service == null || service.spec() == null) {
                    throw new DockerServerException("Could not start service " + containerOrServiceId + ". Could not inspect service spec.");
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

                log.info("Setting service replication to 1.");
                client.updateService(containerOrServiceId, version, updatedSpec);
            } else {
                log.info("Starting container: id " + containerOrServiceId);
                client.startContainer(containerOrServiceId);
            }
        } catch (DockerException | InterruptedException e) {
            log.error(e.getMessage());
            final String containerOrServiceStr = swarmMode ? "service" : "container";
            throw new DockerServerException("Could not start " + containerOrServiceStr + " " + containerOrServiceId, e);
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
        return pullImage(name, hub, null, null);
    }

    @Override
    @Nullable
    public DockerImage pullImage(final String name, final @Nullable DockerHub hub, final @Nullable String username, final @Nullable String password) throws NoDockerServerException, DockerServerException, NotFoundException {
        final DockerClient client = getClient();
        _pullImage(name, registryAuth(hub, username, password), client);  // We want to throw NotFoundException here if the image is not found on the hub
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
        List<com.spotify.docker.client.messages.Container> containerList;

        // Transform param map to ListImagesParam array
        final List<DockerClient.ListContainersParam> dockerParamsList = Lists.newArrayList();
        if (params != null && params.size() > 0) {
            for (final Map.Entry<String, String> paramEntry : params.entrySet()) {
                dockerParamsList.add(DockerClient.ListContainersParam.create(paramEntry.getKey(), paramEntry.getValue()));
            }
        }
        final DockerClient.ListContainersParam[] dockerParams =
                dockerParamsList.toArray(new DockerClient.ListContainersParam[dockerParamsList.size()]);

        try (final DockerClient dockerClient = getClient()) {
            containerList = dockerClient.listContainers(dockerParams);
        } catch (DockerException | InterruptedException e) {
            log.error(e.getMessage());
            throw new DockerServerException(e);
        }
        return Lists.newArrayList(
                Lists.transform(containerList, new Function<com.spotify.docker.client.messages.Container, ContainerMessage>() {
                    @Override
                    @Nullable
                    public ContainerMessage apply(final @Nullable com.spotify.docker.client.messages.Container container) {
                        return spotifyToNrg(container);
                    }
                })
        );
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

    @Override
    public String getStdoutLog(final Container container) throws NoDockerServerException, DockerServerException {
        return getContainerLog(container, LogsParam.stdout());
    }

    @Override
    public String getStderrLog(final Container container) throws NoDockerServerException, DockerServerException {
        return getContainerLog(container, LogsParam.stderr());
    }

    private String getContainerLog(final Container container, final LogsParam logType) throws NoDockerServerException, DockerServerException {
        try (final LogStream logStream = logStream(container, logType)) {
            return logStream.readFully();
        } catch (NoDockerServerException e) {
            throw e;
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new DockerServerException(e);
        }
    }

    private LogStream logStream(final Container container, final LogsParam logType) throws DockerServerException, NoDockerServerException, DockerException, InterruptedException {
        final DockerServer server = getServer();
        return server.swarmMode() && container.isSwarmService() ?
                getClient(server).serviceLogs(container.serviceId(), logType) :
                getClient(server).logs(container.containerId(), logType);
    }

    @VisibleForTesting
    @Nonnull
    public DockerClient getClient() throws NoDockerServerException, DockerServerException {
        return getClient(getServer());
    }

    @Nonnull
    private DockerClient getClient(final @Nonnull DockerServer server) throws DockerServerException {

        DefaultDockerClient.Builder clientBuilder =
            DefaultDockerClient.builder()
                .uri(server.host());

        if (StringUtils.isNotBlank(server.certPath())) {
            try {
                final DockerCertificates certificates =
                    new DockerCertificates(Paths.get(server.certPath()));
                clientBuilder = clientBuilder.dockerCertificates(certificates);
            } catch (DockerCertificateException e) {
                log.error("Could not find docker certificates at " + server.certPath(), e);
            }
        }

        try {
            return clientBuilder.build();
        } catch (Throwable e) {
            log.error("Could not create DockerClient instance. Reason: " + e.getMessage());
            throw new DockerServerException(e);
        }
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

    @Override
    public void throwContainerEvents(final Date since, final Date until) throws NoDockerServerException, DockerServerException {
        final List<DockerContainerEvent> events = getContainerEvents(since, until);

        for (final DockerContainerEvent event : events) {
            if (log.isDebugEnabled()) {
                log.debug("Throwing docker container event: " + event);
            }
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
        } catch (InterruptedException | DockerException e) {
            log.error(e.getMessage(), e);
            throw new DockerServerException(e);
        } catch (DockerServerException e) {
            log.error(e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public void killContainer(final String id) throws NoDockerServerException, DockerServerException, NotFoundException {
        try(final DockerClient client = getClient()) {
            log.info("Killing container " + id);
            client.killContainer(id);
        } catch (ContainerNotFoundException e) {
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

    @Override
    @Nullable
    public ServiceTask getTaskForService(final Container service) throws NoDockerServerException, DockerServerException, ServiceNotFoundException {
        return getTaskForService(getServer(), service);
    }

    @Override
    @Nullable
    public ServiceTask getTaskForService(final DockerServer dockerServer, final Container service)
            throws DockerServerException, ServiceNotFoundException {
        try (final DockerClient client = getClient(dockerServer)) {
            Task task = null;

            if (service.taskId() == null) {
                log.trace("Service {} does not have task information yet.", service.serviceId());
                final com.spotify.docker.client.messages.swarm.Service serviceResponse = client.inspectService(service.serviceId());
                log.trace("Service {} has name {}. Finding tasks by service name.", service.serviceId(), serviceResponse.spec().name());
                final List<Task> tasks = client.listTasks(Task.Criteria.builder().serviceName(serviceResponse.spec().name()).build());

                if (tasks.size() == 1) {
                    log.trace("Found one task for service name {}.", serviceResponse.spec().name());
                    task = tasks.get(0);
                } else if (tasks.size() == 0) {
                    log.trace("No tasks found for service name {}.", serviceResponse.spec().name());
                } else {
                    log.trace("Found {} tasks for service name {}. Not sure which to use.", serviceResponse.spec().name());
                }
            } else {
                log.trace("Service {} has task ID {}.", service.serviceId(), service.taskId());
                final String taskId = service.taskId();
                task = client.inspectTask(taskId);
            }

            if (task != null) {
                final ServiceTask serviceTask = ServiceTask.create(task, service.serviceId());

                if (serviceTask.isExitStatus() && serviceTask.exitCode() == null) {
                    // The Task is supposed to have the container exit code, but docker doesn't report it where it should.
                    // So go get the container info and get the exit code
                    log.debug("Looking up exit code for container {}.", serviceTask.containerId());
                    if (serviceTask.containerId() != null) {
                        final ContainerInfo containerInfo = client.inspectContainer(serviceTask.containerId());
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
        } catch (ServiceNotFoundException e) {
            log.error(e.getMessage());
            throw e;
        } catch (DockerException | InterruptedException e) {
            log.error(e.getMessage(), e);
            throw new DockerServerException(e);
        } catch (DockerServerException e) {
            log.error(e.getMessage(), e);
            throw e;
        }
        return null;
    }

    @Override
    public void throwTaskEventForService(final Container service) throws NoDockerServerException, DockerServerException, ServiceNotFoundException {
        throwTaskEventForService(getServer(), service);
    }

    @Override
    public void throwTaskEventForService(final DockerServer dockerServer, final Container service) throws DockerServerException, ServiceNotFoundException {
        final ServiceTask task = getTaskForService(dockerServer, service);
        if (task != null) {
            final ServiceTaskEvent serviceTaskEvent = ServiceTaskEvent.create(task, service);
            log.trace("Throwing service task event for service {}.", serviceTaskEvent.service().serviceId());
            eventService.triggerEvent(serviceTaskEvent);
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
    private ContainerMessage spotifyToNrg(final @Nullable com.spotify.docker.client.messages.Container dockerContainer) {
        return dockerContainer == null ? null : ContainerMessage.create(dockerContainer.id(), dockerContainer.status());
    }

    /**
     * Convert spotify-docker Container object to xnat-container Container object
     *
     * @param dockerContainer Spotify-Docker ContainerInfo object
     * @return NRG Container object
     **/
    @Nullable
    private ContainerMessage spotifyToNrg(final @Nullable com.spotify.docker.client.messages.ContainerInfo dockerContainer) {
        return dockerContainer == null ? null : ContainerMessage.create(
                dockerContainer.id(),
                dockerContainer.state().running() ? "Running" :
                        dockerContainer.state().paused() ? "Paused" :
                        dockerContainer.state().restarting() != null && dockerContainer.state().restarting() ? "Restarting" :
                        dockerContainer.state().exitCode() != null ? "Exited" :
                        null
        );
    }
}
