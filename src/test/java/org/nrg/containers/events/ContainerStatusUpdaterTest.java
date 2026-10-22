package org.nrg.containers.events;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.nrg.containers.api.ContainerControlApi;
import org.nrg.containers.api.KubernetesClient;
import org.nrg.containers.api.KubernetesClientFactory;
import org.nrg.containers.model.server.docker.Backend;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.services.ContainerService;
import org.nrg.containers.services.DockerServerService;
import org.nrg.framework.services.NrgEventServiceI;
import org.nrg.xnat.services.XnatAppInfo;
import org.springframework.jms.core.JmsTemplate;

import java.util.Arrays;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** After a restart (or a client rebuild on settings change) every namespace with live jobs must be watched again. */
@RunWith(MockitoJUnitRunner.class)
public class ContainerStatusUpdaterTest {

    @Mock private ContainerService containerService;
    @Mock private KubernetesClientFactory factory;
    @Mock private KubernetesClient client;

    @Test
    public void kubernetesCycleStartsTheClientThenRestoresPerProjectWatches() throws Exception {
        when(factory.getKubernetesClient()).thenReturn(client);
        when(containerService.retrieveNonfinalizedKubernetesNamespaces()).thenReturn(Arrays.asList("xnat-proj01", "xnat-proj02"));

        updater().checkForUpdatesAndThrowEvents(kubernetesServer());

        final InOrder inOrder = Mockito.inOrder(client);
        inOrder.verify(client).start();
        inOrder.verify(client).ensureWatching("xnat-proj01");
        inOrder.verify(client).ensureWatching("xnat-proj02");
    }

    @Test
    public void oneNamespaceFailingToRegisterDoesNotStopTheOthers() throws Exception {
        when(factory.getKubernetesClient()).thenReturn(client);
        when(containerService.retrieveNonfinalizedKubernetesNamespaces()).thenReturn(Arrays.asList("xnat-offboarded", "xnat-proj02"));
        doThrow(new IllegalStateException("forbidden")).when(client).ensureWatching("xnat-offboarded");

        updater().checkForUpdatesAndThrowEvents(kubernetesServer());

        verify(client).ensureWatching("xnat-offboarded");
        verify(client).ensureWatching("xnat-proj02");
    }

    private ContainerStatusUpdater updater() {
        return new ContainerStatusUpdater(mock(ContainerControlApi.class), containerService,
                mock(DockerServerService.class), mock(NrgEventServiceI.class), mock(XnatAppInfo.class), factory, mock(JmsTemplate.class));
    }

    private static DockerServer kubernetesServer() {
        return DockerServer.builder().name("k8s").host("").backend(Backend.KUBERNETES).build();
    }
}
