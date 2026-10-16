package org.nrg.containers.api;

import io.kubernetes.client.openapi.models.V1Affinity;
import io.kubernetes.client.openapi.models.V1NodeSelectorRequirement;
import io.kubernetes.client.openapi.models.V1Toleration;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;
import org.nrg.containers.model.server.docker.DockerServerBase.KubernetesToleration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertThat;

@Slf4j
public class KubernetesClientTest {

    @Test
    public void parseSwarmConstraint() throws Exception {
        final String constraintKey = "a-key";
        final String constraintValue = "a-value";
        final String swarmComparator = "==";
        final String expectedKubernetesOperator = "In";

        final String swarmConstraint = constraintKey + swarmComparator + constraintValue;

        // Test the underlying method that takes one constraint string
        final KubernetesClientImpl.ParsedConstraint parsedConstraint = KubernetesClientImpl.parseSwarmConstraint(swarmConstraint);

        assertThat(parsedConstraint.label(), is(constraintKey));
        assertThat(parsedConstraint.operator(), is(expectedKubernetesOperator));
        assertThat(parsedConstraint.value(), is(constraintValue));

        // Test the method that takes a list and puts it into the kubernetes object format
        final V1Affinity affinity = KubernetesClientImpl.parseSwarmConstraints(Collections.singletonList(swarmConstraint));

        // Dig out the properties at the bottom of the nested objects
        V1NodeSelectorRequirement nodeSelector = affinity.getNodeAffinity().getRequiredDuringSchedulingIgnoredDuringExecution().getNodeSelectorTerms().get(0).getMatchExpressions().get(0);
        assertThat(nodeSelector.getKey(), is(constraintKey));
        assertThat(nodeSelector.getOperator(), is(expectedKubernetesOperator));
        assertThat(nodeSelector.getValues(), is(Collections.singletonList(constraintValue)));
    }

    @Test
    public void testTolerationConversion() throws Exception {
        // Test that KubernetesToleration POJOs are properly converted to V1Toleration objects
        // by exercising the same logic used in KubernetesClientImpl.createJob

        final KubernetesToleration equalToleration = KubernetesToleration.builder()
                .id(0L)
                .key("workload.type")
                .operator("Equal")
                .value("cs")
                .effect("NoSchedule")
                .build();

        final KubernetesToleration existsToleration = KubernetesToleration.builder()
                .id(0L)
                .key("dedicated")
                .operator("Exists")
                .build();

        List<KubernetesToleration> tolerations = Arrays.asList(equalToleration, existsToleration);

        // Convert using the extracted static method
        List<V1Toleration> v1Tolerations = KubernetesClientImpl.convertTolerations(tolerations);

        assertThat(v1Tolerations, hasSize(2));

        V1Toleration v1Equal = v1Tolerations.get(0);
        assertThat(v1Equal.getKey(), is("workload.type"));
        assertThat(v1Equal.getOperator(), is("Equal"));
        assertThat(v1Equal.getValue(), is("cs"));
        assertThat(v1Equal.getEffect(), is("NoSchedule"));

        V1Toleration v1Exists = v1Tolerations.get(1);
        assertThat(v1Exists.getKey(), is("dedicated"));
        assertThat(v1Exists.getOperator(), is("Exists"));
        assertThat(v1Exists.getValue(), is(nullValue()));
        assertThat(v1Exists.getEffect(), is(nullValue()));
    }

    @Test
    public void debugLoggingRedactsTheTokenAndSkipsBodies() throws Exception {
        final List<String> lines = new ArrayList<>();
        final OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(KubernetesClientImpl.debugLoggingInterceptor(lines::add))
                // Answers the call here instead of over the network.
                .addInterceptor(chain -> new Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(201)
                        .message("Created")
                        .body(ResponseBody.create("{\"kind\":\"Job\"}", MediaType.get("application/json")))
                        .build())
                .build();
        final Request request = new Request.Builder()
                .url("https://kubernetes.default.svc/apis/batch/v1/namespaces/xnat/jobs")
                .header("Authorization", "Bearer service-account-token")
                .post(RequestBody.create("{\"env\":[{\"name\":\"XNAT_PASS\",\"value\":\"alias-token-secret\"}]}",
                                         MediaType.get("application/json")))
                .build();
        try (final Response response = client.newCall(request).execute()) {
            assertThat(response.code(), is(201));
        }

        final String logged = String.join("\n", lines);
        assertThat(logged, containsString("--> POST https://kubernetes.default.svc/apis/batch/v1/namespaces/xnat/jobs"));
        assertThat(logged, containsString("Authorization: \u2588\u2588"));
        assertThat(logged, not(containsString("service-account-token")));
        assertThat(logged, not(containsString("alias-token-secret")));
    }

}
