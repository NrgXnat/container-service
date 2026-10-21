package org.nrg.containers.api;

import io.kubernetes.client.openapi.ApiClient;
import org.nrg.containers.exceptions.ContainerBackendException;
import org.nrg.containers.exceptions.ContainerException;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.server.docker.DockerServerBase;
import org.nrg.framework.exceptions.NotFoundException;

import javax.annotation.Nullable;
import java.time.OffsetDateTime;

public interface KubernetesClient {
    ApiClient getBackendClient();
    void setBackendClient(ApiClient apiClient);

    /** The kubeconfig context namespace (or the pod's own namespace in-cluster). Used when a container records no namespace. */
    String getNamespace();

    void start();
    void stop();

    String ping() throws ContainerBackendException;
    String getLog(String podName, @Nullable String namespace, final LogType logType, final Boolean withTimestamp, final OffsetDateTime since) throws ContainerBackendException;

    /**
     * Create a Kubernetes job for the container in {@code toCreate.namespace()}, falling back to {@link #getNamespace()}
     * when null. Reads these settings from {@code server}: containerUser, gpuVendor, kubernetesTolerations,
     * secretVolumeType, secretNameTemplate and jobServiceAccount.
     */
    String createJob(final Container toCreate, final DockerControlApi.NumReplicas numReplicas, final DockerServerBase.DockerServer server)
            throws ContainerBackendException, ContainerException;
    void unsuspendJob(final String jobName, @Nullable String namespace) throws ContainerBackendException;
    void removeJob(String jobName, @Nullable String namespace) throws NotFoundException, ContainerBackendException;
}
