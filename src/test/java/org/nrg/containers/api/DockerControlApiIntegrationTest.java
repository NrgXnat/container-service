package org.nrg.containers.api;

import lombok.extern.slf4j.Slf4j;
import org.hamcrest.CustomTypeSafeMatcher;
import org.hamcrest.Matcher;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mandas.docker.client.DockerClient;
import org.mandas.docker.client.exceptions.DockerException;
import org.mandas.docker.client.messages.ContainerConfig;
import org.mandas.docker.client.messages.ContainerCreation;
import org.mandas.docker.client.messages.HostConfig;
import org.mandas.docker.client.messages.Info;
import org.mandas.docker.client.messages.Version;
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

import java.util.Date;
import java.util.HashSet;
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
import static org.mockito.Mockito.when;

@Slf4j
@RunWith(MockitoJUnitRunner.class)
public class DockerControlApiIntegrationTest {
    private static final String BUSYBOX_LATEST = "busybox:latest";
    private static final String ALPINE_LATEST = "alpine:latest";
    private static final String BUSYBOX_ID = "sha256:47bcc53f74dc94b1920f0b34f6036096526296767650f223433fe65c35f149eb";
    private static final String BUSYBOX_NAME = "busybox:1.24.2-uclibc";
    private static final DockerHubBase.DockerHub DOCKER_HUB = DockerHubBase.DockerHub.DEFAULT;

    private final static Set<String> containersToCleanUp = new HashSet<>();
    private final static Set<String> imagesToCleanUp = new HashSet<>();

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
        for (final String containerToCleanUp : containersToCleanUp) {
            try {
                controlApi.getDockerClient().removeContainer(containerToCleanUp, DockerClient.RemoveContainerParam.forceKill());
            } catch (Exception e) {
                // do nothing
            }
        }
        containersToCleanUp.clear();

        for (final String imageToCleanUp : imagesToCleanUp) {
            try {
                controlApi.getDockerClient().removeImage(imageToCleanUp, true, false);
            } catch (Exception e) {
                // do nothing
            }
        }
        imagesToCleanUp.clear();
    }


    @Test
    public void testPingServer() throws Exception {
        assertThat(TestingUtils.canConnectToDocker(controlApi.getDockerClient()), is(true));
    }

    @Test
    public void testGetAllImages() throws Exception {

        imagesToCleanUp.add(BUSYBOX_LATEST);
        imagesToCleanUp.add(ALPINE_LATEST);

        controlApi.getDockerClient().pull(BUSYBOX_LATEST);
        controlApi.getDockerClient().pull(ALPINE_LATEST);
        final List<DockerImage> images = controlApi.getAllImages();

        final Set<String> imageNames = images.stream()
                .map(DockerImage::tags)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .collect(Collectors.toSet());
        assertThat(BUSYBOX_LATEST, isIn(imageNames));
        assertThat(ALPINE_LATEST, isIn(imageNames));
    }

    @Test
    public void testPingHub() throws Exception {
        assertThat(controlApi.pingHub(DOCKER_HUB).ping(), is(true));
    }

    @Test
    public void testPullImage() throws Exception {
        imagesToCleanUp.add(BUSYBOX_LATEST);

        controlApi.pullImage(BUSYBOX_LATEST, DOCKER_HUB);
    }

    @Test
    public void testPullPrivateImage() throws Exception {
        final String privateImageName = "xnattest/private";
        imagesToCleanUp.add(privateImageName);

        exception.expect(imageNotFoundException(privateImageName));
        controlApi.pullImage(privateImageName, DOCKER_HUB);

        final DockerImage test = controlApi.pullImage(privateImageName, DOCKER_HUB, "xnattest", "windmill susanna portico",
                "farfegnugen", "holla@me.com");
        assertNotNull(test);
    }

    @Test
    public void testDeleteImage() throws DockerException, InterruptedException, NoDockerServerException, DockerServerException {
        imagesToCleanUp.add(BUSYBOX_NAME);
        controlApi.getDockerClient().pull(BUSYBOX_NAME);
        int beforeImageCount = controlApi.getDockerClient().listImages().size();
        controlApi.deleteImageById(BUSYBOX_ID, true);
        List<org.mandas.docker.client.messages.Image> images = controlApi.getDockerClient().listImages();
        int afterImageCount = images.size();
        assertThat(afterImageCount+1, is(beforeImageCount));
        for(org.mandas.docker.client.messages.Image image:images){
            assertThat(image.id(), is(not(BUSYBOX_ID)));
        }
    }

    @Test(timeout = 10000)
    public void testEventPolling() throws Exception {
        log.debug("Starting event polling test.");

        if (log.isDebugEnabled()) {
            final Version version = controlApi.getDockerClient().version();
            log.debug("Docker version: {}", version);
        }
        
        final Info dockerInfo = controlApi.getDockerClient().info();
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
                    .cmd("hwclock", "-s")
                    .hostConfig(HostConfig.builder()
                            .privileged(true)
                            .autoRemove(true)
                            .build())
                    .build();
            imagesToCleanUp.add("alpine");
            final ContainerCreation containerCreation = controlApi.getDockerClient().createContainer(containerConfig);

            containersToCleanUp.add(containerCreation.id());
            controlApi.getDockerClient().startContainer(containerCreation.id());
        }

        final Date start = new Date();
        log.debug("Start time is {}", start.getTime() / 1000);
        Thread.sleep(1000); // Wait to ensure we get some events

        imagesToCleanUp.add(BUSYBOX_LATEST);
        controlApi.pullImage(BUSYBOX_LATEST);

        // Create container, to ensure we have some events to read
        final ContainerConfig config = ContainerConfig.builder()
                .image(BUSYBOX_LATEST)
                .cmd("sh", "-c", "echo Hello world")
                .build();

        final ContainerCreation creation = controlApi.getDockerClient().createContainer(config);
        containersToCleanUp.add(creation.id());
        controlApi.getDockerClient().startContainer(creation.id());

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


