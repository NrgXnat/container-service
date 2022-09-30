package org.nrg.containers.secrets;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.SneakyThrows;

import java.util.Map;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = EnvironmentVariableSecretDestination.class, name = EnvironmentVariableSecretDestination.JSON_TYPE_NAME)
})
public interface SecretDestination {
    String type();
    String identifier();
    Map<String, String> otherProperties();
}
