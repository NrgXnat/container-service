package org.nrg.containers.api;

import io.kubernetes.client.openapi.models.V1Affinity;
import io.kubernetes.client.openapi.models.V1NodeSelectorRequirement;
import io.kubernetes.client.openapi.models.V1Toleration;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.nrg.containers.model.server.docker.DockerServerBase.KubernetesToleration;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.CoreMatchers.is;
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

}
