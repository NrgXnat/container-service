package org.nrg.containers.model;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import org.apache.commons.lang3.StringUtils;
import org.junit.Test;
import org.nrg.containers.exceptions.InvalidDefinitionException;
import org.nrg.containers.model.server.docker.Backend;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServerWithPing;
import org.nrg.containers.model.server.docker.DockerServerEntity;
import org.nrg.containers.model.server.docker.JobNamespaceMode;
import org.nrg.containers.model.server.docker.SecretVolumeType;

import java.util.Date;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

/**
 * The six backend settings behind Kubernetes file secrets (design doc section 3.1): JSON defaults, round trip, enum
 * parsing, the entity round trip and the validate() rules, which apply to the Kubernetes backend only.
 */
public class DockerServerSecretSettingsTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new GuavaModule());

    private static String json(final String extraProperties) {
        return "{\"name\":\"test\",\"host\":\"unix:///var/run/docker.sock\"" + extraProperties + "}";
    }

    @Test
    public void omittedSecretSettingsFallBackToDefaults() throws Exception {
        final DockerServer server = mapper.readValue(json(""), DockerServer.class);

        assertThat(server.secretNameTemplate(), is(nullValue()));
        assertThat(server.secretVolumeType(), is(SecretVolumeType.SECRET));
        assertThat(server.jobNamespaceMode(), is(JobNamespaceMode.SINGLE));
        assertThat(server.jobNamespaceTemplate(), is(nullValue()));
        assertThat(server.jobServiceAccount(), is(nullValue()));
        assertThat(server.secretMountTimeoutSeconds(), is(600));
    }

    @Test
    public void secretSettingsRoundTripThroughJson() throws Exception {
        final DockerServer server = mapper.readValue(json(
                ",\"backend\":\"kubernetes\"" +
                ",\"secret-name-template\":\"site-#PROJECT#-{identifier}\"" +
                ",\"secret-volume-type\":\"csi\"" +
                ",\"job-namespace-mode\":\"per-project\"" +
                ",\"job-namespace-template\":\"xnat-#PROJECT#\"" +
                ",\"job-service-account\":\"xnat-jobs\"" +
                ",\"secret-mount-timeout-seconds\":0"), DockerServer.class);

        assertThat(server.secretNameTemplate(), is("site-#PROJECT#-{identifier}"));
        assertThat(server.secretVolumeType(), is(SecretVolumeType.CSI));
        assertThat(server.jobNamespaceMode(), is(JobNamespaceMode.PER_PROJECT));
        assertThat(server.jobNamespaceTemplate(), is("xnat-#PROJECT#"));
        assertThat(server.jobServiceAccount(), is("xnat-jobs"));
        assertThat("explicit zero disables the watchdog and must not become the default", server.secretMountTimeoutSeconds(), is(0));

        final String written = mapper.writeValueAsString(server);
        assertThat(written, containsString("\"secret-volume-type\":\"csi\""));
        assertThat(written, containsString("\"job-namespace-mode\":\"per-project\""));
        assertThat(mapper.readValue(written, DockerServer.class).jobNamespaceMode(), is(JobNamespaceMode.PER_PROJECT));
    }

    /** The settings form submits "" for an unselected option; that must mean "default", not a 400. */
    @Test
    public void blankEnumValuesFallBackToDefaults() throws Exception {
        final DockerServer server = mapper.readValue(json(
                ",\"secret-volume-type\":\"\",\"job-namespace-mode\":\"\",\"job-service-account\":\"  \""), DockerServer.class);

        assertThat(server.secretVolumeType(), is(SecretVolumeType.SECRET));
        assertThat(server.jobNamespaceMode(), is(JobNamespaceMode.SINGLE));
        assertThat("blank strings are stored as null", server.jobServiceAccount(), is(nullValue()));
    }

    @Test
    public void unknownVolumeTypeIsRejected() throws Exception {
        try {
            mapper.readValue(json(",\"secret-volume-type\":\"vault\""), DockerServer.class);
            fail("Expected JsonMappingException");
        } catch (JsonMappingException e) {
            assertThat(e.getMessage(), containsString("vault"));
        }
    }

    /** The with-ping JSON creator must forward the settings to the plain creator, not let defaults replace them. */
    @Test
    public void serverWithPingJsonKeepsSecretSettings() throws Exception {
        final DockerServerWithPing server = mapper.readValue(json(
                ",\"secret-volume-type\":\"csi\",\"ping\":true"), DockerServerWithPing.class);

        assertThat(server.secretVolumeType(), is(SecretVolumeType.CSI));
        assertThat(server.ping(), is(true));
    }

    /** updateEventCheckTime rebuilds the pojo on the status polling path; dropped settings would silently reset. */
    @Test
    public void updateEventCheckTimePreservesSecretSettings() {
        final DockerServer original = DockerServer.builder()
                .name("test").host("h").backend(Backend.KUBERNETES)
                .secretNameTemplate("site-#PROJECT#-{identifier}")
                .secretVolumeType(SecretVolumeType.CSI)
                .jobNamespaceMode(JobNamespaceMode.PER_PROJECT)
                .jobNamespaceTemplate("xnat-#PROJECT#")
                .jobServiceAccount("xnat-jobs")
                .secretMountTimeoutSeconds(30)
                .build();

        final DockerServer updated = original.updateEventCheckTime(new Date());

        assertThat(updated.secretNameTemplate(), is("site-#PROJECT#-{identifier}"));
        assertThat(updated.secretVolumeType(), is(SecretVolumeType.CSI));
        assertThat(updated.jobNamespaceMode(), is(JobNamespaceMode.PER_PROJECT));
        assertThat(updated.jobNamespaceTemplate(), is("xnat-#PROJECT#"));
        assertThat(updated.jobServiceAccount(), is("xnat-jobs"));
        assertThat(updated.secretMountTimeoutSeconds(), is(30));
    }

    @Test
    public void entityRoundTripKeepsSecretSettings() {
        final DockerServer server = DockerServer.builder()
                .name("k8s").host("").backend(Backend.KUBERNETES)
                .secretNameTemplate("site-#PROJECT#-{identifier}")
                .secretVolumeType(SecretVolumeType.CSI)
                .jobNamespaceMode(JobNamespaceMode.PER_PROJECT)
                .jobNamespaceTemplate("xnat-#PROJECT#")
                .jobServiceAccount("xnat-jobs")
                .secretMountTimeoutSeconds(120)
                .build();

        final DockerServer back = DockerServer.create(DockerServerEntity.create(server));

        assertThat(back.secretNameTemplate(), is("site-#PROJECT#-{identifier}"));
        assertThat(back.secretVolumeType(), is(SecretVolumeType.CSI));
        assertThat(back.jobNamespaceMode(), is(JobNamespaceMode.PER_PROJECT));
        assertThat(back.jobNamespaceTemplate(), is("xnat-#PROJECT#"));
        assertThat(back.jobServiceAccount(), is("xnat-jobs"));
        assertThat(back.secretMountTimeoutSeconds(), is(120));
    }

    /** A pre-upgrade row has NULL in the new columns; hydration must not break and defaults must apply. */
    @Test
    public void nullEntityColumnsFallBackToDefaults() {
        final DockerServerEntity entity = new DockerServerEntity();
        entity.setSecretMountTimeoutSeconds(null);
        entity.setSecretVolumeType(null);
        entity.setJobNamespaceMode(null);

        assertThat(entity.getSecretMountTimeoutSeconds(), is(600));
        assertThat("negative values are clamped, not left to fail validation on every status poll",
                clampedTimeout(-5), is(0));
        entity.setBackend(Backend.KUBERNETES);   // AutoValue rejects a null backend; a real row always has one
        final DockerServer server = DockerServer.create(entity);
        assertThat(server.secretVolumeType(), is(SecretVolumeType.SECRET));
        assertThat(server.jobNamespaceMode(), is(JobNamespaceMode.SINGLE));
    }

    private static int clampedTimeout(final int value) {
        final DockerServerEntity entity = new DockerServerEntity();
        entity.setSecretMountTimeoutSeconds(value);
        return entity.getSecretMountTimeoutSeconds();
    }

    private static DockerServer.Builder kubernetesBase() {
        return DockerServer.builder().name("k8s").host("").backend(Backend.KUBERNETES);
    }

    @Test
    public void kubernetesSecretNameTemplateMustContainIdentifierPlaceholder() throws Exception {
        kubernetesBase().secretNameTemplate("site-#PROJECT#-{identifier}").build().validate();
        kubernetesBase().secretNameTemplate(null).build().validate();   // blank means the built-in default
        assertValidationError(kubernetesBase().secretNameTemplate("site-#PROJECT#").build(), "{identifier}");
    }

    @Test
    public void perProjectModeRequiresARenderableNamespaceTemplate() throws Exception {
        kubernetesBase().jobNamespaceMode(JobNamespaceMode.PER_PROJECT).jobNamespaceTemplate("xnat-#PROJECT#").build().validate();
        assertValidationError(kubernetesBase().jobNamespaceMode(JobNamespaceMode.PER_PROJECT).build(), "required");
        assertValidationError(kubernetesBase().jobNamespaceMode(JobNamespaceMode.PER_PROJECT).jobNamespaceTemplate("xnat-jobs").build(), "#PROJECT#");
        assertValidationError(kubernetesBase().jobNamespaceMode(JobNamespaceMode.PER_PROJECT)
                .jobNamespaceTemplate(StringUtils.repeat('n', 70) + "-#PROJECT#").build(), "63");
        // single mode never looks at the template
        kubernetesBase().jobNamespaceMode(JobNamespaceMode.SINGLE).jobNamespaceTemplate("nonsense").build().validate();
    }

    @Test
    public void negativeMountTimeoutIsRejected() {
        assertValidationError(kubernetesBase().secretMountTimeoutSeconds(-1).build(), "0 or more");
    }

    /** Docker and Swarm ignore these settings; storing a bad value there must not block saving the server. */
    @Test
    public void otherBackendsIgnoreTheKubernetesSecretSettings() throws Exception {
        DockerServer.builder().name("d").host("unix:///var/run/docker.sock").backend(Backend.DOCKER)
                .secretNameTemplate("no-placeholder")
                .jobNamespaceMode(JobNamespaceMode.PER_PROJECT)
                .secretMountTimeoutSeconds(-1)
                .build().validate();
        DockerServer.builder().name("s").host("tcp://swarm:2375").backend(Backend.SWARM)
                .secretNameTemplate("no-placeholder")
                .build().validate();
    }

    private static void assertValidationError(final DockerServer server, final String expectedFragment) {
        try {
            server.validate();
            fail("Expected InvalidDefinitionException containing: " + expectedFragment);
        } catch (InvalidDefinitionException e) {
            assertThat(e.getMessage(), containsString(expectedFragment));
        }
    }
}
