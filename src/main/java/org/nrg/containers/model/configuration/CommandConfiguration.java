package org.nrg.containers.model.configuration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.auto.Command.CommandInput;
import org.nrg.containers.model.command.auto.Command.CommandWrapper;
import org.nrg.containers.model.command.auto.Command.CommandWrapperDerivedInput;
import org.nrg.containers.model.command.auto.Command.CommandWrapperExternalInput;
import org.nrg.containers.model.command.auto.Command.CommandWrapperOutput;
import org.nrg.containers.model.command.auto.Command.ConfiguredCommand;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

@AutoValue
public abstract class CommandConfiguration implements Serializable {
    private static final long serialVersionUID = 8170342547718966567L;

    @JsonProperty("inputs") public abstract ImmutableMap<String, CommandInputConfiguration> inputs();
    @JsonProperty("outputs") public abstract ImmutableMap<String, CommandOutputConfiguration> outputs();

    @JsonCreator
    public static CommandConfiguration create(@JsonProperty("inputs") final Map<String, CommandInputConfiguration> inputs,
                                              @JsonProperty("outputs") final Map<String, CommandOutputConfiguration> outputs) {
        return builder()
                .inputs(inputs == null ? Collections.<String, CommandInputConfiguration>emptyMap() : inputs)
                .outputs(outputs == null ? Collections.<String, CommandOutputConfiguration>emptyMap() : outputs)
                .build();
    }

    public static CommandConfiguration create(final @Nonnull Command command,
                                              final @Nonnull CommandWrapper commandWrapper,
                                              final @Nullable CommandConfigurationInternal commandConfigurationInternal) {
        Builder builder = builder();
        final Set<String> handledCommandInputs = Sets.newHashSet();

        final Map<String, CommandConfigurationInternal.CommandInputConfiguration> configuredInputs
                = commandConfigurationInternal == null ?
                        Collections.<String, CommandConfigurationInternal.CommandInputConfiguration>emptyMap() :
                        commandConfigurationInternal.inputs();
        final Map<String, CommandConfigurationInternal.CommandOutputConfiguration> configuredOutputs
                = commandConfigurationInternal == null ?
                        Collections.<String, CommandConfigurationInternal.CommandOutputConfiguration>emptyMap() :
                        commandConfigurationInternal.outputs();

        for (final Command.CommandWrapperExternalInput externalInput : commandWrapper.externalInputs()) {
            builder = builder.addInput(externalInput.name(),
                    CommandInputConfiguration.create(externalInput, configuredInputs.get(externalInput.name())));
            handledCommandInputs.add(externalInput.providesValueForCommandInput());
        }
        for (final Command.CommandWrapperDerivedInput derivedInput : commandWrapper.derivedInputs()) {
            builder = builder.addInput(derivedInput.name(),
                    CommandInputConfiguration.create(derivedInput, configuredInputs.get(derivedInput.name())));
            handledCommandInputs.add(derivedInput.providesValueForCommandInput());
        }
        for (final Command.CommandWrapperOutput wrapperOutput : commandWrapper.outputHandlers()) {
            builder = builder.addOutput(wrapperOutput.name(),
                    CommandOutputConfiguration.create(wrapperOutput, configuredOutputs.get(wrapperOutput.name())));
        }

        for (final Command.CommandInput commandInput : command.inputs()) {
            if (!handledCommandInputs.contains(commandInput.name())) {
                builder = builder.addInput(commandInput.name(),
                        CommandInputConfiguration.create(commandInput, configuredInputs.get(commandInput.name())));
            }
        }

        return builder.build();
    }

    public static Builder builder() {
        return new AutoValue_CommandConfiguration.Builder();
    }

    @AutoValue.Builder
    public abstract static class Builder {
        public abstract Builder inputs(@Nonnull Map<String, CommandInputConfiguration> inputs);
        abstract ImmutableMap.Builder<String, CommandInputConfiguration> inputsBuilder();
        public Builder addInput(final @Nonnull String inputName, final CommandInputConfiguration commandInputConfiguration) {
            inputsBuilder().put(inputName, commandInputConfiguration);
            return this;
        }

        public abstract Builder outputs(@Nonnull Map<String, CommandOutputConfiguration> outputs);
        abstract ImmutableMap.Builder<String, CommandOutputConfiguration> outputsBuilder();
        public Builder addOutput(final @Nonnull String outputName, final CommandOutputConfiguration commandOutputConfiguration) {
            outputsBuilder().put(outputName, commandOutputConfiguration);
            return this;
        }

        public abstract CommandConfiguration build();
    }

    @AutoValue
    @JsonInclude(Include.ALWAYS)
    public static abstract class CommandInputConfiguration implements Serializable {
        private static final long serialVersionUID = 4213914303065089464L;

        @Nullable @JsonProperty("description") public abstract String description();
        @Nullable @JsonProperty("type") public abstract String type();
        @Nullable @JsonProperty("default-value") public abstract String defaultValue();
        @Nullable @JsonProperty("matcher") public abstract String matcher();
        @Nullable @JsonProperty("user-settable") public abstract Boolean userSettable();
        @Nullable @JsonProperty("advanced") public abstract Boolean advanced();
        @Nullable @JsonProperty("required") public abstract Boolean required();

        @JsonCreator
        static CommandInputConfiguration create(@JsonProperty("description") final String description,
                                                @JsonProperty("type") final String type,
                                                @JsonProperty("default-value") final String defaultValue,
                                                @JsonProperty("matcher") final String matcher,
                                                @JsonProperty("user-settable") final Boolean userSettable,
                                                @JsonProperty("advanced") final Boolean advanced,
                                                @JsonProperty("required") final Boolean required) {
            return builder()
                    .description(description)
                    .type(type)
                    .defaultValue(defaultValue)
                    .matcher(matcher)
                    .userSettable(userSettable)
                    .advanced(advanced)
                    .required(required)
                    .build();
        }

        static CommandInputConfiguration create(final Command.CommandInput commandInput,
                                                final CommandConfigurationInternal.CommandInputConfiguration commandInputConfiguration) {
            final Builder builder = builder()
                    .description(commandInput.description())
                    .type(commandInput.type())
                    .defaultValue(commandInput.defaultValue())
                    .matcher(commandInput.matcher())
                    .required(commandInput.required());

            if (commandInputConfiguration != null) {
                // Set those things that the command input does not have, even if they are null
                builder.userSettable(commandInputConfiguration.userSettable())
                        .advanced(commandInputConfiguration.advanced());

                // Override those things the command input does have only if they are not null
                if (commandInputConfiguration.defaultValue() != null) {
                    builder.defaultValue(commandInputConfiguration.defaultValue());
                }
                if (commandInputConfiguration.matcher() != null) {
                    builder.matcher(commandInputConfiguration.matcher());
                }
            }

            return builder.build();
        }

        static CommandInputConfiguration create(final Command.CommandWrapperInput commandWrapperInput,
                                                final CommandConfigurationInternal.CommandInputConfiguration commandInputConfiguration) {
            final Builder builder = builder()
                    .description(commandWrapperInput.description())
                    .type(commandWrapperInput.type())
                    .defaultValue(commandWrapperInput.defaultValue())
                    .matcher(commandWrapperInput.matcher())
                    .userSettable(commandWrapperInput.userSettable());

            if (commandInputConfiguration != null) {
                // Set those things that the command wrapper input does not have, even if they are null
                builder.advanced(commandInputConfiguration.advanced());

                // Override those things the command wrapper input does have only if they are not null
                if (commandInputConfiguration.defaultValue() != null) {
                    builder.defaultValue(commandInputConfiguration.defaultValue());
                }
                if (commandInputConfiguration.matcher() != null) {
                    builder.matcher(commandInputConfiguration.matcher());
                }
                if (commandInputConfiguration.userSettable() != null) {
                    builder.userSettable(commandInputConfiguration.userSettable());
                }
            }

            return builder.build();
        }

        public static Builder builder() {
            return new AutoValue_CommandConfiguration_CommandInputConfiguration.Builder()
                    .userSettable(true)
                    .advanced(false);
        }

        @AutoValue.Builder
        public abstract static class Builder {
            public abstract Builder description(String description);
            public abstract Builder type(String type);
            public abstract Builder defaultValue(final String defaultValue);
            public abstract Builder matcher(final String matcher);
            public abstract Builder userSettable(final Boolean userSettable);
            public abstract Builder advanced(final Boolean advanced);
            public abstract Builder required(final Boolean required);

            public abstract CommandInputConfiguration build();
        }
    }

    @AutoValue
    @JsonInclude(Include.ALWAYS)
    public static abstract class CommandOutputConfiguration implements Serializable {
        private static final long serialVersionUID = 7264552512492029282L;

        @Nullable @JsonProperty("type") public abstract String type();
        @Nullable @JsonProperty("label") public abstract String label();

        @JsonCreator
        public static CommandOutputConfiguration create(@JsonProperty("type") final String type,
                                                        @JsonProperty("label") final String label) {
            return builder()
                    .type(type)
                    .label(label)
                    .build();
        }

        static CommandOutputConfiguration create(final Command.CommandWrapperOutput commandWrapperOutput,
                                                 final CommandConfigurationInternal.CommandOutputConfiguration commandOutputConfiguration) {
            final Builder builder = builder()
                    .type(commandWrapperOutput.type())
                    .label(commandWrapperOutput.label());

            if (commandOutputConfiguration != null) {
                if (commandOutputConfiguration.label() != null) {
                    builder.label(commandOutputConfiguration.label());
                }
            }

            return builder.build();
        }

        public static Builder builder() {
            return new AutoValue_CommandConfiguration_CommandOutputConfiguration.Builder();
        }

        @AutoValue.Builder
        public static abstract class Builder {
            public abstract Builder type(String type);
            public abstract Builder label(String label);

            public abstract CommandOutputConfiguration build();
        }
    }

    @Nonnull
    public ConfiguredCommand apply(final Command commandWithOneWrapper) {
        // Initialize the command builder copy
        final ConfiguredCommand.Builder commandBuilder = ConfiguredCommand.initialize(commandWithOneWrapper);

        // Things we need to apply configuration to:
        // command inputs
        // wrapper inputs
        // wrapper outputs

        for (final CommandInput commandInput : commandWithOneWrapper.inputs()) {
            commandBuilder.addInput(
                    this.inputs().containsKey(commandInput.name()) ?
                            commandInput.applyConfiguration(this.inputs().get(commandInput.name())) :
                            commandInput
            );
        }

        final CommandWrapper originalCommandWrapper = commandWithOneWrapper.xnatCommandWrappers().get(0);
        final CommandWrapper.Builder commandWrapperBuilder = CommandWrapper.builder()
                .id(originalCommandWrapper.id())
                .name(originalCommandWrapper.name())
                .description(originalCommandWrapper.description())
                .contexts(originalCommandWrapper.contexts());

        for (final CommandWrapperExternalInput externalInput : originalCommandWrapper.externalInputs()) {
            commandWrapperBuilder.addExternalInput(
                    this.inputs().containsKey(externalInput.name()) ?
                            externalInput.applyConfiguration(this.inputs().get(externalInput.name())) :
                            externalInput
            );
        }

        for (final CommandWrapperDerivedInput derivedInput : originalCommandWrapper.derivedInputs()) {
            commandWrapperBuilder.addDerivedInput(
                    this.inputs().containsKey(derivedInput.name()) ?
                            derivedInput.applyConfiguration(this.inputs().get(derivedInput.name())) :
                            derivedInput
            );
        }

        for (final CommandWrapperOutput output : originalCommandWrapper.outputHandlers()) {
            commandWrapperBuilder.addOutputHandler(
                    this.outputs().containsKey(output.name()) ?
                            output.applyConfiguration(this.outputs().get(output.name())) :
                            output
            );
        }

        return commandBuilder.wrapper(commandWrapperBuilder.build()).build();
    }
}
