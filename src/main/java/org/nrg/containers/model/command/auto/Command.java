package org.nrg.containers.model.command.auto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auto.value.AutoValue;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.model.command.entity.CommandEntity;
import org.nrg.containers.model.command.entity.CommandInputEntity;
import org.nrg.containers.model.command.entity.CommandMountEntity;
import org.nrg.containers.model.command.entity.CommandOutputEntity;
import org.nrg.containers.model.command.entity.CommandType;
import org.nrg.containers.model.command.entity.CommandWrapperDerivedInputEntity;
import org.nrg.containers.model.command.entity.CommandWrapperEntity;
import org.nrg.containers.model.command.entity.CommandWrapperExternalInputEntity;
import org.nrg.containers.model.command.entity.CommandWrapperInputType;
import org.nrg.containers.model.command.entity.CommandWrapperOutputEntity;
import org.nrg.containers.model.command.entity.DockerCommandEntity;
import org.nrg.containers.model.configuration.CommandConfiguration.CommandInputConfiguration;
import org.nrg.containers.model.configuration.CommandConfiguration.CommandOutputConfiguration;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@AutoValue
public abstract class Command {
    @JsonProperty("id") public abstract long id();
    @Nullable @JsonProperty("name") public abstract String name();
    @Nullable @JsonProperty("label") public abstract String label();
    @Nullable @JsonProperty("description") public abstract String description();
    @Nullable @JsonProperty("version") public abstract String version();
    @Nullable @JsonProperty("schema-version") public abstract String schemaVersion();
    @Nullable @JsonProperty("info-url") public abstract String infoUrl();
    @Nullable @JsonProperty("image") public abstract String image();
    @Nullable @JsonProperty("container-name") public abstract String containerName();
    @JsonProperty("type") public abstract String type();
    @Nullable @JsonProperty("index") public abstract String index();
    @Nullable @JsonProperty("hash") public abstract String hash();
    @Nullable @JsonProperty("working-directory") public abstract String workingDirectory();
    @Nullable @JsonProperty("command-line") public abstract String commandLine();
    @Nullable @JsonProperty("override-entrypoint") public abstract Boolean overrideEntrypoint();
    @JsonProperty("mounts") public abstract ImmutableList<CommandMount> mounts();
    @JsonProperty("environment-variables") public abstract ImmutableMap<String, String> environmentVariables();
    @JsonProperty("ports") public abstract ImmutableMap<String, String> ports();
    @JsonProperty("inputs") public abstract ImmutableList<CommandInput> inputs();
    @JsonProperty("outputs") public abstract ImmutableList<CommandOutput> outputs();
    @JsonProperty("xnat") public abstract ImmutableList<CommandWrapper> xnatCommandWrappers();
    @Nullable @JsonProperty("reserve-memory") public abstract Long reserveMemory();
    @Nullable @JsonProperty("limit-memory") public abstract Long limitMemory();
    @Nullable @JsonProperty("limit-cpu") public abstract Double limitCpu();
    @Nullable @JsonProperty("runtime") public abstract String runtime();
    @Nullable @JsonProperty("ipc-mode") public abstract String ipcMode();
    @Nullable @JsonProperty("auto-remove") public abstract Boolean autoRemove();
    @Nullable @JsonProperty("shm-size") public abstract Long shmSize();
    @Nullable @JsonProperty("network") public abstract String network();
    @Nullable @JsonProperty("container-labels") public abstract ImmutableMap<String, String> containerLabels();
    @Nullable @JsonProperty("gpus") public abstract String gpus();
    @Nullable @JsonProperty("generic-resources") public abstract ImmutableMap<String, String> genericResources();
    @JsonIgnore private static Pattern regCharPattern = Pattern.compile("[^A-Za-z0-9_-]");
    @JsonIgnore private static ObjectMapper mapper = new ObjectMapper();

    @JsonCreator
    static Command create(@JsonProperty("id") final long id,
                          @JsonProperty("name") final String name,
                          @JsonProperty("label") final String label,
                          @JsonProperty("description") final String description,
                          @JsonProperty("version") final String version,
                          @JsonProperty("schema-version") final String schemaVersion,
                          @JsonProperty("info-url") final String infoUrl,
                          @JsonProperty("image") final String image,
                          @JsonProperty("container-name") final String containerName,
                          @JsonProperty("type") final String type,
                          @JsonProperty("index") final String index,
                          @JsonProperty("hash") final String hash,
                          @JsonProperty("working-directory") final String workingDirectory,
                          @JsonProperty("command-line") final String commandLine,
                          @JsonProperty("override-entrypoint") final Boolean overrideEntrypoint,
                          @JsonProperty("mounts") final List<CommandMount> mounts,
                          @JsonProperty("environment-variables") final Map<String, String> environmentVariables,
                          @JsonProperty("ports") final Map<String, String> ports,
                          @JsonProperty("inputs") final List<CommandInput> inputs,
                          @JsonProperty("outputs") final List<CommandOutput> outputs,
                          @JsonProperty("xnat") final List<CommandWrapper> xnatCommandWrappers,
                          @JsonProperty("reserve-memory") final Long reserveMemory,
                          @JsonProperty("limit-memory") final Long limitMemory,
                          @JsonProperty("limit-cpu") final Double limitCpu,
                          @JsonProperty("runtime") final String runtime,
                          @JsonProperty("ipc-mode") final String ipcMode,
                          @JsonProperty("auto-remove") final Boolean autoRemove,
                          @JsonProperty("shm-size") final Long shmSize,
                          @JsonProperty("network") final String network,
                          @JsonProperty("container-labels") Map<String, String> containerLabels,
                          @JsonProperty("gpus") final String gpus,
                          @JsonProperty("generic-resources") Map<String, String> genericResources) {
        return builder()
                .id(id)
                .name(name)
                .label(label)
                .description(description)
                .version(version)
                .schemaVersion(schemaVersion)
                .infoUrl(infoUrl)
                .image(image)
                .containerName(containerName)
                .type(type == null ? CommandEntity.DEFAULT_TYPE.getName() : type)
                .index(index)
                .hash(hash)
                .workingDirectory(workingDirectory)
                .commandLine(commandLine)
                .overrideEntrypoint(overrideEntrypoint)
                .mounts(mounts == null ? Collections.<CommandMount>emptyList() : mounts)
                .environmentVariables(environmentVariables == null ? Collections.<String, String>emptyMap() : environmentVariables)
                .ports(ports == null ? Collections.<String, String>emptyMap() : ports)
                .inputs(inputs == null ? Collections.<CommandInput>emptyList() : inputs)
                .outputs(outputs == null ? Collections.<CommandOutput>emptyList() : outputs)
                .xnatCommandWrappers(xnatCommandWrappers == null ? Collections.<CommandWrapper>emptyList() : xnatCommandWrappers)
                .reserveMemory(reserveMemory)
                .limitMemory(limitMemory)
                .limitCpu(limitCpu)
                .runtime(runtime)
                .ipcMode(ipcMode)
                .autoRemove(autoRemove)
                .shmSize(shmSize)
                .network(network)
                .containerLabels(containerLabels)
                .gpus(gpus)
                .genericResources(genericResources)
                .build();
    }

    public static Command create(final CommandEntity commandEntity) {
        if (commandEntity == null) {
            return null;
        }
        Command.Builder builder = builder()
                .id(commandEntity.getId())
                .name(commandEntity.getName())
                .label(commandEntity.getLabel())
                .description(commandEntity.getDescription())
                .version(commandEntity.getVersion())
                .schemaVersion(commandEntity.getSchemaVersion())
                .infoUrl(commandEntity.getInfoUrl())
                .image(commandEntity.getImage())
                .containerName(commandEntity.getContainerName())
                .type(commandEntity.getType().getName())
                .workingDirectory(commandEntity.getWorkingDirectory())
                .commandLine(commandEntity.getCommandLine())
                .overrideEntrypoint(commandEntity.getOverrideEntrypoint())
                .reserveMemory(commandEntity.getReserveMemory())
                .limitMemory(commandEntity.getLimitMemory())
                .limitCpu(commandEntity.getLimitCpu())
                .runtime(commandEntity.getRuntime())
                .ipcMode(commandEntity.getIpcMode())
                .gpus(commandEntity.getGpus())
                .genericResources(commandEntity.getGenericResources())

                .environmentVariables(commandEntity.getEnvironmentVariables() == null ?
                        Collections.<String, String>emptyMap() :
                        commandEntity.getEnvironmentVariables())
                .mounts(commandEntity.getMounts() == null ?
                        Collections.<CommandMount>emptyList() :
                        Lists.newArrayList(Lists.transform(commandEntity.getMounts(), new Function<CommandMountEntity, CommandMount>() {
                            @Nullable
                            @Override
                            public CommandMount apply(@Nullable final CommandMountEntity mount) {
                                return mount == null ? null : CommandMount.create(mount);
                            }
                        })))
                .inputs(commandEntity.getInputs() == null ?
                        Collections.<CommandInput>emptyList() :
                        Lists.newArrayList(Lists.transform(commandEntity.getInputs(), new Function<CommandInputEntity, CommandInput>() {
                            @Nullable
                            @Override
                            public CommandInput apply(@Nullable final CommandInputEntity commandInput) {
                                return commandInput == null ? null : CommandInput.create(commandInput);
                            }
                        })))
                .outputs(commandEntity.getOutputs() == null ?
                        Collections.<CommandOutput>emptyList() :
                        Lists.newArrayList(Lists.transform(commandEntity.getOutputs(), new Function<CommandOutputEntity, CommandOutput>() {
                            @Nullable
                            @Override
                            public CommandOutput apply(@Nullable final CommandOutputEntity commandOutput) {
                                return commandOutput == null ? null : CommandOutput.create(commandOutput);
                            }
                        })))
                .xnatCommandWrappers(commandEntity.getCommandWrapperEntities() == null ?
                        Collections.<CommandWrapper>emptyList() :
                        Lists.newArrayList(Lists.transform(commandEntity.getCommandWrapperEntities(), new Function<CommandWrapperEntity, CommandWrapper>() {
                            @Nullable
                            @Override
                            public CommandWrapper apply(@Nullable final CommandWrapperEntity xnatCommandWrapper) {
                                return xnatCommandWrapper == null ? null : CommandWrapper.create(xnatCommandWrapper);
                            }
                        })));

        switch (commandEntity.getType()) {
            case DOCKER:
                builder = builder.index(((DockerCommandEntity) commandEntity).getIndex())
                        .hash(((DockerCommandEntity) commandEntity).getHash())
                        .ports(((DockerCommandEntity) commandEntity).getPorts() == null ?
                                Collections.<String, String>emptyMap() :
                                Maps.newHashMap(((DockerCommandEntity) commandEntity).getPorts()))
                        .autoRemove(((DockerCommandEntity) commandEntity).getAutoRemove())
                        .shmSize(((DockerCommandEntity) commandEntity).getShmSize())
                        .network(((DockerCommandEntity) commandEntity).getNetwork())
                        .containerLabels(((DockerCommandEntity) commandEntity).getContainerLabels());
                break;
        }

        return builder.build();
    }

    /**
     * This method is useful to create a command deserialized from REST.
     * @param creation An object that looks just like a command but everything can be null.
     * @return A Command, which is just like a CommandCreation but fewer things can be null.
     */
    public static Command create(final CommandCreation creation) {
        return builder()
                .name(creation.name())
                .label(creation.label())
                .description(creation.description())
                .version(creation.version())
                .schemaVersion(creation.schemaVersion())
                .infoUrl(creation.infoUrl())
                .image(creation.image())
                .containerName(creation.containerName())
                .type(creation.type() == null ? CommandEntity.DEFAULT_TYPE.getName() : creation.type())
                .index(creation.index())
                .hash(creation.hash())
                .workingDirectory(creation.workingDirectory())
                .commandLine(creation.commandLine())
                .overrideEntrypoint(creation.overrideEntrypoint())
                .reserveMemory(creation.reserveMemory())
                .limitMemory(creation.limitMemory())
                .limitCpu(creation.limitCpu())
                .runtime(creation.runtime())
                .ipcMode(creation.ipcMode())
                .autoRemove(creation.autoRemove())
                .shmSize(creation.shmSize())
                .network(creation.network())
                .containerLabels(creation.containerLabels())
                .gpus(creation.gpus())
                .genericResources(creation.genericResources())
                .mounts(creation.mounts() == null ? Collections.<CommandMount>emptyList() : creation.mounts())
                .environmentVariables(creation.environmentVariables() == null ? Collections.<String, String>emptyMap() : creation.environmentVariables())
                .ports(creation.ports() == null ? Collections.<String, String>emptyMap() : creation.ports())
                .inputs(creation.inputs() == null ? Collections.<CommandInput>emptyList() : creation.inputs())
                .outputs(creation.outputs() == null ? Collections.<CommandOutput>emptyList() : creation.outputs())
                .xnatCommandWrappers(creation.commandWrapperCreations() == null ? Collections.<CommandWrapper>emptyList() :
                        Lists.transform(creation.commandWrapperCreations(), new Function<CommandWrapperCreation, CommandWrapper>() {
                            @Override
                            public CommandWrapper apply(final CommandWrapperCreation input) {
                                return CommandWrapper.create(input);
                            }
                        })
                )
                .build();
    }

    /**
     * This method is useful to create a command deserialized from REST
     * when the user does not want to set the "image" property in the command JSON request body.
     * @param commandCreation An object that looks just like a command but everything can be null.
     * @param image The name of the image that should be saved in the command.
     * @return A Command, which is just like a CommandCreation but fewer things can be null.
     */
    public static Command create(final CommandCreation commandCreation, final String image) {
        final Command command = Command.create(commandCreation);
        if (StringUtils.isNotBlank(image)) {
            return command.toBuilder().image(image).build();
        }
        return command;
    }

    public abstract Builder toBuilder();

    public static Builder builder() {
        return new AutoValue_Command.Builder()
                .id(0L)
                .name("")
                .type(CommandEntity.DEFAULT_TYPE.getName());
    }

    @Nonnull
    public List<String> validate() {
        final List<String> errors = new ArrayList<>();
        final List<String> commandTypeNames = CommandType.names();

        if (StringUtils.isBlank(name())) {
            errors.add("Command name cannot be blank.");
        }
        final String commandName = "Command \"" + name() + "\" - ";

        if (StringUtils.isBlank(image())) {
            errors.add(commandName + "image name cannot be blank.");
        }

        if (type().equals(CommandType.DOCKER.getName())) {
            errors.addAll(validateDockerCommand());
        } else if (type().equals(CommandType.DOCKER_SETUP.getName())) {
            errors.addAll(validateDockerSetupOrWrapupCommand("Setup"));
        } else if (type().equals(CommandType.DOCKER_WRAPUP.getName())) {
            errors.addAll(validateDockerSetupOrWrapupCommand("Wrapup"));
        } else {
            errors.add(commandName + "Cannot validate command of type \"" + type() + "\". Known types: " +
                    StringUtils.join(commandTypeNames, ", "));
        }

        return errors;
    }

    @Nonnull
    private List<String> validateDockerCommand() {
        final List<String> errors = new ArrayList<>();
        final String commandName = "Command \"" + name() + "\" - ";

        final Function<String, String> addCommandNameToError = new Function<String, String>() {
            @Override
            public String apply(@Nullable final String input) {
                return commandName + input;
            }
        };

        final Set<String> mountNames = Sets.newHashSet();
        for (final CommandMount mount : mounts()) {
            final List<String> mountErrors = Lists.newArrayList();
            mountErrors.addAll(Lists.transform(mount.validate(), addCommandNameToError));

            if (mountNames.contains(mount.name())) {
                errors.add(commandName + "mount name \"" + mount.name() + "\" is not unique.");
            } else {
                mountNames.add(mount.name());
            }

            if (!mountErrors.isEmpty()) {
                errors.addAll(mountErrors);
            }
        }
        final String knownMounts = StringUtils.join(mountNames, ", ");

        final Set<String> inputNames = Sets.newHashSet();
        final Map<String, CommandInput> commandInputs = Maps.newHashMap();
        for (final CommandInput input : inputs()) {
            final List<String> inputErrors = Lists.newArrayList();
            inputErrors.addAll(Lists.transform(input.validate(), addCommandNameToError));

            if (inputNames.contains(input.name())) {
                errors.add(commandName + "input name \"" + input.name() + "\" is not unique.");
            } else {
                inputNames.add(input.name());
                commandInputs.put(input.name(), input);
            }

            if (!inputErrors.isEmpty()) {
                errors.addAll(inputErrors);
            }
        }

        final Set<String> outputNames = Sets.newHashSet();
        for (final CommandOutput output : outputs()) {
            final List<String> outputErrors = Lists.newArrayList();
            outputErrors.addAll(Lists.transform(output.validate(), addCommandNameToError));

            if (outputNames.contains(output.name())) {
                errors.add(commandName + "output name \"" + output.name() + "\" is not unique.");
            } else {
                outputNames.add(output.name());
            }

            if (!mountNames.contains(output.mount())) {
                errors.add(commandName + "output \"" + output.name() + "\" references unknown mount \"" +
                        output.mount() + "\". Known mounts: " + knownMounts);
            }

            if (!outputErrors.isEmpty()) {
                errors.addAll(outputErrors);
            }
        }
        final String knownOutputs = StringUtils.join(outputNames, ", ");

        final Set<String> wrapperNames = Sets.newHashSet();
        for (final CommandWrapper commandWrapper : xnatCommandWrappers()) {
            final List<String> wrapperErrors = Lists.newArrayList();
            wrapperErrors.addAll(Lists.transform(commandWrapper.validate(), addCommandNameToError));

            if (wrapperNames.contains(commandWrapper.name())) {
                errors.add(commandName + "wrapper name \"" + commandWrapper.name() + "\" is not unique.");
            } else {
                wrapperNames.add(commandWrapper.name());
            }
            final String wrapperName = commandName + "wrapper \"" + commandWrapper.name() + "\" - ";
            final Function<String, String> addWrapperNameToError = new Function<String, String>() {
                @Override
                public String apply(@Nullable final String input) {
                    return wrapperName + input;
                }
            };

            final Map<String, CommandWrapperInput> wrapperInputs = Maps.newHashMap();
            for (final CommandWrapperInput external : commandWrapper.externalInputs()) {
                final List<String> inputErrors = Lists.newArrayList();
                inputErrors.addAll(Lists.transform(external.validate(), addWrapperNameToError));

                if (wrapperInputs.containsKey(external.name())) {
                    errors.add(wrapperName + "external input name \"" + external.name() + "\" is not unique.");
                } else {
                    wrapperInputs.put(external.name(), external);
                }

                CommandInput provFor = commandInputs.get(external.providesValueForCommandInput());
                if (provFor != null) {
                    if (provFor.isSelect() || !provFor.selectValues().isEmpty()) {
                        errors.add(wrapperName + "external input \"" + external.name() + "\" provides values for " +
                                "command input \"" + provFor.name() + "\", which is a select type. Note that command " +
                                "inputs with values provided by xnat inputs shouldn't be designated as select " +
                                "(they'll automatically render as a select if their xnat input resolves " +
                                "to more than one value).");
                    }
                }

                if (!inputErrors.isEmpty()) {
                    errors.addAll(inputErrors);
                }
            }

            final List<String> derivedInputsWithMultiple = new ArrayList<>();
            for (final CommandWrapperDerivedInput derived : commandWrapper.derivedInputs()) {
                final List<String> inputErrors = Lists.newArrayList();
                inputErrors.addAll(Lists.transform(derived.validate(), addWrapperNameToError));


                if (wrapperInputs.containsKey(derived.name())) {
                    errors.add(wrapperName + "derived input name \"" + derived.name() + "\" is not unique.");
                } else {
                    wrapperInputs.put(derived.name(), derived);
                }

                if (derived.name().equals(derived.derivedFromWrapperInput())) {
                    errors.add(wrapperName + "derived input \"" + derived.name() + "\" is derived from itself.");
                }
                if (!wrapperInputs.containsKey(derived.derivedFromWrapperInput())) {
                    errors.add(wrapperName + "derived input \"" + derived.name() +
                            "\" is derived from an unknown XNAT input \"" + derived.derivedFromWrapperInput() +
                            "\". Known inputs: " + StringUtils.join(wrapperInputs, ", "));
                }

                CommandInput provFor = commandInputs.get(derived.providesValueForCommandInput());
                if (provFor != null) {
                    if (provFor.isSelect() || !provFor.selectValues().isEmpty()) {
                        errors.add(wrapperName + "derived input \"" + derived.name() + "\" provides values for " +
                                "command input \"" + provFor.name() + "\", which is a select type. Note that command " +
                                "inputs with values provided by xnat inputs shouldn't be designated as select " +
                                "(they'll automatically render as a select if their xnat input resolves " +
                                "to more than one value).");
                    }
                }

                if (!inputErrors.isEmpty()) {
                    errors.addAll(inputErrors);
                }

                if (derived.multiple()) derivedInputsWithMultiple.add(derived.name());
            }
            final String knownWrapperInputs = StringUtils.join(wrapperInputs, ", ");

            final Set<String> wrapperOutputNames = Sets.newHashSet();
            final Set<String> handledOutputs = Sets.newHashSet();
            for (final CommandWrapperOutput output : commandWrapper.outputHandlers()) {
                final List<String> outputErrors = Lists.newArrayList();
                outputErrors.addAll(Lists.transform(output.validate(), addWrapperNameToError));

                if (!outputNames.contains(output.commandOutputName())) {
                    errors.add(wrapperName + "output handler refers to unknown command output \"" +
                            output.commandOutputName() + "\". Known outputs: " + knownOutputs + ".");
                } else {
                    handledOutputs.add(output.commandOutputName());
                }

                String target = output.targetName();
                if (!(wrapperInputs.containsKey(target) || wrapperOutputNames.contains(target))) {
                    errors.add(wrapperName + "output handler does not refer to a known wrapper input or output. " +
                            "\"as-a-child-of\": \"" + target + "\"." +
                            "\nKnown inputs: " + knownWrapperInputs + "." +
                            "\nKnown outputs (so far): " + StringUtils.join(wrapperOutputNames, ", ") + ".");
                }

                if (derivedInputsWithMultiple.contains(target)) {
                    errors.add(wrapperName + "output handler \"" + output.name() + "\" has \"as-a-child-of\": \"" +
                            target + "\", but that input is set to allow multiple values.");
                }

                CommandWrapperInput input;
                String derivedFromProp;
                if (wrapperInputs.containsKey(target) &&
                        (input = wrapperInputs.get(target)) instanceof CommandWrapperDerivedInput &&
                        (derivedFromProp = ((CommandWrapperDerivedInput) input).derivedFromXnatObjectProperty()) != null &&
                        !derivedFromProp.equals("uri")) {
                    errors.add(wrapperName + "output handler \"" + output.name() + "\" has \"as-a-child-of\": \"" +
                            target + "\", but that input's value is set to \"" + derivedFromProp + "\", which will " +
                            "cause the upload to fail (a \"uri\" is required for upload).");
                }

                if (wrapperOutputNames.contains(output.name())) {
                    errors.add(wrapperName + "output handler name \"" + output.name() + "\" is not unique.");
                } else {
                    wrapperOutputNames.add(output.name());
                }

                if (!outputErrors.isEmpty()) {
                    errors.addAll(outputErrors);
                }
            }

            // Check that all command outputs are handled by some output handler
            if (!handledOutputs.containsAll(outputNames)) {
                // We know at least one output is not handled. Now find out which.
                for (final String commandOutput : outputNames) {
                    if (!handledOutputs.contains(commandOutput)) {
                        errors.add(wrapperName + "command output \"" + commandOutput +
                                "\" is not handled by any output handler.");
                    }
                }
            }

            if (!wrapperErrors.isEmpty()) {
                errors.addAll(wrapperErrors);
            }
        }

        return errors;
    }

    @Nonnull
    private List<String> validateDockerSetupOrWrapupCommand(final String setupOrWrapup) {
        final List<String> errors = new ArrayList<>();
        final String commandName = "Command \"" + name() + "\" - ";

        if (mounts().size() > 0) {
            errors.add(commandName + " " + setupOrWrapup + " commands cannot declare any mounts.");
        }
        if (inputs().size() > 0) {
            errors.add(commandName + " " + setupOrWrapup + " commands cannot declare any inputs.");
        }
        if (outputs().size() > 0) {
            errors.add(commandName + " " + setupOrWrapup + " commands cannot declare any outputs.");
        }
        if (xnatCommandWrappers().size() > 0) {
            errors.add(commandName + " " + setupOrWrapup + " commands cannot declare any wrappers.");
        }
        if (environmentVariables().size() > 0) {
            errors.add(commandName + " " + setupOrWrapup + " commands cannot declare any environment variables.");
        }
        if (ports().size() > 0) {
            errors.add(commandName + " " + setupOrWrapup + " commands cannot declare any ports.");
        }


        return errors;
    }

    @AutoValue.Builder
    public abstract static class Builder {
        public abstract Builder id(long id);

        public abstract Builder name(String name);

        public abstract Builder label(String label);

        public abstract Builder description(String description);

        public abstract Builder version(String version);

        public abstract Builder schemaVersion(String schemaVersion);

        public abstract Builder infoUrl(String infoUrl);

        public abstract Builder image(String image);

        public abstract Builder containerName(String containerName);

        public abstract Builder type(String type);

        public abstract Builder index(String index);

        public abstract Builder hash(String hash);

        public abstract Builder workingDirectory(String workingDirectory);

        public abstract Builder commandLine(String commandLine);

        public abstract Builder overrideEntrypoint(Boolean overrideEntrypoint);

        public abstract Builder mounts(List<CommandMount> mounts);
        public abstract Builder mounts(CommandMount... mounts);
        abstract ImmutableList.Builder<CommandMount> mountsBuilder();
        public Builder addMount(final @Nonnull CommandMount commandMount) {
            mountsBuilder().add(commandMount);
            return this;
        }

        public abstract Builder environmentVariables(Map<String, String> environmentVariables);
        abstract ImmutableMap.Builder<String, String> environmentVariablesBuilder();
        public Builder addEnvironmentVariable(final @Nonnull String name, final String value) {
            environmentVariablesBuilder().put(name, value);
            return this;
        }

        public abstract Builder ports(Map<String, String> ports);
        abstract ImmutableMap.Builder<String, String> portsBuilder();
        public Builder addPort(final @Nonnull String containerPort, final String hostPort) {
            portsBuilder().put(containerPort, hostPort);
            return this;
        }

        public abstract Builder inputs(List<CommandInput> inputs);
        public abstract Builder inputs(CommandInput... inputs);
        abstract ImmutableList.Builder<CommandInput> inputsBuilder();
        public Builder addInput(final @Nonnull CommandInput commandInput) {
            inputsBuilder().add(commandInput);
            return this;
        }

        public abstract Builder outputs(List<CommandOutput> outputs);
        public abstract Builder outputs(CommandOutput... outputs);
        abstract ImmutableList.Builder<CommandOutput> outputsBuilder();
        public Builder addOutput(final @Nonnull CommandOutput commandOutput) {
            outputsBuilder().add(commandOutput);
            return this;
        }

        public abstract Builder xnatCommandWrappers(List<CommandWrapper> xnatCommandWrappers);
        public abstract Builder xnatCommandWrappers(CommandWrapper... xnatCommandWrappers);
        public abstract ImmutableList.Builder<CommandWrapper> xnatCommandWrappersBuilder();
        public Builder addCommandWrapper(final @Nonnull CommandWrapper commandWrapper) {
            xnatCommandWrappersBuilder().add(commandWrapper);
            return this;
        }

        public abstract Builder reserveMemory(Long reserveMemory);
        public abstract Builder limitMemory(Long limitMemory);
        public abstract Builder limitCpu(Double limitCpu);
        public abstract Builder runtime(String runtime);
        public abstract Builder ipcMode(String ipcMode);
        public abstract Builder autoRemove(Boolean autoRemove);
        public abstract Builder shmSize(Long shmSize);
        public abstract Builder network(String network);
        public abstract Builder containerLabels(Map<String, String> containerLabels);
        public abstract Builder gpus(String gpus);
        public abstract Builder genericResources(Map<String, String> genericResources);
        public abstract Command build();
    }

    @AutoValue
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static abstract class CommandMount {
        @JsonIgnore public abstract long id();
        @Nullable @JsonProperty("name") public abstract String name();
        @JsonProperty("writable") public abstract boolean writable();
        @Nullable @JsonProperty("path") public abstract String path();

        @JsonCreator
        public static CommandMount create(@JsonProperty("name") final String name,
                                          @JsonProperty("writable") final Boolean writable,
                                          @JsonProperty("path") final String path) {
            return create(0L, name == null ? "" : name, writable == null ? false : writable, path);
        }

        public static CommandMount create(final long id,
                                          final String name,
                                          final Boolean writable,
                                          final String path) {
            return new AutoValue_Command_CommandMount(id, name == null ? "" : name, writable == null ? false : writable, path);
        }

        public static CommandMount create(final CommandMountEntity mount) {
            if (mount == null) {
                return null;
            }
            return CommandMount.create(mount.getName(), mount.isWritable(), mount.getContainerPath());
        }

        List<String> validate() {
            final List<String> errors = Lists.newArrayList();
            if (StringUtils.isBlank(name())) {
                errors.add("Mount name cannot be blank.");
            }
            if (StringUtils.isBlank(path())) {
                errors.add("Mount \"" + name() + "\" path cannot be blank.");
            }

            return errors;
        }
    }

    @AutoValue
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static abstract class CommandInput extends Input {
        @Nullable @JsonProperty("command-line-flag") public abstract String commandLineFlag();
        @Nullable @JsonProperty("command-line-separator") public abstract String commandLineSeparator();
        @Nullable @JsonProperty("true-value") public abstract String trueValue();
        @Nullable @JsonProperty("false-value") public abstract String falseValue();
        @JsonProperty("select-values") public abstract ImmutableList<String> selectValues();
        @Nullable @JsonProperty("multiple-delimiter") public abstract String multipleDelimiter();

        @JsonCreator
        static CommandInput create(@JsonProperty("name") final String name,
                                   @JsonProperty("label") final String label,
                                   @JsonProperty("description") final String description,
                                   @JsonProperty("type") final String type,
                                   @JsonProperty("required") final Boolean required,
                                   @JsonProperty("matcher") final String matcher,
                                   @JsonProperty("default-value") final String defaultValue,
                                   @JsonProperty("replacement-key") final String rawReplacementKey,
                                   @JsonProperty("command-line-flag") final String commandLineFlag,
                                   @JsonProperty("command-line-separator") final String commandLineSeparator,
                                   @JsonProperty("true-value") final String trueValue,
                                   @JsonProperty("false-value") final String falseValue,
                                   @JsonProperty("sensitive") final Boolean sensitive,
                                   @JsonProperty("select-values") final List<String> selectValues,
                                   @JsonProperty("multiple-delimiter") final String multipleDelimiter) {
            return builder()
                    .name(name)
                    .label(label)
                    .description(description)
                    .type(type == null ? CommandInputEntity.DEFAULT_TYPE.getName() : type)
                    .required(required == null ? false : required)
                    .matcher(matcher)
                    .defaultValue(defaultValue)
                    .rawReplacementKey(rawReplacementKey)
                    .commandLineFlag(commandLineFlag)
                    .commandLineSeparator(commandLineSeparator)
                    .trueValue(trueValue)
                    .falseValue(falseValue)
                    .sensitive(sensitive)
                    .selectValues(selectValues == null ? Collections.emptyList() : selectValues)
                    .multipleDelimiter(multipleDelimiter)
                    .build();
        }

        public static CommandInput create(final CommandInputEntity commandInputEntity) {
            if (commandInputEntity == null) {
                return null;
            }
            CommandInputEntity.MultipleDelimiter md = commandInputEntity.getMultipleDelimiter();
            return builder()
                    .id(commandInputEntity.getId())
                    .name(commandInputEntity.getName())
                    .label(commandInputEntity.getLabel())
                    .description(commandInputEntity.getDescription())
                    .type(commandInputEntity.getType().getName())
                    .required(commandInputEntity.isRequired())
                    .matcher(commandInputEntity.getMatcher())
                    .defaultValue(commandInputEntity.getDefaultValue())
                    .rawReplacementKey(commandInputEntity.getRawReplacementKey())
                    .commandLineFlag(commandInputEntity.getCommandLineFlag())
                    .commandLineSeparator(commandInputEntity.getCommandLineSeparator())
                    .trueValue(commandInputEntity.getTrueValue())
                    .falseValue(commandInputEntity.getFalseValue())
                    .sensitive(commandInputEntity.getSensitive())
                    .selectValues(commandInputEntity.getSelectValues())
                    .multipleDelimiter(md == null ? null : md.getName())
                    .build();
        }

        public static Builder builder() {
            return new AutoValue_Command_CommandInput.Builder()
                    .id(0L)
                    .name("")
                    .type(CommandInputEntity.DEFAULT_TYPE.getName())
                    .required(false)
                    .selectValues(Collections.emptyList());
        }

        public CommandInput applyConfiguration(final CommandInputConfiguration commandInputConfiguration) {
            return builder()
                    .name(this.name())
                    .id(this.id())
                    .label(this.label())
                    .description(this.description())
                    .type(this.type())
                    .required(this.required())
                    .rawReplacementKey(this.rawReplacementKey())
                    .commandLineFlag(this.commandLineFlag())
                    .commandLineSeparator(this.commandLineSeparator())
                    .trueValue(this.trueValue())
                    .falseValue(this.falseValue())
                    .sensitive(this.sensitive())
                    .selectValues(this.selectValues())
                    .multipleDelimiter(this.multipleDelimiter())
                    .defaultValue(commandInputConfiguration.defaultValue())
                    .matcher(commandInputConfiguration.matcher())
                    .build();
        }

        @JsonIgnore
        public boolean isSelect() {
            String type = type();
            return type.equals(CommandInputEntity.Type.MULTISELECT.getName()) ||
                    type.equals(CommandInputEntity.Type.SELECT.getName());
        }

        @JsonIgnore
        public boolean isMultiSelect() {
            return type().equals(CommandInputEntity.Type.MULTISELECT.getName());
        }

        public abstract Builder toBuilder();

        @Nonnull
        List<String> validate() {
            final List<String> errors = Lists.newArrayList();
            if (StringUtils.isBlank(name())) {
                errors.add("Command input name cannot be blank");
            }
            Pattern p = Pattern.compile("[^A-Za-z0-9_-]");
            Matcher m = p.matcher(name());
            if (m.find()){
                errors.add("Command input \"" +  name()  + "\" name should contain only alphanumeric, _ and - characters.");
            }
            String md = multipleDelimiter();
            List<String> names;
            if (md != null && !(names = CommandInputEntity.MultipleDelimiter.names()).contains(md)) {
                errors.add("Invalid multiple-delimiter \"" + md + "\", choose from: " +
                        StringUtils.join(names, ", "));
            }
            List<String> selectValues = selectValues();
            if (isSelect() && selectValues.isEmpty()) {
                errors.add("Command input \"" +  name()  + "\" is designated as type \"" + type() + "\" but doesn't list " +
                        "select-values. Note that command inputs with values provided by xnat inputs shouldn't be " +
                        "designated as select (they'll automatically render as a select if their xnat input resolves " +
                        "to more than one value).");
            } else if (!isSelect() && !selectValues.isEmpty()) {
                errors.add("Command input \"" +  name()  + "\" has select-values set, but is not a select type.");
            }
            String defaultVal = defaultValue();
            List<String> defaultValList = null;
            if (isMultiSelect()) {
                try {
                    defaultValList = mapper.readValue(defaultVal, new TypeReference<List<String>>() {});
                } catch (IOException e) {
                    // Not a list, treat defaultVal as string
                }
            }
            if (defaultValList != null) {
                if (!selectValues.isEmpty() && !selectValues.containsAll(defaultValList)) {
                    errors.add("Command input \"" + name() + "\" one or more default values in \"" + defaultValList +
                            "\" is not in the list of select-values \"" + selectValues + "\"");
                }
            } else if (defaultVal != null && !selectValues.isEmpty() && !selectValues.contains(defaultVal)) {
                errors.add("Command input \"" +  name()  + "\" default value \"" + defaultVal + "\" is not one of the " +
                        "select-values \"" + selectValues + "\"");
            }
            return errors;
        }

        @AutoValue.Builder
        public abstract static class Builder {
            public abstract Builder id(final long id);
            public abstract Builder name(final String name);
            public abstract Builder label(final String label);
            public abstract Builder description(final String description);
            public abstract Builder type(final String type);
            public abstract Builder required(final boolean required);
            public abstract Builder matcher(final String matcher);
            public abstract Builder defaultValue(final String defaultValue);
            public abstract Builder rawReplacementKey(final String rawReplacementKey);
            public abstract Builder commandLineFlag(final String commandLineFlag);
            public abstract Builder commandLineSeparator(final String commandLineSeparator);
            public abstract Builder trueValue(final String trueValue);
            public abstract Builder falseValue(final String falseValue);
            public abstract Builder sensitive(Boolean sensitive);
            public abstract Builder selectValues(final List<String> selectValues);
            public abstract Builder multipleDelimiter(final String multipleDelimiter);

            public abstract CommandInput build();
        }
    }

    @AutoValue
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static abstract class CommandOutput {
        @JsonIgnore public abstract long id();
        @Nullable @JsonProperty("name") public abstract String name();
        @Nullable @JsonProperty("description") public abstract String description();
        @Nullable @JsonProperty("required") public abstract Boolean required();
        @Nullable @JsonProperty("mount") public abstract String mount();
        @Nullable @JsonProperty("path") public abstract String path();
        @Nullable @JsonProperty("glob") public abstract String glob();

        @JsonCreator
        static CommandOutput create(@JsonProperty("name") final String name,
                                    @JsonProperty("description") final String description,
                                    @JsonProperty("required") final Boolean required,
                                    @JsonProperty("mount") final String mount,
                                    @JsonProperty("path") final String path,
                                    @JsonProperty("glob") final String glob) {
            return builder()
                    .name(name)
                    .description(description)
                    .required(required)
                    .mount(mount)
                    .path(path)
                    .glob(glob)
                    .build();
        }

        static CommandOutput create(final long id,
                                    final String name,
                                    final String description,
                                    final Boolean required,
                                    final String mount,
                                    final String path,
                                    final String glob) {
            return builder()
                    .id(id)
                    .name(name)
                    .description(description)
                    .required(required)
                    .mount(mount)
                    .path(path)
                    .glob(glob)
                    .build();
        }

        static CommandOutput create(final CommandOutputEntity commandOutputEntity) {
            if (commandOutputEntity == null) {
                return null;
            }

            return builder()
                    .id(commandOutputEntity.getId())
                    .name(commandOutputEntity.getName())
                    .description(commandOutputEntity.getDescription())
                    .required(commandOutputEntity.isRequired())
                    .mount(commandOutputEntity.getMount())
                    .path(commandOutputEntity.getPath())
                    .glob(commandOutputEntity.getGlob())
                    .build();
        }

        @Nonnull
        List<String> validate() {
            final List<String> errors = Lists.newArrayList();
            if (StringUtils.isBlank(name())) {
                errors.add("Output name cannot be blank.");
            }
            if (StringUtils.isBlank(mount())) {
                errors.add("Output \"" + name() + "\" - mount cannot be blank.");
            }
            Matcher m = regCharPattern.matcher(name());
            if (m.find()){
                errors.add("Command output \"" +  name()  + "\" name should contain only alphanumeric, _ and - characters.");
            }
            return errors;
        }

        public static Builder builder() {
            return new AutoValue_Command_CommandOutput.Builder()
                    .id(0L)
                    .name("")
                    .required(false);
        }

        @AutoValue.Builder
        public static abstract class Builder {
            public abstract Builder id(long id);
            public abstract Builder name(String name);
            public abstract Builder description(String description);
            public abstract Builder required(Boolean required);
            public abstract Builder mount(String mount);
            public abstract Builder path(String path);
            public abstract Builder glob(String glob);
            public abstract CommandOutput build();
        }
    }

    @AutoValue
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static abstract class CommandWrapper {
        @JsonProperty("id") public abstract long id();
        @Nullable @JsonProperty("name") public abstract String name();
        @Nullable @JsonProperty("label") public abstract String label();
        @Nullable @JsonProperty("description") public abstract String description();
        @JsonProperty("contexts") public abstract ImmutableSet<String> contexts();
        @JsonProperty("external-inputs") public abstract ImmutableList<CommandWrapperExternalInput> externalInputs();
        @JsonProperty("derived-inputs") public abstract ImmutableList<CommandWrapperDerivedInput> derivedInputs();
        @JsonProperty("output-handlers") public abstract ImmutableList<CommandWrapperOutput> outputHandlers();

        @JsonCreator
        static CommandWrapper create(@JsonProperty("id") final long id,
                                     @JsonProperty("name") final String name,
                                     @JsonProperty("label") final String label,
                                     @JsonProperty("description") final String description,
                                     @JsonProperty("contexts") final Set<String> contexts,
                                     @JsonProperty("external-inputs") final List<CommandWrapperExternalInput> externalInputs,
                                     @JsonProperty("derived-inputs") final List<CommandWrapperDerivedInput> derivedInputs,
                                     @JsonProperty("output-handlers") final List<CommandWrapperOutput> outputHandlers) {
            return builder()
                    .id(id)
                    .name(name == null ? "" : name)
                    .label(label)
                    .description(description)
                    .contexts(contexts == null ? Collections.<String>emptySet() : contexts)
                    .externalInputs(externalInputs == null ? Collections.<CommandWrapperExternalInput>emptyList() : externalInputs)
                    .derivedInputs(derivedInputs == null ? Collections.<CommandWrapperDerivedInput>emptyList() : derivedInputs)
                    .outputHandlers(outputHandlers == null ? Collections.<CommandWrapperOutput>emptyList() : outputHandlers)
                    .build();
        }

        public static CommandWrapper create(final CommandWrapperCreation creation) {
            return builder()
                    .name(creation.name())
                    .label(creation.label())
                    .description(creation.description())
                    .contexts(creation.contexts() == null ? Collections.<String>emptySet() : creation.contexts())
                    .externalInputs(creation.externalInputs() == null ? Collections.<CommandWrapperExternalInput>emptyList() : creation.externalInputs())
                    .derivedInputs(creation.derivedInputs() == null ? Collections.<CommandWrapperDerivedInput>emptyList() : creation.derivedInputs())
                    .outputHandlers(creation.outputHandlers() == null ?
                            Collections.<CommandWrapperOutput>emptyList() :
                            Lists.<CommandWrapperOutput>newArrayList(Lists.transform(creation.outputHandlers(), new Function<CommandWrapperOutputCreation, CommandWrapperOutput>() {
                        @Override
                        public CommandWrapperOutput apply(final CommandWrapperOutputCreation input) {
                            return CommandWrapperOutput.create(input);
                        }
                    })))
                    .build();
        }

        public static Builder builder() {
            return new AutoValue_Command_CommandWrapper.Builder()
                    .id(0L)
                    .name("");
        }

        public abstract Builder toBuilder();

        public static CommandWrapper create(final CommandWrapperEntity commandWrapperEntity) {
            if (commandWrapperEntity == null) {
                return null;
            }
            final Set<String> contexts = commandWrapperEntity.getContexts() == null ?
                    Collections.<String>emptySet() :
                    Sets.newHashSet(commandWrapperEntity.getContexts());
            final List<CommandWrapperExternalInput> external = commandWrapperEntity.getExternalInputs() == null ?
                    Collections.<CommandWrapperExternalInput>emptyList() :
                    Lists.newArrayList(Lists.transform(commandWrapperEntity.getExternalInputs(), new Function<CommandWrapperExternalInputEntity, CommandWrapperExternalInput>() {
                        @Nullable
                        @Override
                        public CommandWrapperExternalInput apply(@Nullable final CommandWrapperExternalInputEntity xnatCommandInput) {
                            return xnatCommandInput == null ? null : CommandWrapperExternalInput.create(xnatCommandInput);
                        }
                    }));
            final List<CommandWrapperDerivedInput> derived = commandWrapperEntity.getDerivedInputs() == null ?
                    Collections.<CommandWrapperDerivedInput>emptyList() :
                    Lists.newArrayList(Lists.transform(commandWrapperEntity.getDerivedInputs(), new Function<CommandWrapperDerivedInputEntity, CommandWrapperDerivedInput>() {
                        @Nullable
                        @Override
                        public CommandWrapperDerivedInput apply(@Nullable final CommandWrapperDerivedInputEntity xnatCommandInput) {
                            return xnatCommandInput == null ? null : CommandWrapperDerivedInput.create(xnatCommandInput);
                        }
                    }));
            final List<CommandWrapperOutput> outputs = commandWrapperEntity.getOutputHandlers() == null ?
                    Collections.<CommandWrapperOutput>emptyList() :
                    Lists.newArrayList(Lists.transform(commandWrapperEntity.getOutputHandlers(), new Function<CommandWrapperOutputEntity, CommandWrapperOutput>() {
                        @Nullable
                        @Override
                        public CommandWrapperOutput apply(@Nullable final CommandWrapperOutputEntity xnatCommandOutput) {
                            return xnatCommandOutput == null ? null : CommandWrapperOutput.create(xnatCommandOutput);
                        }
                    }));
            return builder()
                    .id(commandWrapperEntity.getId())
                    .name(commandWrapperEntity.getName())
                    .label(commandWrapperEntity.getLabel())
                    .description(commandWrapperEntity.getDescription())
                    .contexts(contexts)
                    .externalInputs(external)
                    .derivedInputs(derived)
                    .outputHandlers(outputs)
                    .build();
        }

        @Nonnull
        List<String> validate() {
            final List<String> errors = Lists.newArrayList();
            if (StringUtils.isBlank(name())) {
                errors.add("Command wrapper name cannot be blank.");
            }

            return errors;
        }

        @AutoValue.Builder
        public abstract static class Builder {
            public abstract Builder id(long id);

            public abstract Builder name(String name);

            public abstract Builder label(String label);

            public abstract Builder description(String description);

            public abstract Builder contexts(Set<String> contexts);
            public abstract Builder contexts(String... contexts);
            abstract ImmutableSet.Builder<String> contextsBuilder();
            public Builder addContext(final @Nonnull String context) {
                contextsBuilder().add(context);
                return this;
            }

            public abstract Builder externalInputs(List<CommandWrapperExternalInput> externalInputs);
            public abstract Builder externalInputs(CommandWrapperExternalInput... externalInputs);
            abstract ImmutableList.Builder<CommandWrapperExternalInput> externalInputsBuilder();
            public Builder addExternalInput(final @Nonnull CommandWrapperExternalInput externalInput) {
                externalInputsBuilder().add(externalInput);
                return this;
            }

            public abstract Builder derivedInputs(List<CommandWrapperDerivedInput> derivedInputs);
            public abstract Builder derivedInputs(CommandWrapperDerivedInput... derivedInputs);
            abstract ImmutableList.Builder<CommandWrapperDerivedInput> derivedInputsBuilder();
            public Builder addDerivedInput(final @Nonnull CommandWrapperDerivedInput derivedInput) {
                derivedInputsBuilder().add(derivedInput);
                return this;
            }

            public abstract Builder outputHandlers(List<CommandWrapperOutput> outputHandlers);
            public abstract Builder outputHandlers(CommandWrapperOutput... outputHandlers);
            abstract ImmutableList.Builder<CommandWrapperOutput> outputHandlersBuilder();
            public Builder addOutputHandler(final @Nonnull CommandWrapperOutput output) {
                outputHandlersBuilder().add(output);
                return this;
            }

            public abstract CommandWrapper build();
        }
    }

    @AutoValue
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static abstract class CommandWrapperCreation {
        @Nullable @JsonProperty("name") public abstract String name();
        @Nullable @JsonProperty("label") public abstract String label();
        @Nullable @JsonProperty("description") public abstract String description();
        @JsonProperty("contexts") public abstract ImmutableSet<String> contexts();
        @JsonProperty("external-inputs") public abstract ImmutableList<CommandWrapperExternalInput> externalInputs();
        @JsonProperty("derived-inputs") public abstract ImmutableList<CommandWrapperDerivedInput> derivedInputs();
        @JsonProperty("output-handlers") public abstract ImmutableList<CommandWrapperOutputCreation> outputHandlers();

        @JsonCreator
        static CommandWrapperCreation create(@JsonProperty("name") final String name,
                                             @JsonProperty("label") final String label,
                                             @JsonProperty("description") final String description,
                                             @JsonProperty("contexts") final Set<String> contexts,
                                             @JsonProperty("external-inputs") final List<CommandWrapperExternalInput> externalInputs,
                                             @JsonProperty("derived-inputs") final List<CommandWrapperDerivedInput> derivedInputs,
                                             @JsonProperty("output-handlers") final List<CommandWrapperOutputCreation> outputHandlers) {
            return new AutoValue_Command_CommandWrapperCreation(name, label, description,
                    contexts == null ? ImmutableSet.<String>of() : ImmutableSet.copyOf(contexts),
                    externalInputs == null ? ImmutableList.<CommandWrapperExternalInput>of() : ImmutableList.copyOf(externalInputs),
                    derivedInputs == null ? ImmutableList.<CommandWrapperDerivedInput>of() : ImmutableList.copyOf(derivedInputs),
                    outputHandlers == null ? ImmutableList.<CommandWrapperOutputCreation>of() : ImmutableList.copyOf(outputHandlers));
        }
    }

    public static abstract class CommandWrapperInput extends Input {
        @Nullable @JsonProperty("provides-value-for-command-input") public abstract String providesValueForCommandInput();
        @Nullable @JsonProperty("provides-files-for-command-mount") public abstract String providesFilesForCommandMount();
        @Nullable @JsonProperty("via-setup-command") public abstract String viaSetupCommand();
        @Nullable @JsonProperty("user-settable") public abstract Boolean userSettable();
        @JsonProperty("load-children") public abstract boolean loadChildren();

        @Nonnull
        List<String> validate() {
            final List<String> errors = Lists.newArrayList();
            if (StringUtils.isBlank(name())) {
                errors.add("Command wrapper input name cannot be blank.");
            }

            final List<String> types = CommandWrapperInputType.names();
            if (!types.contains(type())) {
                errors.add("Command wrapper input \"" + name() + "\" - Unknown type \"" + type() + "\". Known types: " +
                        StringUtils.join(types, ", "));
            }

            if (StringUtils.isNotBlank(viaSetupCommand()) && StringUtils.isBlank(providesFilesForCommandMount())) {
                errors.add("Command wrapper input \"" + name() + "\" - \"via-setup-command\": \"" + viaSetupCommand() + "\" - " +
                        "You cannot set \"via-setup-command\" on an input that does not provide files for a command mount.");
            }

            Matcher m = regCharPattern.matcher(name());
            if (m.find()){
                errors.add("Command wrapper input \"" +  name()  +
                        "\" name should contain only alphanumeric, _ and - characters.");
            }

            return errors;
        }

        public String replacementKey() {
            return StringUtils.isNotBlank(rawReplacementKey()) ? rawReplacementKey() : "#" + name() + "#";
        }
    }

    @AutoValue
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static abstract class CommandWrapperExternalInput extends CommandWrapperInput {
        @JsonCreator
        static CommandWrapperExternalInput create(@JsonProperty("name") final String name,
                                                  @JsonProperty("label") final String label,
                                                  @JsonProperty("description") final String description,
                                                  @JsonProperty("type") final String type,
                                                  @JsonProperty("matcher") final String matcher,
                                                  @JsonProperty("provides-value-for-command-input") final String providesValueForCommandInput,
                                                  @JsonProperty("provides-files-for-command-mount") final String providesFilesForCommandMount,
                                                  @JsonProperty("via-setup-command") final String viaSetupCommand,
                                                  @JsonProperty("default-value") final String defaultValue,
                                                  @JsonProperty("user-settable") final Boolean userSettable,
                                                  @JsonProperty("replacement-key") final String rawReplacementKey,
                                                  @JsonProperty("required") final Boolean required,
                                                  @JsonProperty("load-children") final Boolean loadChildren,
                                                  @JsonProperty("sensitive") final Boolean sensitive) {
            return builder()
                    .name(name)
                    .label(label)
                    .description(description)
                    .type(type == null ? CommandWrapperExternalInputEntity.DEFAULT_TYPE.getName() : type)
                    .matcher(matcher)
                    .providesValueForCommandInput(providesValueForCommandInput)
                    .providesFilesForCommandMount(providesFilesForCommandMount)
                    .viaSetupCommand(viaSetupCommand)
                    .defaultValue(defaultValue)
                    .userSettable(userSettable)
                    .rawReplacementKey(rawReplacementKey)
                    .required(required == null || required)
                    .loadChildren(loadChildren == null || loadChildren)
                    .sensitive(sensitive)
                    .build();
        }

        static CommandWrapperExternalInput create(final CommandWrapperExternalInputEntity wrapperInput) {
            if (wrapperInput == null) {
                return null;
            }

            return builder()
                    .id(wrapperInput.getId())
                    .name(wrapperInput.getName())
                    .label(wrapperInput.getLabel())
                    .description(wrapperInput.getDescription())
                    .type(wrapperInput.getType().getName())
                    .matcher(wrapperInput.getMatcher())
                    .providesValueForCommandInput(wrapperInput.getProvidesValueForCommandInput())
                    .providesFilesForCommandMount(wrapperInput.getProvidesFilesForCommandMount())
                    .viaSetupCommand(wrapperInput.getViaSetupCommand())
                    .defaultValue(wrapperInput.getDefaultValue())
                    .userSettable(wrapperInput.getUserSettable())
                    .rawReplacementKey(wrapperInput.getRawReplacementKey())
                    .required(wrapperInput.isRequired() == null || wrapperInput.isRequired())
                    .loadChildren(wrapperInput.getLoadChildren())
                    .sensitive(wrapperInput.getSensitive())
                    .build();
        }

        public static Builder builder() {
            return new AutoValue_Command_CommandWrapperExternalInput.Builder()
                    .id(0L)
                    .name("")
                    .type(CommandWrapperExternalInputEntity.DEFAULT_TYPE.getName())
                    .required(false)
                    .loadChildren(true);
        }

        public CommandWrapperExternalInput applyConfiguration(final CommandInputConfiguration commandInputConfiguration) {
            return builder()
                    .id(this.id())
                    .name(this.name())
                    .label(this.label())
                    .description(this.description())
                    .type(this.type())
                    .providesValueForCommandInput(this.providesValueForCommandInput())
                    .providesFilesForCommandMount(this.providesFilesForCommandMount())
                    .viaSetupCommand(this.viaSetupCommand())
                    .required(this.required())
                    .loadChildren(this.loadChildren())
                    .sensitive(this.sensitive())
                    .defaultValue(commandInputConfiguration.defaultValue())
                    .matcher(commandInputConfiguration.matcher())
                    .userSettable(commandInputConfiguration.userSettable())
                    .rawReplacementKey(this.rawReplacementKey())
                    .build();
        }

        @AutoValue.Builder
        public abstract static class Builder {
            public abstract Builder id(final long id);
            public abstract Builder name(final String name);
            public abstract Builder label(final String label);
            public abstract Builder description(final String description);
            public abstract Builder type(final String type);
            public abstract Builder matcher(final String matcher);
            public abstract Builder sensitive(Boolean sensitive);
            public abstract Builder providesValueForCommandInput(final String providesValueForCommandInput);
            public abstract Builder providesFilesForCommandMount(final String providesFilesForCommandMount);
            public abstract Builder viaSetupCommand(final String viaSetupCommand);
            public abstract Builder defaultValue(final String defaultValue);
            public abstract Builder userSettable(final Boolean userSettable);
            public abstract Builder rawReplacementKey(final String rawReplacementKey);
            public abstract Builder required(final boolean required);
            public abstract Builder loadChildren(final boolean loadChildren);

            public abstract CommandWrapperExternalInput build();
        }
    }

    @AutoValue
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static abstract class CommandWrapperDerivedInput extends CommandWrapperInput {
        @Nullable @JsonProperty("derived-from-wrapper-input") public abstract String derivedFromWrapperInput();
        @Nullable @JsonProperty("derived-from-xnat-object-property") public abstract String derivedFromXnatObjectProperty();
        @Nullable @JsonProperty("via-setup-command") public abstract String viaSetupCommand();
        @JsonProperty("multiple") public abstract boolean multiple();
        @Nullable @JsonProperty("parser") public abstract String parser();

        @JsonCreator
        static CommandWrapperDerivedInput create(@JsonProperty("name") final String name,
                                                 @JsonProperty("label") final String label,
                                                 @JsonProperty("description") final String description,
                                                 @JsonProperty("type") final String type,
                                                 @JsonProperty("derived-from-wrapper-input") final String derivedFromWrapperInput,
                                                 @JsonProperty("derived-from-xnat-object-property") final String derivedFromXnatObjectProperty,
                                                 @JsonProperty("matcher") final String matcher,
                                                 @JsonProperty("provides-value-for-command-input") final String providesValueForCommandInput,
                                                 @JsonProperty("provides-files-for-command-mount") final String providesFilesForCommandMount,
                                                 @JsonProperty("via-setup-command") final String viaSetupCommand,
                                                 @JsonProperty("default-value") final String defaultValue,
                                                 @JsonProperty("user-settable") final Boolean userSettable,
                                                 @JsonProperty("replacement-key") final String rawReplacementKey,
                                                 @JsonProperty("required") final Boolean required,
                                                 @JsonProperty("load-children") final Boolean loadChildren,
                                                 @JsonProperty("sensitive") final Boolean sensitive,
                                                 @JsonProperty("multiple") final Boolean multiple,
                                                 @JsonProperty("parser") final String parser) {
            return builder()
                    .name(name)
                    .label(label)
                    .description(description)
                    .type(type == null ? CommandWrapperDerivedInputEntity.DEFAULT_TYPE.getName() : type)
                    .derivedFromWrapperInput(derivedFromWrapperInput)
                    .derivedFromXnatObjectProperty(derivedFromXnatObjectProperty)
                    .matcher(matcher)
                    .providesValueForCommandInput(providesValueForCommandInput)
                    .providesFilesForCommandMount(providesFilesForCommandMount)
                    .viaSetupCommand(viaSetupCommand)
                    .defaultValue(defaultValue)
                    .userSettable(userSettable)
                    .rawReplacementKey(rawReplacementKey)
                    .required(required == null || required)
                    .loadChildren(loadChildren == null || loadChildren)
                    .sensitive(sensitive)
                    .multiple(multiple != null && multiple)
                    .parser(parser)
                    .build();
        }

        static CommandWrapperDerivedInput create(final CommandWrapperDerivedInputEntity wrapperInput) {
            if (wrapperInput == null) {
                return null;
            }

            return builder()
                    .id(wrapperInput.getId())
                    .name(wrapperInput.getName())
                    .label(wrapperInput.getLabel())
                    .description(wrapperInput.getDescription())
                    .type(wrapperInput.getType().getName())
                    .derivedFromWrapperInput(wrapperInput.getDerivedFromWrapperInput())
                    .derivedFromXnatObjectProperty(wrapperInput.getDerivedFromXnatObjectProperty())
                    .matcher(wrapperInput.getMatcher())
                    .providesValueForCommandInput(wrapperInput.getProvidesValueForCommandInput())
                    .providesFilesForCommandMount(wrapperInput.getProvidesFilesForCommandMount())
                    .viaSetupCommand(wrapperInput.getViaSetupCommand())
                    .defaultValue(wrapperInput.getDefaultValue())
                    .userSettable(wrapperInput.getUserSettable())
                    .rawReplacementKey(wrapperInput.getRawReplacementKey())
                    .required(wrapperInput.isRequired() == null || wrapperInput.isRequired())
                    .loadChildren(wrapperInput.getLoadChildren())
                    .sensitive(wrapperInput.getSensitive())
                    .multiple(wrapperInput.getMultiple())
                    .parser(wrapperInput.getParser())
                    .build();
        }

        public static Builder builder() {
            return new AutoValue_Command_CommandWrapperDerivedInput.Builder()
                    .id(0L)
                    .name("")
                    .type(CommandWrapperDerivedInputEntity.DEFAULT_TYPE.getName())
                    .required(false)
                    .loadChildren(true)
                    .multiple(false);
        }

        public CommandWrapperDerivedInput applyConfiguration(final CommandInputConfiguration commandInputConfiguration) {
            return builder()
                    .id(this.id())
                    .name(this.name())
                    .label(this.label())
                    .description(this.description())
                    .type(this.type())
                    .derivedFromWrapperInput(this.derivedFromWrapperInput())
                    .derivedFromXnatObjectProperty(this.derivedFromXnatObjectProperty())
                    .providesValueForCommandInput(this.providesValueForCommandInput())
                    .providesFilesForCommandMount(this.providesFilesForCommandMount())
                    .viaSetupCommand(this.viaSetupCommand())
                    .rawReplacementKey(this.rawReplacementKey())
                    .required(this.required())
                    .loadChildren(this.loadChildren())
                    .sensitive(this.sensitive())
                    .multiple(this.multiple())
                    .defaultValue(commandInputConfiguration.defaultValue())
                    .matcher(commandInputConfiguration.matcher())
                    .userSettable(commandInputConfiguration.userSettable())
                    .parser(this.parser())
                    .build();
        }

        @Nonnull
        List<String> validate() {
            // Derived inputs have all the same constraints as external inputs, plus more
            final List<String> errors = super.validate();

            if (StringUtils.isBlank(derivedFromWrapperInput())) {
                errors.add("Command wrapper input \"" + name() + "\" - property \"derived-from-wrapper-input\" cannot be blank.");
            }

            if (multiple()) {
                if (StringUtils.isNotBlank(providesFilesForCommandMount())) {
                    errors.add("derived input \"" + name() + "\" is designated as a \"multiple\" input, which " +
                            "means it cannot provide files for command mounts (consider mounting the parent element).");
                }
                if (StringUtils.isBlank(providesValueForCommandInput())) {
                    errors.add("derived input \"" + name() + "\" is designated as a \"multiple\" input, which" +
                            "means it must directly provide values for some command input.");
                }
            }

            return errors;
        }

        @AutoValue.Builder
        public abstract static class Builder {
            public abstract Builder id(final long id);
            public abstract Builder name(final String name);
            public abstract Builder label(final String label);
            public abstract Builder description(final String description);
            public abstract Builder type(final String type);
            public abstract Builder matcher(final String matcher);
            public abstract Builder sensitive(Boolean sensitive);
            public abstract Builder providesValueForCommandInput(final String providesValueForCommandInput);
            public abstract Builder providesFilesForCommandMount(final String providesFilesForCommandMount);
            public abstract Builder viaSetupCommand(final String viaSetupCommand);
            public abstract Builder defaultValue(final String defaultValue);
            public abstract Builder userSettable(final Boolean userSettable);
            public abstract Builder rawReplacementKey(final String rawReplacementKey);
            public abstract Builder required(final boolean required);
            public abstract Builder loadChildren(final boolean loadChildren);
            public abstract Builder multiple(final boolean multiple);
            public abstract Builder parser(final String parser);
            public abstract Builder derivedFromWrapperInput(final String derivedFromWrapperInput);
            public abstract Builder derivedFromXnatObjectProperty(final String derivedFromXnatObjectProperty);

            public abstract CommandWrapperDerivedInput build();
        }
    }

    @AutoValue
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static abstract class CommandWrapperOutput {
        @JsonIgnore public abstract long id();
        @Nullable @JsonProperty("name") public abstract String name();
        @Nullable @JsonProperty("accepts-command-output") public abstract String commandOutputName();
        @Nullable @JsonProperty("via-wrapup-command") public abstract String viaWrapupCommand();
        @Nullable @JsonProperty("as-a-child-of") public abstract String targetName();
        @JsonProperty("type") public abstract String type();
        @Nullable @JsonProperty("label") public abstract String label();
        @Nullable @JsonProperty("format") public abstract String format();
        @Nullable @JsonProperty("description") public abstract String description();
        @Nullable @JsonProperty("content") public abstract String content();
        @Nullable @JsonProperty("tags") public abstract ImmutableList<String> tags();

        @JsonCreator
        public static CommandWrapperOutput create(@JsonProperty("name") final String name,
                                                  @JsonProperty("accepts-command-output") final String commandOutputName,
                                                  @JsonProperty("as-a-child-of") final String targetName,
                                                  @JsonProperty("via-wrapup-command") final String viaWrapupCommand,
                                                  @JsonProperty("type") final String type,
                                                  @JsonProperty("label") final String label,
                                                  @JsonProperty("format") final String format,
                                                  @JsonProperty("description") final String description,
                                                  @JsonProperty("content") final String content,
                                                  @JsonProperty("tags") final List<String> tags) {
            return builder()
                    .name(name)
                    .commandOutputName(commandOutputName)
                    .targetName(targetName)
                    .viaWrapupCommand(viaWrapupCommand)
                    .type(type == null ? CommandWrapperOutputEntity.DEFAULT_TYPE.getName() : type)
                    .label(label)
                    .format(format)
                    .description(description)
                    .content(content)
                    .tags(tags)
                    .build();
        }

        public static CommandWrapperOutput create(final CommandWrapperOutputEntity wrapperOutput) {
            if (wrapperOutput == null) {
                return null;
            }
            return builder()
                    .id(wrapperOutput.getId())
                    .name(wrapperOutput.getName())
                    .commandOutputName(wrapperOutput.getCommandOutputName())
                    .targetName(wrapperOutput.getWrapperInputName())
                    .viaWrapupCommand(wrapperOutput.getViaWrapupCommand())
                    .type(wrapperOutput.getType().getName())
                    .label(wrapperOutput.getLabel())
                    .format(wrapperOutput.getFormat())
                    .description(wrapperOutput.getDescription())
                    .content(wrapperOutput.getContent())
                    .tags(wrapperOutput.getTags())
                    .build();
        }

        public static CommandWrapperOutput create(final CommandWrapperOutputCreation commandWrapperOutputCreation) {
            return builder()
                    .name(commandWrapperOutputCreation.name())
                    .commandOutputName(commandWrapperOutputCreation.commandOutputName())
                    .targetName(commandWrapperOutputCreation.targetName())
                    .viaWrapupCommand(commandWrapperOutputCreation.viaWrapupCommand())
                    .type(commandWrapperOutputCreation.type())
                    .label(commandWrapperOutputCreation.label())
                    .format(commandWrapperOutputCreation.format())
                    .description(commandWrapperOutputCreation.description())
                    .content(commandWrapperOutputCreation.content())
                    .tags(commandWrapperOutputCreation.tags())
                    .build();
        }

        public CommandWrapperOutput applyConfiguration(final CommandOutputConfiguration commandOutputConfiguration) {
            return toBuilder()
                    .label(commandOutputConfiguration.label())
                    .build();
        }

        public static Builder builder() {
            return new AutoValue_Command_CommandWrapperOutput.Builder()
                    .id(0L)
                    .type(CommandWrapperOutputEntity.DEFAULT_TYPE.getName());
        }

        public abstract Builder toBuilder();

        @Nonnull
        List<String> validate() {
            final List<String> errors = Lists.newArrayList();

            if (StringUtils.isBlank(name())) {
                errors.add("Command wrapper output - name cannot be blank.");
            }

            final String prefix = "Command wrapper output \"" + name() + "\" - ";
            if (StringUtils.isBlank(commandOutputName())) {
                errors.add(prefix + "property \"accepts-command-output\" cannot be blank.");
            }
            if (StringUtils.isBlank(targetName())) {
                errors.add(prefix + "property \"as-a-child-of\" cannot be blank.");
            }
            final List<String> types = CommandWrapperOutputEntity.Type.names();
            if (!types.contains(type())) {
                errors.add(prefix + "Unknown type \"" + type() + "\". Known types: " + StringUtils.join(types, ", "));
            }

            if (type().equals(CommandWrapperOutputEntity.Type.RESOURCE.getName()) && StringUtils.isBlank(label())) {
                errors.add(prefix + "when type = Resource, label cannot be blank.");
            }
            Matcher m = regCharPattern.matcher(name());
            if (m.find()){
                errors.add("Command wrapper output \"" +  name()  + "\" name should contain only alphanumeric, _ and - characters.");
            }
            return errors;
        }

        @AutoValue.Builder
        public abstract static class Builder {
            public abstract Builder id(final long id);

            public abstract Builder name(final String name);

            public abstract Builder commandOutputName(final String commandOutputName);

            public abstract Builder viaWrapupCommand(final String viaWrapupCommand);

            public abstract Builder targetName(final String targetName);

            public abstract Builder type(final String type);

            public abstract Builder label(final String label);

            public abstract Builder format(final String format);

            public abstract Builder description(String description);

            public abstract Builder content(String content);

            public abstract Builder tags(List<String> tags);

            public abstract CommandWrapperOutput build();
        }
    }

    /**
     * A command with no IDs. Intended to be sent in by a user when creating a new command.
     */
    @AutoValue
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static abstract class CommandCreation {
        @Nullable @JsonProperty("name") public abstract String name();
        @Nullable @JsonProperty("label") public abstract String label();
        @Nullable @JsonProperty("description") public abstract String description();
        @Nullable @JsonProperty("version") public abstract String version();
        @Nullable @JsonProperty("schema-version") public abstract String schemaVersion();
        @Nullable @JsonProperty("info-url") public abstract String infoUrl();
        @Nullable @JsonProperty("image") public abstract String image();
        @Nullable @JsonProperty("container-name") public abstract String containerName();
        @Nullable @JsonProperty("type") public abstract String type();
        @Nullable @JsonProperty("index") public abstract String index();
        @Nullable @JsonProperty("hash") public abstract String hash();
        @Nullable @JsonProperty("working-directory") public abstract String workingDirectory();
        @Nullable @JsonProperty("command-line") public abstract String commandLine();
        @Nullable @JsonProperty("override-entrypoint") public abstract Boolean overrideEntrypoint();
        @JsonProperty("mounts") public abstract ImmutableList<CommandMount> mounts();
        @JsonProperty("environment-variables") public abstract ImmutableMap<String, String> environmentVariables();
        @JsonProperty("ports") public abstract ImmutableMap<String, String> ports();
        @JsonProperty("inputs") public abstract ImmutableList<CommandInput> inputs();
        @JsonProperty("outputs") public abstract ImmutableList<CommandOutput> outputs();
        @JsonProperty("xnat") public abstract ImmutableList<CommandWrapperCreation> commandWrapperCreations();
        @Nullable @JsonProperty("reserve-memory") public abstract Long reserveMemory();
        @Nullable @JsonProperty("limit-memory") public abstract Long limitMemory();
        @Nullable @JsonProperty("limit-cpu") public abstract Double limitCpu();
        @Nullable @JsonProperty("runtime") public abstract String runtime();
        @Nullable @JsonProperty("ipc-mode") public abstract String ipcMode();
        @Nullable @JsonProperty("auto-remove") public abstract Boolean autoRemove();
        @Nullable @JsonProperty("shm-size") public abstract Long shmSize();
        @Nullable @JsonProperty("network") public abstract String network();
        @Nullable @JsonProperty("container-labels") public abstract ImmutableMap<String, String> containerLabels();
        @Nullable @JsonProperty("gpus") public abstract String gpus();
        @Nullable @JsonProperty("generic-resources") public abstract ImmutableMap<String, String> genericResources();

        @JsonCreator
        static CommandCreation create(@JsonProperty("name") final String name,
                                      @JsonProperty("label") final String label,
                                      @JsonProperty("description") final String description,
                                      @JsonProperty("version") final String version,
                                      @JsonProperty("schema-version") final String schemaVersion,
                                      @JsonProperty("info-url") final String infoUrl,
                                      @JsonProperty("image") final String image,
                                      @JsonProperty("container-name") String containerName,
                                      @JsonProperty("type") final String type,
                                      @JsonProperty("index") final String index,
                                      @JsonProperty("hash") final String hash,
                                      @JsonProperty("working-directory") final String workingDirectory,
                                      @JsonProperty("command-line") final String commandLine,
                                      @JsonProperty("override-entrypoint") final Boolean overrideEntrypoint,
                                      @JsonProperty("mounts") final List<CommandMount> mounts,
                                      @JsonProperty("environment-variables") final Map<String, String> environmentVariables,
                                      @JsonProperty("ports") final Map<String, String> ports,
                                      @JsonProperty("inputs") final List<CommandInput> inputs,
                                      @JsonProperty("outputs") final List<CommandOutput> outputs,
                                      @JsonProperty("xnat") final List<CommandWrapperCreation> commandWrapperCreations,
                                      @JsonProperty("reserve-memory") final Long reserveMemory,
                                      @JsonProperty("limit-memory") final Long limitMemory,
                                      @JsonProperty("limit-cpu") final Double limitCpu,
                                      @JsonProperty("runtime") final String runtime,
                                      @JsonProperty("ipcMode") final String ipcMode,
                                      @JsonProperty("auto-remove") final Boolean autoRemove,
                                      @JsonProperty("shm-size") final Long shmSize,
                                      @JsonProperty("network") final String network,
                                      @JsonProperty("container-labels") final ImmutableMap<String, String> containerLabels,
                                      @JsonProperty("gpus") final String gpus,
                                      @JsonProperty("generic-resources") final ImmutableMap<String, String> genericResources) {
            return new AutoValue_Command_CommandCreation(name, label, description, version, schemaVersion, infoUrl, image,
                    containerName, type, index, hash, workingDirectory, commandLine, overrideEntrypoint,
                    mounts == null ? ImmutableList.<CommandMount>of() : ImmutableList.copyOf(mounts),
                    environmentVariables == null ? ImmutableMap.<String, String>of() : ImmutableMap.copyOf(environmentVariables),
                    ports == null ? ImmutableMap.<String, String>of() : ImmutableMap.copyOf(ports),
                    inputs == null ? ImmutableList.<CommandInput>of() : ImmutableList.copyOf(inputs),
                    outputs == null ? ImmutableList.<CommandOutput>of() : ImmutableList.copyOf(outputs),
                    commandWrapperCreations == null ? ImmutableList.<CommandWrapperCreation>of() : ImmutableList.copyOf(commandWrapperCreations),
                    reserveMemory, limitMemory, limitCpu, runtime, ipcMode,
                    autoRemove, shmSize, network, containerLabels, gpus, genericResources);
        }
    }

    @AutoValue
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static abstract class CommandWrapperOutputCreation {
        @Nullable @JsonProperty("name") public abstract String name();
        @Nullable @JsonProperty("accepts-command-output") public abstract String commandOutputName();
        @Nullable @JsonProperty("via-wrapup-command") public abstract String viaWrapupCommand();
        @Nullable @JsonProperty("as-a-child-of") public abstract String targetName();
        @JsonProperty("type") public abstract String type();
        @Nullable @JsonProperty("label") public abstract String label();
        @Nullable @JsonProperty("format") public abstract String format();
        @Nullable @JsonProperty("description") public abstract String description();
        @Nullable @JsonProperty("content") public abstract String content();
        @Nullable @JsonProperty("tags") public abstract ImmutableList<String> tags();

        @JsonCreator
        public static CommandWrapperOutputCreation create(@JsonProperty("name") final String name,
                                                          @JsonProperty("accepts-command-output") final String commandOutputName,
                                                          @JsonProperty("as-a-child-of") final String targetName,
                                                          @JsonProperty("as-a-child-of-wrapper-input") final String oldStyleTargetName,
                                                          @JsonProperty("via-wrapup-command") final String viaWrapupCommand,
                                                          @JsonProperty("type") final String type,
                                                          @JsonProperty("label") final String label,
                                                          @JsonProperty("format") final String format,
                                                          @JsonProperty("description") final String description,
                                                          @JsonProperty("content") final String content,
                                                          @JsonProperty("tags") final List<String> tags) {
            return builder()
                    .name(name)
                    .commandOutputName(commandOutputName)
                    .targetName(targetName != null ? targetName : oldStyleTargetName)
                    .viaWrapupCommand(viaWrapupCommand)
                    .type(type == null ? CommandWrapperOutputEntity.DEFAULT_TYPE.getName() : type)
                    .label(label)
                    .format(format)
                    .description(description)
                    .content(content)
                    .tags(tags == null ? Collections.emptyList() : tags)
                    .build();
        }

        public static Builder builder() {
            return new AutoValue_Command_CommandWrapperOutputCreation.Builder()
                    .type(CommandWrapperOutputEntity.DEFAULT_TYPE.getName());
        }

        public abstract Builder toBuilder();

        @AutoValue.Builder
        public abstract static class Builder {
            public abstract Builder name(final String name);
            public abstract Builder commandOutputName(final String commandOutputName);
            public abstract Builder viaWrapupCommand(final String viaWrapupCommand);
            public abstract Builder targetName(final String targetName);
            public abstract Builder type(final String type);
            public abstract Builder label(final String label);
            public abstract Builder format(final String format);
            public abstract Builder description(String description);
            public abstract Builder content(String content);
            public abstract Builder tags(List<String> tags);

            public abstract CommandWrapperOutputCreation build();
        }
    }


    /**
     * A command with project- or site-wide configuration applied. Contains only a single wrapper.
     */
    @AutoValue
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static abstract class ConfiguredCommand {
        public abstract long id();
        public abstract String name();
        @Nullable public abstract String label();
        @Nullable public abstract String description();
        @Nullable public abstract String version();
        @Nullable public abstract String schemaVersion();
        @Nullable public abstract String infoUrl();
        @Nullable public abstract String image();
        @Nullable public abstract String containerName();
        public abstract String type();
        @Nullable public abstract String index();
        @Nullable public abstract String hash();
        @Nullable public abstract String workingDirectory();
        @Nullable public abstract String commandLine();
        @Nullable public abstract Boolean overrideEntrypoint();
        public abstract ImmutableList<CommandMount> mounts();
        public abstract ImmutableMap<String, String> environmentVariables();
        public abstract ImmutableMap<String, String> ports();
        public abstract ImmutableList<CommandInput> inputs();
        public abstract ImmutableList<CommandOutput> outputs();
        public abstract CommandWrapper wrapper();
        @Nullable public abstract Long reserveMemory();
        @Nullable public abstract Long limitMemory();
        @Nullable public abstract Double limitCpu();
        @Nullable public abstract String runtime();
        @Nullable public abstract String ipcMode();
        @Nullable public abstract Boolean autoRemove();
        @Nullable public abstract Long shmSize();
        @Nullable public abstract String network();
        @Nullable public abstract ImmutableMap<String, String> containerLabels();
        @Nullable public abstract String gpus();
        @Nullable public abstract ImmutableMap<String, String> genericResources();

        public static ConfiguredCommand.Builder initialize(final Command command) {
            return builder()
                    .id(command.id())
                    .name(command.name())
                    .label(command.label())
                    .description(command.description())
                    .version(command.version())
                    .schemaVersion(command.schemaVersion())
                    .infoUrl(command.infoUrl())
                    .image(command.image())
                    .containerName(command.containerName())
                    .type(command.type())
                    .workingDirectory(command.workingDirectory())
                    .commandLine(command.commandLine())
                    .overrideEntrypoint(command.overrideEntrypoint())
                    .environmentVariables(command.environmentVariables())
                    .mounts(command.mounts())
                    .index(command.index())
                    .hash(command.hash())
                    .ports(command.ports())
                    .outputs(command.outputs())
                    .reserveMemory(command.reserveMemory())
                    .limitMemory(command.limitMemory())
                    .limitCpu(command.limitCpu())
                    .runtime(command.runtime())
                    .ipcMode(command.ipcMode())
                    .autoRemove(command.autoRemove())
                    .shmSize(command.shmSize())
                    .network(command.network())
                    .containerLabels(command.containerLabels())
                    .gpus(command.gpus())
                    .genericResources(command.genericResources());

        }

        static Builder builder() {
            return new AutoValue_Command_ConfiguredCommand.Builder();
        }

        @AutoValue.Builder
        public static abstract class Builder {
            public abstract Builder id(long id);
            public abstract Builder name(String name);
            public abstract Builder label(String label);
            public abstract Builder description(String description);
            public abstract Builder version(String version);
            public abstract Builder schemaVersion(String schemaVersion);
            public abstract Builder infoUrl(String infoUrl);
            public abstract Builder image(String image);
            public abstract Builder containerName(String containerName);
            public abstract Builder type(String type);
            public abstract Builder index(String index);
            public abstract Builder hash(String hash);
            public abstract Builder workingDirectory(String workingDirectory);
            public abstract Builder commandLine(String commandLine);
            public abstract Builder overrideEntrypoint(Boolean overrideEntrypoint);

            public abstract Builder mounts(List<CommandMount> mounts);
            public abstract Builder mounts(CommandMount... mounts);
            abstract ImmutableList.Builder<CommandMount> mountsBuilder();
            public Builder addMount(final @Nonnull CommandMount commandMount) {
                mountsBuilder().add(commandMount);
                return this;
            }

            public abstract Builder environmentVariables(Map<String, String> environmentVariables);
            abstract ImmutableMap.Builder<String, String> environmentVariablesBuilder();
            public Builder addEnvironmentVariable(final @Nonnull String name, final String value) {
                environmentVariablesBuilder().put(name, value);
                return this;
            }

            public abstract Builder ports(Map<String, String> ports);
            abstract ImmutableMap.Builder<String, String> portsBuilder();
            public Builder addPort(final @Nonnull String containerPort, final String hostPort) {
                portsBuilder().put(containerPort, hostPort);
                return this;
            }

            public abstract Builder inputs(List<CommandInput> inputs);
            public abstract Builder inputs(CommandInput... inputs);
            abstract ImmutableList.Builder<CommandInput> inputsBuilder();
            public Builder addInput(final @Nonnull CommandInput commandInput) {
                inputsBuilder().add(commandInput);
                return this;
            }

            public abstract Builder outputs(List<CommandOutput> outputs);
            public abstract Builder outputs(CommandOutput... outputs);
            abstract ImmutableList.Builder<CommandOutput> outputsBuilder();
            public Builder addOutput(final @Nonnull CommandOutput commandOutput) {
                outputsBuilder().add(commandOutput);
                return this;
            }

            public abstract Builder wrapper(CommandWrapper commandWrapper);

            public abstract Builder reserveMemory(Long reserveMemory);
            public abstract Builder limitMemory(Long limitMemory);
            public abstract Builder limitCpu(Double limitCpu);
            public abstract Builder runtime(String runtime);
            public abstract Builder ipcMode(String ipcMode);
            public abstract Builder autoRemove(Boolean autoRemove);
            public abstract Builder shmSize(Long shmSize);
            public abstract Builder network(String network);
            public abstract Builder containerLabels(Map<String, String> containerLabels);
            public abstract Builder gpus(String gpus);
            public abstract Builder genericResources(Map<String, String> genericResources);

            public abstract ConfiguredCommand build();
        }
    }

    public static abstract class Input {
        @JsonIgnore public abstract long id();
        @JsonProperty("name") public abstract String name();
        @Nullable @JsonProperty("label") public abstract String label();
        @Nullable @JsonProperty("description") public abstract String description();
        @JsonProperty("type") public abstract String type();
        @Nullable @JsonProperty("matcher") public abstract String matcher();
        @Nullable @JsonProperty("default-value") public abstract String defaultValue();
        @JsonProperty("required") public abstract boolean required();
        @Nullable @JsonProperty("replacement-key") public abstract String rawReplacementKey();
        @Nullable @JsonProperty("sensitive") public abstract Boolean sensitive();

        public String replacementKey() {
            return StringUtils.isNotBlank(rawReplacementKey()) ? rawReplacementKey() : "#" + name() + "#";
        }
    }
}
