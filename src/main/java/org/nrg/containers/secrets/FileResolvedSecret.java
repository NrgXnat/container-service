package org.nrg.containers.secrets;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * A resolved {@link K8sSecretSource} → {@link FileSecretDestination} secret.
 * <p>
 * Carries no value: XNAT never obtains it. The Kubernetes backend turns this into a volume
 * referencing the Secret by name plus a volume mount at {@link #mountPath()}.
 */
@ResolverFor(source = K8sSecretSource.class, destination = FileSecretDestination.class)
public class FileResolvedSecret extends ResolvedSecret {

    @SuppressWarnings("unused")  // Reflectively called by ContainerSecretServiceImpl
    public FileResolvedSecret(final Secret secret,
                              @SuppressWarnings("OptionalUsedAsFieldOrParameterType") final Optional<String> ignoredValue) {
        super(secret);
    }

    public FileResolvedSecret(final Secret secret) {
        super(secret);
    }

    /** Logical name of the secret as declared in the command, e.g. {@code redcap}. */
    public String logicalName() {
        return source().identifier();
    }

    /** Explicit Kubernetes Secret name from the command, or null when the site template applies. */
    @Nullable
    public String explicitSecretName() {
        return ((K8sSecretSource) source()).secretName();
    }

    /** Directory inside the container where the secret's keys are mounted as files. */
    public String mountPath() {
        return ((FileSecretDestination) destination()).mountPath();
    }

    @Override
    public String toString() {
        return "FileResolvedSecret{logicalName=\"" + logicalName() + "\", mountPath=\"" + mountPath() + "\"}";
    }
}
