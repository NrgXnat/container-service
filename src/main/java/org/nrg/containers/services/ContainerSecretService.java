package org.nrg.containers.services;

import org.nrg.containers.exceptions.ContainerServiceSecretException;
import org.nrg.containers.secrets.ResolvedSecret;
import org.nrg.containers.secrets.Secret;

public interface ContainerSecretService {
    ResolvedSecret resolve(Secret secret) throws ContainerServiceSecretException;
}
