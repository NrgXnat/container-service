package org.nrg.containers.model.command.auto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;

import javax.annotation.Nullable;
import java.io.Serializable;

/**
 * This is a value class that will be returned when the UI requests
 * a list of commands that are available to be launched for a given context.
 */
@AutoValue
public abstract class CommandSummaryForContext implements Serializable {
    private static final long serialVersionUID = -1096965712427376973L;

    @JsonProperty("command-id") public abstract long commandId();
    @JsonProperty("command-name") public abstract String commandName();
    @Nullable @JsonProperty("command-label") public abstract String commandLabel();
    @Nullable @JsonProperty("command-description") public abstract String commandDescription();
    @JsonProperty("wrapper-id") public abstract long wrapperId();
    @JsonProperty("wrapper-name") public abstract String wrapperName();
    @Nullable @JsonProperty("wrapper-label") public abstract String wrapperLabel();
    @Nullable @JsonProperty("wrapper-description") public abstract String wrapperDescription();
    @JsonProperty("wrapper-contexts") public abstract ImmutableSet<String> wrapperContexts();
    @JsonProperty("image-name") public abstract String imageName();
    @JsonProperty("image-type") public abstract String imageType();
    @JsonProperty("enabled") public abstract boolean enabled();
    @JsonProperty("root-element-name") public abstract String externalInputName();

    @JsonCreator
    @VisibleForTesting
    public static CommandSummaryForContext create(@JsonProperty("command-id") final long commandId,
                                                  @JsonProperty("command-name") final String commandName,
                                                  @JsonProperty("command-label") final String commandLabel,
                                                  @JsonProperty("command-description") final String commandDescription,
                                                  @JsonProperty("wrapper-id") final long wrapperId,
                                                  @JsonProperty("wrapper-name") final String wrapperName,
                                                  @JsonProperty("wrapper-label") final String wrapperLabel,
                                                  @JsonProperty("wrapper-description") final String wrapperDescription,
                                                  @JsonProperty("wrapper-contexts") final ImmutableSet<String> wrapperContexts,
                                                  @JsonProperty("image-name") final String imageName,
                                                  @JsonProperty("image-type") final String imageType,
                                                  @JsonProperty("enabled") final boolean enabled,
                                                  @JsonProperty("root-element-name") final String externalInputName) {
        return new AutoValue_CommandSummaryForContext(
                commandId,
                commandName,
                commandLabel,
                commandDescription,
                wrapperId,
                wrapperName,
                wrapperLabel,
                wrapperDescription,
                wrapperContexts,
                imageName,
                imageType,
                enabled,
                externalInputName
        );
    }

    public static CommandSummaryForContext create(final Command command,
                                                  final Command.CommandWrapper wrapper,
                                                  final boolean enabled,
                                                  final String externalInputName) {
        return create(
                command.id(),
                command.name(),
                command.label(),
                command.description(),
                wrapper.id(),
                wrapper.name(),
                wrapper.label(),
                wrapper.description(),
                wrapper.contexts(),
                command.image(),
                command.type(),
                enabled,
                externalInputName
        );
    }
}
