package org.nrg.containers.model.command.auto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.exceptions.CommandResolutionException;
import org.nrg.containers.model.command.entity.CommandEntity;
import org.nrg.containers.model.command.entity.CommandInputEntity;
import org.nrg.containers.model.container.ContainerInputType;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Collectors;

@AutoValue
public abstract class ResolvedCommand {
    private ImmutableSet<ResolvedCommandInput> externalWrapperInputValues;
    private ImmutableSet<ResolvedCommandInput> derivedWrapperInputValues;
    private ImmutableSet<ResolvedCommandInput> commandInputValues;

    @JsonProperty("wrapper-id") public abstract Long wrapperId();
    @JsonProperty("wrapper-name") public abstract String wrapperName();
    @JsonProperty("wrapper-description") @Nullable public abstract String wrapperDescription();
    @JsonProperty("command-id") public abstract Long commandId();
    @JsonProperty("command-name") public abstract String commandName();
    @JsonProperty("command-description") @Nullable public abstract String commandDescription();
    @JsonProperty("image") public abstract String image();
    @JsonProperty("type") public abstract String type();
    @JsonProperty("project") @Nullable public abstract String project();
    @JsonProperty("raw-input-values") public abstract ImmutableMap<String, String> rawInputValues();
    @JsonIgnore public abstract ImmutableList<ResolvedInputTreeNode<? extends Command.Input>> resolvedInputTrees();
    @JsonProperty("command-line") public abstract String commandLine();
    @JsonProperty("overrideEntrypoint") public abstract Boolean overrideEntrypoint();
    @JsonProperty("environment-variables") public abstract ImmutableMap<String, String> environmentVariables();
    @JsonProperty("ports") public abstract ImmutableMap<String, String> ports();
    @JsonProperty("mounts") public abstract ImmutableList<ResolvedCommandMount> mounts();
    @JsonProperty("outputs") public abstract ImmutableList<ResolvedCommandOutput> outputs();
    @JsonProperty("working-directory") @Nullable public abstract String workingDirectory();
    @JsonProperty("setup-commands") public abstract ImmutableList<ResolvedCommand> setupCommands();
    @JsonProperty("wrapup-commands") public abstract ImmutableList<ResolvedCommand> wrapupCommands();
    @JsonProperty("reserve-memory") @Nullable public abstract Long reserveMemory();
    @JsonProperty("limit-memory") @Nullable public abstract Long limitMemory();
    @JsonProperty("limit-cpu") @Nullable public abstract Double limitCpu();
    @JsonProperty("swarm-constraints") @Nullable public abstract List<String> swarmConstraints();
    @JsonProperty("parent-source-object-name") @Nullable public abstract String parentSourceObjectName();

    @JsonProperty("external-wrapper-input-values")
    public ImmutableSet<ResolvedCommandInput> externalWrapperInputValues() {
        if (externalWrapperInputValues == null) {
            setUpLegacyInputLists();
        }
        return externalWrapperInputValues;
    }

    @JsonProperty("derived-input-values")
    public ImmutableSet<ResolvedCommandInput> derivedWrapperInputValues() {
        if (derivedWrapperInputValues == null) {
            setUpLegacyInputLists();
        }
        return derivedWrapperInputValues;
    }

    @JsonProperty("command-input-values")
    public ImmutableSet<ResolvedCommandInput> commandInputValues() {
        if (commandInputValues == null) {
            setUpLegacyInputLists();
        }
        return commandInputValues;
    }

    @JsonIgnore
    public ImmutableSet<ResolvedCommandInput> wrapperInputValues() {
        final ImmutableSet.Builder<ResolvedCommandInput> wrapperValuesBuilder = ImmutableSet.builder();
        wrapperValuesBuilder.addAll(externalWrapperInputValues());
        wrapperValuesBuilder.addAll(derivedWrapperInputValues());
        return wrapperValuesBuilder.build();
    }

    @JsonIgnore
    public ImmutableSet<ResolvedCommandInput> inputValues() {
        final ImmutableSet.Builder<ResolvedCommandInput> inputBuilder = ImmutableSet.builder();
        inputBuilder.addAll(commandInputValues());
        inputBuilder.addAll(externalWrapperInputValues());
        inputBuilder.addAll(derivedWrapperInputValues());
        return inputBuilder.build();
    }

    private void setUpLegacyInputLists() {
        // Read out all the input trees into Map<String, String>s
        final List<ResolvedInputTreeNode<? extends Command.Input>> flatTrees = flattenInputTrees();
        final ImmutableSet.Builder<ResolvedCommandInput> externalWrapperInputValuesBuilder = ImmutableSet.builder();
        final ImmutableSet.Builder<ResolvedCommandInput> derivedWrapperInputValuesBuilder = ImmutableSet.builder();
        final ImmutableSet.Builder<ResolvedCommandInput> commandInputValuesBuilder = ImmutableSet.builder();
        for (final ResolvedInputTreeNode<? extends Command.Input> node : flatTrees) {
            final Command.Input input = node.input();
            final String inputName = input.name();
            final Boolean sensitiveCouldBeNull = input.sensitive();
            final boolean sensitive = sensitiveCouldBeNull != null && sensitiveCouldBeNull;

            final List<ResolvedInputTreeNode.ResolvedInputTreeValueAndChildren> valuesAndChildren = node.valuesAndChildren();
            final String value = (valuesAndChildren != null && !valuesAndChildren.isEmpty()) ?
                    valuesAndChildren.get(0).resolvedValue().value() :
                    null;
            String nonNullValue = value == null ? "null" : value;
            if (node.input() instanceof Command.CommandWrapperExternalInput) {
                externalWrapperInputValuesBuilder.add(
                        ResolvedCommandInput.wrapperExternal(inputName, nonNullValue, sensitive)
                );
            } else if (node.input() instanceof Command.CommandWrapperDerivedInput) {
                if (((Command.CommandWrapperDerivedInput) node.input()).multiple() &&
                        valuesAndChildren != null && !valuesAndChildren.isEmpty()) {
                    nonNullValue = valuesAndChildren.stream()
                            .map(v -> v.resolvedValue().value())
                            .collect(Collectors.joining(", "));
                }
                derivedWrapperInputValuesBuilder.add(
                        ResolvedCommandInput.wrapperDerived(inputName, nonNullValue, sensitive)
                );
            } else {
                commandInputValuesBuilder.add(
                        ResolvedCommandInput.command(inputName, nonNullValue, sensitive)
                );
            }
        }
        externalWrapperInputValues = externalWrapperInputValuesBuilder.build();
        derivedWrapperInputValues = derivedWrapperInputValuesBuilder.build();
        commandInputValues = commandInputValuesBuilder.build();
    }

    @JsonIgnore
    public static Command.CommandInput collectCommandInputChildrenOfMultipleDerivedInput(final Command.CommandWrapperInput wrapperInput,
                                                                                         final List<ResolvedInputTreeNode.ResolvedInputTreeValueAndChildren> derivedInputValuesAndChildren,
                                                                                         final List<String> commandInputChildrenValues)
            throws CommandResolutionException {
        return collectCommandInputChildrenOfMultipleDerivedInput(wrapperInput, derivedInputValuesAndChildren,
                commandInputChildrenValues, true);
    }

    @JsonIgnore
    public static Command.CommandInput collectCommandInputChildrenOfMultipleDerivedInput(final Command.CommandWrapperInput wrapperInput,
                                                                                         final List<ResolvedInputTreeNode.ResolvedInputTreeValueAndChildren> derivedInputValuesAndChildren,
                                                                                         final List<String> commandInputChildrenValues,
                                                                                         final boolean throwExceptionIfNullAndReqd)
            throws CommandResolutionException {

        String commandInputName = wrapperInput.providesValueForCommandInput();
        if (StringUtils.isBlank(commandInputName)) {
            // Shouldn't ever happen because of validation in Command.CommandWrapperDerivedInput
            throw new CommandResolutionException("Input \"" + wrapperInput.name() + "\" is a multiple input, but it " +
                    "does not provide values for a command input.");
        }

        Command.CommandInput ci = null;
        for (ResolvedInputTreeNode.ResolvedInputTreeValueAndChildren singleValue : derivedInputValuesAndChildren) {
            List<ResolvedInputTreeNode<? extends Command.Input>> children = singleValue.children();
            if (children.size() != 1) {
                throw new CommandResolutionException(wrapperInput.name() + " must have precisely one command " +
                        "input child element");
            }
            ResolvedInputTreeNode<? extends Command.Input> child = children.get(0);
            if (!(child.input() instanceof Command.CommandInput) || !child.input().name().equals(commandInputName) ||
                    child.valuesAndChildren().size() != 1) {
                throw new CommandResolutionException("Invalid child for " + wrapperInput.name() +
                        "; expecting a command input with no children named " + commandInputName);
            } else if (ci == null) {
                ci = (Command.CommandInput) child.input();
            }
            String val = child.valuesAndChildren().get(0).resolvedValue().value();
            if (StringUtils.isNotBlank(val)) {
                commandInputChildrenValues.add(val);
            }
        }

        if (ci == null && throwExceptionIfNullAndReqd && wrapperInput.required()) {
            // ci == null will occur if the derived input has no resolved values e.g. nothing matches the matcher.
            // For the UI (a.k.a., when this is run for preresolution), we want to make the user aware of the issue,
            // which is accomplished with a warning from commandUiLauncher.js. So, we don't want to throw an exception.
            // Otherwise (during command resolution), we probably want to throw an exception, but if the parent input
            // isn't required, then perhaps it's okay...
            throw new CommandResolutionException(wrapperInput.name() + " must have precisely one command " +
                    "input child element");
        }
        return ci;
    }

    @JsonIgnore
    public List<ResolvedInputTreeNode<? extends Command.Input>> flattenInputTrees() {
        final List<ResolvedInputTreeNode<? extends Command.Input>> flatTree = Lists.newArrayList();
        for (final ResolvedInputTreeNode<? extends Command.Input> rootNode : resolvedInputTrees()) {
            flatTree.addAll(flattenTree(rootNode));
        }
        return flatTree;
    }

    private List<ResolvedInputTreeNode<? extends Command.Input>> flattenTree(final ResolvedInputTreeNode<? extends Command.Input> node) {
        final List<ResolvedInputTreeNode<? extends Command.Input>> flatTree = Lists.newArrayList();
        flatTree.add(node);

        Command.Input input = node.input();
        final List<ResolvedInputTreeNode.ResolvedInputTreeValueAndChildren> resolvedValueAndChildren = node.valuesAndChildren();
        if (resolvedValueAndChildren.size() == 1) {
            // This node has a single value, so we can attempt to flatten its children
            final ResolvedInputTreeNode.ResolvedInputTreeValueAndChildren singleValue = resolvedValueAndChildren.get(0);
            final List<ResolvedInputTreeNode<? extends Command.Input>> children = singleValue.children();
            if (!(children == null || children.isEmpty())) {
                for (final ResolvedInputTreeNode<? extends Command.Input> child : children) {
                    flatTree.addAll(flattenTree(child));
                }
            } else {
                // Input has a uniquely resolved value, but no children.
            }
        } else if (input instanceof Command.CommandWrapperDerivedInput && ((Command.CommandWrapperDerivedInput) input).multiple()) {
            final List<String> commandInputChildrenValues = new ArrayList<>();
            try {
                Command.CommandInput ci = ResolvedCommand.collectCommandInputChildrenOfMultipleDerivedInput((Command.CommandWrapperDerivedInput) input,
                        resolvedValueAndChildren, commandInputChildrenValues);
                ResolvedInputTreeNode.ResolvedInputTreeValueAndChildren valueAndChildren =
                        ResolvedInputTreeNode.ResolvedInputTreeValueAndChildren.create(ResolvedInputValue.builder()
                                .value(String.join(" ", commandInputChildrenValues))
                                .build());
                if (ci != null) {
                    flatTree.add(ResolvedInputTreeNode.create(ci, Collections.singletonList(valueAndChildren)));
                }
            } catch (CommandResolutionException e) {
                // Ignore for purposes of flattening tree
            }
        } else {
            // Cannot flatten children of arbitrary input with multiple values
        }
        return flatTree;
    }

    public static Builder builder() {
        return new AutoValue_ResolvedCommand.Builder()
                .type(CommandEntity.DEFAULT_TYPE.getName())
                .overrideEntrypoint(Boolean.FALSE);
    }

    /**
     * Creates ResolvedCommands for setup and wrapup commands.
     * @param command The Command definition for the setup or wrapup command
     * @param inputMountXnatHostPath Path on the XNAT host to the input mount
     * @param inputMountContainerHostPath Path on the container host to the input mount
     * @param outputMountXnatHostPath Path on the XNAT host to the output mount
     * @param outputMountContainerHostPath Path on the container host to the output mount
     * @param parentSourceObjectName Name of the Resolved Command Mount / Container Mount (for setup commands) or
     *                               Resolved Command Output / Container Ouput (for wrapup commands) from which this
     *                               special Resolved Command is being created.
     * @return A Resolved Setup Command or Resolved Wrapup Command
     */
    public static ResolvedCommand fromSpecialCommandType(final Command command,
                                                         final String inputMountXnatHostPath,
                                                         final String inputMountContainerHostPath,
                                                         final String outputMountXnatHostPath,
                                                         final String outputMountContainerHostPath,
                                                         final String parentSourceObjectName) {
        return builder()
                .wrapperId(0L)
                .wrapperName("")
                .type(command.type())
                .commandId(command.id())
                .commandName(command.name())
                .image(command.image())
                .commandLine(command.commandLine())
                .workingDirectory(command.workingDirectory())
                .reserveMemory(command.reserveMemory())
                .limitMemory(command.limitMemory())
                .limitCpu(command.limitCpu())
                .parentSourceObjectName(parentSourceObjectName)
                .addMount(ResolvedCommandMount.builder()
                        .name("input")
                        .containerPath("/input")
                        .xnatHostPath(inputMountXnatHostPath)
                        .containerHostPath(inputMountContainerHostPath)
                        .writable(false)
                        .build())
                .addMount(ResolvedCommandMount.builder()
                        .name("output")
                        .containerPath("/output")
                        .xnatHostPath(outputMountXnatHostPath)
                        .containerHostPath(outputMountContainerHostPath)
                        .writable(true)
                        .build())
                .build();
    }

    public abstract Builder toBuilder();

    @AutoValue.Builder
    public static abstract class Builder {
        public abstract Builder wrapperId(Long wrapperId);
        public abstract Builder wrapperName(String wrapperDescription);
        public abstract Builder wrapperDescription(String wrapperDescription);
        public abstract Builder commandId(Long commandId);
        public abstract Builder commandName(String commandDescription);
        public abstract Builder commandDescription(String commandDescription);
        public abstract Builder image(String image);
        public abstract Builder type(String type);
        public abstract Builder project(String project);
        public abstract Builder rawInputValues(Map<String, String> rawInputValues);
        public abstract ImmutableMap.Builder<String, String> rawInputValuesBuilder();
        public Builder addRawInputValue(final String inputName, final String inputValue) {
            rawInputValuesBuilder().put(inputName, inputValue);
            return this;
        }
        public abstract Builder resolvedInputTrees(List<ResolvedInputTreeNode<? extends Command.Input>> resolvedInputTrees);
        public abstract ImmutableList.Builder<ResolvedInputTreeNode<? extends Command.Input>> resolvedInputTreesBuilder();
        public Builder addResolvedInputTree(final ResolvedInputTreeNode<? extends Command.Input> resolvedInputTree) {
            resolvedInputTreesBuilder().add(resolvedInputTree);
            return this;
        }
        public abstract Builder commandLine(String commandLine);
        public abstract Builder overrideEntrypoint(Boolean overrideEntrypoint);
        public abstract Builder environmentVariables(Map<String, String> environmentVariables);
        public abstract ImmutableMap.Builder<String, String> environmentVariablesBuilder();
        public Builder addEnvironmentVariable(final String name, final String value) {
            environmentVariablesBuilder().put(name, value);
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
        public abstract Builder mounts(List<ResolvedCommandMount> mounts);
        public abstract Builder mounts(ResolvedCommandMount... mounts);
        public abstract ImmutableList.Builder<ResolvedCommandMount> mountsBuilder();
        public Builder addMount(final ResolvedCommandMount mount) {
            mountsBuilder().add(mount);
            return this;
        }
        public abstract Builder outputs(List<ResolvedCommandOutput> outputs);
        public abstract Builder outputs(ResolvedCommandOutput... outputs);
        public abstract ImmutableList.Builder<ResolvedCommandOutput> outputsBuilder();
        public Builder addOutput(final ResolvedCommandOutput output) {
            outputsBuilder().add(output);
            return this;
        }
        public abstract Builder workingDirectory(String workingDirectory);

        public abstract Builder setupCommands(List<ResolvedCommand> setupCommands);
        public abstract Builder setupCommands(ResolvedCommand... setupCommands);
        public abstract ImmutableList.Builder<ResolvedCommand> setupCommandsBuilder();
        public Builder addSetupCommand(final ResolvedCommand setupCommand) {
            setupCommandsBuilder().add(setupCommand);
            return this;
        }

        public abstract Builder wrapupCommands(List<ResolvedCommand> wrapupCommands);
        public abstract Builder wrapupCommands(ResolvedCommand... wrapupCommands);
        public abstract ImmutableList.Builder<ResolvedCommand> wrapupCommandsBuilder();
        public Builder addWrapupCommand(final ResolvedCommand wrapupCommand) {
            wrapupCommandsBuilder().add(wrapupCommand);
            return this;
        }

        public abstract Builder reserveMemory(Long reserveMemory);
        public abstract Builder limitMemory(Long limitMemory);
        public abstract Builder limitCpu(Double limitCpu);
        public abstract Builder swarmConstraints(List<String> swarmConstraints);
        public abstract Builder parentSourceObjectName(String parentSourceObjectName);

        public abstract ResolvedCommand build();
    }

    @AutoValue
    public abstract static class PartiallyResolvedCommand {
        public abstract Long wrapperId();
        public abstract String wrapperName();
        @Nullable public abstract String wrapperLabel();
        @Nullable public abstract String wrapperDescription();
        public abstract Long commandId();
        public abstract String commandName();
        @Nullable public abstract String commandLabel();
        @Nullable public abstract String commandDescription();
        public abstract String image();
        public abstract String type();
        @Nullable public abstract String project();
        public abstract Boolean overrideEntrypoint();
        public abstract ImmutableMap<String, String> rawInputValues();
        public abstract ImmutableList<ResolvedInputTreeNode<? extends Command.Input>> resolvedInputTrees();

        public static Builder builder() {
            return new AutoValue_ResolvedCommand_PartiallyResolvedCommand.Builder()
                    .type(CommandEntity.DEFAULT_TYPE.getName())
                    .overrideEntrypoint(Boolean.FALSE);
        }

        public abstract Builder toBuilder();

        @AutoValue.Builder
        public static abstract class Builder {
            public abstract Builder wrapperId(Long wrapperId);
            public abstract Builder wrapperName(String wrapperDescription);
            public abstract Builder wrapperLabel(String wrapperLabel);
            public abstract Builder wrapperDescription(String wrapperDescription);
            public abstract Builder commandId(Long commandId);
            public abstract Builder commandName(String commandDescription);
            public abstract Builder commandLabel(String commandLabel);
            public abstract Builder commandDescription(String commandDescription);
            public abstract Builder image(String image);
            public abstract Builder type(String type);
            public abstract Builder project(String project);
            public abstract Builder overrideEntrypoint(Boolean overrideEntrypoint);
            public abstract Builder rawInputValues(Map<String, String> rawInputValues);
            public abstract ImmutableMap.Builder<String, String> rawInputValuesBuilder();
            public Builder addRawInputValue(final String inputName, final String inputValue) {
                rawInputValuesBuilder().put(inputName, inputValue);
                return this;
            }
            public abstract Builder resolvedInputTrees(List<ResolvedInputTreeNode<? extends Command.Input>> resolvedInputTrees);
            abstract ImmutableList.Builder<ResolvedInputTreeNode<? extends Command.Input>> resolvedInputTreesBuilder();
            public Builder addResolvedInputTree(final ResolvedInputTreeNode<? extends Command.Input> root) {
                resolvedInputTreesBuilder().add(root);
                return this;
            }

            public abstract PartiallyResolvedCommand build();
        }
    }

    @AutoValue
    public abstract static class PartiallyResolvedCommandMount {
        public abstract String name();
        public abstract Boolean writable();
        public abstract String containerPath();
        @Nullable public abstract String fromWrapperInput();
        @Nullable public abstract String viaSetupCommand();
        @Nullable public abstract String fromUri();
        @Nullable public abstract String fromRootDirectory();
        @Nullable public abstract String fromFilePath();

        public static Builder builder() {
            return new AutoValue_ResolvedCommand_PartiallyResolvedCommandMount.Builder();
        }

        public ResolvedCommandMount.Builder toResolvedCommandMountBuilder() {
            return ResolvedCommandMount.builder()
                    .name(this.name())
                    .writable(this.writable())
                    .containerPath(this.containerPath())
                    .fromWrapperInput(this.fromWrapperInput())
                    .viaSetupCommand(this.viaSetupCommand())
                    .fromUri(this.fromUri())
                    .fromRootDirectory(this.fromRootDirectory())
                    .fromFilePath(this.fromFilePath());
        }

        @AutoValue.Builder
        public abstract static class Builder {
            public abstract Builder name(String name);
            public abstract Builder writable(Boolean writable);
            public abstract Builder containerPath(String containerPath);
            public abstract Builder fromWrapperInput(String fromWrapperInput);
            public abstract Builder viaSetupCommand(String viaSetupCommand);
            public abstract Builder fromUri(String fromUri);
            public abstract Builder fromRootDirectory(String fromRootDirectory);
            public abstract Builder fromFilePath(String fromFilePath);

            public abstract PartiallyResolvedCommandMount build();
        }
    }

    @AutoValue
    public abstract static class ResolvedCommandOutput {
        @JsonProperty("name") public abstract String name();
        @JsonProperty("from-command-output") public abstract String fromCommandOutput();
        @JsonProperty("from-output-handler") public abstract String fromOutputHandler();
        @JsonProperty("type") public abstract String type();
        @JsonProperty("required") public abstract Boolean required();
        @JsonProperty("mount") public abstract String mount();
        @JsonProperty("path") @Nullable public abstract String path();
        @JsonProperty("glob") @Nullable public abstract String glob();
        @JsonProperty("label") @Nullable public abstract String label();
        @JsonProperty("format") @Nullable public abstract String format();
        @JsonProperty("handled-by") public abstract String handledBy();
        @Nullable @JsonProperty("via-wrapup-command") public abstract String viaWrapupCommand();

        public static Builder builder() {
            return new AutoValue_ResolvedCommand_ResolvedCommandOutput.Builder();
        }

        @AutoValue.Builder
        public static abstract class Builder {
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
            public abstract Builder handledBy(String handledBy);
            public abstract Builder viaWrapupCommand(String viaWrapupCommand);

            public abstract ResolvedCommandOutput build();
        }
    }

    @AutoValue
    public abstract static class ResolvedCommandInput {
        @JsonProperty("name") public abstract String name();
        @JsonProperty("value") public abstract String value();
        @JsonProperty("type") public abstract ContainerInputType type();
        @JsonProperty("sensitive") public abstract boolean sensitive();

        @JsonCreator
        public static ResolvedCommandInput create(@JsonProperty("name") final String name,
                                                  @JsonProperty("value") final String value,
                                                  @JsonProperty("type") final ContainerInputType type,
                                                  @JsonProperty("sensitive") final boolean sensitive) {
            return new AutoValue_ResolvedCommand_ResolvedCommandInput(name, value, type, sensitive);
        }

        public static ResolvedCommandInput command(final String name, final String value) {
            return command(name, value, false);
        }

        public static ResolvedCommandInput command(final String name, final String value, final boolean sensitive) {
            return create(name, value, ContainerInputType.COMMAND, sensitive);
        }

        public static ResolvedCommandInput wrapperExternal(final String name, final String value) {
            return wrapperExternal(name, value, false);
        }

        public static ResolvedCommandInput wrapperExternal(final String name, final String value, final boolean sensitive) {
            return create(name, value, ContainerInputType.WRAPPER_EXTERNAL, sensitive);
        }

        public static ResolvedCommandInput wrapperDerived(final String name, final String value) {
            return wrapperDerived(name, value, false);
        }

        public static ResolvedCommandInput wrapperDerived(final String name, final String value, final boolean sensitive) {
            return create(name, value, ContainerInputType.WRAPPER_DERIVED, sensitive);
        }
    }
}
