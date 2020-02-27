package org.nrg.containers.model.command.auto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import com.google.auto.value.AutoValue;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.model.command.auto.Command.Input;
import org.nrg.containers.model.command.auto.ResolvedCommand.PartiallyResolvedCommand;
import org.nrg.containers.model.command.auto.ResolvedInputTreeNode.ResolvedInputTreeValueAndChildren;
import org.nrg.containers.model.command.entity.CommandInputEntity;
import org.nrg.containers.model.command.entity.CommandWrapperInputType;
import org.nrg.containers.model.configuration.CommandConfiguration.CommandInputConfiguration;
import org.nrg.containers.model.server.docker.DockerServerBase;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@SuppressWarnings("NullableProblems")
@AutoValue
public abstract class LaunchUi {

    @JsonProperty("meta") public abstract LaunchUiMeta meta();
    @JsonProperty("container-server-config") public abstract LaunchUiServer containerServerConfig();
    @JsonProperty("input-config") public abstract List<LaunchUiInputTree> inputTrees();
    @JsonProperty("input-values") public abstract List<LaunchUiValueTree> valueTrees();

    public static LaunchUi create(final @Nonnull PartiallyResolvedCommand partiallyResolvedCommand,
                                  final @Nonnull Map<String, CommandInputConfiguration> inputConfigurationMap,
                                  final @Nonnull DockerServerBase.DockerServer server,
                                  final boolean bulkLaunch) {
        return builder()
                .meta(LaunchUiMeta.create(partiallyResolvedCommand))
                .containerServerConfig(LaunchUiServer.create(server))
                .populateInputTreeAndInputValueTreeFromResolvedInputTrees(partiallyResolvedCommand.resolvedInputTrees(),
                        inputConfigurationMap, bulkLaunch)
                .build();
    }

    public static Builder builder() {
        return new AutoValue_LaunchUi.Builder();
    }

    @AutoValue.Builder
    public static abstract class Builder {
        public abstract Builder meta(@Nonnull LaunchUiMeta meta);
        public abstract Builder inputTrees(@Nonnull List<LaunchUiInputTree> inputTrees);
        public abstract Builder valueTrees(@Nonnull List<LaunchUiValueTree> valueTrees);
        public abstract Builder containerServerConfig(@Nonnull LaunchUiServer server);

        public Builder populateInputTreeAndInputValueTreeFromResolvedInputTrees(final @Nonnull List<ResolvedInputTreeNode<? extends Input>> resolvedInputTrees,
                                                                                final @Nonnull Map<String, CommandInputConfiguration> inputConfigurationMap,
                                                                                final boolean bulkLaunch) {
            final List<LaunchUiInputTree> inputTrees = new ArrayList<>();
            final List<LaunchUiValueTree> valueTrees = new ArrayList<>();
            for (final ResolvedInputTreeNode<? extends Input> rootNode : resolvedInputTrees) {
                final Map<String, Integer> maxInputValues = new HashMap<>();
                final Map<String, Integer> minInputValues = new HashMap<>();
                findMaxAndMinInputValuesForTree(rootNode, maxInputValues, minInputValues);

                final String inputName = rootNode.input().name();
                if (log.isDebugEnabled()) {
                    log.debug("ROOT " + inputName + " - Populating input relationship tree.");
                }
                inputTrees.add(convertResolvedInputTreeToLaunchUiInputTree(rootNode, inputConfigurationMap,
                        maxInputValues, minInputValues, bulkLaunch));

                if (log.isDebugEnabled()) {
                    log.debug("ROOT " + inputName + " - Populating input value tree.");
                }
                valueTrees.add(LaunchUiValueTree.create(rootNode));
            }
            return this
                    .inputTrees(inputTrees)
                    .valueTrees(valueTrees);
        }

        public abstract LaunchUi build();
    }

    @AutoValue
    public static abstract class LaunchUiServer {
        @Nullable @JsonProperty("swarm-constraints") public abstract List<LaunchUiServerSwarmConstraint> swarmConstraints();

        public static LaunchUiServer create(final DockerServerBase server) {
            List<LaunchUiServerSwarmConstraint> uiconstraints = null;
            List<DockerServerBase.DockerServerSwarmConstraint> constraints = server.swarmConstraints();
            if (constraints != null) {
                uiconstraints = new ArrayList<>();
                for (DockerServerBase.DockerServerSwarmConstraint constraint : constraints) {
                    if (constraint.userSettable()) {
                        // Only add to UI json if user-settable
                        uiconstraints.add(LaunchUiServerSwarmConstraint.create(constraint));
                    }
                }
            }
            return builder()
                    .swarmConstraints(uiconstraints)
                    .build();
        }

        public static Builder builder() {
            return new AutoValue_LaunchUi_LaunchUiServer.Builder();
        }

        @AutoValue.Builder
        public abstract static class Builder {
            public abstract Builder swarmConstraints(@Nullable List<LaunchUiServerSwarmConstraint> swarmConstraints);

            public abstract LaunchUiServer build();
        }
    }

    @AutoValue
    public static abstract class LaunchUiServerSwarmConstraint {
        //@JsonProperty("user-settable") public abstract boolean userSettable();
        @JsonProperty("attribute") public abstract String attribute();
        @JsonProperty("comparator") public abstract String comparator();
        @JsonProperty("values") public abstract List<String> values();

        public static LaunchUiServerSwarmConstraint create(DockerServerBase.DockerServerSwarmConstraint constraint) {
            return create(constraint.attribute(),
                    constraint.comparator(),
                    constraint.values());
        }

        public static LaunchUiServerSwarmConstraint create(final @Nonnull String attribute,
                                                      final @Nonnull String comparator,
                                                      final @Nonnull List<String> values) {
            return builder()
                    .attribute(attribute)
                    .comparator(comparator)
                    .values(values)
                    .build();
        }

        public static Builder builder() {
            return new AutoValue_LaunchUi_LaunchUiServerSwarmConstraint.Builder();
        }

        @AutoValue.Builder
        public abstract static class Builder {
            public abstract Builder attribute(@Nonnull String attribute);
            public abstract Builder comparator(@Nullable String comparator);
            public abstract Builder values(@Nullable List<String> values);

            public abstract LaunchUiServerSwarmConstraint build();
        }
    }

    @AutoValue
    public static abstract class LaunchUiServerConstraintSelected {
        @JsonProperty("attribute") public abstract String attribute();
        @JsonProperty("value") public abstract String value();

        @JsonCreator
        public static LaunchUiServerConstraintSelected create(@JsonProperty("attribute") final @Nonnull String attribute,
                                                              @JsonProperty("value") final @Nonnull String value) {
            return builder()
                    .attribute(attribute)
                    .value(value)
                    .build();
        }

        public static Builder builder() {
            return new AutoValue_LaunchUi_LaunchUiServerConstraintSelected.Builder();
        }

        @AutoValue.Builder
        public abstract static class Builder {
            public abstract Builder attribute(@Nonnull String attribute);
            public abstract Builder value(@Nonnull String value);

            public abstract LaunchUiServerConstraintSelected build();
        }
    }

    @AutoValue
    public static abstract class LaunchUiMeta {
        @JsonProperty("command-id") public abstract Long commandId();
        @JsonProperty("command-name") public abstract String commandName();
        @Nullable @JsonProperty("command-label") public abstract String commandLabel();
        @Nullable @JsonProperty("command-description") public abstract String commandDescription();
        @JsonProperty("wrapper-id") public abstract Long wrapperId();
        @JsonProperty("wrapper-name") public abstract String wrapperName();
        @Nullable @JsonProperty("wrapper-label") public abstract String wrapperLabel();
        @Nullable @JsonProperty("wrapper-description") public abstract String wrapperDescription();
        @JsonProperty("image-name") public abstract String imageName();
        @JsonProperty("image-type") public abstract String imageType();

        public static LaunchUiMeta create(final @Nonnull Long commandId,
                                          final @Nonnull String commandName,
                                          final @Nullable String commandLabel,
                                          final @Nullable String commandDescription,
                                          final @Nonnull Long wrapperId,
                                          final @Nonnull String wrapperName,
                                          final @Nullable String wrapperLabel,
                                          final @Nullable String wrapperDescription,
                                          final @Nonnull String imageName,
                                          final @Nonnull String imageType) {
            return builder()
                    .commandId(commandId)
                    .commandName(commandName)
                    .commandLabel(commandLabel)
                    .commandDescription(commandDescription)
                    .wrapperId(wrapperId)
                    .wrapperName(wrapperName)
                    .wrapperLabel(wrapperLabel)
                    .wrapperDescription(wrapperDescription)
                    .imageName(imageName)
                    .imageType(imageType)
                    .build();
        }

        public static LaunchUiMeta create(final @Nonnull PartiallyResolvedCommand partiallyResolvedCommand) {
            return builder()
                    .commandId(partiallyResolvedCommand.commandId())
                    .commandName(partiallyResolvedCommand.commandName())
                    .commandLabel(partiallyResolvedCommand.commandLabel())
                    .commandDescription(partiallyResolvedCommand.commandDescription())
                    .wrapperId(partiallyResolvedCommand.wrapperId())
                    .wrapperName(partiallyResolvedCommand.wrapperName())
                    .wrapperDescription(partiallyResolvedCommand.wrapperDescription())
                    .imageName(partiallyResolvedCommand.image())
                    .imageType(partiallyResolvedCommand.type())
                    .build();
        }

        public static LaunchUiMeta create(final @Nonnull Command.ConfiguredCommand configuredCommand) {
            Command.CommandWrapper wrapper = configuredCommand.wrapper();
            return builder()
                    .commandId(configuredCommand.id())
                    .commandName(configuredCommand.name())
                    .commandLabel(configuredCommand.label())
                    .commandDescription(configuredCommand.description())
                    .wrapperId(wrapper.id())
                    .wrapperName(wrapper.name())
                    .wrapperDescription(wrapper.description())
                    .imageName(configuredCommand.image())
                    .imageType(configuredCommand.type())
                    .build();
        }

        public static Builder builder() {
            return new AutoValue_LaunchUi_LaunchUiMeta.Builder();
        }

        @AutoValue.Builder
        public abstract static class Builder {
            public abstract Builder commandId(@Nonnull Long commandId);
            public abstract Builder commandName(@Nonnull String commandName);
            public abstract Builder commandLabel(@Nullable String commandLabel);
            public abstract Builder commandDescription(@Nullable String commandDescription);
            public abstract Builder wrapperId(@Nonnull Long wrapperId);
            public abstract Builder wrapperName(@Nonnull String wrapperName);
            public abstract Builder wrapperLabel(@Nullable String wrapperLabel);
            public abstract Builder wrapperDescription(@Nullable String wrapperDescription);
            public abstract Builder imageName(@Nonnull String imageName);
            public abstract Builder imageType(@Nonnull String imageType);

            public abstract LaunchUiMeta build();
        }
    }

    @AutoValue
    public static abstract class LaunchUiInputTree {
        @JsonProperty("name") public abstract String name();
        @Nullable @JsonProperty("label") public abstract String label();
        @Nullable @JsonProperty("description") public abstract String description();
        @JsonProperty("advanced") public abstract boolean advanced();
        @JsonProperty("required") public abstract boolean required();
        @JsonProperty("user-settable") public abstract boolean userSettable();
        @JsonProperty("multiple") public abstract boolean multiple();
        @JsonProperty("input-type") public abstract UiInputType uiInputType();
        @Nullable @JsonProperty("derived-value-info") public abstract String derivedValueInfo();
        @Nullable @JsonProperty("derived-value-message") public abstract String derivedValueMessage();
        @JsonProperty("select-values") public abstract List<String> selectValues();
        @JsonProperty("children") public abstract List<LaunchUiInputTree> children();

        public static LaunchUiInputTree create(final @Nonnull String name,
                                               final @Nullable String label,
                                               final @Nullable String description,
                                               final boolean advanced,
                                               final boolean required,
                                               final boolean userSettable,
                                               final boolean multiple,
                                               final @Nonnull UiInputType uiInputType,
                                               @Nullable final String derivedValueInfo,
                                               @Nullable final String derivedValueMessage,
                                               final List<String> selectValues,
                                               final @Nonnull List<LaunchUiInputTree> children) {
            return builder()
                    .name(name)
                    .label(label)
                    .description(description)
                    .advanced(advanced)
                    .required(required)
                    .userSettable(userSettable)
                    .multiple(multiple)
                    .uiInputType(uiInputType)
                    .derivedValueInfo(derivedValueInfo)
                    .derivedValueMessage(derivedValueMessage)
                    .selectValues(selectValues)
                    .children(children)
                    .build();
        }

        public static Builder builder() {
            return new AutoValue_LaunchUi_LaunchUiInputTree.Builder();
        }

        @AutoValue.Builder
        public abstract static class Builder {
            public abstract Builder name(@Nonnull String name);
            public abstract Builder label(@Nullable String label);
            public abstract Builder description(@Nullable String description);
            public abstract Builder advanced(boolean advanced);
            public abstract Builder required(boolean required);
            public abstract Builder userSettable(boolean userSettable);
            public abstract Builder multiple(boolean multiple);
            public abstract Builder uiInputType(@Nonnull UiInputType uiInputType);
            public abstract Builder derivedValueInfo(@Nullable String derivedValueInfo);
            public abstract Builder derivedValueMessage(@Nullable String derivedValueMessage);
            public abstract Builder selectValues(List<String> selectValues);
            public abstract Builder children(@Nonnull List<LaunchUiInputTree> children);


            public abstract LaunchUiInputTree build();
        }
    }

    @AutoValue
    public static abstract class LaunchUiValueTree {
        @JsonProperty("name") public abstract String name();
        @JsonProperty("values") public abstract List<LaunchUiValueTreeValueAndChildren> valueAndChildrenList();

        public static LaunchUiValueTree create(final @Nonnull String name,
                                               final @Nonnull List<LaunchUiValueTreeValueAndChildren> valueAndChildrenList) {
            return LaunchUiValueTree.builder()
                    .name(name)
                    .valueAndChildrenList(valueAndChildrenList)
                    .build();
        }

        public static LaunchUiValueTree create(final @Nonnull ResolvedInputTreeNode<? extends Input> resolvedInputTree) {
            return LaunchUiValueTree.builder()
                    .name(resolvedInputTree.input().name())
                    .valueAndChildrenListFromResolvedInputTreeValuesAndChildren(resolvedInputTree.valuesAndChildren())
                    .build();
        }

        public static Builder builder() {
            return new AutoValue_LaunchUi_LaunchUiValueTree.Builder();
        }

        @AutoValue.Builder
        public abstract static class Builder {
            public abstract Builder name(@Nonnull String name);

            public abstract Builder valueAndChildrenList(@Nonnull List<LaunchUiValueTreeValueAndChildren> valueAndChildrenList);

            public Builder valueAndChildrenListFromResolvedInputTreeValuesAndChildren(final @Nonnull List<ResolvedInputTreeValueAndChildren> resolvedInputTreeValueAndChildrenList) {
                final List<LaunchUiValueTreeValueAndChildren> valueAndChildrenList = new ArrayList<>();
                for (final ResolvedInputTreeValueAndChildren resolvedInputTreeValueAndChildren : resolvedInputTreeValueAndChildrenList) {
                    final LaunchUiValueTreeValueAndChildren launchUiValueTreeValueAndChildren = LaunchUiValueTreeValueAndChildren.create(resolvedInputTreeValueAndChildren);
                    if (launchUiValueTreeValueAndChildren != null) {
                        valueAndChildrenList.add(launchUiValueTreeValueAndChildren);
                    }
                }
                return this.valueAndChildrenList(valueAndChildrenList);
            }

            public abstract LaunchUiValueTree build();
        }
    }

    @AutoValue
    public static abstract class LaunchUiValueTreeValueAndChildren {
        @JsonProperty("value") public abstract String value();
        @JsonProperty("label") public abstract String label();
        @JsonProperty("children") public abstract List<LaunchUiValueTree> children();

        public static LaunchUiValueTreeValueAndChildren create(final @Nonnull String value,
                                                               final @Nonnull String label,
                                                               final @Nonnull List<LaunchUiValueTree> children) {
            return builder()
                    .value(value)
                    .label(label)
                    .children(children)
                    .build();
        }

        public static LaunchUiValueTreeValueAndChildren create(final @Nonnull ResolvedInputTreeValueAndChildren resolvedInputTreeValueAndChildren) {
            final ResolvedInputValue resolvedInputValue = resolvedInputTreeValueAndChildren.resolvedValue();
            final String value = resolvedInputValue.value();
            if (value == null) {
                return null;
            }
            final String valueLabel = resolvedInputValue.valueLabel();
            return builder()
                    .value(value)
                    .label(valueLabel == null ? value : valueLabel)
                    .childrenFromResolvedInputTrees(resolvedInputTreeValueAndChildren.children())
                    .build();
        }

        public static Builder builder() {
            return new AutoValue_LaunchUi_LaunchUiValueTreeValueAndChildren.Builder();
        }

        @AutoValue.Builder
        public abstract static class Builder {
            public abstract Builder value(@Nonnull String value);
            public abstract Builder label(@Nullable String label);
            public abstract Builder children(@Nonnull List<LaunchUiValueTree> children);

            public Builder childrenFromResolvedInputTrees(@Nonnull List<ResolvedInputTreeNode<? extends Input>> resolvedInputTrees) {
                final List<LaunchUiValueTree> children = new ArrayList<>();
                for (final ResolvedInputTreeNode<? extends Input> resolvedInputTree : resolvedInputTrees) {
                    if (Command.CommandInput.class.isAssignableFrom(resolvedInputTree.input().getClass())) {
                        // This is a command input which can be derived from a wrapper input.
                        // We can safely skip it.
                        continue;
                    }
                    children.add(LaunchUiValueTree.create(resolvedInputTree));
                }
                return this.children(children);
            }

            public abstract LaunchUiValueTreeValueAndChildren build();
        }
    }

    private static String getWarningString(Input input, String itemType, String modType) {
        String info = "<span class='text-error'><strong>bulk launch likely to throw exception for this " + itemType
                + "</strong></span> (and possibly others, though others may launch without issue). " +
                "<span class='text-info'>You can still launch the container in bulk: "+ itemType + "s that can be " +
                "launched will launch and those that cannot will fail</span> with an exception message, which you " +
                "can review and go from there. Alternatively, you may opt to launch containers individually " +
                "(e.g., via session page) rather than in bulk so that you can manually set this input. ";
        boolean doAppend = false;
        if (StringUtils.isNotBlank(input.matcher())) {
            info += "Or, you could try making the matcher more " + modType;
            doAppend = true;
        } else if (modType.equals("specific")) {
            info += "Or, you could try defining a JSON matcher to filter amongst these results";
            doAppend = true;
        }
        if (doAppend) {
            info += " by modifying the command.json or through site or project settings for this container. ";
        }
        return info;
    }

    private static LaunchUiInputTree convertResolvedInputTreeToLaunchUiInputTree(final @Nonnull ResolvedInputTreeNode<? extends Input> node,
                                                                                 final @Nonnull Map<String, CommandInputConfiguration> inputConfigurationMap,
                                                                                 final @Nonnull Map<String, Integer> maxInputValues,
                                                                                 final @Nonnull Map<String, Integer> minInputValues,
                                                                                 final boolean bulkLaunch) {

        final List<LaunchUiInputTree> children = new ArrayList<>();
        final Set<String> alreadyAddedChildNames = new HashSet<>();
        for (final ResolvedInputTreeValueAndChildren valueAndChildren : node.valuesAndChildren()) {
            for (final ResolvedInputTreeNode<? extends Input> child : valueAndChildren.children()) {
                if (Command.CommandInput.class.isAssignableFrom(child.input().getClass())) {
                    // This is a command input which can be derived from a wrapper input.
                    // We can safely skip it.
                    continue;
                }

                if (alreadyAddedChildNames.contains(child.input().name())) {
                    // We're flattening out the resolved input tree; we don't care about the values.
                    // Since for each value we see the children again, we've likely already seen this child.
                    // So skip it.
                    continue;
                }

                log.debug("Adding {} child {}", node.input().name(), child.input().name());

                alreadyAddedChildNames.add(child.input().name());
                children.add(convertResolvedInputTreeToLaunchUiInputTree(child, inputConfigurationMap,
                        maxInputValues, minInputValues, bulkLaunch));
            }

        }

        final Input input = node.input();
        final CommandInputConfiguration inputConfiguration =
                inputConfigurationMap.containsKey(input.name()) && inputConfigurationMap.get(input.name()) != null ?
                        inputConfigurationMap.get(input.name()) :
                        CommandInputConfiguration.builder().build();

        final Boolean requiredConfig = inputConfiguration.required();
        final boolean inputIsRequired = input.required() || (requiredConfig != null && requiredConfig);
        final Boolean settableConfig = inputConfiguration.userSettable();
        final boolean userSettable = settableConfig == null || settableConfig; // default: true
        final Boolean advancedConfig = inputConfiguration.advanced();
        final boolean advanced = advancedConfig != null && advancedConfig; // default: false
        boolean multiple = false;

        UiInputType uiInputType = null;
        String derivedValueInfo = null;
        String derivedValueMessage = null;
        List<String> selectValues = new ArrayList<>();

        if (input.type().equals(CommandInputEntity.Type.BOOLEAN.getName())) {
            // This input is a simple boolean type. Make it a switch box.
            uiInputType = UiInputType.BOOLEAN;
        } else if (Command.CommandInput.class.isAssignableFrom(input.getClass())) {
            selectValues = ((Command.CommandInput) input).selectValues();
            if (input.type().equals(CommandInputEntity.Type.SELECT.getName())) {
                uiInputType = UiInputType.SELECT;
            } else if (input.type().equals(CommandInputEntity.Type.MULTISELECT.getName())) {
                uiInputType = UiInputType.MULTISELECT;
            } else {
                // This input is a simple string or number. Make it editable.
                uiInputType = UiInputType.TEXT;
            }
        }

        if (uiInputType == null){
            // We know we have an external or derived wrapper input.
            final Integer maxNumValuesObj = maxInputValues.get(input.name());
            final int maxNumValues = maxNumValuesObj == null ? 0 : maxNumValuesObj;
            final Integer minNumValuesObj = minInputValues.get(input.name());
            final int minNumValues = minNumValuesObj == null ? 0 : minNumValuesObj;

            String itemType = "item";
            if (bulkLaunch) {
                String derivedFrom;
                if (input instanceof Command.CommandWrapperDerivedInput) {
                    StringJoiner derivedInfo = new StringJoiner(" <i class='fa fa-angle-right'></i> ");

                    Command.CommandWrapperDerivedInput derivedInput = (Command.CommandWrapperDerivedInput) input;
                    itemType = derivedInput.derivedFromWrapperInput();
                    derivedInfo.add(itemType);
                    if (CommandWrapperInputType.xnatTypeNames().contains(derivedInput.type())) {
                        derivedInfo.add(derivedInput.type());
                    }
                    if (StringUtils.isNotBlank(derivedInput.derivedFromXnatObjectProperty())) {
                        derivedInfo.add(derivedInput.derivedFromXnatObjectProperty());
                    }
                    derivedFrom = derivedInfo.toString();

                    if (StringUtils.isNotBlank(derivedInput.matcher())) {
                        derivedFrom += " via matcher]<br/><kbd>" + derivedInput.matcher() + "</kbd>";
                    } else {
                        derivedFrom += "]";
                    }
                } else {
                    itemType = input.type();
                    derivedFrom = "XNAT " + itemType + " object]";
                }
                derivedValueInfo = "<code>[Derived from " + derivedFrom + "</code>";
            }

            if (minNumValues == 0 && maxNumValues <= 1) {
                // This input has zero values somewhere in the tree and it's not a child of something for which alternative children do have values
                // We need to make it a text box so the user can enter something.
                // It is also possible we should throw an error here (though only if the input is required), because
                // usually this means something didn't get resolved properly. But right now we just continue on.
                if (bulkLaunch) {
                    derivedValueMessage = "Unable to resolve for first " + itemType + ": " +
                            getWarningString(input, itemType,"general");
                }
                uiInputType = UiInputType.TEXT;
            } else if (maxNumValues > 1) {
                // This input has more than zero values everywhere in the tree, and
                // more than one value at least somewhere in the tree.
                String values = "";
                if (bulkLaunch) {
                    values = node.valuesAndChildren()
                            .stream()
                            .map(resolvedInputTreeValueAndChildren -> resolvedInputTreeValueAndChildren.resolvedValue().valueLabel())
                            .collect(Collectors.joining("<br/>"));
                }

                if (input instanceof Command.CommandWrapperDerivedInput &&
                        ((Command.CommandWrapperDerivedInput) input).multiple()) {
                    multiple = true;
                    // If it's a multiple input, offer a multi-select for single launch; for bulk launch, display sample list
                    if (bulkLaunch) {
                        derivedValueMessage = "Values for first " + itemType + " as a sample:<br/>" + values;
                    }
                    uiInputType = UiInputType.MULTISELECT;
                } else {
                    // Otherwise, it should be a single-select menu for single launch; for bulk launch, a warning
                    if (bulkLaunch) {
                        derivedValueMessage = "Multiple possible values for first " + itemType + ": " +
                                getWarningString(input, itemType, "specific") +
                                "<br/>Values for first " + itemType + " as a sample:<br/>" + values;
                    }
                    uiInputType = UiInputType.SELECT;
                }
            } else {
                // The minimum number of values is > 0, and the max is <= 1.
                // That implies we have one and only one input value everywhere in the tree.
                // Since we already know this is a derived or external wrapper input, having
                // one value means either it was given to us (therefore don't change it)
                // or it was derived from something that was given to us (therefore don't change it).
                if (bulkLaunch) {
                    derivedValueMessage = "Value for first " + itemType + " as a sample: \"" +
                            node.valuesAndChildren().get(0).resolvedValue().valueLabel() + "\"";
                }
                uiInputType = UiInputType.STATIC;
            }
            if (bulkLaunch) {
                // It's derived, so unless it has precisely one possible value, the user can't set it in bulk
                uiInputType = UiInputType.DERIVED;
            } else if (!userSettable) {
                // The user can't set this input.
                uiInputType = UiInputType.STATIC;
            }

        }

        String label = StringUtils.defaultIfBlank(input.label(), input.name());
        return LaunchUiInputTree.builder()
                .name(input.name())
                .label(label)
                .description(input.description())
                .required(inputIsRequired)
                .userSettable(userSettable)
                .advanced(advanced)
                .multiple(multiple)
                .uiInputType(uiInputType)
                .derivedValueInfo(derivedValueInfo)
                .derivedValueMessage(derivedValueMessage)
                .selectValues(selectValues)
                .children(children)
                .build();
    }

    /**
     * Search across the entire tree of resolved values for the max number of values per input.
     * The reason we need to know this is to figure out how a particular input should be
     * represented in the UI.
     * @param node A resolved input tree node
     * @param maxNumValues A map storing the max number of values each input can have in any subtree
     * @param minNumValues A map storing the min number of values each input can have in any subtree
     */
    private static void findMaxAndMinInputValuesForTree(final @Nonnull ResolvedInputTreeNode<? extends Input> node,
                                                        final @Nonnull Map<String, Integer> maxNumValues,
                                                        final @Nonnull Map<String, Integer> minNumValues) {
        // Recursively check children
        // For each value that this input can take, how many possible values can each of its descendants take?
        // For each descendant, keep the max and min number of values it takes on any subtree.
        for (final ResolvedInputTreeValueAndChildren valueAndChildren : node.valuesAndChildren()) {
            for (final ResolvedInputTreeNode<? extends Input> child : valueAndChildren.children()) {
                findMaxAndMinInputValuesForTree(child, maxNumValues, minNumValues);
            }
        }

        // How many values does *this* input have?
        final String inputName = node.input().name();
        final int numValuesForThisInput = node.valuesAndChildren().size();

        final Integer currentMaxNumValues = maxNumValues.get(inputName);
        if (currentMaxNumValues == null || currentMaxNumValues < numValuesForThisInput) {
            maxNumValues.put(inputName, numValuesForThisInput);
        }

        final Integer currentMinNumValues = minNumValues.get(inputName);
        if (currentMaxNumValues == null || currentMinNumValues > numValuesForThisInput) {
            minNumValues.put(inputName, numValuesForThisInput);
        }
    }


    public enum UiInputType {
        TEXT("text"),
        BOOLEAN("boolean"),
        SELECT("select-one"),
        MULTISELECT("select-many"),
        STATIC("static"),
        DERIVED("derived");

        public final String name;

        @JsonCreator
        UiInputType(final String name) {
            this.name = name;
        }

        @JsonValue
        public String getName() {
            return name;
        }
    }
}
