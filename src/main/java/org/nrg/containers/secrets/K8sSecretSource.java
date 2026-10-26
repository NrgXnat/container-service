package org.nrg.containers.secrets;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.commons.lang3.StringUtils;

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
 * {@code identifier} is the logical name (e.g. {@code redcap}). {@code name-template} is optional: when the command
 * gives one it overrides the site template and follows the same rules ({@code #PROJECT#}, {@code #<input-name>#}
 * and a mandatory {@code {identifier}}); when absent the site template applies. {@code name} is never declared in
 * a command: command resolution renders the template and stores the result there, so a persisted (jsonb) secret
 * carries both the declared template and the rendered object name.
 * {@code required} defaults to true; false lets the container start without the object (see FileResolvedSecret).
 */
public class K8sSecretSource implements SecretSource {
    @JsonIgnore public static final String JSON_TYPE_NAME = "k8s-secret";
    @JsonIgnore public static final String NAME_TEMPLATE_PROPERTY = "name-template";
    @JsonIgnore public static final String RENDERED_NAME_PROPERTY = "name";

    @JsonProperty("identifier") private final String identifier;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty(NAME_TEMPLATE_PROPERTY) private final String nameTemplate;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty(RENDERED_NAME_PROPERTY) private final String secretName;
    @JsonProperty("required") private final boolean required;

    @JsonCreator
    public K8sSecretSource(@JsonProperty("identifier") final String identifier,
                           @Nullable @JsonProperty(NAME_TEMPLATE_PROPERTY) final String nameTemplate,
                           @Nullable @JsonProperty(RENDERED_NAME_PROPERTY) final String secretName,
                           @Nullable @JsonProperty("required") final Boolean required) {
        this.identifier = identifier;
        this.nameTemplate = StringUtils.trimToNull(nameTemplate);
        this.secretName = StringUtils.trimToNull(secretName);
        this.required = required == null || required;
    }

    public K8sSecretSource(final String identifier, @Nullable final String nameTemplate, @Nullable final Boolean required) {
        this(identifier, nameTemplate, null, required);
    }

    public K8sSecretSource(final String identifier, @Nullable final String nameTemplate) {
        this(identifier, nameTemplate, null, null);
    }

    public K8sSecretSource(final String identifier) {
        this(identifier, null, null, null);
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

    /** Command-level name template (may contain placeholders, must contain {identifier}); null means "use the site template". */
    @Nullable
    public String nameTemplate() {
        return nameTemplate;
    }

    /** Rendered Kubernetes Secret / SecretProviderClass name; null until command resolution has run. */
    @Nullable
    public String secretName() {
        return secretName;
    }

    public boolean required() {
        return required;
    }

    /** A copy carrying the rendered name; the declared template is kept so the record still shows where the name came from. */
    public K8sSecretSource withSecretName(final String renderedSecretName) {
        Objects.requireNonNull(renderedSecretName, "rendered secret name");
        return new K8sSecretSource(identifier, nameTemplate, renderedSecretName, required);
    }

    @Override
    @JsonIgnore
    public Map<String, String> otherProperties() {
        final Map<String, String> properties = new HashMap<>();
        if (nameTemplate != null) {
            properties.put(NAME_TEMPLATE_PROPERTY, nameTemplate);
        }
        if (secretName != null) {
            properties.put(RENDERED_NAME_PROPERTY, secretName);
        }
        if (!required) {
            properties.put("required", "false");
        }
        return Collections.unmodifiableMap(properties);
    }

    @Override
    public String toString() {
        return "K8sSecret{\"" + identifier + "\""
                + (nameTemplate == null ? "" : ", name-template=\"" + nameTemplate + "\"")
                + (secretName == null ? "" : ", name=\"" + secretName + "\"")
                + (required ? "" : ", required=false") + "}";
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final K8sSecretSource that = (K8sSecretSource) o;
        return required == that.required && Objects.equals(identifier, that.identifier)
                && Objects.equals(nameTemplate, that.nameTemplate) && Objects.equals(secretName, that.secretName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(identifier, nameTemplate, secretName, required);
    }
}
