package org.nrg.containers.secrets;

import lombok.extern.slf4j.Slf4j;
import org.nrg.containers.model.container.auto.Container;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class ContainerPropertiesWithSecretValues {
    // TODO add more secret properties as they are implemented
    private final Map<String, String> environmentVariables;

    private ContainerPropertiesWithSecretValues(final Map<String, String> environmentVariables) {
        this.environmentVariables = environmentVariables;
    }

    public static ContainerPropertiesWithSecretValues prepareSecretsForLaunch(final Container container) {

        // Secrets
        final Map<Class<? extends ResolvedSecret>, List<ResolvedSecret>> secretsByType = container.secretsByType();

        final Map<String, String> environmentVariables = new HashMap<>(container.environmentVariables());
        environmentVariables.putAll(SecretUtils.extractEnvironmentVariableSecrets(secretsByType));

        // TODO add handling for more secret types as they are implemented

        secretsByType.forEach((clazz, resolvedSecrets) ->
                log.warn("Have not implemented handling for resolved secret type {}. Ignoring {} secret values.",
                        clazz.getSimpleName(), resolvedSecrets.size()
                )
        );

        return new ContainerPropertiesWithSecretValues(environmentVariables);
    }

    public Map<String, String> environmentVariables() {
        return environmentVariables;
    }

}
