package org.nrg.containers.model;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import org.junit.Test;
import org.nrg.containers.model.server.docker.Backend;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.model.server.docker.JobNamespaceMode;
import org.nrg.containers.model.server.docker.SecretVolumeType;

import java.util.Date;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

/**
 * The six backend settings behind Kubernetes file secrets (design doc section 3.1): JSON defaults, round trip,
 * entity mapping and validation. Only meaningful on the Kubernetes backend; other backends must store them unchanged.
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
}
