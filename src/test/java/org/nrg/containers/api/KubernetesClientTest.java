package org.nrg.containers.api;

import io.kubernetes.client.openapi.models.V1Affinity;
import io.kubernetes.client.openapi.models.V1NodeSelectorRequirement;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;

import java.util.Collections;

import static org.hamcrest.CoreMatchers.is;
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
}
