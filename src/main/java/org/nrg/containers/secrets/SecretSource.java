package org.nrg.containers.secrets;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Map;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        // Add new source classes here as they are created
        @JsonSubTypes.Type(value = SystemPropertySecretSource.class, name = SystemPropertySecretSource.JSON_TYPE_NAME)
})
public interface SecretSource {
    String type();
    String identifier();
    Map<String, String> otherProperties();

    interface ValueObtainingSecretSource extends SecretSource {}

    /**
     * Used as default value in {@link ResolverFor} annotation
     */
    abstract class AnySource implements SecretSource {}
}
