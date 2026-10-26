package org.nrg.containers.secrets;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.Optional;

/**
 * A resolved {@link K8sSecretSource} → {@link FileSecretDestination} secret.
 * <p>
 * Carries no value: XNAT never obtains it. Command resolution renders {@link #secretName()} from the command's
 * {@link #nameTemplate()} or the site template and writes it back into the source, so the Kubernetes backend only
 * has to turn this into a volume named {@link #volumeName()} plus a read-only mount at {@link #mountPath()}.
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

    /** Command-level name template, or null when the site template applies. */
    @Nullable
    public String nameTemplate() {
        return k8sSource().nameTemplate();
    }

    /** Rendered Kubernetes Secret / SecretProviderClass name; null before resolution. */
    @Nullable
    public String secretName() {
        return k8sSource().secretName();
    }

    /** False lets the Pod start without the object (see K8sSecretSource). */
    public boolean required() {
        return k8sSource().required();
    }

    /** Directory inside the container where the secret's keys are mounted as files. */
    public String mountPath() {
        return ((FileSecretDestination) destination()).mountPath();
    }

    /** Pod volume name, derived from the identifier; Kubernetes requires it to be unique within the Pod. */
    public String volumeName() {
        return K8sSecretNameTemplate.sanitizeLabel(VOLUME_NAME_PREFIX + identifier());
    }

    /** A copy whose source carries the rendered (non-null) name, so it survives the jsonb round trip. */
    public FileResolvedSecret withSecretName(final String renderedSecretName) {
        Objects.requireNonNull(renderedSecretName, "rendered secret name");
        return new FileResolvedSecret(new Secret(k8sSource().withSecretName(renderedSecretName), destination()));
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
