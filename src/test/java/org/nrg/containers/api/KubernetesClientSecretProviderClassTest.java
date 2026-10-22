package org.nrg.containers.api;

import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.nrg.containers.secrets.SecretProviderClassCheck;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

public class KubernetesClientSecretProviderClassTest {
    private static final String GROUP = "secrets-store.csi.x-k8s.io";
    private final KubernetesClientImpl client = Mockito.mock(KubernetesClientImpl.class);
    private final CustomObjectsApi customObjectsApi = Mockito.mock(CustomObjectsApi.class);

    @Before
    public void setUp() throws Exception {
        setField("contextNamespace", "ctx");
        setField("customObjectsApi", customObjectsApi);
        Mockito.when(client.secretProviderClassExists(Mockito.any(), Mockito.anyString())).thenCallRealMethod();
    }

    private void setField(final String name, final Object value) throws Exception {
        final Field field = KubernetesClientImpl.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(client, value);
    }

    @Test
    public void existingObjectInTheGivenNamespace() throws Exception {
        Mockito.when(customObjectsApi.getNamespacedCustomObject(GROUP, "v1", "xnat-proj01", "secretproviderclasses", "xnat-proj01-redcap"))
                .thenReturn(Collections.singletonMap("kind", "SecretProviderClass"));
        assertThat(client.secretProviderClassExists("xnat-proj01", "xnat-proj01-redcap").result(), is(SecretProviderClassCheck.Result.EXISTS));
    }

    @Test
    public void nullNamespaceMeansTheContextNamespace() throws Exception {
        Mockito.when(customObjectsApi.getNamespacedCustomObject(GROUP, "v1", "ctx", "secretproviderclasses", "x"))
                .thenThrow(new ApiException(404, Collections.<String, List<String>>emptyMap(),
                        "{\"message\":\"secretproviderclasses.secrets-store.csi.x-k8s.io \\\"x\\\" not found\"}"));
        final SecretProviderClassCheck check = client.secretProviderClassExists(null, "x");
        assertThat(check.result(), is(SecretProviderClassCheck.Result.MISSING));
        assertThat(check.reason().contains("namespace \"ctx\""), is(true));
    }
}
