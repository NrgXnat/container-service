package org.nrg.containers.api;

import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.models.V1Job;
import org.apache.log4j.Level;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.server.docker.Backend;
import org.nrg.containers.model.server.docker.DockerServerBase;
import org.nrg.containers.secrets.EnvironmentVariableResolvedSecret;
import org.nrg.containers.secrets.EnvironmentVariableSecretDestination;
import org.nrg.containers.secrets.Secret;
import org.nrg.containers.secrets.SystemPropertySecretSource;
import org.nrg.containers.utils.LogCapture;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

/** Job creation logging must never expose environment variable values, which carry env-type secrets. */
public class KubernetesClientJobLoggingTest {
    private static final String SECRET_VALUE = "s3cr3t-redcap-token";
    private static final String PLAIN_VALUE = "plain-env-value";

    private final KubernetesClientImpl client = Mockito.mock(KubernetesClientImpl.class);
    private final BatchV1Api batchApi = Mockito.mock(BatchV1Api.class);
    private final DockerServerBase.DockerServer server = DockerServerBase.DockerServer.builder().name("k8s").host("").backend(Backend.KUBERNETES).build();
    @Rule
    public final LogCapture logCapture = new LogCapture(KubernetesClientImpl.class);

    @Before
    public void setUp() throws Exception {
        ReflectionTestUtils.setField(client, "contextNamespace", "ctx");
        ReflectionTestUtils.setField(client, "batchApi", batchApi);
        Mockito.when(batchApi.createNamespacedJob(Mockito.eq("xnat-proj01"), Mockito.any(V1Job.class), Mockito.isNull(), Mockito.isNull(), Mockito.isNull(), Mockito.isNull()))
                .thenAnswer(invocation -> invocation.getArgument(1, V1Job.class));
    }


    private static Container containerWithSecretEnv() {
        final Secret secret = new Secret(new SystemPropertySecretSource("redcap.token"), new EnvironmentVariableSecretDestination("REDCAP_TOKEN"));
        return Container.builder().wrapperId(0L).commandId(0L).dockerImage("busybox:latest").commandLine("true")
                .backend(Backend.KUBERNETES).userId("u").namespace("xnat-proj01")
                .environmentVariables(Collections.singletonMap("PLAIN_VAR", PLAIN_VALUE))
                .secrets(Collections.singletonList(new EnvironmentVariableResolvedSecret(secret, SECRET_VALUE)))
                .build();
    }

    private String createJobAndCollectLogs(final Level level) throws Exception {
        logCapture.setLevel(level);
        final Container toCreate = containerWithSecretEnv();
        Mockito.when(client.createJob(toCreate, DockerControlApi.NumReplicas.ZERO, server)).thenCallRealMethod();
        client.createJob(toCreate, DockerControlApi.NumReplicas.ZERO, server);
        return logCapture.messages();
    }

    @Test
    public void traceLogDoesNotContainEnvironmentValues() throws Exception {
        final String logged = createJobAndCollectLogs(Level.TRACE);

        assertThat(logged, containsString("REDCAP_TOKEN"));
        assertThat(logged, not(containsString(SECRET_VALUE)));
        assertThat(logged, not(containsString(PLAIN_VALUE)));
    }

    @Test
    public void traceLogStillDescribesTheJob() throws Exception {
        final String logged = createJobAndCollectLogs(Level.TRACE);

        assertThat(logged, containsString("busybox:latest"));
        assertThat(logged, containsString("PLAIN_VAR"));
    }

    @Test
    public void redactionDoesNotChangeTheJobSentToTheApiServer() throws Exception {
        createJobAndCollectLogs(Level.TRACE);

        final ArgumentCaptor<V1Job> sent = ArgumentCaptor.forClass(V1Job.class);
        Mockito.verify(batchApi).createNamespacedJob(Mockito.eq("xnat-proj01"), sent.capture(), Mockito.isNull(), Mockito.isNull(), Mockito.isNull(), Mockito.isNull());
        final String sentEnv = sent.getValue().getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().toString();
        assertThat(sentEnv.contains(SECRET_VALUE), is(true));
    }

    @Test
    public void debugLogDoesNotContainEnvironmentValues() throws Exception {
        final String logged = createJobAndCollectLogs(Level.DEBUG);

        assertThat(logged, not(containsString(SECRET_VALUE)));
    }
}
