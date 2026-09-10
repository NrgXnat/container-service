package org.nrg.containers.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import org.junit.Test;
import org.nrg.containers.exceptions.InvalidDefinitionException;
import org.nrg.containers.model.server.docker.DockerServerBase;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;

import java.util.Date;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

/**
 * The settings paths where a defect would silently make cleanup more aggressive than the administrator asked for.
 * Ordinary round-tripping of the five properties is left to the existing DockerServerEntity and DockerRestApi
 * tests, and is visible directly on GET /xapi/docker/server.
 */
public class DockerServerBuildDirSettingsTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new GuavaModule());

    private static String json(final String extraProperties) {
        return "{\"name\":\"test\",\"host\":\"unix:///var/run/docker.sock\"" + extraProperties + "}";
    }

    /**
     * The boxed-Integer trap. Were the create parameters primitive int, an omitted property would deserialize to 0,
     * meaning "delete on the next scheduled run" - the opposite of a safe default. An explicit 0 must still be
     * honoured, so absent and zero have to stay distinguishable.
     */
    @Test
    public void omittedPropertiesDefaultButExplicitZeroIsHonoured() throws Exception {
        final DockerServer omitted = mapper.readValue(json(""), DockerServer.class);

        assertThat(omitted.buildDirCleanupEnabled(), is(false));
        assertThat(omitted.buildDirRetainDaysCompleted(), is(7));
        assertThat(omitted.buildDirRetainDaysFailed(), is(14));
        assertThat(omitted.buildDirRetainDaysKilled(), is(1));
        assertThat(omitted.buildDirCleanupTime(), is("02:00"));

        final DockerServer explicitZero = mapper.readValue(json(
                ",\"build-dir-retain-days-completed\":0,\"build-dir-retain-days-failed\":0"), DockerServer.class);

        assertThat(explicitZero.buildDirRetainDaysCompleted(), is(0));
        assertThat(explicitZero.buildDirRetainDaysFailed(), is(0));
        assertThat("untouched properties still default", explicitZero.buildDirRetainDaysKilled(), is(1));
    }

    /**
     * updateEventCheckTime rebuilds the whole pojo and runs on the container status polling path. If it dropped
     * these values, a long custom retention would silently collapse back to the shorter defaults every few
     * seconds, deleting sooner than configured.
     */
    @Test
    public void updateEventCheckTimePreservesTheSettings() {
        final DockerServer original = DockerServer.builder()
                .name("test").host("h")
                .buildDirCleanupEnabled(true)
                .buildDirRetainDaysCompleted(300)
                .buildDirRetainDaysFailed(21)
                .buildDirRetainDaysKilled(5)
                .buildDirCleanupTime("04:30")
                .build();

        final DockerServer updated = original.updateEventCheckTime(new Date());

        assertThat(updated.buildDirCleanupEnabled(), is(true));
        assertThat(updated.buildDirRetainDaysCompleted(), is(300));
        assertThat(updated.buildDirRetainDaysFailed(), is(21));
        assertThat(updated.buildDirRetainDaysKilled(), is(5));
        assertThat(updated.buildDirCleanupTime(), is("04:30"));
    }

    /** Retention has to stay inside 0-364, so a typo cannot turn into a retention window nobody intended. */
    @Test
    public void validateBoundsRetentionToZeroThrough364() throws Exception {
        validBase()
                .buildDirRetainDaysCompleted(0)
                .buildDirRetainDaysFailed(DockerServerBase.MAX_BUILD_DIR_RETAIN_DAYS)
                .buildDirRetainDaysKilled(0)
                .buildDirCleanupTime("00:00")
                .build()
                .validate();

        assertValidationError(validBase().buildDirRetainDaysCompleted(-1).build(), "completed");
        assertValidationError(validBase().buildDirRetainDaysFailed(-1).build(), "failed");
        assertValidationError(validBase().buildDirRetainDaysKilled(-1).build(), "killed");
        assertValidationError(validBase().buildDirRetainDaysCompleted(365).build(), "between 0 and 364");
        assertValidationError(validBase().buildDirRetainDaysFailed(1000).build(), "between 0 and 364");
    }

    private static DockerServer.Builder validBase() {
        return DockerServer.builder().name("test").host("unix:///var/run/docker.sock");
    }

    private static void assertValidationError(final DockerServer server, final String expectedFragment) {
        try {
            server.validate();
            fail("Expected InvalidDefinitionException for: " + expectedFragment);
        } catch (InvalidDefinitionException e) {
            assertThat(e.getMessage(), containsString(expectedFragment));
        }
    }
}
