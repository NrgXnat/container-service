package org.nrg.containers.api;

import org.nrg.containers.exceptions.NoContainerServerException;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.ExecutorService;

@Service
public class KubernetesClientFactoryImpl implements KubernetesClientFactory {
    private final ExecutorService executorService;
    private final JmsTemplate template;

    private volatile KubernetesClientImpl kubernetesClient = null;

    public KubernetesClientFactoryImpl(final ExecutorService executorService,
                                       final JmsTemplate template) {
        this.executorService = executorService;
        this.template = template;
    }

    @Override
    public KubernetesClient getKubernetesClient() throws NoContainerServerException {
        if (kubernetesClient == null) {
            synchronized (this) {
                if (kubernetesClient == null) {
                    try {
                        kubernetesClient = new KubernetesClientImpl(executorService, template);
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
