package org.nrg.containers.secrets;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * A resolved {@link K8sSecretSource} → {@link FileSecretDestination} secret.
 * <p>
 * Carries no value: XNAT never obtains it. Command resolution renders {@link #secretName()} (site template or
 * command-level name plus placeholders) and writes it back into the source, so the Kubernetes backend only has
 * to turn this into a volume named {@link #volumeName()} plus a read-only mount at {@link #mountPath()}.
 */
@ResolverFor(source = K8sSecretSource.class, destination = FileSecretDestination.class)
public class FileResolvedSecret extends ResolvedSecret {
    static final String VOLUME_NAME_PREFIX = "secret-";

    @SuppressWarnings("unused")  // Reflectively called by ContainerSecretServiceImpl
    public FileResolvedSecret(final Secret secret,
                              @SuppressWarnings("OptionalUsedAsFieldOrParameterType") final Optional<String> ignoredValue) {
        super(secret);
    }

    public FileResolvedSecret(final Secret secret) {
        super(secret);
    }

    /** Logical name declared in the command, e.g. {@code redcap}. */
    public String identifier() {
        return source().identifier();
    }

    /** Rendered Kubernetes Secret / SecretProviderClass name, or the unrendered command value (or null) before resolution. */
    @Nullable
    public String secretName() {
        return k8sSource().secretName();
    }

    public boolean required() {
        return k8sSource().required();
    }

    /** Directory inside the container where the secret's keys are mounted as files. */
    public String mountPath() {
        return ((FileSecretDestination) destination()).mountPath();
    }

    /** Pod volume name, derived from the identifier; Kubernetes requires it to be unique within the Pod. */
    public String volumeName() {
        return K8sSecretNameTemplate.sanitize(VOLUME_NAME_PREFIX + identifier());
    }

    /** A copy whose source carries the rendered name, so it survives the jsonb round trip. */
    public FileResolvedSecret withSecretName(final String renderedSecretName) {
        return new FileResolvedSecret(new Secret(
                new K8sSecretSource(identifier(), renderedSecretName, required()), destination()));
    }

    private K8sSecretSource k8sSource() {
        return (K8sSecretSource) source();
    }

    @Override
    public String toString() {
        return "FileResolvedSecret{identifier=\"" + identifier() + "\", secretName=\"" + secretName()
                + "\", mountPath=\"" + mountPath() + "\", required=" + required() + "}";
    }
}
