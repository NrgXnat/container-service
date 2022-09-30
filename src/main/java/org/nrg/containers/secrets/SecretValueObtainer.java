package org.nrg.containers.secrets;

import java.util.Optional;

public abstract class SecretValueObtainer<T extends SecretSource.ValueObtainingSecretSource> {
    private final Class<T> sourceType;
    protected SecretValueObtainer(final Class<T> sourceType) {
        this.sourceType = sourceType;
    }

    public Class<T> handledType() {
        return sourceType;
    }

    public abstract Optional<String> obtainValue(SecretSource.ValueObtainingSecretSource source);
}
