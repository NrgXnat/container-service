package org.nrg.containers.model.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.auto.value.AutoValue;

import javax.annotation.Nullable;
import java.io.Serializable;

@AutoValue
public abstract class PluginVersionCheck implements Serializable {
    private static final long serialVersionUID = 270432226185468124L;

    @JsonProperty("compatible") public abstract Boolean compatible();
    @Nullable @JsonProperty("xnat-version-detected") public abstract String xnatVersionDetected();
    @Nullable @JsonProperty("min-xnat-version-required") public abstract String xnatVersionRequired();
    @Nullable @JsonProperty("message") public abstract String message();

    public static Builder builder() {return new AutoValue_PluginVersionCheck.Builder();}

    @AutoValue.Builder
    public abstract static class Builder {
        public abstract Builder compatible(Boolean compatible);

        public abstract Builder xnatVersionDetected(String xnatVersionDetected);

        public abstract Builder xnatVersionRequired(String xnatVersionRequired);

        public abstract Builder message(String message);

        public abstract PluginVersionCheck build();
    }
}
