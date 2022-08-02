package org.nrg.containers.api;

import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1Pod;

public interface KubernetesInformer {
    void start();
    void stop();

    V1Job getJob(final String name);
    V1Pod getPod(final String name);
}
