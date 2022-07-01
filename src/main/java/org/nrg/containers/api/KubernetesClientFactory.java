package org.nrg.containers.api;

import org.nrg.containers.exceptions.NoContainerServerException;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;

public interface KubernetesClientFactory {
    KubernetesClient getKubernetesClient(DockerServer dockerServer) throws NoContainerServerException;
    void shutdown();
}
