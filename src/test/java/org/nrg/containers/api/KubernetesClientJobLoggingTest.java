package org.nrg.containers.api;

import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.models.V1Job;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;
import org.junit.After;
import org.junit.Before;
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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
    private final Logger logger = Logger.getLogger(KubernetesClientImpl.class);
    private final List<String> messages = new ArrayList<>();
    private final AppenderSkeleton capture = new AppenderSkeleton() {
        @Override
        protected void append(final LoggingEvent event) {
            messages.add(event.getRenderedMessage());
        }

        @Override
        public void close() {
        }

        @Override
        public boolean requiresLayout() {
            return false;
        }
    };
    private Level originalLevel;

    @Before
    public void setUp() throws Exception {
        setField("contextNamespace", "ctx");
        setField("batchApi", batchApi);
        originalLevel = logger.getLevel();
        logger.addAppender(capture);
        Mockito.when(batchApi.createNamespacedJob(Mockito.eq("xnat-proj01"), Mockito.any(V1Job.class), Mockito.isNull(), Mockito.isNull(), Mockito.isNull(), Mockito.isNull()))
                .thenAnswer(invocation -> invocation.getArgument(1, V1Job.class));
    }

    @After
    public void tearDown() {
        logger.removeAppender(capture);
        logger.setLevel(originalLevel);
    }

    private void setField(final String name, final Object value) throws Exception {
        final Field field = KubernetesClientImpl.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(client, value);
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
        logger.setLevel(level);
        final Container toCreate = containerWithSecretEnv();
        Mockito.when(client.createJob(toCreate, DockerControlApi.NumReplicas.ZERO, server)).thenCallRealMethod();
        client.createJob(toCreate, DockerControlApi.NumReplicas.ZERO, server);
        return String.join("\n", messages);
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
