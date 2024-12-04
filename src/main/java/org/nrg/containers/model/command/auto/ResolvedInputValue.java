package org.nrg.containers.model.command.auto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.auto.value.AutoValue;
import org.nrg.containers.model.command.entity.CommandInputEntity;
import org.nrg.containers.model.xnat.XnatModelObject;

import javax.annotation.Nullable;
import java.io.Serializable;

@AutoValue
public abstract class ResolvedInputValue implements Serializable {
    private static final long serialVersionUID = 3736382357316063998L;

    @JsonProperty("type") public abstract String type();
    @Nullable @JsonProperty("value") public abstract String value();
    @Nullable @JsonProperty("value-label") public abstract String valueLabel();
    @JsonIgnore @Nullable public abstract XnatModelObject xnatModelObject();
    @Nullable @JsonProperty("json-value") public abstract String jsonValue();

    @JsonCreator
    public static ResolvedInputValue create(@JsonProperty("type") final String type,
                                            @JsonProperty("value") final String value,
                                            @JsonProperty("value-label") final String valueLabel,
                                            @JsonProperty("json-value") final String jsonValue) {
        return builder()
                .type(type)
                .value(value)
                .valueLabel(valueLabel)
                .jsonValue(jsonValue == null ? value : jsonValue)
                .build();
    }

    public static Builder builder() {
        return new AutoValue_ResolvedInputValue.Builder()
                .type(CommandInputEntity.DEFAULT_TYPE.getName());
    }

    @AutoValue.Builder
    public static abstract class Builder {
        public abstract Builder type(String type);
        public abstract Builder value(String value);
        public abstract Builder valueLabel(String valueLabel);
        public abstract Builder xnatModelObject(XnatModelObject xnatModelObject);
        public abstract Builder jsonValue(String jsonValue);

        public abstract ResolvedInputValue build();
    }
}
