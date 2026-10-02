package org.nrg.containers.secrets;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * A secret that already exists as a Kubernetes Secret object in the cluster.
 * <p>
 * XNAT never reads the value. The Kubernetes backend references the Secret by name in the
 * Pod spec and kubelet mounts it. This is therefore <em>not</em> a {@link ValueObtainingSecretSource}.
 * <p>
 * The {@code identifier} is a logical name (e.g. {@code redcap}); the real Secret name is derived
 * from it at launch time by the site-level name template, unless {@code name} is given explicitly.
 */
public class K8sSecretSource implements SecretSource {
    @JsonIgnore public static final String JSON_TYPE_NAME = "k8s-secret";

    @JsonProperty("identifier") private final String identifier;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("name") private final String secretName;

    @JsonCreator
    public K8sSecretSource(@JsonProperty("identifier") final String identifier,
                           @Nullable @JsonProperty("name") final String secretName) {
        this.identifier = identifier;
        this.secretName = secretName;
    }

    public K8sSecretSource(final String identifier) {
        this(identifier, null);
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

    /**
     * Explicit Kubernetes Secret name overriding the site template, or null to use the template.
     */
    @Nullable
    public String secretName() {
        return secretName;
    }

    @Override
    @JsonIgnore
    public Map<String, String> otherProperties() {
        return secretName == null ? Collections.emptyMap() : Collections.singletonMap("name", secretName);
    }

    @Override
    public String toString() {
        return "K8sSecret{\"" + identifier + "\"" + (secretName == null ? "" : ", name=\"" + secretName + "\"") + "}";
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final K8sSecretSource that = (K8sSecretSource) o;
        return Objects.equals(identifier, that.identifier) && Objects.equals(secretName, that.secretName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(identifier, secretName);
    }
}
