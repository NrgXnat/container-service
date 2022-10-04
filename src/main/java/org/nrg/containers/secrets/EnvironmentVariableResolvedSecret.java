package org.nrg.containers.secrets;

import org.nrg.containers.exceptions.ContainerServiceSecretException;

import java.util.Optional;

@ResolverFor(destination = EnvironmentVariableSecretDestination.class)
public class EnvironmentVariableResolvedSecret extends ResolvedSecret.WithValue {
    @SuppressWarnings("unused")
    public EnvironmentVariableResolvedSecret(
            final Secret secret,
            @SuppressWarnings("OptionalUsedAsFieldOrParameterType") final Optional<String> value
    ) throws ContainerServiceSecretException {
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
