package org.nrg.containers.api;

import org.nrg.containers.exceptions.NoContainerServerException;
import org.nrg.framework.services.NrgEventServiceI;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.ExecutorService;

@Service
public class KubernetesClientFactoryImpl implements KubernetesClientFactory {
    private final ExecutorService executorService;
    private final NrgEventServiceI eventService;

    private volatile KubernetesClientImpl kubernetesClient = null;

    public KubernetesClientFactoryImpl(ExecutorService executorService, NrgEventServiceI eventService) {
        this.executorService = executorService;
        this.eventService = eventService;
    }

    @Override
    public KubernetesClient getKubernetesClient() throws NoContainerServerException {
        if (kubernetesClient == null) {
            synchronized (this) {
                if (kubernetesClient == null) {
                    try {
                        kubernetesClient = new KubernetesClientImpl(executorService, eventService);
                    } catch (IOException e) {
                        throw new NoContainerServerException("Could not create kubernetes client", e);
                    }
                }
            }
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
