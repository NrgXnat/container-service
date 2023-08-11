package org.nrg.containers.model.container.auto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.auto.value.AutoValue;
import com.google.common.base.Function;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.events.model.ContainerEvent;
import org.nrg.containers.exceptions.ContainerException;
import org.nrg.containers.model.command.auto.ResolvedCommand;
import org.nrg.containers.model.command.auto.ResolvedCommandMount;
import org.nrg.containers.model.container.ContainerInputType;
import org.nrg.containers.model.container.entity.ContainerEntity;
import org.nrg.containers.model.container.entity.ContainerEntityHistory;
import org.nrg.containers.model.container.entity.ContainerEntityInput;
import org.nrg.containers.model.container.entity.ContainerEntityMount;
import org.nrg.containers.model.container.entity.ContainerEntityOutput;
import org.nrg.containers.model.container.entity.ContainerMountFilesEntity;
import org.nrg.containers.secrets.ResolvedSecret;
import org.nrg.containers.model.server.docker.Backend;
import org.nrg.containers.utils.ContainerUtils;
import org.nrg.containers.utils.JsonDateSerializer;
import org.nrg.containers.utils.JsonStringToDateSerializer;
import org.nrg.xft.event.persist.PersistentWorkflowI;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.utils.WorkflowUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.nrg.containers.utils.ContainerUtils.MIB_TO_BYTES;
import static org.nrg.containers.utils.ContainerUtils.NANO;
import static org.nrg.containers.utils.ContainerUtils.instanceOrDefault;

@Slf4j
@AutoValue
public abstract class Container {
    @JsonIgnore private volatile List<String> _bindMountStrings = null;
    @JsonIgnore private volatile List<String> _envStrings = null;
    @JsonIgnore private volatile List<String> _portStrings = null;
    @JsonIgnore private volatile String exitCode;
    @JsonIgnore private volatile List<ContainerHistory> sortedHist = null;

    @JsonProperty("id") public abstract long databaseId();
    @JsonProperty("command-id") public abstract long commandId();
    @Nullable @JsonProperty("status") public abstract String status();
    @JsonSerialize(using=JsonDateSerializer.class)
    @Nullable @JsonProperty("status-time") public abstract Date statusTime();
    @JsonProperty("wrapper-id") public abstract long wrapperId();
    @Nullable @JsonProperty("container-id") public abstract String containerId();
    @Nullable @JsonProperty("workflow-id") public abstract String workflowId();
    @JsonProperty("user-id") public abstract String userId();
    @JsonProperty("project") @Nullable public abstract String project();
    @JsonProperty("backend") public abstract Backend backend();
    @Nullable @JsonProperty("service-id") public abstract String serviceId();
    @Nullable @JsonProperty("task-id") public abstract String taskId();
    @Nullable @JsonProperty("node-id") public abstract String nodeId();
    @JsonProperty("docker-image") public abstract String dockerImage();
    @Nullable @JsonProperty("container-name") public abstract String containerName();
    @JsonProperty("command-line") public abstract String commandLine();
    @Nullable @JsonProperty("override-entrypoint") public abstract Boolean overrideEntrypoint();
    @Nullable @JsonProperty("working-directory") public abstract String workingDirectory();
    @Nullable @JsonProperty("subtype") public abstract String subtype();
    @Nullable @JsonIgnore public abstract Container parent();
    @Nullable @JsonProperty("parent-source-object-name") public abstract String parentSourceObjectName();
    @JsonProperty("env") public abstract ImmutableMap<String, String> environmentVariables();
    @JsonProperty("ports") public abstract ImmutableMap<String, String> ports();
    @JsonProperty("mounts") public abstract ImmutableList<ContainerMount> mounts();
    @JsonIgnore public abstract ImmutableList<ContainerInput> inputs();
    @JsonProperty("outputs") public abstract ImmutableList<ContainerOutput> outputs();
    @JsonProperty("history") public abstract ImmutableList<ContainerHistory> history();
    @JsonProperty("log-paths") public abstract ImmutableList<String> logPaths();
    @Nullable @JsonProperty("reserve-memory") public abstract Long reserveMemory();
    @Nullable @JsonProperty("limit-memory") public abstract Long limitMemory();
    @Nullable @JsonProperty("limit-cpu") public abstract Double limitCpu();
    @Nullable @JsonProperty("swarm-constraints") public abstract List<String> swarmConstraints();
    @Nullable @JsonProperty("runtime") public abstract String runtime();
    @Nullable @JsonProperty("ipc-mode") public abstract String ipcMode();
    @Nullable @JsonProperty("auto-remove") public abstract Boolean autoRemove();
    @Nullable @JsonProperty("shm-size") public abstract Long shmSize();
    @Nullable @JsonProperty("network") public abstract String network();
    @Nullable @JsonProperty("container-labels") public abstract ImmutableMap<String, String> containerLabels();
    @JsonProperty("gpus") @Nullable public abstract String gpus();
    @JsonProperty("generic-resources") @Nullable public abstract ImmutableMap<String, String> genericResources();
    @JsonProperty("ulimits") @Nullable public abstract ImmutableMap<String, String> ulimits();
    @JsonProperty("secrets") public abstract List<ResolvedSecret> secrets();

    /**
     * @deprecated Use {@link Container#backend()} instead
     */
    @Deprecated
    @JsonProperty("swarm")
    public Boolean swarm() {
        return backend() == Backend.SWARM;
    }

    @JsonIgnore
    public String podName() {
        return backend() == Backend.KUBERNETES ? taskId() : null;
    }

    @JsonIgnore
    public String jobName() {
        return backend() == Backend.KUBERNETES ? serviceId() : null;
    }

    @JsonIgnore
    public String containerNameOrRandom() {
        final String name = containerName();
        return Strings.isNullOrEmpty(name) ? UUID.randomUUID().toString() : name;
    }

    @JsonIgnore
    public boolean overrideEntrypointNonnull() {
        final Boolean overrideEntrypoint = overrideEntrypoint();
        return overrideEntrypoint != null && overrideEntrypoint;
    }

    @JsonIgnore
    @Nonnull
    public Long reserveMemoryBytes() {
        return MIB_TO_BYTES * instanceOrDefault(reserveMemory(), 0L);
    }

    @JsonIgnore
    @Nonnull
    public Long limitMemoryBytes() {
        return MIB_TO_BYTES * instanceOrDefault(limitMemory(), 0L);
    }

    @JsonIgnore
    @Nonnull
    public Long nanoCpus() {
        return (new Double(NANO * instanceOrDefault(limitCpu(), 0D))).longValue();
    }

    @JsonIgnore
    @Nonnull
    public List<String> bindMountStrings() {
        if (_bindMountStrings == null) {
            _bindMountStrings = mounts().stream().map(ContainerMount::toBindMountString).collect(Collectors.toList());
        }
        return _bindMountStrings;
    }

    @JsonIgnore
    @Nonnull
    public List<String> environmentVariableStrings() {
        if (_envStrings == null) {
            _envStrings = environmentVariables()
                    .entrySet()
                    .stream()
                    .map(entry -> String.format("%s=%s", entry.getKey(), entry.getValue()))
                    .collect(Collectors.toList());
        }
        return _envStrings;
    }

    @JsonIgnore
    @Nonnull
    public List<String> portStrings() {
        if (_portStrings == null) {
            _portStrings = ports()
                    .entrySet()
                    .stream()
                    .map(entry -> String.format("host %s -> container %s", entry.getValue(), entry.getKey()))
                    .collect(Collectors.toList());
        }
        return _portStrings;
    }

    /**
     * @deprecated Use {@link Container#backend()} instead
     */
    @Deprecated
    @JsonIgnore
    public boolean isSwarmService() {
        return backend() == Backend.SWARM;
    }

    @JsonIgnore
    public String containerOrServiceId() {
        return backend() == Backend.DOCKER ? containerId() : serviceId();
    }

    @JsonIgnore
    public synchronized List<ContainerHistory> getSortedHistory() {
        if (sortedHist == null) {
            sortedHist = Ordering.natural().reverse().sortedCopy(this.history()); //Descending order (most recent first)
        }
        return new ArrayList<>(sortedHist);
    }

    @JsonIgnore
    @Nullable
    private ContainerHistory getLatestServiceHistory() {
        List<ContainerHistory> relevantHistory = getSortedHistory();
        for (ContainerHistory latestNonSysHistory : relevantHistory) {
            if (latestNonSysHistory.entityType().equals("service")) {
                return latestNonSysHistory;
            }
        }
        return null;
    }

    @JsonIgnore
    @NotNull
    public ServiceTask makeTaskFromLastHistoryItem() throws ContainerException {
        // We generally want exit code / status from a service history event
        ContainerHistory hist = getLatestServiceHistory();
        if (hist == null) {
            List<ContainerHistory> sortedHistory = getSortedHistory();
            if (sortedHistory.isEmpty()) {
                // Must have at least one history item to get here, so this shouldn't happen, but...
                throw new ContainerException("No history for container " + containerOrServiceId());
            }
            // We cannot pass a null history item into createFromHistoryAndService, so give it the latest one,
            // even if it's not a service item
            hist = sortedHistory.get(0);
        }
        return ServiceTask.createFromHistoryAndService(hist, this);
    }

    @JsonIgnore
    @Nullable
    public String exitCode() {
        if (exitCode == null) {
            // Assumption: At most one container history item will have a non-null exit code.
            // "": This event is an exit event (status == kill, die, or oom) but the attributes map
            //      did not contain an "exitCode" key
            // "0": success
            // "1" to "255": failure
            for (final ContainerHistory history : getSortedHistory()) {
                if (history.exitCode() != null) {
                    exitCode = history.exitCode();
                    break;
                }
            }
        }
        return exitCode;
    }

    @JsonIgnore
    @Nullable
    public String lastHistoryStatus() {
        return getSortedHistory().get(0).status();
    }

    @JsonIgnore
    public int countRestarts() {
        int restarts = 0;
        for (ContainerHistory history : this.history()) {
            if (history.isRestartStatus()) {
                restarts++;
            }
        }
        return restarts;
    }

    @JsonIgnore
    @Nullable
    public String getWorkflowStatus(UserI user) {
        final PersistentWorkflowI workflow = WorkflowUtils.getUniqueWorkflow(user, workflowId());
        return workflow == null ? null : workflow.getStatus();
    }

    @JsonIgnore
    public boolean statusIsTerminal() {
        return ContainerUtils.statusIsTerminal(status());
    }


    /**
     * Get outputs in order such that an output that creates a data type (say an assessor or scan xml) will be earlier
     * in the list than an output that uploads to that data type. Put differently, an output with
     * "as-a-child-of": "some-other-output-handler" will be placed after the output handled by
     * "some-other-output-handler" in this list.
     *
     * @return the ordered list of outputs
     */
    @JsonIgnore
    public List<ContainerOutput> getOrderedOutputs() {
        Map<String, ContainerOutput> outputsByHandler = outputs().stream()
                .collect(Collectors.toMap(ContainerOutput::fromOutputHandler, java.util.function.Function.identity()));

        List<ContainerOutput> orderedOutputs = new ArrayList<>();
        for (ContainerOutput output : this.outputs()) {
            addOutputAndDependencies(output, outputsByHandler, orderedOutputs);
        }
        return orderedOutputs;
    }

    /**
     * Add any dependencies for an output and then the output itself to the ordered list of container outputs
     * @param output            the output
     * @param outputsByHandler  a map of handler names to outputs
     * @param orderedOutputs    the ordered list of outputs
     */
    @JsonIgnore
    private void addOutputAndDependencies(ContainerOutput output, Map<String, ContainerOutput> outputsByHandler,
                                          List<ContainerOutput> orderedOutputs) {
        ContainerOutput dependency;
        // if output is handled by another output, then it has a dependency. If we haven't already added this dependency,
        // we need to add it and any of its dependencies (hence the recursion)
        if (outputsByHandler.containsKey(output.handledBy()) &&
                !orderedOutputs.contains(dependency = outputsByHandler.get(output.handledBy()))) {
            addOutputAndDependencies(dependency, outputsByHandler, orderedOutputs);
        }
        // if we haven't added the output itself to the list, do it now
        if (!orderedOutputs.contains(output)) {
            orderedOutputs.add(output);
        }
    }

    /**
     * Group secrets by their type, so they can be more easily used with the backend APIs
     * @return Map from resolved secret classes to a list of secrets of that type
     */
    @JsonIgnore
    public Map<Class<? extends ResolvedSecret>, List<ResolvedSecret>> secretsByType() {
        return secrets().stream().collect(Collectors.groupingBy(ResolvedSecret::getClass));
    }

    @JsonCreator
    public static Container create(@JsonProperty("id") final long databaseId,
                                   @JsonProperty("command-id") final long commandId,
                                   @JsonProperty("status") final String status,
                                   @JsonProperty("status-time") final Date statusTime,
                                   @JsonProperty("wrapper-id") final long wrapperId,
                                   @JsonProperty("container-id") final String containerId,
                                   @JsonProperty("workflow-id") final String workflowId,
                                   @JsonProperty("user-id") final String userId,
                                   @JsonProperty("project") final String project,
                                   @JsonProperty("backend") Backend backend,
                                   @JsonProperty("swarm") final Boolean swarm,
                                   @JsonProperty("service-id") final String serviceId,
                                   @JsonProperty("task-id") final String taskId,
                                   @JsonProperty("node-id") final String nodeId,
                                   @JsonProperty("docker-image") final String dockerImage,
                                   @JsonProperty("container-name") final String containerName,
                                   @JsonProperty("command-line") final String commandLine,
                                   @JsonProperty("override-entrypoint") final Boolean overrideEntrypoint,
                                   @JsonProperty("working-directory") final String workingDirectory,
                                   @JsonProperty("subtype") final String subtype,
                                   @JsonProperty("parent-source-object-name") final String parentSourceObjectName,
                                   @JsonProperty("env") final Map<String, String> environmentVariables,
                                   @JsonProperty("ports") final Map<String, String> ports,
                                   @JsonProperty("mounts") final List<ContainerMount> mounts,
                                   @JsonProperty("inputs") final List<ContainerInput> inputs,
                                   @JsonProperty("outputs") final List<ContainerOutput> outputs,
                                   @JsonProperty("history") final List<ContainerHistory> history,
                                   @JsonProperty("log-paths") final List<String> logPaths,
                                   @JsonProperty("reserve-memory") final Long reserveMemory,
                                   @JsonProperty("limit-memory") final Long limitMemory,
                                   @JsonProperty("limit-cpu") final Double limitCpu,
                                   @JsonProperty("swarm-constraints") final List<String> swarmConstraints,
                                   @JsonProperty("runtime") final String runtime,
                                   @JsonProperty("ipc-mode") final String ipcMode,
                                   @JsonProperty("auto-remove") final Boolean autoremove,
                                   @JsonProperty("shm-size") final Long shmSize,
                                   @JsonProperty("network") final String network,
                                   @JsonProperty("container-labels") final Map<String, String> containerLabels,
                                   @JsonProperty("secrets") final List<ResolvedSecret> secrets)
    {
        if (backend == null) {
            backend = swarm != null && swarm ? Backend.SWARM : Backend.DOCKER;
        }
        return builder()
                .databaseId(databaseId)
                .status(status)
                .statusTime(statusTime == null ? null : new Date(statusTime.getTime()))
                .commandId(commandId)
                .wrapperId(wrapperId)
                .containerId(containerId)
                .workflowId(workflowId)
                .userId(userId)
                .project(project)
                .backend(backend)
                .serviceId(serviceId)
                .taskId(taskId)
                .nodeId(nodeId)
                .dockerImage(dockerImage)
                .containerName(containerName)
                .commandLine(commandLine)
                .overrideEntrypoint(overrideEntrypoint)
                .workingDirectory(workingDirectory)
                .subtype(subtype)
                .parentSourceObjectName(parentSourceObjectName)
                .environmentVariables(environmentVariables == null ? Collections.emptyMap() : environmentVariables)
                .ports(ports == null ? Collections.emptyMap() : ports)
                .mounts(mounts == null ? Collections.emptyList() : mounts)
                .inputs(inputs == null ? Collections.emptyList() : inputs)
                .outputs(outputs == null ? Collections.emptyList() : outputs)
                .history(history == null ? Collections.emptyList() : history)
                .logPaths(logPaths == null ? Collections.emptyList() : logPaths)
                .reserveMemory(reserveMemory)
                .limitMemory(limitMemory)
                .limitCpu(limitCpu)
                .swarmConstraints(swarmConstraints)
                .runtime(runtime)
                .ipcMode(ipcMode)
                .autoRemove(autoremove)
                .shmSize(shmSize)
                .network(network)
                .containerLabels(containerLabels)
                .secrets(secrets == null ? Collections.emptyList() : secrets)
                .build();
    }

    public static Container create(final ContainerEntity containerEntity) {
        if (containerEntity == null) {
            return null;
        }
        return builder()
                .databaseId(containerEntity.getId())
                .status(containerEntity.getStatus())
                .statusTime(containerEntity.getStatusTime() == null ? null : new Date(containerEntity.getStatusTime().getTime()))
                .commandId(containerEntity.getCommandId())
                .wrapperId(containerEntity.getWrapperId())
                .containerId(containerEntity.getContainerId())
                .workflowId(containerEntity.getWorkflowId())
                .userId(containerEntity.getUserId())
                .project(containerEntity.getProject())
                .backend(containerEntity.getBackend())
                .serviceId(containerEntity.getServiceId())
                .taskId(containerEntity.getTaskId())
                .nodeId(containerEntity.getNodeId())
                .dockerImage(containerEntity.getDockerImage())
                .containerName(containerEntity.getContainerName())
                .commandLine(containerEntity.getCommandLine())
                .overrideEntrypoint(containerEntity.getOverrideEntrypoint())
                .workingDirectory(containerEntity.getWorkingDirectory())
                .subtype(containerEntity.getSubtype())
                .parent(create(containerEntity.getParentContainerEntity()))
                .parentSourceObjectName(containerEntity.getParentSourceObjectName())
                .environmentVariables(containerEntity.getEnvironmentVariables() == null ?
                        Collections.emptyMap() :
                        containerEntity.getEnvironmentVariables()
                )
                .ports(containerEntity.getPorts() == null ? Collections.emptyMap() : containerEntity.getPorts())
                .logPaths(containerEntity.getLogPaths() == null ? Collections.emptyList() : containerEntity.getLogPaths())
                .mounts(containerEntity.getMounts() == null ?
                        Collections.emptyList() :
                        containerEntity.getMounts().stream().map(ContainerMount::create).collect(Collectors.toList())
                )
                .inputs(containerEntity.getInputs() == null ?
                        Collections.emptyList() :
                        containerEntity.getInputs().stream().map(ContainerInput::create).collect(Collectors.toList())
                )
                .outputs(containerEntity.getOutputs() == null ?
                        Collections.emptyList() :
                        containerEntity.getOutputs().stream().map(ContainerOutput::create).collect(Collectors.toList())
                )
                .history(containerEntity.getHistory() == null ?
                        Collections.emptyList() :
                        containerEntity.getHistory().stream().map(ContainerHistory::create).collect(Collectors.toList())
                )
                .reserveMemory(containerEntity.getReserveMemory())
                .limitMemory(containerEntity.getLimitMemory())
                .limitCpu(containerEntity.getLimitCpu())
                .swarmConstraints(containerEntity.getSwarmConstraints())
                .runtime(containerEntity.getRuntime())
                .ipcMode(containerEntity.getIpcMode())
                .autoRemove(containerEntity.getAutoRemove())
                .shmSize(containerEntity.getShmSize())
                .network(containerEntity.getNetwork())
                .containerLabels(containerEntity.getContainerLabels())
                .secrets(containerEntity.getSecrets() == null ? Collections.emptyList() :
                        containerEntity.getSecrets().stream()
                                .map(ResolvedSecret::fromUnresolved)
                                .collect(Collectors.toList()))
                .build();
    }

    public static Container.Builder builderFromResolvedCommand(final ResolvedCommand resolvedCommand) {
        return builder()
                .commandId(resolvedCommand.commandId())
                .wrapperId(resolvedCommand.wrapperId())
                .project(resolvedCommand.project())
                .dockerImage(resolvedCommand.image())
                .containerName(resolvedCommand.containerName())
                .commandLine(resolvedCommand.commandLine())
                .overrideEntrypoint(resolvedCommand.overrideEntrypoint())
                .workingDirectory(resolvedCommand.workingDirectory())
                .environmentVariables(resolvedCommand.environmentVariables())
                .ports(resolvedCommand.ports())
                .subtype(resolvedCommand.type())
                .mountsFromResolvedCommand(resolvedCommand.mounts())
                .addRawInputs(resolvedCommand.rawInputValues())
                .addResolvedInputs(resolvedCommand.inputValues())
                .addOutputsFromResolvedCommand(resolvedCommand.outputs())
                .reserveMemory(resolvedCommand.reserveMemory())
                .limitMemory(resolvedCommand.limitMemory())
                .limitCpu(resolvedCommand.limitCpu())
                .swarmConstraints(resolvedCommand.swarmConstraints())
                .parentSourceObjectName(resolvedCommand.parentSourceObjectName())
                .parentSourceObjectName(resolvedCommand.parentSourceObjectName())
                .runtime(resolvedCommand.runtime())
                .ipcMode(resolvedCommand.ipcMode())
                .autoRemove(resolvedCommand.autoRemove())
                .shmSize(resolvedCommand.shmSize())
                .network(resolvedCommand.network())
                .containerLabels(resolvedCommand.containerLabels())
                .gpus(resolvedCommand.gpus())
                .genericResources(resolvedCommand.genericResources())
                .ulimits(resolvedCommand.ulimits())
                .secrets(resolvedCommand.secrets());
    }

    public static Builder builder() {
        return new AutoValue_Container.Builder()
                .databaseId(0L)
                .backend(Backend.DOCKER)
                .containerLabels(Collections.emptyMap())
                .secrets(Collections.emptyList());
    }

    public abstract Builder toBuilder();

    private Map<String, String> getInputs(final ContainerInputType type) {
        final Map<String, String> inputs = Maps.newHashMap();
        for (final ContainerInput input : inputs()) {
            if (input.type() == type) {
                inputs.put(input.name(), input.value());
            }
        }
        return inputs;
    }

   
    
    @JsonIgnore
    public Map<String, String> getCommandInputs() {
        return getInputs(ContainerInputType.COMMAND);
    }

    @JsonIgnore
    @SuppressWarnings("deprecation")
    public Map<String, String> getWrapperInputs() {
        final Map<String, String> wrapperInputs = Maps.newHashMap();
        wrapperInputs.putAll(getLegacyWrapperInputs());
        wrapperInputs.putAll(getExternalWrapperInputs());
        wrapperInputs.putAll(getDerivedWrapperInputs());
        return wrapperInputs;
    }

    @JsonIgnore
    public Map<String, String> getExternalWrapperInputs() {
        return getInputs(ContainerInputType.WRAPPER_EXTERNAL);
    }

    @JsonIgnore
    public Map<String, String> getDerivedWrapperInputs() {
        return getInputs(ContainerInputType.WRAPPER_DERIVED);
    }

    /**
     * Get inputs of type "wrapper".
     * We no longer save inputs of this type. Now the wrapper inputs are separately saved
     * as type "wrapper_external" or "wrapper_derived". But we keep this here for legacy containers.
     * @return A map of wrapper input names to values.
     * @since 1.2
     */
    @JsonIgnore
    @Deprecated
    public Map<String, String> getLegacyWrapperInputs() {
        return getInputs(ContainerInputType.WRAPPER_DEPRECATED);
    }

    @JsonIgnore
    public Map<String, String> getRawInputs() {
        return getInputs(ContainerInputType.RAW);
    }

    /**
     * This will be returned in the container JSON as "inputs" rather than the stored list
     * of inputs. If any of the inputs are marked as "sensitive", we mask them out. That
     * already happens in {@link ContainerInput#maskedValue()}. But once one input has a
     * sensitive value, any sensitive value, we can no longer trust the "raw" inputs,
     * i.e. the input values we received directly from the user. If no inputs are sensitive, we
     * trust the raw inputs are fine to show; if any inputs are sensitive, then all raw inputs
     * have got to go.
     * @return The list of container inputs with raw input values removed if any other inputs are sensitive.
     */
    @JsonGetter("inputs")
    @SuppressWarnings("unused")
    public ImmutableList<ContainerInput> maskedInputs() {
        final ImmutableList<ContainerInput> inputs = inputs();
        boolean anyAreSensitive = false;
        for (final ContainerInput input : inputs) {
            final Boolean inputIsSensitive = input.sensitive();
            anyAreSensitive = (inputIsSensitive != null && inputIsSensitive);
            if (anyAreSensitive) {
                break;
            }
        }

        // If none of the inputs were sensitive, we can trust the raw inputs
        if (!anyAreSensitive) {
            return inputs;
        }

        // If any inputs were sensitive, we can no longer trust the raw inputs. Do not return them.
        final ImmutableList.Builder<ContainerInput> maskedInputsBuilder = ImmutableList.builder();
        for (final ContainerInput input : inputs) {
            if (input.type() != ContainerInputType.RAW) {
                maskedInputsBuilder.add(input);
            }
        }
        return maskedInputsBuilder.build();
    }

    @JsonIgnore
    public String getLogPath(final String filename) {
        String fullFileName = filename;
        if (!filename.endsWith(".log")) {
            fullFileName += ".log";
        }
        for (final String path : logPaths()) {
            if (path.endsWith(fullFileName)) {
                return path;
            }
        }
        return null;
    }

    @AutoValue.Builder
    public static abstract class Builder {
        public abstract Builder databaseId(long databaseId);
        public abstract Builder commandId(long commandId);
        public abstract Builder wrapperId(long wrapperId);
        public abstract Builder containerId(String containerId);
        public abstract Builder workflowId(String workflowId);
        public abstract Builder userId(String userId);
        public abstract Builder project(String project);
        public abstract Builder dockerImage(String dockerImage);
        public abstract Builder containerName(String containerName);
        public abstract Builder commandLine(String commandLine);
        public abstract Builder overrideEntrypoint(Boolean overrideEntrypoint);
        public abstract Builder workingDirectory(String workingDirectory);
        public abstract Builder backend(Backend backend);
        public abstract Builder serviceId(String serviceId);
        public abstract Builder taskId(String taskId);
        public abstract Builder nodeId(String nodeId);
        public abstract Builder status(String status);
        public abstract Builder statusTime(Date statusTime);
        public abstract Builder subtype(String subtype);
        public abstract Builder parent(Container parent);
        public abstract Builder parentSourceObjectName(String parentSourceObjectName);
        public abstract Builder reserveMemory(Long reserveMemory);
        public abstract Builder limitMemory(Long limitMemory);
        public abstract Builder limitCpu(Double limitCpu);
        public abstract Builder swarmConstraints(List<String> swarmConstraints);
        public abstract Builder runtime(String runtime);
        public abstract Builder ipcMode(String ipcMode);
        public abstract Builder autoRemove(Boolean autoRemove);
        public abstract Builder shmSize(Long shmSize);
        public abstract Builder network(String network);
        public abstract Builder containerLabels(Map<String, String> containerLabels);
        public abstract Builder gpus(String gpus);
        public abstract Builder genericResources(Map<String, String> genericResources);
        public abstract Builder ulimits(Map<String, String> ulimits);
        public abstract Builder secrets(List<ResolvedSecret> secrets);

        public abstract Builder environmentVariables(Map<String, String> environmentVariables);
        abstract ImmutableMap.Builder<String, String> environmentVariablesBuilder();
        public Builder addEnvironmentVariable(final String envKey, final String envValue) {
            environmentVariablesBuilder().put(envKey, envValue);
            return this;
        }
        public Builder addEnvironmentVariables(final Map<String, String> environmentVariables) {
            if (environmentVariables != null) {
                for (final Map.Entry<String, String> env : environmentVariables.entrySet()) {
                    addEnvironmentVariable(env.getKey(), env.getValue());
                }
            }
            return this;
        }

        public abstract Builder ports(Map<String, String> ports);
        public abstract ImmutableMap.Builder<String, String> portsBuilder();
        public Builder addPort(final String name, final String value) {
            portsBuilder().put(name, value);
            return this;
        }

        public abstract Builder mounts(List<ContainerMount> mounts);
        abstract ImmutableList.Builder<ContainerMount> mountsBuilder();
        public Builder addMount(final ContainerMount mounts) {
            mountsBuilder().add(mounts);
            return this;
        }
        public Builder mountsFromResolvedCommand(final List<ResolvedCommandMount> resolvedCommandMounts) {
            if (resolvedCommandMounts != null) {
                for (final ResolvedCommandMount resolvedCommandMount : resolvedCommandMounts) {
                    addMount(ContainerMount.create(resolvedCommandMount));
                }
            }
            return this;
        }

        public abstract Builder inputs(List<ContainerInput> inputs);
        abstract ImmutableList.Builder<ContainerInput> inputsBuilder();
        public Builder addInput(final ContainerInput input) {
            inputsBuilder().add(input);
            return this;
        }

        public Builder addResolvedInput(final ResolvedCommand.ResolvedCommandInput resolvedCommandInput) {
            return addInput(ContainerInput.create(resolvedCommandInput));
        }
        public Builder addResolvedInputs(final Collection<ResolvedCommand.ResolvedCommandInput> resolvedCommandInputs) {
            if (resolvedCommandInputs != null) {
                for (final ResolvedCommand.ResolvedCommandInput resolvedCommandInput : resolvedCommandInputs) {
                    addResolvedInput(resolvedCommandInput);
                }
            }
            return this;
        }
        public Builder addRawInputs(Map<String, String> inputMap) {
            if (inputMap != null) {
                for (final Map.Entry<String, String> input : inputMap.entrySet()) {
                    addInput(ContainerInput.create(0L, ContainerInputType.RAW, input.getKey(), input.getValue(), false));
                }
            }
            return this;
        }

        public abstract Builder outputs(List<ContainerOutput> outputs);
        abstract ImmutableList.Builder<ContainerOutput> outputsBuilder();
        public Builder addOutput(final ContainerOutput outputs) {
            outputsBuilder().add(outputs);
            return this;
        }
        public Builder addOutputsFromResolvedCommand(final List<ResolvedCommand.ResolvedCommandOutput> resolvedCommandOutputs) {
            if (resolvedCommandOutputs != null) {
                for (final ResolvedCommand.ResolvedCommandOutput resolvedCommandOutput : resolvedCommandOutputs) {
                    addOutput(ContainerOutput.create(resolvedCommandOutput));
                }
            }
            return this;
        }

        public abstract Builder history(List<ContainerHistory> history);
        abstract ImmutableList.Builder<ContainerHistory> historyBuilder();
        public Builder addHistoryItem(final ContainerHistory history) {
            historyBuilder().add(history);
            return this;
        }

        public abstract Builder logPaths(List<String> logPaths);
        abstract ImmutableList.Builder<String> logPathsBuilder();
        public Builder addLogPath(final String logPaths) {
            logPathsBuilder().add(logPaths);
            return this;
        }

        public abstract Container build();
    }

    @AutoValue
    public static abstract class ContainerMount {
        @JsonProperty("id") public abstract long databaseId();
        @JsonProperty("name") public abstract String name();
        @JsonProperty("writable") public abstract boolean writable();
        @JsonProperty("xnat-host-path") public abstract String xnatHostPath();
        @JsonProperty("container-host-path") public abstract String containerHostPath();
        @JsonProperty("container-path") public abstract String containerPath();
        @JsonProperty("mount-pvc-name") @Nullable public abstract String mountPvcName();

        /**
         * This used to return a list of the files that were found in an input mount. But we didn't use it anywhere in
         * the code. Now I think it just takes up space in the database for nothing.
         *
         * @return An empty list
         * @deprecated Since 2.0.0
         */
        @Deprecated @JsonProperty("input-files") public abstract ImmutableList<ContainerMountFiles> inputFiles();

        @JsonCreator
        @SuppressWarnings("deprecation")
        public static ContainerMount create(@JsonProperty("id") final long databaseId,
                                            @JsonProperty("name") final String name,
                                            @JsonProperty("writable") final boolean writable,
                                            @JsonProperty("xnat-host-path") final String xnatHostPath,
                                            @JsonProperty("container-host-path") final String containerHostPath,
                                            @JsonProperty("container-path") final String containerPath,
                                            @JsonProperty("input-files") final List<ContainerMountFiles> inputFiles,
                                            @JsonProperty("mount-pvc-name") final String mountPvcName) {
            return builder()
                    .databaseId(databaseId)
                    .name(name)
                    .writable(writable)
                    .xnatHostPath(xnatHostPath)
                    .containerHostPath(containerHostPath)
                    .containerPath(containerPath)
                    .inputFiles(inputFiles == null ? Collections.<ContainerMountFiles>emptyList() : inputFiles)
                    .mountPvcName(mountPvcName)
                    .build();
        }

        @SuppressWarnings("deprecation")
        public static ContainerMount create(final ContainerEntityMount containerEntityMount) {
            final List<ContainerMountFiles> containerMountFiles = containerEntityMount.getInputFiles() == null ? null :
                    Lists.transform(containerEntityMount.getInputFiles(), new Function<ContainerMountFilesEntity, ContainerMountFiles>() {
                        @Override
                        public ContainerMountFiles apply(final ContainerMountFilesEntity input) {
                            return ContainerMountFiles.create(input);
                        }
                    });
            return create(containerEntityMount.getId(), containerEntityMount.getName(), containerEntityMount.isWritable(),
                    containerEntityMount.getXnatHostPath(), containerEntityMount.getContainerHostPath(),
                    containerEntityMount.getContainerPath(), containerMountFiles, containerEntityMount.getMountPvcName());
        }

        public static ContainerMount create(final ResolvedCommandMount resolvedCommandMount) {
            return create(0L,
                    resolvedCommandMount.name(),
                    resolvedCommandMount.writable(),
                    resolvedCommandMount.xnatHostPath(),
                    resolvedCommandMount.containerHostPath(),
                    resolvedCommandMount.containerPath(),
                    null,
                    resolvedCommandMount.mountPvcName());
        }

        @JsonIgnore
        public String toBindMountString() {
            return containerHostPath() + ":" + containerPath() + (writable() ? "" : ":ro");
        }

        public static Builder builder() {
            return new AutoValue_Container_ContainerMount.Builder();
        }

        public abstract Builder toBuilder();

        @AutoValue.Builder
        public static abstract class Builder {
            public abstract Builder databaseId(long databaseId);
            public abstract Builder name(String name);
            public abstract Builder writable(boolean writable);
            public abstract Builder xnatHostPath(String xnatHostPath);
            public abstract Builder containerHostPath(String containerHostPath);
            public abstract Builder containerPath(String containerPath);
            public abstract Builder mountPvcName(String mountPvcName);

            @Deprecated public abstract Builder inputFiles(List<ContainerMountFiles> inputFiles);
            @Deprecated abstract ImmutableList.Builder<ContainerMountFiles> inputFilesBuilder();

            public abstract ContainerMount build();
        }
    }

    /**
     * A file mounted when a container was launched. No longer used.
     *
     * @deprecated Since 2.0.0
     */
    @AutoValue
    @Deprecated
    public static abstract class ContainerMountFiles {
        @JsonProperty("id") public abstract long databaseId();
        @Nullable @JsonProperty("from-xnat-input") public abstract String fromXnatInput();
        @Nullable @JsonProperty("from-uri") public abstract String fromUri();
        @Nullable @JsonProperty("root-directory") public abstract String rootDirectory();
        @Nullable @JsonProperty("path") public abstract String path();

        @JsonCreator
        public static ContainerMountFiles create(@JsonProperty("id") final long databaseId,
                                                 @JsonProperty("from-xnat-input") final String fromXnatInput,
                                                 @JsonProperty("from-uri") final String fromUri,
                                                 @JsonProperty("root-directory") final String rootDirectory,
                                                 @JsonProperty("path") final String path) {
            return new AutoValue_Container_ContainerMountFiles(databaseId, fromXnatInput, fromUri, rootDirectory, path);
        }

        public static ContainerMountFiles create(final ContainerMountFilesEntity containerMountFilesEntity) {
            return create(containerMountFilesEntity.getId(), containerMountFilesEntity.getFromXnatInput(), containerMountFilesEntity.getFromUri(),
                    containerMountFilesEntity.getRootDirectory(), containerMountFilesEntity.getPath());
        }
    }

    @AutoValue
    public static abstract class ContainerInput {
        @JsonProperty("id") public abstract long databaseId();
        @JsonProperty("type") public abstract ContainerInputType type();
        @JsonProperty("name") public abstract String name();
        @JsonIgnore public abstract String value();
        @Nullable @JsonProperty("sensitive") public abstract Boolean sensitive();

        @JsonCreator
        public static ContainerInput create(@JsonProperty("id") final long databaseId,
                                            @JsonProperty("type") final ContainerInputType type,
                                            @JsonProperty("name") final String name,
                                            @JsonProperty("value") final String value,
                                            @JsonProperty("sensitive") final Boolean sensitive) {
            return new AutoValue_Container_ContainerInput(databaseId, type, name, value, sensitive);
        }

        public static ContainerInput create(final ContainerEntityInput containerEntityInput) {
            return create(
                    containerEntityInput.getId(),
                    containerEntityInput.getType(),
                    containerEntityInput.getName(),
                    containerEntityInput.getValue(),
                    containerEntityInput.getSensitive()
            );
        }

        public static ContainerInput create(final ResolvedCommand.ResolvedCommandInput resolvedCommandInput) {
            return create(
                    0L,
                    resolvedCommandInput.type(),
                    resolvedCommandInput.name(),
                    resolvedCommandInput.value(),
                    resolvedCommandInput.sensitive()
            );
        }

        @JsonGetter("value")
        public String maskedValue() {
            final Boolean sensitive = sensitive();
            return sensitive != null && sensitive ? "*****" : value();
        }
    }

    @AutoValue
    public static abstract class ContainerOutput {
        @JsonProperty("id") public abstract long databaseId();
        @JsonProperty("name") public abstract String name();
        @Nullable @JsonProperty("from-command-output") public abstract String fromCommandOutput();
        @Nullable @JsonProperty("from-output-handler") public abstract String fromOutputHandler();
        @JsonProperty("type") public abstract String type();
        @JsonProperty("required") public abstract Boolean required();
        @JsonProperty("mount") public abstract String mount();
        @Nullable @JsonProperty("path") public abstract String path();
        @Nullable @JsonProperty("glob") public abstract String glob();
        @Nullable @JsonProperty("label") public abstract String label();
        @Nullable @JsonProperty("format") public abstract String format();
        @Nullable @JsonProperty("description") public abstract String description();
        @Nullable @JsonProperty("content") public abstract String content();
        @Nullable @JsonProperty("tags") public abstract ImmutableList<String> tags();
        @Nullable @JsonProperty("created") public abstract String created();
        @JsonProperty("handled-by") public abstract String handledBy();
        @Nullable @JsonProperty("via-wrapup-container") public abstract String viaWrapupContainer();

        @JsonCreator
        public static ContainerOutput create(@JsonProperty("id") final long databaseId,
                                             @JsonProperty("name") final String name,
                                             @JsonProperty("from-command-output") final String fromCommandOutput,
                                             @JsonProperty("from-output-handler") final String fromOutputHandler,
                                             @JsonProperty("type") final String type,
                                             @JsonProperty("required") final Boolean required,
                                             @JsonProperty("mount") final String mount,
                                             @JsonProperty("path") final String path,
                                             @JsonProperty("glob") final String glob,
                                             @JsonProperty("label") final String label,
                                             @JsonProperty("format") final String format,
                                             @JsonProperty("description") final String description,
                                             @JsonProperty("content") final String content,
                                             @JsonProperty("tags") final List<String> tags,
                                             @JsonProperty("created") final String created,
                                             @JsonProperty("handled-by") final String handledByWrapperInput,
                                             @JsonProperty("via-wrapup-container") final String viaWrapupContainer) {
            return builder()
                    .databaseId(databaseId)
                    .name(name)
                    .fromCommandOutput(fromCommandOutput)
                    .fromOutputHandler(fromOutputHandler)
                    .type(type)
                    .required(required)
                    .mount(mount)
                    .path(path)
                    .glob(glob)
                    .label(label)
                    .format(format)
                    .description(description)
                    .content(content)
                    .tags(tags == null ? Collections.emptyList() : tags)
                    .created(created)
                    .handledBy(handledByWrapperInput)
                    .viaWrapupContainer(viaWrapupContainer)
                    .build();
        }

        public static ContainerOutput create(final ContainerEntityOutput containerEntityOutput) {
            return create(containerEntityOutput.getId(),
                    containerEntityOutput.getName(),
                    containerEntityOutput.getFromCommandOutput(),
                    containerEntityOutput.getFromOutputHandler(),
                    containerEntityOutput.getType(),
                    containerEntityOutput.isRequired(),
                    containerEntityOutput.getMount(),
                    containerEntityOutput.getPath(),
                    containerEntityOutput.getGlob(),
                    containerEntityOutput.getLabel(),
                    containerEntityOutput.getFormat(),
                    containerEntityOutput.getDescritpion(),
                    containerEntityOutput.getContent(),
                    containerEntityOutput.getTags(),
                    containerEntityOutput.getCreated(),
                    containerEntityOutput.getHandledByXnatCommandInput(),
                    containerEntityOutput.getViaWrapupContainer());
        }

        public static ContainerOutput create(final ResolvedCommand.ResolvedCommandOutput resolvedCommandOutput) {
            return create(0L,
                    resolvedCommandOutput.name(),
                    resolvedCommandOutput.fromCommandOutput(),
                    resolvedCommandOutput.fromOutputHandler(),
                    resolvedCommandOutput.type(),
                    resolvedCommandOutput.required(),
                    resolvedCommandOutput.mount(),
                    resolvedCommandOutput.path(),
                    resolvedCommandOutput.glob(),
                    resolvedCommandOutput.label(),
                    resolvedCommandOutput.format(),
                    resolvedCommandOutput.description(),
                    resolvedCommandOutput.content(),
                    resolvedCommandOutput.tags(),
                    null,
                    resolvedCommandOutput.handledBy(),
                    resolvedCommandOutput.viaWrapupCommand());
        }

        public static Builder builder() {
            return new AutoValue_Container_ContainerOutput.Builder();
        }

        public abstract Builder toBuilder();

        @AutoValue.Builder
        public static abstract class Builder {
            public abstract Builder databaseId(long databaseId);
            public abstract Builder name(String name);
            public abstract Builder fromCommandOutput(String fromCommandOutput);
            public abstract Builder fromOutputHandler(String fromOutputHandler);
            public abstract Builder type(String type);
            public abstract Builder required(Boolean required);
            public abstract Builder mount(String mount);
            public abstract Builder path(String path);
            public abstract Builder glob(String glob);
            public abstract Builder label(String label);
            public abstract Builder format(String format);
            public abstract Builder description(String description);
            public abstract Builder content(String content);
            public abstract Builder tags(List<String> tags);
            public abstract Builder created(String created);
            public abstract Builder handledBy(String handledBy);
            public abstract Builder viaWrapupContainer(String viaWrapupContainer);

            public abstract ContainerOutput build();
        }
    }

    @AutoValue
    public static abstract class ContainerHistory implements Comparable<ContainerHistory> {
        @Nullable @JsonProperty("id") public abstract Long databaseId();
        @JsonProperty("status") public abstract String status();
        @JsonProperty("entity-type") public abstract String entityType();
        @Nullable @JsonProperty("entity-id") public abstract String entityId();
        @JsonSerialize(using=JsonDateSerializer.class)
        @JsonProperty("time-recorded") public abstract Date timeRecorded();
        @JsonSerialize(using=JsonStringToDateSerializer.class)
        @Nullable @JsonProperty("external-timestamp") public abstract String externalTimestamp();
        @Nullable @JsonProperty("message") public abstract String message();
        @Nullable @JsonProperty("exitCode") public abstract String exitCode();

        @JsonIgnore
        public final static String restartStatus = "Restart";

        @JsonCreator
        public static ContainerHistory create(@JsonProperty("id") final long databaseId,
                                              @JsonProperty("status") final String status,
                                              @JsonProperty("entity-type") final String entityType,
                                              @JsonProperty("entity-id") final String entityId,
                                              @JsonProperty("time-recorded") final Date timeRecorded,
                                              @JsonProperty("external-timestamp") final String externalTimestamp,
                                              @JsonProperty("message") final String message,
                                              @JsonProperty("exitCode") final String exitCode) {
            return builder()
                    .databaseId(databaseId)
                    .status(status)
                    .entityType(entityType)
                    .entityId(entityId)
                    .timeRecorded(timeRecorded)
                    .externalTimestamp(externalTimestamp)
                    .message(message)
                    .exitCode(exitCode)
                    .build();
        }

        public static ContainerHistory create(final ContainerEntityHistory containerEntityHistory) {
            return builder()
                    .databaseId(containerEntityHistory.getId())
                    .status(containerEntityHistory.getStatus())
                    .entityType(containerEntityHistory.getEntityType())
                    .entityId(containerEntityHistory.getEntityId())
                    .timeRecorded(containerEntityHistory.getTimeRecorded())
                    .externalTimestamp(containerEntityHistory.getExternalTimestamp())
                    .message(containerEntityHistory.getMessage())
                    .exitCode(containerEntityHistory.getExitCode())
                    .build();
        }

        public static ContainerHistory fromContainerEvent(final ContainerEvent containerEvent) {
            return builder()
                    .status(containerEvent.status())
                    .entityType("event")
                    .entityId(null)
                    .timeRecorded(new Date())
                    .externalTimestamp(containerEvent.externalTimestamp())
                    .message(null)
                    .exitCode(containerEvent.exitCode())
                    .build();
        }

        public static ContainerHistory fromSystem(final String status,
                                                  final String message) {
            return fromSystem(status, message, null);
        }

        public static ContainerHistory fromSystem(final String status,
                                                  final String message,
                                                  final String exitCode) {
            Date now = new Date();
            return builder()
                    .status(status)
                    .entityType("system")
                    .entityId(null)
                    .timeRecorded(now)
                    .externalTimestamp(String.valueOf(now.getTime()))
                    .message(message)
                    .exitCode(exitCode)
                    .build();
        }

        public static ContainerHistory fromUserAction(final String status, final String username) {
            return builder()
                    .status(status)
                    .entityType("user")
                    .entityId(username)
                    .timeRecorded(new Date())
                    .externalTimestamp(null)
                    .message(null)
                    .build();
        }

        public static ContainerHistory fromServiceTask(final ServiceTask task) {
            String message = StringUtils.defaultString(task.message(), "");
            if (StringUtils.isNotBlank(message) && StringUtils.isNotBlank(task.err())) {
                message += ": ";
            }
            message += StringUtils.defaultString(task.err(), "");
            return builder()
                    .entityType("service")
                    .entityId(null)
                    .status(task.status())
                    .exitCode(task.exitCode() == null ? null : String.valueOf(task.exitCode()))
                    .timeRecorded(new Date())
                    .externalTimestamp(task.statusTime() == null ? null : String.valueOf(task.statusTime().getTime()))
                    .message(message)
                    .build();
        }

        @Override
        public int compareTo(@NotNull ContainerHistory other) {
            Date thisTime = this.timeRecorded();
            Date otherTime = other.timeRecorded();
            if (thisTime == null || otherTime == null) {
                return 0;
            }
            return thisTime.compareTo(otherTime);
        }

        @JsonIgnore
        public boolean isRestartStatus() {
            return status().equals(restartStatus);
        }

        public static Builder builder() {
            return new AutoValue_Container_ContainerHistory.Builder();
        }

        @AutoValue.Builder
        public static abstract class Builder {
            public abstract Builder databaseId(Long databaseId);
            public abstract Builder status(String status);
            public abstract Builder entityType(String entityType);
            public abstract Builder entityId(String entityId);
            public abstract Builder timeRecorded(Date timeRecorded);
            public abstract Builder externalTimestamp(String externalTimestamp);
            public abstract Builder message(String message);
            public abstract Builder exitCode(String exitCode);
            public abstract ContainerHistory build();
        }
    }
}
