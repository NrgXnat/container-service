package org.nrg.containers.api;

import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodStatus;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.nrg.containers.events.model.ContainerEvent;
import org.nrg.containers.events.model.KubernetesStatusChangeEvent;
import org.nrg.containers.jms.utils.QueueUtils;
import org.nrg.containers.model.kubernetes.KubernetesPodPhase;
import org.springframework.jms.core.JmsTemplate;

import java.util.ArrayList;
import java.util.Collections;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

public class KubernetesInformerTest {
    private static final String JOB_NAME = "container-service-job-abc123";
    private static final String POD_NAME = JOB_NAME + "-x7k2p";

    /**
     * A pod that has been scheduled but whose kubelet has not yet reported on its container can arrive with
     * an empty containerStatuses list rather than none at all. Reading element zero of that list throws out
     * of the informer's event handler.
     */
    @Test
    public void emptyContainerStatusListIsTreatedAsNoContainerStatus() {
        final JmsTemplate template = mock(JmsTemplate.class);

        final V1Pod pod = new V1Pod()
                .metadata(new V1ObjectMeta()
                        .name(POD_NAME)
                        .labels(Collections.singletonMap("job-name", JOB_NAME)))
                .status(new V1PodStatus()
                        .phase(KubernetesPodPhase.PENDING.toString())
                        .containerStatuses(new ArrayList<>()));

        final ArgumentCaptor<KubernetesStatusChangeEvent> event =
                ArgumentCaptor.forClass(KubernetesStatusChangeEvent.class);

        try (final MockedStatic<QueueUtils> mockedQueueUtils = mockStatic(QueueUtils.class)) {
            new KubernetesInformerImpl.PodEventHandler(template).onAdd(pod);

            mockedQueueUtils.verify(() ->
                    QueueUtils.sendJmsRequest(eq(template), eq(ContainerEvent.QUEUE), event.capture()));
        }

        assertThat(event.getValue().getPodPhase(), is(KubernetesPodPhase.PENDING));
        assertThat(event.getValue().getContainerId(), is(nullValue()));
        assertThat(event.getValue().getContainerState(), is(nullValue()));
    }
}
