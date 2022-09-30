package org.nrg.containers.secrets;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SecretUtils {

    /**
     * Remove environment variable resolved secrets from resolved secrets map, return them as a name: value map
     * @param resolvedSecretsByClass Map from resolved secret classes to a list of secrets of that type
     * @return Map of environment variable secrets: name to value
     */
    public static Map<String, String> extractEnvironmentVariableSecrets(final Map<Class<? extends ResolvedSecret>, List<ResolvedSecret>> resolvedSecretsByClass) {
        return extractResolvedSecretType(EnvironmentVariableResolvedSecret.class, resolvedSecretsByClass)
                        .collect(Collectors.toMap(EnvironmentVariableResolvedSecret::envName, EnvironmentVariableResolvedSecret::envValue));
    }

    /**
     * Remove and return a particular type of ResolvedSecrets from the resolved secrets by type map
     * @param type A subclass of {@link ResolvedSecret}
     * @param resolvedSecretsByClass Map from resolved secret classes to a list of secrets of that type
     * @return A stream of the ResolvedSecrets of the specified type
     */
    public static <T extends ResolvedSecret> Stream<T> extractResolvedSecretType(
            Class<T> type, final Map<Class<? extends ResolvedSecret>, List<ResolvedSecret>> resolvedSecretsByClass
    ) {
        final List<ResolvedSecret> secretTs = resolvedSecretsByClass.remove(type);
        return secretTs == null ? Stream.empty() : secretTs.stream().map(type::cast);
    }
}
