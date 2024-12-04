package org.nrg.containers.model.configuration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.auto.value.AutoValue;

import java.io.Serializable;

@AutoValue
public abstract class ProjectEnabledReport implements Serializable {
    private static final long serialVersionUID = -6360752238770532920L;

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
