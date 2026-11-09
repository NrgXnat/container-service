package org.nrg.containers.api;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.ApiResponse;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodCondition;
import io.kubernetes.client.openapi.models.V1PodStatus;
import okhttp3.Call;
import okio.Timeout;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.nrg.containers.model.kubernetes.PodMountState;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

/** FU-3: the watchdog asks for the pod's current state before timing it out. */
public class KubernetesClientPodMountStateTest {
    private static final String POD = "secret-probe-7fd2a-x7k2p";

    private final KubernetesClientImpl client = Mockito.mock(KubernetesClientImpl.class);
    private final CoreV1Api coreApi = Mockito.mock(CoreV1Api.class);
    private final ApiClient apiClient = Mockito.mock(ApiClient.class);
    private final Call call = Mockito.mock(Call.class);
    private final Timeout timeout = new Timeout();

    @Before
    public void setUp() throws Exception {
        ReflectionTestUtils.setField(client, "contextNamespace", "ctx");
        ReflectionTestUtils.setField(client, "coreApi", coreApi);
        ReflectionTestUtils.setField(client, "apiClient", apiClient);
        Mockito.when(call.timeout()).thenReturn(timeout);
        Mockito.when(client.podMountState(Mockito.any(), Mockito.any())).thenCallRealMethod();
    }

    private static V1Pod pastMount() {
        return new V1Pod().status(new V1PodStatus().phase("Pending")
                .addConditionsItem(new V1PodCondition().type("PodReadyToStartContainers").status("True")));
    }

    @Test
    public void readsThePodInItsNamespaceAndClassifiesIt() throws Exception {
        Mockito.when(coreApi.readNamespacedPodCall(POD, "xnat-proj01", null, null)).thenReturn(call);
        Mockito.when(apiClient.<V1Pod>execute(Mockito.eq(call), Mockito.eq(V1Pod.class)))
                .thenReturn(new ApiResponse<>(200, Collections.<String, List<String>>emptyMap(), pastMount()));

        assertThat(client.podMountState(POD, "xnat-proj01"), is(PodMountState.PAST_VOLUME_MOUNT));
    }

    @Test
    public void nullNamespaceMeansTheContextNamespace() throws Exception {
        Mockito.when(coreApi.readNamespacedPodCall(POD, "ctx", null, null)).thenReturn(call);
        Mockito.when(apiClient.<V1Pod>execute(Mockito.eq(call), Mockito.eq(V1Pod.class)))
                .thenReturn(new ApiResponse<>(200, Collections.<String, List<String>>emptyMap(), pastMount()));

        assertThat(client.podMountState(POD, null), is(PodMountState.PAST_VOLUME_MOUNT));
    }

    @Test
    public void lookupHasItsOwnReadTimeout() throws Exception {
        Mockito.when(coreApi.readNamespacedPodCall(POD, "xnat-proj01", null, null)).thenReturn(call);

        client.podMountState(POD, "xnat-proj01");

        assertThat(timeout.timeoutNanos(), is(TimeUnit.SECONDS.toNanos(10)));
    }

    @Test
    public void unknownPodNameIsUnknownWithoutAnApiCall() throws Exception {
        assertThat(client.podMountState(null, "xnat-proj01"), is(PodMountState.UNKNOWN));
        Mockito.verifyNoInteractions(coreApi);
    }

    @Test
    public void apiErrorsAreUnknown() throws Exception {
        Mockito.when(coreApi.readNamespacedPodCall(POD, "xnat-proj01", null, null)).thenReturn(call);
        Mockito.when(apiClient.<V1Pod>execute(Mockito.eq(call), Mockito.eq(V1Pod.class)))
                .thenThrow(new ApiException(404, Collections.<String, List<String>>emptyMap(), "{\"reason\":\"NotFound\"}"));

        assertThat(client.podMountState(POD, "xnat-proj01"), is(PodMountState.UNKNOWN));
    }

    @Test
    public void unexpectedRuntimeExceptionsAreUnknown() throws Exception {
        Mockito.when(coreApi.readNamespacedPodCall(POD, "xnat-proj01", null, null)).thenThrow(new IllegalStateException("boom"));

        assertThat(client.podMountState(POD, "xnat-proj01"), is(PodMountState.UNKNOWN));
    }
}
