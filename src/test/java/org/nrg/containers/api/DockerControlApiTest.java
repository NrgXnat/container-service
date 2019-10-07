package org.nrg.containers.api;

import com.google.common.collect.Lists;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.exceptions.DockerException;
import com.spotify.docker.client.messages.ContainerConfig;
import com.spotify.docker.client.messages.ContainerCreation;
import com.spotify.docker.client.messages.HostConfig;
import com.spotify.docker.client.messages.Info;
import com.spotify.docker.client.messages.Version;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.hamcrest.CustomTypeSafeMatcher;
import org.hamcrest.Matcher;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.nrg.containers.config.DockerControlApiTestConfig;
import org.nrg.containers.events.model.DockerContainerEvent;
import org.nrg.containers.exceptions.DockerServerException;
import org.nrg.containers.exceptions.NoDockerServerException;
import org.nrg.containers.model.dockerhub.DockerHubBase;
import org.nrg.containers.model.image.docker.DockerImage;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.services.DockerServerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

import static java.lang.System.getenv;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isEmptyOrNullString;
import static org.hamcrest.Matchers.isIn;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeThat;
import static org.mockito.Mockito.when;

@Slf4j
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = DockerControlApiTestConfig.class)
public class DockerControlApiTest {
    private static String CONTAINER_HOST;
    private static String CERT_PATH;

    private static DockerClient CLIENT;

    private static final String BUSYBOX_LATEST = "busybox:latest";
    private static final String ALPINE_LATEST = "alpine:latest";
    // private static final String KELSEYM_PYDICOM = "kelseym/pydicom:latest";
    private static final String BUSYBOX_ID = "sha256:47bcc53f74dc94b1920f0b34f6036096526296767650f223433fe65c35f149eb";
    private static final String BUSYBOX_NAME = "busybox:1.24.2-uclibc";
    private static final DockerHubBase.DockerHub DOCKER_HUB = DockerHubBase.DockerHub.DEFAULT;

    @Autowired private DockerControlApi controlApi;
    @Autowired private DockerServerService mockDockerServerService;

    @Rule public ExpectedException exception = ExpectedException.none();

    @Before
    public void setup() throws Exception {

        final String defaultHost = "unix:///var/run/docker.sock";
        final String hostEnv = System.getenv("DOCKER_HOST");
        final String certPathEnv = System.getenv("DOCKER_CERT_PATH");
        final String tlsVerify = System.getenv("DOCKER_TLS_VERIFY");

        final boolean useTls = tlsVerify != null && tlsVerify.equals("1");
        if (useTls) {
            if (StringUtils.isBlank(certPathEnv)) {
                throw new Exception("Must set DOCKER_CERT_PATH if DOCKER_TLS_VERIFY=1.");
            }
            CERT_PATH = certPathEnv;
        } else {
            CERT_PATH = "";
        }

        if (StringUtils.isBlank(hostEnv)) {
            CONTAINER_HOST = defaultHost;
        } else {
            final Pattern tcpShouldBeHttpRe = Pattern.compile("tcp://.*");
            final java.util.regex.Matcher tcpShouldBeHttpMatch = tcpShouldBeHttpRe.matcher(hostEnv);
            if (tcpShouldBeHttpMatch.matches()) {
                // Must switch out tcp:// for either http:// or https://
                CONTAINER_HOST = hostEnv.replace("tcp://", "http" + (useTls ? "s" : "") + "://");
            } else {
                CONTAINER_HOST = hostEnv;
            }
        }

        final DockerServer mockDockerServer = DockerServer.create(0L, "Test server", CONTAINER_HOST, CERT_PATH, false, null, null, null, false, null, null);
        when(mockDockerServerService.getServer()).thenReturn(mockDockerServer);

        CLIENT = controlApi.getClient();
    }

    private boolean canConnectToDocker() {
        try {
            return CLIENT.ping().equals("OK");
        } catch (InterruptedException | DockerException e) {
            log.warn("Could not connect to docker.", e);
        }
        return false;
    }

    @Test
    public void testPingServer() throws Exception {
        assertThat(canConnectToDocker(), is(true));
    }

    @Test
    public void testGetAllImages() throws Exception {
        assumeThat(canConnectToDocker(), is(true));

        CLIENT.pull(BUSYBOX_LATEST);
        CLIENT.pull(ALPINE_LATEST);
        final List<DockerImage> images = controlApi.getAllImages();

        final List<String> imageNames = imagesToTags(images);
        assertThat(BUSYBOX_LATEST, isIn(imageNames));
        assertThat(ALPINE_LATEST, isIn(imageNames));
    }

    private List<String> imagesToTags(final List<DockerImage> images) {
        final List<String> tags = Lists.newArrayList();
        for (final DockerImage image : images) {
            if (image.tags() != null) {
                tags.addAll(image.tags());
            }
        }
        return tags;
    }

//    @Test
//    public void testLaunchImage() throws Exception {
//        final List<String> cmd = Lists.newArrayList("ls", "/data/pyscript.py");
//        final List<String> vol =
//            Lists.newArrayList("/Users/Kelsey/Projects/XNAT/1.7/pydicomDocker/data:/data");
//
//        client.pull(KELSEYM_PYDICOM);
//        String containerId = controlApi.launchImage(KELSEYM_PYDICOM, cmd, vol);
//        assertThat(containerId, not(isEmptyOrNullString()));
//    }

//    @Test
//    public void testLaunchPythonScript() throws Exception {
//       // python pyscript.py -h <hostname> -u <user> -p <password> -s <session_id>
//        final List<String> cmd = Lists.newArrayList(
//            "python", "/data/pyscript.py",
//            "-h", "https://central.xnat.org",
//            "-u", "admin",
//            "-p", "admin",
//            "-s", "CENTRAL_E07096"
//        );
//        final List<String> vol =
//            Lists.newArrayList("/Users/Kelsey/Projects/XNAT/1.7/pydicomDocker/data:/data");
//
//        client.pull(KELSEYM_PYDICOM);
//        String containerId = controlApi.launchImage(KELSEYM_PYDICOM, cmd, vol);
//    }

    @Test
    public void testPingHub() throws Exception {
        assumeThat(canConnectToDocker(), is(true));

        assertThat(controlApi.pingHub(DOCKER_HUB), is("OK"));
    }

    @Test
    public void testPullImage() throws Exception {
        assumeThat(canConnectToDocker(), is(true));

        controlApi.pullImage(BUSYBOX_LATEST, DOCKER_HUB);
    }

    @Test
    public void testPullPrivateImage() throws Exception {
        assumeThat(canConnectToDocker(), is(true));

        final String privateImageName = "xnattest/private";
        exception.expect(imageNotFoundException(privateImageName));
        controlApi.pullImage(privateImageName, DOCKER_HUB);

        final DockerImage test = controlApi.pullImage(privateImageName, DOCKER_HUB, "xnattest", "windmill susanna portico");
        assertNotNull(test);
    }

    @Test
    public void testDeleteImage() throws DockerException, InterruptedException, NoDockerServerException, DockerServerException {
        assumeThat(canConnectToDocker(), is(true));

        CLIENT.pull(BUSYBOX_NAME);
        int beforeImageCount = CLIENT.listImages().size();
        controlApi.deleteImageById(BUSYBOX_ID, true);
        List<com.spotify.docker.client.messages.Image> images = CLIENT.listImages();
        int afterImageCount = images.size();
        assertThat(afterImageCount+1, is(beforeImageCount));
        for(com.spotify.docker.client.messages.Image image:images){
            assertThat(image.id(), is(not(BUSYBOX_ID)));
        }
    }

    @Test(timeout = 10000)
    public void testEventPolling() throws Exception {
        assumeThat(getenv("CIRCLECI"), isEmptyOrNullString());  // This test is flaky on circle, so skip it
        assumeThat(canConnectToDocker(), is(true));
        log.debug("Starting event polling test.");

        if (log.isDebugEnabled()) {
            final Version version = CLIENT.version();
            log.debug("Docker version: {}", version);
        }
        
        final Info dockerInfo = CLIENT.info();
        if (dockerInfo.kernelVersion().contains("moby")) {
            // If we are running docker in the moby VM, then it isn't running natively
            //   on the host machine. Sometimes the clocks on the host and VM can get out
            //   out sync, and this test will fail. This especially happens on laptops that
            //   have docker running and go to sleep.
            // We can run this container command:
            //     docker run --rm --privileged alpine hwclock -s
            // to sync up the clocks. It requires 'privileged' mode, which may cause problems
            // running in a CI environment.
            log.debug("Synchronizing host and vm clocks.");
            final ContainerConfig containerConfig = ContainerConfig.builder()
                    .image("alpine")
                    .cmd(new String[]{"hwclock", "-s"})
                    .hostConfig(HostConfig.builder()
                            .privileged(true)
                            .autoRemove(true)
                            .build())
                    .build();
            final ContainerCreation containerCreation = CLIENT.createContainer(containerConfig);
            CLIENT.startContainer(containerCreation.id());
        }

        final Date start = new Date();
        log.debug("Start time is {}", start.getTime() / 1000);
        Thread.sleep(1000); // Wait to ensure we get some events

        controlApi.pullImage(BUSYBOX_LATEST);

        // Create container, to ensure we have some events to read
        final ContainerConfig config = ContainerConfig.builder()
                .image(BUSYBOX_LATEST)
                .cmd("sh", "-c", "echo Hello world")
                .build();

        final ContainerCreation creation = CLIENT.createContainer(config);
        CLIENT.startContainer(creation.id());

        Thread.sleep(1000); // Wait to ensure we get some events
        final Date end = new Date();
        log.debug("End time is {}", end.getTime() / 1000);

        log.debug("Checking for events in the time window.");
        final List<DockerContainerEvent> events = controlApi.getContainerEvents(start, end);

        // The fact that we have a list of events and not a timeout failure is already a victory
        assertThat(events, not(empty()));

        // TODO assert more things about the events
    }

    private Matcher<Exception> imageNotFoundException(final String name) {
        final String exceptionMessage = "Image not found: " + name;
        final String description = "Image not found exception with image name " + name;
        return new CustomTypeSafeMatcher<Exception>(description) {
            @Override
            protected boolean matchesSafely(final Exception ex) {
                return ex.getMessage().contains(exceptionMessage);
            }
        };
    }
}


