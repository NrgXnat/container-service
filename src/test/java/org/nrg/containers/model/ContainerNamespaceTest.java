package org.nrg.containers.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.nrg.containers.config.ObjectMapperConfig;
import org.nrg.containers.model.command.auto.ResolvedCommand;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.entity.ContainerEntity;
import org.nrg.containers.model.server.docker.Backend;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

/**
 * The Kubernetes namespace a job was created in travels with the container record (design doc section 3.4),
 * so later unsuspend / log / delete calls and event matching use the recorded value, not the current setting.
 */
public class ContainerNamespaceTest {
    private static final ObjectMapper MAPPER = new ObjectMapperConfig().objectMapper();

    private static ResolvedCommand resolvedCommand(final String namespace) {
        return ResolvedCommand.builder()
                .image("busybox:latest").commandLine("true").commandId(0L).commandName("c").wrapperId(0L).wrapperName("w")
                .namespace(namespace)
                .build();
    }

    @Test
    public void namespaceSurvivesResolvedCommandContainerAndEntityRoundTrip() throws Exception {
        final Container container = Container.builderFromResolvedCommand(resolvedCommand("xnat-proj01"))
                .userId("u").backend(Backend.KUBERNETES).build();
        assertThat(container.namespace(), is("xnat-proj01"));

        final Container fromEntity = Container.create(ContainerEntity.fromPojo(container));
        assertThat(fromEntity.namespace(), is("xnat-proj01"));

        final String json = MAPPER.writeValueAsString(fromEntity);
        assertThat(json, containsString("\"namespace\":\"xnat-proj01\""));
        assertThat(MAPPER.readValue(json, Container.class).namespace(), is("xnat-proj01"));
    }

    /** Single mode and pre-upgrade rows: null means "the kubeconfig context namespace". */
    @Test
    public void namespaceStaysNullWhenNotSet() {
        final Container container = Container.builderFromResolvedCommand(resolvedCommand(null)).userId("u").build();
        assertThat(container.namespace(), is(nullValue()));
        assertThat(Container.create(ContainerEntity.fromPojo(container)).namespace(), is(nullValue()));
    }
}
