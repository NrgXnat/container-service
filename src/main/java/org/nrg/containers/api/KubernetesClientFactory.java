package org.nrg.containers.api;

import org.nrg.containers.exceptions.NoContainerServerException;

public interface KubernetesClientFactory {
    KubernetesClient getKubernetesClient() throws NoContainerServerException;
    void shutdown();
}
