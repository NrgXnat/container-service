package org.nrg.containers.model.image.docker;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.entity.CommandEntity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@AutoValue
public abstract class DockerImageAndCommandSummary implements Serializable {
    private static final long serialVersionUID = 6496872347287750335L;

    @Nullable @JsonProperty("image-id") public abstract String imageId();
    @Nullable @JsonProperty("server") public abstract String server();
    @JsonProperty("names") public abstract ImmutableSet<String> imageNames();
    @JsonProperty("commands") public abstract ImmutableList<Command> commands();

    @JsonCreator
    public static DockerImageAndCommandSummary create(@JsonProperty("image-id") final String imageId,
                                                      @JsonProperty("server") final String server,
                                                      @JsonProperty("names") final Set<String> imageNames,
                                                      @JsonProperty("commands") final List<Command> commands) {
        return builder()
                .imageId(imageId)
                .server(server)
                .imageNames(imageNames == null ? Collections.<String>emptySet() : imageNames)
                .commands(commands == null ? Collections.<Command>emptyList() : commands)
                .build();
    }

    public static Builder builder() {
        return new AutoValue_DockerImageAndCommandSummary.Builder();
    }

    @AutoValue.Builder
    public abstract static class Builder {
        public abstract Builder server(String server);

        public abstract Builder imageId(String imageId);

        public abstract Builder imageNames(@Nonnull Set<String> imageNames);
        abstract ImmutableSet.Builder<String> imageNamesBuilder();
        public Builder addImageName(final String imageName) {
            if (StringUtils.isNotBlank(imageName)) {
                imageNamesBuilder().add(imageName);
            }
            return this;
        }
        public Builder imageNames(@Nonnull List<String> imageNames) {
            return this.imageNames(new HashSet<>(imageNames));
        }

        public Builder addDockerImage(final @Nonnull DockerImage dockerImage) {
            return this.imageId(dockerImage.imageId())
                    .imageNames(dockerImage.tags());
        }

        public abstract Builder commands(@Nonnull List<Command> commands);
        abstract ImmutableList.Builder<Command> commandsBuilder();
        public Builder addCommand(final @Nonnull Command command) {
            commandsBuilder().add(command);
            return addImageName(command.image());
        }
        public Builder addCommand(final @Nonnull CommandEntity commandEntity) {
            return addCommand(Command.create(commandEntity));
        }

        public abstract DockerImageAndCommandSummary build();
    }
}
