package org.nrg.containers.model.configuration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.auto.value.AutoValue;

@AutoValue
@JsonInclude(Include.ALWAYS)
public abstract class ProjectEnabledReport {
    @JsonProperty("enabled-for-site") public abstract Boolean enabledForSite();
    @JsonProperty("enabled-for-project") public abstract Boolean enabledForProject();
    @JsonProperty("project") public abstract String project();

    @JsonCreator
    public static ProjectEnabledReport create(@JsonProperty("enabled-for-site") final Boolean enabledForSite,
                                              @JsonProperty("enabled-for-project") final Boolean enabledForProject,
                                              @JsonProperty("project") final String project) {
        return new AutoValue_ProjectEnabledReport(enabledForSite, enabledForProject, project);
    }
}
