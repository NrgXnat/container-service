package org.nrg.containers.model.configuration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Collections;
import java.util.Map;

@AutoValue
public abstract class CommandConfigurationInternal implements Serializable {
    private static final long serialVersionUID = -3178906512250243619L;

    @Nullable @JsonProperty("enabled") public abstract Boolean enabled();
    @JsonProperty("inputs") abstract ImmutableMap<String, CommandInputConfiguration> inputs();
    @JsonProperty("outputs") abstract ImmutableMap<String, CommandOutputConfiguration> outputs();

    @JsonCreator
    public static CommandConfigurationInternal create(@JsonProperty("enabled") final Boolean enabled,
                                                      @JsonProperty("inputs") final Map<String, CommandInputConfiguration> inputs,
                                                      @JsonProperty("outputs") final Map<String, CommandOutputConfiguration> outputs) {
        return builder()
                .enabled(enabled)
                .inputs(inputs == null ? Collections.<String, CommandInputConfiguration>emptyMap() : inputs)
                .outputs(outputs == null ? Collections.<String, CommandOutputConfiguration>emptyMap() : outputs)
                .build();
    }

    public static CommandConfigurationInternal create(final Boolean enabled,
                                                      final CommandConfiguration configuration) {
        final Builder builder = builder().enabled(enabled);

        if (configuration != null) {
            for (final Map.Entry<String, CommandConfiguration.CommandInputConfiguration> inputEntry : configuration.inputs().entrySet()) {
                builder.addInput(inputEntry.getKey(), inputEntry.getValue());
            }
            for (final Map.Entry<String, CommandConfiguration.CommandOutputConfiguration> outputEntry : configuration.outputs().entrySet()) {
                builder.addOutput(outputEntry.getKey(), outputEntry.getValue());
            }
        }
        return builder.build();
    }

    public static Builder builder() {
        return new AutoValue_CommandConfigurationInternal.Builder();
    }

    public abstract Builder toBuilder();

    public CommandConfigurationInternal merge(final CommandConfigurationInternal overlay, final boolean enabled) {
        if (overlay == null) {
            return this;
        }

        final Map<String, CommandInputConfiguration> mergedInputs = Maps.newHashMap(this.inputs());
        for (final Map.Entry<String, CommandInputConfiguration> otherInput : overlay.inputs().entrySet()) {
            final CommandInputConfiguration thisInputValue = this.inputs().get(otherInput.getKey());
            mergedInputs.put(otherInput.getKey(),
                    thisInputValue == null ? otherInput.getValue() : thisInputValue.merge(otherInput.getValue()));
        }

        final Map<String, CommandOutputConfiguration> mergedOutputs = Maps.newHashMap(this.outputs());
        for (final Map.Entry<String, CommandOutputConfiguration> otherOutput : overlay.outputs().entrySet()) {
            final CommandOutputConfiguration thisOutputValue = this.outputs().get(otherOutput.getKey());
            mergedOutputs.put(otherOutput.getKey(),
                    thisOutputValue == null ? otherOutput.getValue() : thisOutputValue.merge(otherOutput.getValue()));
        }

        return builder()
                .enabled(enabled)
                .inputs(mergedInputs)
                .outputs(mergedOutputs)
                .build();
    }

    @AutoValue.Builder
    public abstract static class Builder {
        public abstract Builder enabled(Boolean enabled);

        public abstract Builder inputs(@Nonnull Map<String, CommandInputConfiguration> inputs);
        abstract ImmutableMap.Builder<String, CommandInputConfiguration> inputsBuilder();
        public Builder addInput(final @Nonnull String inputName, final CommandInputConfiguration commandInputConfiguration) {
            inputsBuilder().put(inputName, commandInputConfiguration);
            return this;
        }
        public Builder addInput(final @Nonnull String inputName, final CommandConfiguration.CommandInputConfiguration commandInputConfiguration) {
            inputsBuilder().put(inputName, CommandInputConfiguration.create(commandInputConfiguration));
            return this;
        }

        public abstract Builder outputs(@Nonnull Map<String, CommandOutputConfiguration> outputs);
        abstract ImmutableMap.Builder<String, CommandOutputConfiguration> outputsBuilder();
        public Builder addOutput(final @Nonnull String outputName, final CommandOutputConfiguration commandOutputConfiguration) {
            outputsBuilder().put(outputName, commandOutputConfiguration);
            return this;
        }
        public Builder addOutput(final @Nonnull String outputName, final CommandConfiguration.CommandOutputConfiguration commandOutputConfiguration) {
            outputsBuilder().put(outputName, CommandOutputConfiguration.create(commandOutputConfiguration));
            return this;
        }

        public abstract CommandConfigurationInternal build();
    }

    @AutoValue
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static abstract class CommandInputConfiguration implements Serializable {
        private static final long serialVersionUID = -3031457962998071158L;

        @Nullable @JsonProperty("default-value") public abstract String defaultValue();
        @Nullable @JsonProperty("matcher") public abstract String matcher();
        @Nullable @JsonProperty("user-settable") public abstract Boolean userSettable();
        @Nullable @JsonProperty("advanced") public abstract Boolean advanced();

        @JsonCreator
        static CommandInputConfiguration create(@JsonProperty("default-value") final String defaultValue,
                                                @JsonProperty("matcher") final String matcher,
                                                @JsonProperty("user-settable") final Boolean userSettable,
                                                @JsonProperty("advanced") final Boolean advanced) {
            return builder()
                    .defaultValue(defaultValue)
                    .matcher(matcher)
                    .userSettable(userSettable)
                    .advanced(advanced)
                    .build();
        }

        static CommandInputConfiguration create(final CommandConfiguration.CommandInputConfiguration commandInputConfiguration) {
            return builder()
                    .defaultValue(commandInputConfiguration.defaultValue())
                    .matcher(commandInputConfiguration.matcher())
                    .userSettable(commandInputConfiguration.userSettable())
                    .advanced(commandInputConfiguration.advanced())
                    .build();
        }

        public static CommandInputConfiguration.Builder builder() {
            return new AutoValue_CommandConfigurationInternal_CommandInputConfiguration.Builder();
        }

        CommandInputConfiguration merge(final CommandInputConfiguration that) {
            if (that == null) {
                return this;
            }
            return create(that.defaultValue() == null ? this.defaultValue() : that.defaultValue(),
                    that.matcher() == null ? this.matcher() : that.matcher(),
                    that.userSettable() == null ? this.userSettable() : that.userSettable(),
                    that.advanced() == null ? this.advanced() : that.advanced());
        }

        @AutoValue.Builder
        public abstract static class Builder {
            public abstract Builder defaultValue(final String defaultValue);
            public abstract Builder matcher(final String matcher);
            public abstract Builder userSettable(final Boolean userSettable);
            public abstract Builder advanced(final Boolean advanced);

            public abstract CommandInputConfiguration build();
        }
    }

    @AutoValue
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static abstract class CommandOutputConfiguration implements Serializable {
        private static final long serialVersionUID = -4905537837345380901L;

        @Nullable @JsonProperty("label") public abstract String label();

        @JsonCreator
        public static CommandOutputConfiguration create(@JsonProperty("label") final String label) {
            return new AutoValue_CommandConfigurationInternal_CommandOutputConfiguration(label);
        }

        static CommandOutputConfiguration create(final CommandConfiguration.CommandOutputConfiguration commandOutputConfiguration) {
            return create(commandOutputConfiguration.label());
        }

        CommandOutputConfiguration merge(final CommandOutputConfiguration that) {
            if (that == null) {
                return this;
            }
            return create(that.label() == null ? this.label() : that.label());
        }
    }
}
