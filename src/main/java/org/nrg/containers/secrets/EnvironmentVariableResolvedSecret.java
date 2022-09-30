package org.nrg.containers.secrets;

import java.util.Optional;

@ResolverFor(destination = EnvironmentVariableSecretDestination.class)
public class EnvironmentVariableResolvedSecret extends ResolvedSecret.WithValue {
    public EnvironmentVariableResolvedSecret(
            final Secret secret,
            @SuppressWarnings("OptionalUsedAsFieldOrParameterType") final Optional<String> value
    ) throws Exception {
        super(secret, value);
    }

    public EnvironmentVariableResolvedSecret(final Secret secret, final String value){
        super(secret, value);
    }

    public String envName() {
        return destination().identifier();
    }

    public String envValue() {
        return value();
    }

    @Override
    public String toString() {
        return "EnvironmentVariableResolvedSecret{" +
                "envName=\"" + envName() + "\"" +
                "}";
    }
}
