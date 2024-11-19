package org.nrg.containers.model.image.docker;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.swagger.annotations.ApiModel;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@AutoValue
@ApiModel(description = "Properties that define an image.")
public abstract class DockerImage {

    @Nullable @JsonProperty("image-id") public abstract String imageId();
    @JsonProperty("tags") public abstract ImmutableList<String> tags();
    @JsonProperty("labels") public abstract ImmutableMap<String, String> labels();

    @JsonCreator
    public static DockerImage create(@JsonProperty("image-id") final String imageId,
                                     @JsonProperty("tags") final List<String> repoTags,
                                     @JsonProperty("labels") final Map<String, String> labels) {
        return builder()
                .imageId(imageId)
                .tags(repoTags == null ? Collections.<String>emptyList() : repoTags)
                .labels(labels == null ? Collections.<String, String>emptyMap() : labels)
                .build();
    }

    public static Builder builder() {
        return new AutoValue_DockerImage.Builder();
    }

    @AutoValue.Builder
    public static abstract class Builder {
        public abstract Builder imageId(String imageId);

        public abstract Builder tags(@Nonnull List<String> tags);
        abstract ImmutableList.Builder<String> tagsBuilder();
        public Builder addTag(final String tag) {
            if (StringUtils.isNotBlank(tag)) {
                tagsBuilder().add(tag);
            }
            return this;
        }

        public abstract Builder labels(@Nonnull Map<String, String> labels);
        abstract ImmutableMap.Builder<String, String> labelsBuilder();
        public Builder addLabel(final String name, final String value) {
            if (StringUtils.isNotBlank(name)) {
                labelsBuilder().put(name, value);
            }
            return this;
        }

        public abstract DockerImage build();
    }

    @Override
    public String toString() {
        return "{\"imageId\": \"" + imageId() + "\", " +
               " \"tags\": [\"" + String.join("\", \"", tags()) + "\"], " +
               " \"labels\": [\"" + labels().entrySet().stream().map(entry -> "\"" + entry.getKey() + "\": \"" + entry.getValue() + "\"").collect(Collectors.joining(", ")) + "\"]}";
    }
}
