package org.nrg.containers.api;

import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.models.V1Job;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.nrg.containers.exceptions.ContainerBackendException;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.server.docker.Backend;
import org.nrg.containers.model.server.docker.DockerServerBase;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

/** Watching a namespace starts only after a job was created there (design doc section 3.5). */
public class KubernetesClientJobCreationTest {
    private final KubernetesClientImpl client = Mockito.mock(KubernetesClientImpl.class);
    private final BatchV1Api batchApi = Mockito.mock(BatchV1Api.class);
    private final DockerServerBase.DockerServer server = DockerServerBase.DockerServer.builder().name("k8s").host("").backend(Backend.KUBERNETES).build();

    @Before
    public void setUp() throws Exception {
        setField("contextNamespace", "ctx");
        setField("batchApi", batchApi);
    }

    private void setField(final String name, final Object value) throws Exception {
        final Field field = KubernetesClientImpl.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(client, value);
    }

    private static Container container(final String namespace) {
        return Container.builder().wrapperId(0L).commandId(0L).dockerImage("busybox:latest").commandLine("true")
                .backend(Backend.KUBERNETES).userId("u").namespace(namespace).build();
    }

    @Test
    public void successfulCreationStartsWatchingThatNamespace() throws Exception {
        Mockito.when(batchApi.createNamespacedJob(Mockito.eq("xnat-proj01"), Mockito.any(V1Job.class), Mockito.isNull(), Mockito.isNull(), Mockito.isNull(), Mockito.isNull()))
                .thenAnswer(invocation -> invocation.getArgument(1, V1Job.class));
        final Container toCreate = container("xnat-proj01");
        Mockito.when(client.createJob(toCreate, DockerControlApi.NumReplicas.ZERO, server)).thenCallRealMethod();

        client.createJob(toCreate, DockerControlApi.NumReplicas.ZERO, server);

        Mockito.verify(client).ensureWatching("xnat-proj01");
    }

    @Test
    public void notFoundIsTranslatedAndNoWatchIsStarted() throws Exception {
        Mockito.when(batchApi.createNamespacedJob(Mockito.eq("xnat-proj02"), Mockito.any(V1Job.class), Mockito.isNull(), Mockito.isNull(), Mockito.isNull(), Mockito.isNull()))
                .thenThrow(new ApiException(404, Collections.<String, List<String>>emptyMap(),
                        "{\"kind\":\"Status\",\"message\":\"namespaces \\\"xnat-proj02\\\" not found\",\"reason\":\"NotFound\",\"code\":404}"));
        final Container toCreate = container("xnat-proj02");
        Mockito.when(client.createJob(toCreate, DockerControlApi.NumReplicas.ZERO, server)).thenCallRealMethod();

        try {
            client.createJob(toCreate, DockerControlApi.NumReplicas.ZERO, server);
            fail("Expected ContainerBackendException");
        } catch (ContainerBackendException e) {
            assertThat(e.getMessage(), containsString("Namespace \"xnat-proj02\" does not exist"));
        }
        Mockito.verify(client, Mockito.never()).ensureWatching(Mockito.anyString());
    }

    @Test
    public void aWatchThatCannotStartDoesNotFailTheLaunch() throws Exception {
        // The API server fills in the name from generateName; the stub does the same.
        Mockito.when(batchApi.createNamespacedJob(Mockito.eq("xnat-proj01"), Mockito.any(V1Job.class), Mockito.isNull(), Mockito.isNull(), Mockito.isNull(), Mockito.isNull()))
                .thenAnswer(invocation -> {
                    final V1Job created = invocation.getArgument(1, V1Job.class);
                    created.getMetadata().setName("container-job-abcde");
                    return created;
                });
        Mockito.doThrow(new IllegalStateException("informer down")).when(client).ensureWatching("xnat-proj01");
        final Container toCreate = container("xnat-proj01");
        Mockito.when(client.createJob(toCreate, DockerControlApi.NumReplicas.ZERO, server)).thenCallRealMethod();

        final String jobName = client.createJob(toCreate, DockerControlApi.NumReplicas.ZERO, server);

        assertThat(jobName, is("container-job-abcde"));
    }

    @Test
    public void aContainerWithoutNamespaceWatchesTheContextNamespace() throws Exception {
        Mockito.when(batchApi.createNamespacedJob(Mockito.eq("ctx"), Mockito.any(V1Job.class), Mockito.isNull(), Mockito.isNull(), Mockito.isNull(), Mockito.isNull()))
                .thenAnswer(invocation -> invocation.getArgument(1, V1Job.class));
        final Container toCreate = container(null);
        Mockito.when(client.createJob(toCreate, DockerControlApi.NumReplicas.ZERO, server)).thenCallRealMethod();

        client.createJob(toCreate, DockerControlApi.NumReplicas.ZERO, server);

        Mockito.verify(client).ensureWatching("ctx");
    }

    @Test
    public void ensureWatchingWithoutAStartedInformerDoesNothing() throws Exception {
        setField("kubernetesInformer", null);
        Mockito.doCallRealMethod().when(client).ensureWatching("ns");

        client.ensureWatching("ns");

        Mockito.verify(batchApi, Mockito.never()).createNamespacedJob(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    public void ensureWatchingDelegatesToTheStartedInformer() throws Exception {
        final KubernetesInformer informer = Mockito.mock(KubernetesInformer.class);
        setField("kubernetesInformer", informer);
        Mockito.doCallRealMethod().when(client).ensureWatching("ns");

        client.ensureWatching("ns");

        Mockito.verify(informer).ensureWatching("ns");
    }
}
