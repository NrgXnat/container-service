package org.nrg.containers.api;

import com.github.dockerjava.api.model.HostConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.SystemUtils;
import org.hamcrest.CustomTypeSafeMatcher;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.nrg.containers.events.model.DockerContainerEvent;
import org.nrg.containers.exceptions.DockerServerException;
import org.nrg.containers.exceptions.NoDockerServerException;
import org.nrg.containers.model.dockerhub.DockerHubBase;
import org.nrg.containers.model.image.docker.DockerImage;
import org.nrg.containers.model.server.docker.Backend;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.services.DockerHubService;
import org.nrg.containers.services.DockerServerService;
import org.nrg.containers.utils.BackendConfig;
import org.nrg.containers.utils.TestingUtils;
import org.nrg.framework.exceptions.NotFoundException;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isIn;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;
import static org.nrg.containers.utils.TestingUtils.BUSYBOX;

@Slf4j
@RunWith(MockitoJUnitRunner.class)
public class DockerControlApiIntegrationTest {
    private static final String ALPINE_LATEST = "alpine:latest";
    private static final String BUSYBOX_SPECIFIC_VERSION_ID = "sha256:3596868f4ba86907dde5849edf4f426f4dc2110b1ace9e5969f5a21838ca9599";
    private static final String BUSYBOX_SPECIFIC_VERSION_NAME = "busybox:1.36.0";
    private static final DockerHubBase.DockerHub DOCKER_HUB = DockerHubBase.DockerHub.DEFAULT;

    private final static List<String> containersToCleanUp = new ArrayList<>();
    private final static List<String> imagesToCleanUp = new ArrayList<>();

    @Mock private DockerServerService dockerServerService;
    @Mock private DockerHubService dockerHubService;
    @Mock private KubernetesClientFactory kubernetesClientFactory;

    private static DockerControlApi controlApi;

    @Rule public ExpectedException exception = ExpectedException.none();

    @BeforeClass
    public static void setupClass() {
        TestingUtils.skipIfNotRunningIntegrationTests();
    }

    @Before
    public void setup() throws Exception {
        final BackendConfig backendConfig = TestingUtils.getBackendConfig();
        final DockerServer dockerServer = DockerServer.builder()
                .name("Test server")
                .host(backendConfig.getContainerHost())
                .certPath(backendConfig.getCertPath())
                .backend(Backend.DOCKER)
                .lastEventCheckTime(new Date())
                .build();
        when(dockerServerService.getServer()).thenReturn(dockerServer);

        controlApi = new DockerControlApi(dockerServerService, dockerHubService, kubernetesClientFactory);

        TestingUtils.skipIfCannotConnectToDocker(controlApi.getDockerClient());

    }

    @AfterClass
    public static void classCleanup() throws Exception {
        if (controlApi != null) {
            TestingUtils.cleanDockerContainers(controlApi.getDockerClient(), containersToCleanUp);
            TestingUtils.cleanDockerImages(controlApi.getDockerClient(), imagesToCleanUp);
        }
    }


    @Test
    public void testPingServer() throws Exception {
        assertTrue(TestingUtils.canConnectToDocker(controlApi.getDockerClient()));
    }

    @Test
    public void testGetAllImages() throws Exception {

        imagesToCleanUp.add(BUSYBOX);
        controlApi.pullImage(BUSYBOX);

        imagesToCleanUp.add(ALPINE_LATEST);
        controlApi.pullImage(ALPINE_LATEST);

        final List<DockerImage> images = controlApi.getAllImages();

        final Set<String> imageNames = images.stream()
                .map(DockerImage::tags)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .collect(Collectors.toSet());
        assertThat(BUSYBOX, isIn(imageNames));
        assertThat(ALPINE_LATEST, isIn(imageNames));
    }

    @Test
    public void testPingHub() throws Exception {
        assertThat(controlApi.pingHub(DOCKER_HUB).ping(), is(true));
    }

    @Test
    public void testPullImage() throws Exception {
        imagesToCleanUp.add(BUSYBOX);

        controlApi.pullImage(BUSYBOX, DOCKER_HUB);
    }

    @Test
    public void testPullPrivateImage() throws Exception {
        final String privateImageName = "xnattest/private";
        imagesToCleanUp.add(privateImageName);

        final String description = "NotFoundException with message containing image name \"" + privateImageName + "\"";
        exception.expect(new CustomTypeSafeMatcher<NotFoundException>(description) {
            @Override
            protected boolean matchesSafely(final NotFoundException ex) {
                return ex.getMessage().contains(privateImageName);
            }
        });
        controlApi.pullImage(privateImageName, DOCKER_HUB);

        final DockerImage test = controlApi.pullImage(privateImageName, DOCKER_HUB, "xnattest", "windmill susanna portico",
                "farfegnugen", "holla@me.com");
        assertNotNull(test);
    }

    @Test
    public void testDeleteImage() throws NotFoundException, NoDockerServerException, DockerServerException {
        imagesToCleanUp.add(BUSYBOX_SPECIFIC_VERSION_NAME);
        controlApi.pullImage(BUSYBOX_SPECIFIC_VERSION_NAME);
        int beforeImageCount = controlApi.getAllImages().size();
        controlApi.deleteImageById(BUSYBOX_SPECIFIC_VERSION_ID, true);
        List<DockerImage> images = controlApi.getAllImages();
        int afterImageCount = images.size();
        assertThat(afterImageCount+1, is(beforeImageCount));
        for (DockerImage image:images){
            assertThat(image.imageId(), is(not(BUSYBOX_SPECIFIC_VERSION_ID)));
        }
    }

    @Test(timeout = 10000)
    public void testEventPolling() throws Exception {
        log.debug("Starting event polling test.");

        if (!Boolean.parseBoolean(SystemUtils.getEnvironmentVariable("CI", "false"))) {
            // If we aren't running in a CI environment, then we can assume docker is running in a VM and
            //   isn't running natively on the host machine. Sometimes the clocks on the host and VM
            //   can get out of sync, causing this test to fail. This especially happens on laptops that
            //   have docker running and go to sleep.
            // We can run this container command:
            //     docker run --rm --privileged alpine hwclock -s
            // to sync up the clocks. It requires 'privileged' mode, which may cause problems
            // running in a CI environment.
            log.debug("Synchronizing host and vm clocks.");

            controlApi.pullImage(ALPINE_LATEST);
            imagesToCleanUp.add(ALPINE_LATEST);

            // Have to use the client directly because the controlApi doesn't expose the hostconfig
            final String containerId = controlApi.getDockerClient().createContainerCmd(ALPINE_LATEST)
                    .withCmd("hwclock", "-s")
                    .withHostConfig(new HostConfig()
                            .withPrivileged(true)
                            .withAutoRemove(true))
                    .exec()
                    .getId();

            containersToCleanUp.add(containerId);
            log.info("Starting container {}", containerId);
            controlApi.getDockerClient().startContainerCmd(containerId).exec();
        }

        final Date start = new Date();
        log.debug("Start time is {}", start.getTime() / 1000);
        Thread.sleep(1000); // Wait to ensure we get some events

        imagesToCleanUp.add(BUSYBOX);
        controlApi.pullImage(BUSYBOX);

        // Create container, to ensure we have some events to read
        log.debug("Creating container");
        final String containerId = controlApi.getDockerClient()
                .createContainerCmd(BUSYBOX)
                .withCmd("sh", "-c", "echo Hello world")
                .exec()
                .getId();
        containersToCleanUp.add(containerId);
        log.debug("Starting container");
        controlApi.getDockerClient().startContainerCmd(containerId).exec();

        Thread.sleep(1000); // Wait to ensure we get some events
        final Date end = new Date();
        log.debug("End time is {}", end.getTime() / 1000);

        log.debug("Checking for events in the time window.");
        final List<DockerContainerEvent> events = controlApi.getContainerEvents(start, end);

        // The fact that we have a list of events and not a timeout failure is already a victory
        assertThat(events, not(empty()));

        // TODO assert more things about the events
    }
}


