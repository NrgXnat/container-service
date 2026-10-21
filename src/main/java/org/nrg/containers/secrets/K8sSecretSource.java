package org.nrg.containers.secrets;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A secret that already exists in the cluster as a Kubernetes Secret or SecretProviderClass.
 * <p>
 * XNAT never reads the value: the Kubernetes backend references the object by name in the Pod spec and
 * kubelet mounts it. This is therefore <em>not</em> a {@link ValueObtainingSecretSource}.
 * <p>
 * {@code identifier} is the logical name (e.g. {@code redcap}). {@code name} is optional: when the command
 * gives one it may contain the same placeholders as the site template ({@code #PROJECT#}, {@code #<input-name>#},
 * {@code {identifier}}); when absent the site template applies. Command resolution renders the final name and
 * writes it back into {@code name}, so a persisted (jsonb) secret always carries the rendered name.
 * {@code required} defaults to true; false lets the container start without the object (see FileResolvedSecret).
 */
public class K8sSecretSource implements SecretSource {
    @JsonIgnore public static final String JSON_TYPE_NAME = "k8s-secret";

    @JsonProperty("identifier") private final String identifier;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("name") private final String secretName;
    @JsonProperty("required") private final boolean required;

    @JsonCreator
    public K8sSecretSource(@JsonProperty("identifier") final String identifier,
                           @Nullable @JsonProperty("name") final String secretName,
                           @Nullable @JsonProperty("required") final Boolean required) {
        this.identifier = identifier;
        this.secretName = secretName;
        this.required = required == null || required;
    }

    public K8sSecretSource(final String identifier, @Nullable final String secretName) {
        this(identifier, secretName, null);
    }

    public K8sSecretSource(final String identifier) {
        this(identifier, null, null);
    }

    @Override
    @JsonIgnore
    public String type() {
        return JSON_TYPE_NAME;
    }

    @Override
    @JsonIgnore
    public String identifier() {
        return identifier;
    }

    /** Name as declared in the command (may contain placeholders), or the rendered name once resolution has run; null means "use the template". */
    @Nullable
    public String secretName() {
        return secretName;
    }

    public boolean required() {
        return required;
    }

    @Override
    @JsonIgnore
    public Map<String, String> otherProperties() {
        final Map<String, String> properties = new HashMap<>();
        if (secretName != null) {
            properties.put("name", secretName);
        }
        if (!required) {
            properties.put("required", "false");
        }
        return Collections.unmodifiableMap(properties);
    }

    @Override
    public String toString() {
        return "K8sSecret{\"" + identifier + "\"" + (secretName == null ? "" : ", name=\"" + secretName + "\"")
                + (required ? "" : ", required=false") + "}";
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final K8sSecretSource that = (K8sSecretSource) o;
        return required == that.required && Objects.equals(identifier, that.identifier) && Objects.equals(secretName, that.secretName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(identifier, secretName, required);
    }
}
