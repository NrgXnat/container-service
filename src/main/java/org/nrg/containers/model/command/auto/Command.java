package org.nrg.containers.model.command.auto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auto.value.AutoValue;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.secrets.Secret;
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
import org.nrg.containers.utils.ContainerServicePermissionUtils.WrapperPermission;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xdat.om.XnatSubjectdata;
import org.nrg.xft.security.UserI;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.nrg.containers.utils.ContainerServicePermissionUtils.CONTEXT_PERMISSION_PLACEHOLDER;

@AutoValue
public abstract class Command {
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final Pattern COMMAND_NAME = Pattern.compile("[^A-Za-z0-9_-]");
    private static final Pattern HAS_TAG = Pattern.compile(".+:[a-zA-Z0-9_.-]+$");
    private static final String DEFAULT_IMAGE_TAG = "latest";

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
    @Nullable @JsonProperty("ulimits") public abstract ImmutableMap<String, String> ulimits();
    @JsonProperty("secrets") public abstract List<Secret> secrets();

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
                          @JsonProperty("generic-resources") Map<String, String> genericResources,
                          @JsonProperty("ulimits") final Map<String, String> ulimits,
                          @JsonProperty("secrets") final List<Secret> secrets) {
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
                .mounts(mounts == null ? Collections.emptyList() : mounts)
                .environmentVariables(environmentVariables == null ? Collections.emptyMap() : environmentVariables)
                .ports(ports == null ? Collections.emptyMap() : ports)
                .inputs(inputs == null ? Collections.emptyList() : inputs)
                .outputs(outputs == null ? Collections.emptyList() : outputs)
                .xnatCommandWrappers(xnatCommandWrappers == null ? Collections.emptyList() : xnatCommandWrappers)
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
                .ulimits(ulimits)
                .secrets(secrets == null ? Collections.emptyList() : secrets)
                .build();
    }

    public static Command create(final CommandEntity commandEntity) {
        if (commandEntity == null) {
            return null;
        }
        Builder builder = builder()
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
                .ulimits(commandEntity.getUlimits())

                .environmentVariables(commandEntity.getEnvironmentVariables() == null ?
                        Collections.emptyMap() :
                        commandEntity.getEnvironmentVariables())
                .mounts(commandEntity.getMounts() == null ?
                        Collections.emptyList() :
                        commandEntity.getMounts().stream()
                                .map(CommandMount::create)
                                .collect(Collectors.toList()))
                .inputs(commandEntity.getInputs() == null ?
                        Collections.emptyList() :
                        commandEntity.getInputs().stream()
                                .map(CommandInput::create)
                                .collect(Collectors.toList()))
                .outputs(commandEntity.getOutputs() == null ?
                        Collections.emptyList() :
                        commandEntity.getOutputs().stream()
                                .map(CommandOutput::create)
                                .collect(Collectors.toList()))
                .xnatCommandWrappers(commandEntity.getCommandWrapperEntities() == null ?
                        Collections.emptyList() :
                        commandEntity.getCommandWrapperEntities().stream()
                                .map(CommandWrapper::create)
                                .collect(Collectors.toList()))
                .secrets(commandEntity.getSecrets() == null ? Collections.emptyList() :
                        new ArrayList<>(commandEntity.getSecrets()));

        if (commandEntity.getType() == CommandType.DOCKER) {
            builder = builder.index(((DockerCommandEntity) commandEntity).getIndex())
                    .hash(((DockerCommandEntity) commandEntity).getHash())
                    .ports(((DockerCommandEntity) commandEntity).getPorts() == null ?
                            Collections.emptyMap() :
                            new HashMap<>(((DockerCommandEntity) commandEntity).getPorts()))
                    .autoRemove(commandEntity.getAutoRemove())
                    .shmSize(commandEntity.getShmSize())
                    .network(commandEntity.getNetwork())
                    .containerLabels(commandEntity.getContainerLabels());
        }

        return builder.build();
    }

    /**
     * This method is useful to create a command deserialized from REST.
     * @param creation An object that looks just like a command but everything can be null.
     * @return A Command, which is just like a CommandCreation but fewer things can be null.
     */
    public static Command create(final CommandCreation creation) {
        return Command.builderFromCreation(creation).build();
    }

    /**
     * This method is useful to create a command deserialized from REST.
     * @param creation An object that looks just like a command but everything can be null.
     * @return A Command builder, which is just like a CommandCreation but fewer things can be null.
     */
    public static Builder builderFromCreation(final CommandCreation creation) {
        return builder()
                .name(creation.name())
                .label(creation.label())
                .description(creation.description())
                .version(creation.version())
                .schemaVersion(creation.schemaVersion())
                .infoUrl(creation.infoUrl())
                .image(setDefaultTag(creation.image()))
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
                .ulimits(creation.ulimits())
                .mounts(creation.mounts() == null ? Collections.emptyList() : creation.mounts())
                .environmentVariables(creation.environmentVariables() == null ? Collections.emptyMap() : creation.environmentVariables())
                .ports(creation.ports() == null ? Collections.emptyMap() : creation.ports())
                .inputs(creation.inputs() == null ? Collections.emptyList() : creation.inputs())
                .outputs(creation.outputs() == null ? Collections.emptyList() : creation.outputs())
                .xnatCommandWrappers(creation.commandWrapperCreations() == null ? Collections.emptyList() :
                        creation.commandWrapperCreations().stream()
                                .map(CommandWrapper::create)
                                .collect(Collectors.toList())
                )
                .secrets(creation.secrets());
    }

    /**
     * This method is useful to create a command deserialized from REST
     * when the user does not want to set the "image" property in the command JSON request body.
     * @param commandCreation An object that looks just like a command but everything can be null.
     * @param image The name of the image that should be saved in the command.
     * @return A Command, which is just like a CommandCreation but fewer things can be null.
     */
    public static Command create(final CommandCreation commandCreation, final String image) {
        final Builder builder = Command.builderFromCreation(commandCreation);
        if (StringUtils.isNotBlank(image)) {
            builder.image(setDefaultTag(image));
        }
        return builder.build();
    }

    private static String setDefaultTag(String image){
        return imageNeedsTag(image) ? String.join(":", image, DEFAULT_IMAGE_TAG) : image;
    }

    private static boolean imageNeedsTag(String image) {
        return !(Strings.isNullOrEmpty(image) || HAS_TAG.matcher(image).matches());
    }

    public abstract Builder toBuilder();

    public static Builder builder() {
        return new AutoValue_Command.Builder()
                .id(0L)
                .name("")
                .secrets(Collections.emptyList())
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
        if (imageNeedsTag(image())) {
            errors.add(commandName + "image name must include version tag. e.g. image_name:tag");
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

        final Function<String, String> addCommandNameToError = input -> commandName + input;

        if (ulimits() != null) {
            for (final String ulimit : ulimits().values()) {
                if (ulimit.isEmpty() || (ulimit.contains(":") && ulimit.split(":").length > 2)){
                    errors.add(commandName + " incorrect ulimit format: " + ulimit + ". Should be \"name\" : \"softlimit:hardlimit\" or \"name\" : \"limit\"");
                }
            }
        }

        final Set<String> mountNames = new HashSet<>(mounts().size());
        for (final CommandMount mount : mounts()) {
            final List<String> mountErrors = mount.validate().stream()
                    .map(addCommandNameToError)
                    .collect(Collectors.toList());

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

        final Map<String, CommandInput> commandInputs = new HashMap<>(inputs().size());
        for (final CommandInput input : inputs()) {
            final List<String> inputErrors = input.validate().stream()
                    .map(addCommandNameToError)
                    .collect(Collectors.toList());

            if (commandInputs.containsKey(input.name())) {
                errors.add(commandName + "input name \"" + input.name() + "\" is not unique.");
            } else {
                commandInputs.put(input.name(), input);
            }

            if (!inputErrors.isEmpty()) {
                errors.addAll(inputErrors);
            }
        }

        final Set<String> outputNames = new HashSet<>(outputs().size());
        for (final CommandOutput output : outputs()) {
            final List<String> outputErrors = output.validate().stream()
                    .map(addCommandNameToError)
                    .collect(Collectors.toList());

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

        final Set<String> wrapperNames = new HashSet<>(xnatCommandWrappers().size());
        for (final CommandWrapper commandWrapper : xnatCommandWrappers()) {
            final List<String> wrapperErrors = commandWrapper.validate().stream()
                    .map(addCommandNameToError)
                    .collect(Collectors.toList());

            if (wrapperNames.contains(commandWrapper.name())) {
                errors.add(commandName + "wrapper name \"" + commandWrapper.name() + "\" is not unique.");
            } else {
                wrapperNames.add(commandWrapper.name());
            }
            final String wrapperName = commandName + "wrapper \"" + commandWrapper.name() + "\" - ";
            final Function<String, String> addWrapperNameToError = input -> wrapperName + input;

            final Map<String, CommandWrapperInput> wrapperInputs = new HashMap<>(commandWrapper.externalInputs().size() + commandWrapper.derivedInputs().size());
            for (final CommandWrapperInput external : commandWrapper.externalInputs()) {
                final List<String> inputErrors = external.validate().stream()
                        .map(addWrapperNameToError)
                        .collect(Collectors.toList());

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

            final Set<String> derivedInputsWithMultiple = new HashSet<>(commandWrapper.derivedInputs().size());
            for (final CommandWrapperDerivedInput derived : commandWrapper.derivedInputs()) {
                final List<String> inputErrors = derived.validate().stream()
                        .map(addWrapperNameToError)
                        .collect(Collectors.toList());

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
                            "\". Known inputs: " + StringUtils.join(wrapperInputs.keySet(), ", "));
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
            final String knownWrapperInputs = StringUtils.join(wrapperInputs.keySet(), ", ");

            final Set<String> wrapperOutputNames = new HashSet<>(commandWrapper.outputHandlers().size());
            final Set<String> handledOutputs = new HashSet<>(commandWrapper.outputHandlers().size());
            for (final CommandWrapperOutput output : commandWrapper.outputHandlers()) {
                final List<String> outputErrors = output.validate().stream()
                        .map(addWrapperNameToError)
                        .collect(Collectors.toList());

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
            final Set<String> outputNamesNotHandled = new HashSet<>(outputNames);
            outputNamesNotHandled.removeAll(handledOutputs);
            errors.addAll(outputNamesNotHandled.stream()
                    .map(outputName -> wrapperName + "command output \"" + outputName +
                            "\" is not handled by any output handler.")
                    .collect(Collectors.toList()));

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
        public abstract Builder ulimits(Map<String, String> ulimits);

        public abstract Builder secrets(List<Secret> secrets);

        public abstract Command build();
    }

    @AutoValue
    public static abstract class CommandMount {
        @JsonIgnore public abstract long id();
        @Nullable @JsonProperty("name") public abstract String name();
        @JsonProperty("writable") public abstract boolean writable();
        @Nullable @JsonProperty("path") public abstract String path();

        @JsonCreator
        public static CommandMount create(@JsonProperty("name") final String name,
                                          @JsonProperty("writable") final Boolean writable,
                                          @JsonProperty("path") final String path) {
            return create(0L, name, writable, path);
        }

        public static CommandMount create(final long id,
                                          final String name,
                                          final Boolean writable,
                                          final String path) {
            return new AutoValue_Command_CommandMount(id, name == null ? "" : name, writable != null && writable, path);
        }

        public static CommandMount create(final CommandMountEntity mount) {
            if (mount == null) {
                return null;
            }
            return CommandMount.create(mount.getName(), mount.isWritable(), mount.getContainerPath());
        }

        List<String> validate() {
            final List<String> errors = new ArrayList<>();
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
                    .required(required != null && required)
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
            final List<String> errors = new ArrayList<>();
            if (StringUtils.isBlank(name())) {
                errors.add("Command input name cannot be blank");
            }
            Matcher m = COMMAND_NAME.matcher(name());
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
                if (!defaultValList.isEmpty() && !selectValues.isEmpty() &&
                        !new HashSet<>(selectValues).containsAll(defaultValList)) {
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
            final List<String> errors = new ArrayList<>();
            if (StringUtils.isBlank(name())) {
                errors.add("Output name cannot be blank.");
            }
            if (StringUtils.isBlank(mount())) {
                errors.add("Output \"" + name() + "\" - mount cannot be blank.");
            }
            Matcher m = COMMAND_NAME.matcher(name());
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
    public static abstract class CommandWrapper {
        @JsonProperty("id") public abstract long id();
        @Nullable @JsonProperty("name") public abstract String name();
        @Nullable @JsonProperty("label") public abstract String label();
        @Nullable @JsonProperty("description") public abstract String description();
        @JsonProperty("contexts") public abstract ImmutableSet<String> contexts();
        @JsonProperty("external-inputs") public abstract ImmutableList<CommandWrapperExternalInput> externalInputs();
        @JsonProperty("derived-inputs") public abstract ImmutableList<CommandWrapperDerivedInput> derivedInputs();
        @JsonProperty("output-handlers") public abstract ImmutableList<CommandWrapperOutput> outputHandlers();

        @JsonIgnore private Set<WrapperPermission> _requiredPermissionsCache = null;

        @JsonIgnore
        @Nonnull
        public List<CommandWrapperDerivedInput> topologicallySortedDerivedInputs() {
            final List<CommandWrapperDerivedInput> derivedInputs = new ArrayList<>(derivedInputs());
            if (derivedInputs.isEmpty()) {
                return Collections.emptyList();
            }

            // Find the names of the "derived from" inputs, and map that to the set of inputs derived from it
            final Map<String, Set<CommandWrapperDerivedInput>> targets = derivedInputs.stream()
                    .collect(Collectors.groupingBy(CommandWrapperDerivedInput::derivedFromWrapperInput,
                            Collectors.mapping(Function.identity(), Collectors.toSet())));

            // Collect the derived inputs in reverse order
            // We want a list where each derived input appears before any that are derived from it.
            // But the easier thing to do is find inputs that no other inputs are derived from.
            // So we build the list in reverse order.
            final CommandWrapperDerivedInput[] orderedDerivedInputs = new CommandWrapperDerivedInput[derivedInputs.size()];
            for (int outputIdx = derivedInputs.size() - 1; outputIdx >= 0; outputIdx--) {   // backwards because of the algo
                for (int inputIdx = derivedInputs.size() - 1; inputIdx >= 0; inputIdx--) {  // backwards so it isn't slow if they're already ordered
                    final CommandWrapperDerivedInput derivedInput = derivedInputs.get(inputIdx);
                    // Are any inputs derived from this one?
                    final Set<CommandWrapperDerivedInput> derivedFromThis = targets.get(derivedInput.name());
                    if (derivedFromThis == null || derivedFromThis.isEmpty()) {
                        // No, this handler is not a target of any others.
                        // Set it to the output array
                        orderedDerivedInputs[outputIdx] = derivedInput;

                        // Remove it from the in-progress list
                        derivedInputs.remove(derivedInput);

                        // Remove it from its target's set (if we have one; we won't if this handler targets an input)
                        final Set<CommandWrapperDerivedInput> derivedFromSameInputAsThis = targets.get(derivedInput.derivedFromWrapperInput());
                        if (derivedFromSameInputAsThis != null) {
                            derivedFromSameInputAsThis.remove(derivedInput);
                        }

                        // Break out of this loop, which continues the outer loop
                        break;
                    }
                }
            }

            return Arrays.asList(orderedDerivedInputs);
        }

        @JsonIgnore
        @Nonnull
        public List<CommandWrapperOutput> topologicallySortedOutputHandlers() {
            final List<CommandWrapperOutput> outputHandlers = new ArrayList<>(outputHandlers());
            if (outputHandlers.isEmpty()) {
                return Collections.emptyList();
            }

            // Find the names of the targets of all output handlers, and map that to the handled output handlers
            final Map<String, Set<CommandWrapperOutput>> targets = outputHandlers.stream()
                    .collect(Collectors.groupingBy(CommandWrapperOutput::targetName,
                            Collectors.mapping(Function.identity(), Collectors.toSet())));

            // Collect the handlers in reverse order
            // We want a list where each handler that is a target appears before any that are targeting it.
            // But the easier thing to do is find handlers that are not targeted by anyone and put them at the end.
            // So if we have handler A which targets handler B, then targets starts as {B: {A}, A: {}}.
            // We go through the output handlers, find that A's set is empty. We add it to the end of the outputs
            //  and remove it from B's targeters. Then targets = {B: {}, A: {}}.
            // On the next pass through the output handlers we notice that B's set of targeters is empty, so we can add it too.
            final CommandWrapperOutput[] orderedHandlers = new CommandWrapperOutput[outputHandlers.size()];
            for (int outputIdx = outputHandlers.size() - 1; outputIdx >= 0; outputIdx--) {   // backwards because of the algo
                for (int inputIdx = outputHandlers.size() - 1; inputIdx >= 0; inputIdx--) {  // backwards so it isn't slow if they're already ordered
                    final CommandWrapperOutput outputHandler = outputHandlers.get(inputIdx);
                    // Is this handler the target of any others?
                    final Set<CommandWrapperOutput> handlersTargetingThisHandler = targets.get(outputHandler.name());
                    if (handlersTargetingThisHandler == null || handlersTargetingThisHandler.isEmpty()) {
                        // No, this handler is not a target of any others.
                        // Set it to the output array
                        orderedHandlers[outputIdx] = outputHandler;

                        // Remove it from the in-progress list
                        outputHandlers.remove(outputHandler);

                        // Remove it from its target's set (if we have one; we won't if this handler targets an input)
                        final Set<CommandWrapperOutput> handlersTargetingThisHandlersTarget = targets.get(outputHandler.targetName());
                        if (handlersTargetingThisHandlersTarget != null) {
                            handlersTargetingThisHandlersTarget.remove(outputHandler);
                        }

                        // Break out of this loop, which continues the outer loop
                        break;
                    }
                }
            }

            return Arrays.asList(orderedHandlers);
        }

        /**
         * @return The first external input, if any are defined, else null.
         */
        @JsonIgnore
        public CommandWrapperExternalInput firstExternalInput() {
            final List<CommandWrapperExternalInput> externalInputs = externalInputs();
            if (externalInputs == null || externalInputs.isEmpty()) {
                return null;
            }
            return externalInputs.get(0);
        }

        /**
         * The set of permissions a user needs to have to execute this wrapper.
         * The intent is to use this when a user requests
         * {@link org.nrg.containers.services.CommandService#available(String, String, UserI)}
         * to filter the list of wrappers to only those where the user has all the
         * required permissions.
         * <p>
         * Example:
         * If a wrapper has a session input and creates an assessor output
         * (and that assessor output handler has "xsi-type": "xnat:qcManualAssessorData")
         * then it needs to read the session type and edit the assessor type.
         * The permissions map will be:
         * <p>
         * requiredPermissions("xnat:mrSessionData") ==
         * {WrapperPermission{"read", "xnat:mrSessionData"}, WrapperPermission{"edit", "xnat:qcManualAssessorData"}}
         * <p>
         * If a wrapper runs on a session and creates a scan resource, the permissions will be
         * requiredPermissions("xnat:mrSessionData") ==
         * {WrapperPermission{"read", "xnat:mrSessionData"}, WrapperPermission{"edit", "xnat:mrSessionData"}}
         * (this is because scan permissions are identified with the parent session permissions).
         *
         * @param contextXsiType The XSI type for the "context", i.e. the type of the
         *                       external input that the wrapper is to be run on.
         * @return The set of permissions a user needs to have to execute this wrapper
         */
        @JsonIgnore
        public Set<WrapperPermission> requiredPermissions(final String contextXsiType) {
            // Replace the context placeholder with this specific context
            if (_requiredPermissionsCache == null) {
                _requiredPermissionsCache = findRequiredPermissions();
            }
            final Set<WrapperPermission> requiredPermissions = new HashSet<>(_requiredPermissionsCache);

            // Replace the placeholder with the specific context
            if (requiredPermissions.remove(WrapperPermission.read(CONTEXT_PERMISSION_PLACEHOLDER))) {
                requiredPermissions.add(WrapperPermission.read(contextXsiType, true));
            }
            if (requiredPermissions.remove(WrapperPermission.edit(CONTEXT_PERMISSION_PLACEHOLDER))) {
                requiredPermissions.add(WrapperPermission.edit(contextXsiType, true));
            }

            return requiredPermissions;
        }

        /**
         * Find the permissions a user needs to run this wrapper
         * They're stored as a set of {@link WrapperPermission}s,
         * i.e. an action (read or edit) and an xsi type.
         * <p>
         * First find all the types for the inputs
         * <p>
         * If the wrapper has an external input, we use a "context placeholder" for that.
         * Any instances of the
         */
        private Set<WrapperPermission> findRequiredPermissions() {
            final Map<String, String> requiredPermissionsByInput = new HashMap<>();

            final CommandWrapperExternalInput firstExternalInput = firstExternalInput();
            if (firstExternalInput != null) {
                // External input will need permission for whatever type is given by the context
                requiredPermissionsByInput.put(firstExternalInput.name(), CONTEXT_PERMISSION_PLACEHOLDER);
            }

            // Check derived inputs
            // They may need permissions on the parent type, or a different type, or maybe we can't tell right now
            for (final CommandWrapperDerivedInput derivedInput : topologicallySortedDerivedInputs()) {
                final String parentXsiType = requiredPermissionsByInput.get(derivedInput.derivedFromWrapperInput());
                requiredPermissionsByInput.put(derivedInput.name(), derivedInput.xsiTypeForPermissions(parentXsiType));
            }

            // Check output handlers
            final List<CommandWrapperOutput> outputHandlers = topologicallySortedOutputHandlers();
            final Map<String, String> requiredPermissionsByOutputHandler = new HashMap<>(outputHandlers.size());
            for (final CommandWrapperOutput outputHandler : outputHandlers) {

                final String targetName = outputHandler.targetName();
                if (targetName == null) {
                    // This should have been caught during validation
                    // But we will do nothing right now
                    continue;
                }

                final String potentialParentInputXsiType = requiredPermissionsByInput.get(targetName);
                final String potentialParentOutputHandlerXsiType = requiredPermissionsByOutputHandler.get(targetName);
                final String parentXsiType = potentialParentInputXsiType != null ?
                        potentialParentInputXsiType :
                        potentialParentOutputHandlerXsiType;

                final String requiredXsiType = outputHandler.requiredEditPermissionXsiType(parentXsiType);
                requiredPermissionsByOutputHandler.put(outputHandler.name(), requiredXsiType);
            }

            return Stream.concat(
                    requiredPermissionsByInput.values().stream().filter(Objects::nonNull).map(WrapperPermission::read),
                    requiredPermissionsByOutputHandler.values().stream().filter(Objects::nonNull).map(WrapperPermission::edit)
            ).collect(Collectors.toSet());
        }

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
                    .contexts(contexts == null ? Collections.emptySet() : contexts)
                    .externalInputs(externalInputs == null ? Collections.emptyList() : externalInputs)
                    .derivedInputs(derivedInputs == null ? Collections.emptyList() : derivedInputs)
                    .outputHandlers(outputHandlers == null ? Collections.emptyList() : outputHandlers)
                    .build();
        }

        public static CommandWrapper create(final CommandWrapperCreation creation) {
            return builder()
                    .name(creation.name())
                    .label(creation.label())
                    .description(creation.description())
                    .contexts(creation.contexts() == null ? Collections.emptySet() : creation.contexts())
                    .externalInputs(creation.externalInputs() == null ? Collections.emptyList() : creation.externalInputs())
                    .derivedInputs(creation.derivedInputs() == null ? Collections.emptyList() : creation.derivedInputs())
                    .outputHandlers(creation.outputHandlers() == null ?
                            Collections.emptyList() :
                            creation.outputHandlers().stream().map(CommandWrapperOutput::create).collect(Collectors.toList()))
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
                    Collections.emptySet() :
                    new HashSet<>(commandWrapperEntity.getContexts());
            final List<CommandWrapperExternalInput> external = commandWrapperEntity.getExternalInputs() == null ?
                    Collections.emptyList() :
                    commandWrapperEntity.getExternalInputs().stream()
                            .map(CommandWrapperExternalInput::create)
                            .collect(Collectors.toList());
            final List<CommandWrapperDerivedInput> derived = commandWrapperEntity.getDerivedInputs() == null ?
                    Collections.emptyList() :
                    commandWrapperEntity.getDerivedInputs().stream()
                            .map(CommandWrapperDerivedInput::create)
                            .collect(Collectors.toList());
            final List<CommandWrapperOutput> outputs = commandWrapperEntity.getOutputHandlers() == null ?
                    Collections.emptyList() :
                    commandWrapperEntity.getOutputHandlers().stream()
                            .map(CommandWrapperOutput::create)
                            .collect(Collectors.toList());
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
            final List<String> errors = new ArrayList<>();
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
                    contexts == null ? ImmutableSet.of() : ImmutableSet.copyOf(contexts),
                    externalInputs == null ? ImmutableList.of() : ImmutableList.copyOf(externalInputs),
                    derivedInputs == null ? ImmutableList.of() : ImmutableList.copyOf(derivedInputs),
                    outputHandlers == null ? ImmutableList.of() : ImmutableList.copyOf(outputHandlers));
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
            final List<String> errors = new ArrayList<>();
            if (StringUtils.isBlank(name())) {
                errors.add("Command wrapper input name cannot be blank.");
            }

            final CommandWrapperInputType enumType = CommandWrapperInputType.fromName(type());
            if (enumType == null) {
                errors.add("Command wrapper input \"" + name() + "\" - Unknown type \"" + type() + "\". Known types: " +
                        StringUtils.join(CommandWrapperInputType.names(), ", "));
            }

            if (StringUtils.isNotBlank(viaSetupCommand()) && StringUtils.isBlank(providesFilesForCommandMount())) {
                errors.add("Command wrapper input \"" + name() + "\" - \"via-setup-command\": \"" + viaSetupCommand() + "\" - " +
                        "You cannot set \"via-setup-command\" on an input that does not provide files for a command mount.");
            }

            Matcher m = COMMAND_NAME.matcher(name());
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
    public static abstract class CommandWrapperDerivedInput extends CommandWrapperInput {
        @Nullable @JsonProperty("derived-from-wrapper-input") public abstract String derivedFromWrapperInput();
        @Nullable @JsonProperty("derived-from-xnat-object-property") public abstract String derivedFromXnatObjectProperty();
        @Nullable @JsonProperty("via-setup-command") public abstract String viaSetupCommand();
        @JsonProperty("multiple") public abstract boolean multiple();
        @Nullable @JsonProperty("parser") public abstract String parser();

        /**
         * What XSI type must the user have permissions on for this input to work?
         * If we can determine a concrete XSI type, the user must have read permissions on objects of that type.
         * If this input is the target of an output handler they may need edit permissions as well.
         * <p>
         * If we cannot determine a concrete type then we return null.
         * Without a concrete type, we cannot check permissions. (The {@link org.nrg.xdat.security.helpers.Permissions}
         * methods do not work with abstract types.)
         * Example:
         * A wrapper has a subject input and a session input gets derived from that.
         * We do not know what type that session will have without going through the full resolution process.
         * <p>
         * How do we determine the XSI types of inputs when we don't have
         * any input values and don't have that information in the wrapper inputs themselves?
         * <p>
         * If the input is a project or subject, we return the concrete XSI types
         * xnat:projectData and xnat:subjectData respectively.
         * <p>
         * If the input is a scan or a resource, return the parent input's XSI type.
         * There are no permissions for these objects separate from their parent.
         *
         * @param parentXsiType The XSI type of the parent input (if known)
         * @return The XSI type needed to read (+ maybe also edit) the object that will be held by this input at resolution time
         */
        @JsonIgnore
        @Nullable
        public String xsiTypeForPermissions(final String parentXsiType) {
            final CommandWrapperInputType enumType = enumType();
            if (enumType == null) {
                return null;
            }
            switch (enumType) {
                case PROJECT:
                    return XnatProjectdata.SCHEMA_ELEMENT_NAME;
                case SUBJECT:
                    return XnatSubjectdata.SCHEMA_ELEMENT_NAME;
                case SCAN:
                case RESOURCE:
                    return parentXsiType;
                default:
                    // All other input types can't be determined solely from their type or parent.
                    // And generic types like xnat:imageSessionData can't be used for permissions checks.
                    return null;
            }
        }

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

        /**
         * Test if parent input is above in XNAT hierarchy.
         * <p>
         * Examples
         * If parent input is "Session" and this input is "Scan", return true.
         * If parent input is "Session" and this input is "Project", return false.
         *
         * @param parent the parent
         * @return true if parent is above input in XNAT hierarchy
         */
        public boolean parentIsAboveInHierarchy(final CommandWrapperInput parent) {
            CommandWrapperInputType type = CommandWrapperInputType.fromName(type());
            CommandWrapperInputType parentType = CommandWrapperInputType.fromName(parent.type());

            return type != null && type.aboveInXnatHierarchy().contains(parentType);
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
    public static abstract class CommandWrapperOutput {
        @JsonIgnore public abstract long id();
        @Nullable @JsonProperty("name") public abstract String name();
        @Nullable @JsonProperty("accepts-command-output") public abstract String commandOutputName();
        @Nullable @JsonProperty("via-wrapup-command") public abstract String viaWrapupCommand();
        @Nullable @JsonProperty("as-a-child-of") public abstract String targetName();
        @JsonProperty("type") public abstract String type();
        @Nullable @JsonProperty("xsi-type") public abstract String xsiType();
        @Nullable @JsonProperty("label") public abstract String label();
        @Nullable @JsonProperty("format") public abstract String format();
        @Nullable @JsonProperty("description") public abstract String description();
        @Nullable @JsonProperty("content") public abstract String content();
        @Nullable @JsonProperty("tags") public abstract ImmutableList<String> tags();

        @JsonIgnore
        public CommandWrapperOutputEntity.Type enumType() {
            final CommandWrapperOutputEntity.Type outputType = CommandWrapperOutputEntity.Type.fromName(type());
            return outputType == null ? CommandWrapperOutputEntity.DEFAULT_TYPE : outputType;
        }

        /**
         * The XSI type that the user must have permission to edit in order to create
         * the object from this output handler.
         * <p>
         * If the handler creates an assessor, the input parentXsiType is ignored.
         * We must have permissions on whatever XSI type is listed in the output handler itself.
         * (This can be null, meaning we don't know in advance if the user can create the output or not.)
         * <p>
         * For other output handler types, the user must have permission to edit the parentXsiType.
         * @param parentXsiType The XSI type of the "parent" object, under which the output object will be created.
         * @return The XSI type for which the user must have edit permissions. (Or null if we don't know.)
         */
        @JsonIgnore
        public String requiredEditPermissionXsiType(final String parentXsiType) {
            if (enumType() == CommandWrapperOutputEntity.Type.ASSESSOR) {
                // To create an assessor, user must be able to edit whatever type the assessor will be.
                // They don't need edit permissions on the parent.
                return xsiType();
            }

            // For both scan and resource output types,
            // user needs to be able to edit the parent
            return parentXsiType;
        }

        @JsonCreator
        public static CommandWrapperOutput create(@JsonProperty("name") final String name,
                                                  @JsonProperty("accepts-command-output") final String commandOutputName,
                                                  @JsonProperty("as-a-child-of") final String targetName,
                                                  @JsonProperty("via-wrapup-command") final String viaWrapupCommand,
                                                  @JsonProperty("type") final String type,
                                                  @JsonProperty("xsi-type") final String xsiType,
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
                    .xsiType(xsiType)
                    .label(label)
                    .format(format)
                    .description(description)
                    .content(content)
                    .tags(tags == null ? Collections.emptyList() : tags)
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
                    .xsiType(wrapperOutput.getXsiType())
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
                    .xsiType(commandWrapperOutputCreation.xsiType())
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
                    .type(CommandWrapperOutputEntity.DEFAULT_TYPE.getName())
                    .tags(Collections.emptyList());
        }

        public abstract Builder toBuilder();

        @Nonnull
        List<String> validate() {
            final List<String> errors = new ArrayList<>();

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
            Matcher m = COMMAND_NAME.matcher(name());
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
            public abstract Builder xsiType(final String xsiType);

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
        @Nullable @JsonProperty("ulimits") public abstract Map<String, String> ulimits();
        @JsonProperty("secrets") public abstract List<Secret> secrets();

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
                                      @JsonProperty("generic-resources") final ImmutableMap<String, String> genericResources,
                                      @JsonProperty("ulimits") final Map<String, String> ulimits,
                                      @JsonProperty("secrets") final List<Secret> secrets) {
            return new AutoValue_Command_CommandCreation(name, label, description, version, schemaVersion, infoUrl, image,
                    containerName, type, index, hash, workingDirectory, commandLine, overrideEntrypoint,
                    mounts == null ? ImmutableList.of() : ImmutableList.copyOf(mounts),
                    environmentVariables == null ? ImmutableMap.of() : ImmutableMap.copyOf(environmentVariables),
                    ports == null ? ImmutableMap.of() : ImmutableMap.copyOf(ports),
                    inputs == null ? ImmutableList.of() : ImmutableList.copyOf(inputs),
                    outputs == null ? ImmutableList.of() : ImmutableList.copyOf(outputs),
                    commandWrapperCreations == null ? ImmutableList.of() : ImmutableList.copyOf(commandWrapperCreations),
                    reserveMemory, limitMemory, limitCpu, runtime, ipcMode,
                    autoRemove, shmSize, network, containerLabels, gpus, genericResources, ulimits,
                    secrets == null ? Collections.emptyList() : secrets);
        }
    }

    @AutoValue
    public static abstract class CommandWrapperOutputCreation {
        @Nullable @JsonProperty("name") public abstract String name();
        @Nullable @JsonProperty("accepts-command-output") public abstract String commandOutputName();
        @Nullable @JsonProperty("via-wrapup-command") public abstract String viaWrapupCommand();
        @Nullable @JsonProperty("as-a-child-of") public abstract String targetName();
        @JsonProperty("type") public abstract String type();
        @Nullable @JsonProperty("xsi-type") public abstract String xsiType();
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
                                                          @JsonProperty("xsi-type") final String xsiType,
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
                    .xsiType(xsiType)
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
            public abstract Builder xsiType(final String xsiType);
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
        @Nullable public abstract ImmutableMap<String, String> ulimits();
        public abstract List<Secret> secrets();

        public static Builder initialize(final Command command) {
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
                    .genericResources(command.genericResources())
                    .ulimits(command.ulimits())
                    .secrets(command.secrets());

        }

        static Builder builder() {
            return new AutoValue_Command_ConfiguredCommand.Builder()
                    .secrets(Collections.emptyList());
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
            public abstract Builder environmentVariables(Map<String, String> environmentVariables);
            public abstract Builder ports(Map<String, String> ports);
            public abstract Builder inputs(List<CommandInput> inputs);
            abstract ImmutableList.Builder<CommandInput> inputsBuilder();
            public Builder addInput(final @Nonnull CommandInput commandInput) {
                inputsBuilder().add(commandInput);
                return this;
            }
            public abstract Builder outputs(List<CommandOutput> outputs);
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
            public abstract Builder ulimits(Map<String, String> ulimits);
            public abstract Builder secrets(List<Secret> secrets);

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

        @JsonIgnore
        public CommandWrapperInputType enumType() {
            return CommandWrapperInputType.fromName(type());
        }

        public String replacementKey() {
            return StringUtils.isNotBlank(rawReplacementKey()) ? rawReplacementKey() : "#" + name() + "#";
        }
    }
}
