package org.nrg.containers.api;

import org.nrg.containers.exceptions.NoContainerServerException;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.framework.services.NrgEventServiceI;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.concurrent.ExecutorService;

@Service
public class KubernetesClientFactoryImpl implements KubernetesClientFactory {
    private final ExecutorService executorService;
    private final NrgEventServiceI eventService;

    private DockerServer dockerServer = null;
    private KubernetesClientImpl kubernetesClient = null;

    public KubernetesClientFactoryImpl(ExecutorService executorService, NrgEventServiceI eventService) {
        this.executorService = executorService;
        this.eventService = eventService;
    }

    @Override
    public synchronized KubernetesClient getKubernetesClient(@Nonnull DockerServer dockerServer) throws NoContainerServerException {
        if (!dockerServer.equals(this.dockerServer)) {
            // Server has changed. Shut down old client (if we have one) then make a new one.
            shutdown();
        }
        if (kubernetesClient == null) {
            try {
                kubernetesClient = new KubernetesClientImpl(executorService, eventService, dockerServer);
            } catch (IOException e) {
                throw new NoContainerServerException("Could not create kubernetes client", e);
            }
            this.dockerServer = dockerServer;
        }
        return kubernetesClient;
    }

    @Override
    public synchronized void shutdown() {
        if (kubernetesClient != null) {
            kubernetesClient.stop();
            kubernetesClient = null;
        }
    }
}
