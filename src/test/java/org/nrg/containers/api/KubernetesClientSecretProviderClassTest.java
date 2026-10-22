package org.nrg.containers.api;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.ApiResponse;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import okhttp3.Call;
import okio.Timeout;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.nrg.containers.secrets.SecretProviderClassCheck;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

public class KubernetesClientSecretProviderClassTest {
    private static final String GROUP = "secrets-store.csi.x-k8s.io";
    private final KubernetesClientImpl client = Mockito.mock(KubernetesClientImpl.class);
    private final CustomObjectsApi customObjectsApi = Mockito.mock(CustomObjectsApi.class);
    private final ApiClient apiClient = Mockito.mock(ApiClient.class);
    private final Call call = Mockito.mock(Call.class);
    private final Timeout timeout = new Timeout();

    @Before
    public void setUp() throws Exception {
        setField("contextNamespace", "ctx");
        setField("customObjectsApi", customObjectsApi);
        setField("apiClient", apiClient);
        Mockito.when(call.timeout()).thenReturn(timeout);
        Mockito.when(client.secretProviderClassExists(Mockito.any(), Mockito.anyString())).thenCallRealMethod();
    }

    private void setField(final String name, final Object value) throws Exception {
        final Field field = KubernetesClientImpl.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(client, value);
    }

    @Test
    public void existingObjectInTheGivenNamespace() throws Exception {
        Mockito.when(customObjectsApi.getNamespacedCustomObjectCall(GROUP, "v1", "xnat-proj01", "secretproviderclasses", "xnat-proj01-redcap", null))
                .thenReturn(call);
        Mockito.when(apiClient.execute(call, Object.class))
                .thenReturn(new ApiResponse<Object>(200, Collections.<String, List<String>>emptyMap(), Collections.singletonMap("kind", "SecretProviderClass")));
        assertThat(client.secretProviderClassExists("xnat-proj01", "xnat-proj01-redcap").result(), is(SecretProviderClassCheck.Result.EXISTS));
    }

    @Test
    public void lookupHasItsOwnReadTimeout() throws Exception {
        Mockito.when(customObjectsApi.getNamespacedCustomObjectCall(GROUP, "v1", "xnat-proj01", "secretproviderclasses", "xnat-proj01-redcap", null))
                .thenReturn(call);
        client.secretProviderClassExists("xnat-proj01", "xnat-proj01-redcap");
        assertThat(timeout.timeoutNanos(), is(TimeUnit.SECONDS.toNanos(10)));
    }

    @Test
    public void nullNamespaceMeansTheContextNamespace() throws Exception {
        Mockito.when(customObjectsApi.getNamespacedCustomObjectCall(GROUP, "v1", "ctx", "secretproviderclasses", "x", null))
                .thenReturn(call);
        Mockito.when(apiClient.execute(call, Object.class))
                .thenThrow(new ApiException(404, Collections.<String, List<String>>emptyMap(),
                        "{\"message\":\"secretproviderclasses.secrets-store.csi.x-k8s.io \\\"x\\\" not found\"}"));
        final SecretProviderClassCheck check = client.secretProviderClassExists(null, "x");
        assertThat(check.result(), is(SecretProviderClassCheck.Result.MISSING));
        assertThat(check.reason(), containsString("namespace \"ctx\""));
    }

    @Test
    public void unexpectedRuntimeExceptionIsUndetermined() throws Exception {
        Mockito.when(customObjectsApi.getNamespacedCustomObjectCall(GROUP, "v1", "ctx", "secretproviderclasses", "x", null))
                .thenThrow(new IllegalStateException("boom"));
        final SecretProviderClassCheck check = client.secretProviderClassExists(null, "x");
        assertThat(check.result(), is(SecretProviderClassCheck.Result.UNDETERMINED));
        assertThat(check.reason(), containsString("Could not check"));
    }
}
