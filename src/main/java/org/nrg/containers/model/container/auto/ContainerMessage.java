package org.nrg.containers.model.container.auto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.auto.value.AutoValue;

import javax.annotation.Nullable;
import java.io.Serializable;

@AutoValue
public abstract class ContainerMessage implements Serializable {
    private static final long serialVersionUID = 3977595045080393805L;

    @JsonProperty("id") public abstract String id();
    @Nullable @JsonProperty("status") public abstract String status();

    @JsonCreator
    public static ContainerMessage create(@JsonProperty("id") final String id,
                                          @JsonProperty("status") final String status) {
        return new AutoValue_ContainerMessage(id, status);
    }
}
