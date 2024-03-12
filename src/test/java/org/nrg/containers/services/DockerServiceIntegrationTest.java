package org.nrg.containers.services;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mandas.docker.client.DockerClient;
import org.mandas.docker.client.exceptions.DockerException;
import org.mockito.Mockito;
import org.nrg.containers.api.DockerControlApi;
import org.nrg.containers.config.IntegrationTestConfig;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.image.docker.DockerImage;
import org.nrg.containers.utils.TestingUtils;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xft.security.UserI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeThat;
import static org.mockito.Mockito.when;
import static org.nrg.containers.model.server.docker.DockerServerBase.DockerServer.DockerServer;

@Slf4j
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = IntegrationTestConfig.class)
@Transactional
public class DockerServiceIntegrationTest {
    private UserI mockUser;

    private final String FAKE_USER = "mockUser";
    private final String FAKE_ALIAS = "alias";
    private final String FAKE_SECRET = "secret";
    private final String FAKE_HOST = "mock://url";

    private static DockerClient CLIENT;

    @Autowired private DockerControlApi controlApi;
    @Autowired private DockerService dockerService;
    @Autowired private CommandService commandService;
    @Autowired private DockerServerService dockerServerService;
    @Autowired private SiteConfigPreferences mockSiteConfigPreferences;
    @Autowired private UserManagementServiceI mockUserManagementServiceI;

    @Rule public TemporaryFolder folder = new TemporaryFolder(new File(System.getProperty("user.dir") + "/build"));

    @BeforeClass
    public static void setupClass() {
        TestingUtils.skipIfNotRunningIntegrationTests();
    }

    @Before
    public void setup() throws Exception {
        // Mock out the prefs bean
        final String defaultHost = "unix:///var/run/docker.sock";
        final String hostEnv = System.getenv("DOCKER_HOST");
        final String certPathEnv = System.getenv("DOCKER_CERT_PATH");
        final String tlsVerify = System.getenv("DOCKER_TLS_VERIFY");

        final boolean useTls = tlsVerify != null && tlsVerify.equals("1");
        final String certPath;
        if (useTls) {
            if (StringUtils.isBlank(certPathEnv)) {
                throw new Exception("Must set DOCKER_CERT_PATH if DOCKER_TLS_VERIFY=1.");
            }
            certPath = certPathEnv;
        } else {
            certPath = "";
        }

        final String containerHost;
        if (StringUtils.isBlank(hostEnv)) {
            containerHost = defaultHost;
        } else {
            final Pattern tcpShouldBeHttpRe = Pattern.compile("tcp://.*");
            final java.util.regex.Matcher tcpShouldBeHttpMatch = tcpShouldBeHttpRe.matcher(hostEnv);
            if (tcpShouldBeHttpMatch.matches()) {
                // Must switch out tcp:// for either http:// or https://
                containerHost = hostEnv.replace("tcp://", "http" + (useTls ? "s" : "") + "://");
            } else {
                containerHost = hostEnv;
            }
        }

        dockerServerService.setServer(DockerServer.create("name", containerHost));

        // Mock the userI
        mockUser = Mockito.mock(UserI.class);
        when(mockUser.getLogin()).thenReturn(FAKE_USER);

        // Mock the user management service
        when(mockUserManagementServiceI.getUser(FAKE_USER)).thenReturn(mockUser);

        // Mock the site config preferences
        when(mockSiteConfigPreferences.getSiteUrl()).thenReturn(FAKE_HOST);
        when(mockSiteConfigPreferences.getBuildPath()).thenReturn(folder.newFolder().getAbsolutePath()); // transporter makes a directory under build
        when(mockSiteConfigPreferences.getArchivePath()).thenReturn(folder.newFolder().getAbsolutePath()); // container logs get stored under archive
        when(mockSiteConfigPreferences.getProperty("processingUrl", FAKE_HOST)).thenReturn(FAKE_HOST);

        CLIENT = controlApi.getDockerClient();
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
    @DirtiesContext
    public void testSaveCommandFromImageLabels() throws Exception {
        assumeThat(canConnectToDocker(), is(true));

        final String imageName = "xnat/testy-test:tag";
        final String dir = Paths.get(ClassLoader.getSystemResource("dockerServiceIntegrationTest").toURI()).toString().replace("%20", " ");

        CLIENT.build(Paths.get(dir), imageName);

        final List<Command> commands = dockerService.saveFromImageLabels(imageName);
        assertThat(commands, hasSize(1));
        final Command command = commands.get(0);
        assertThat(command.id(), not(0L));

        final List<Command.CommandWrapper> wrappers = command.xnatCommandWrappers();
        assertThat(wrappers.size(), greaterThan(0));
        final Command.CommandWrapper wrapper = wrappers.get(0);
        assertThat(wrapper.id(), not(0L));

        CLIENT.removeImage(imageName);
    }

    @Test
    @DirtiesContext
    public void testDeleteCommandWhenDeleteImage() throws Exception {
        assumeThat(canConnectToDocker(), is(true));

        final String imageName = "xnat/testy-test:tag";
        final String dir = Paths.get(ClassLoader.getSystemResource("dockerServiceIntegrationTest").toURI()).toString().replace("%20", " ");

        final String imageId = CLIENT.build(Paths.get(dir), imageName);

        final List<Command> commands = dockerService.saveFromImageLabels(imageName);

        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        final DockerImage dockerImageByName = dockerService.getImage(imageName);
        final DockerImage dockerImageById = dockerService.getImage(imageId);
        assertThat(dockerImageByName, is(not(nullValue(DockerImage.class))));
        assertThat(dockerImageByName, is(dockerImageById));

        dockerService.removeImageById(imageId, true);

        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        try {
            dockerService.getImage(imageId);
            fail("We expect a NotFoundException to be thrown when getting an image that we have removed. If this line is executed it means no exception was thrown.");
        } catch (NotFoundException ignored) {
            // exception is expected
        } catch (Exception e) {
            fail("We expect a NotFoundException to be thrown when getting an image that we have removed. If this line is executed it means another exception type was thrown.\n" + e.getClass().getName() + ": " + e.getMessage());
        }

        for (final Command command : commands) {
            final Command retrieved = commandService.retrieve(command.id());
            assertThat(retrieved, is(nullValue(Command.class)));

            for (final Command.CommandWrapper commandWrapper : command.xnatCommandWrappers()) {
                final Command.CommandWrapper retrievedWrapper = commandService.retrieveWrapper(commandWrapper.id());
                assertThat(retrievedWrapper, is(nullValue(Command.CommandWrapper.class)));
            }
        }
    }
}
