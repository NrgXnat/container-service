package org.nrg.containers.secrets;

import lombok.extern.slf4j.Slf4j;
import org.nrg.containers.exceptions.ContainerException;
import org.nrg.containers.model.container.auto.Container;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class ContainerPropertiesWithSecretValues {
    // TODO add more secret properties as they are implemented
    private final Map<String, String> environmentVariables;
    private final List<FileResolvedSecret> fileSecrets;

    private ContainerPropertiesWithSecretValues(final Map<String, String> environmentVariables,
                                                final List<FileResolvedSecret> fileSecrets) {
        this.environmentVariables = environmentVariables;
        this.fileSecrets = fileSecrets;
    }

    public static ContainerPropertiesWithSecretValues prepareSecretsForLaunch(final Container container) {

        // Secrets
        final Map<Class<? extends ResolvedSecret>, List<ResolvedSecret>> secretsByType = container.secretsByType();

        final Map<String, String> environmentVariables = new HashMap<>(container.environmentVariables());
        environmentVariables.putAll(SecretUtils.extractEnvironmentVariableSecrets(secretsByType));

        // File secrets: mounted by the backend, never valued here
        final List<FileResolvedSecret> fileSecrets = SecretUtils.extractResolvedSecretType(FileResolvedSecret.class, secretsByType)
                .collect(Collectors.toList());

        // TODO add handling for more secret types as they are implemented

        secretsByType.forEach((clazz, resolvedSecrets) ->
                log.warn("Have not implemented handling for resolved secret type {}. Ignoring {} secret values.",
                        clazz.getSimpleName(), resolvedSecrets.size()
                )
        );

        return new ContainerPropertiesWithSecretValues(environmentVariables, fileSecrets);
    }

    public Map<String, String> environmentVariables() {
        return environmentVariables;
    }

    /**
     * Secrets to be mounted as files. Only the Kubernetes backend can honour these.
     */
    public List<FileResolvedSecret> fileSecrets() {
        return fileSecrets;
    }

    /**
     * Backends that cannot mount secrets as files must refuse to launch rather than silently drop them.
     */
    public void requireNoFileSecrets(final String backendName) throws ContainerException {
        if (!fileSecrets.isEmpty()) {
            throw new ContainerException("File secrets are not supported on the " + backendName + " backend: " + fileSecrets);
        }
    }

}
