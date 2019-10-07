package org.nrg.containers;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.json.JsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import com.jayway.jsonpath.spi.mapper.MappingProvider;
import com.spotify.docker.client.DockerClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.nrg.containers.api.DockerControlApi;
import org.nrg.containers.config.QueueConsumerTestConfig;
import org.nrg.containers.exceptions.CommandResolutionException;
import org.nrg.containers.exceptions.ContainerException;
import org.nrg.containers.exceptions.DockerServerException;
import org.nrg.containers.model.command.auto.Command.ConfiguredCommand;
import org.nrg.containers.model.command.auto.Command.CommandWrapper;
import org.nrg.containers.model.command.auto.ResolvedCommand;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.entity.ContainerEntity;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.model.xnat.*;
import org.nrg.containers.services.*;
import org.nrg.containers.utils.TestingUtils;
import org.nrg.xdat.entities.AliasToken;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.security.helpers.Users;
import org.nrg.xdat.security.services.PermissionsServiceI;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xdat.services.AliasTokenService;
import org.nrg.xft.ItemI;
import org.nrg.xft.event.EventMetaI;
import org.nrg.xft.event.persist.PersistentWorkflowI;
import org.nrg.xft.event.persist.PersistentWorkflowUtils;
import org.nrg.xft.schema.XFTManager;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.helpers.uri.UriParserUtils;
import org.nrg.xnat.utils.WorkflowUtils;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.modules.junit4.PowerMockRunnerDelegate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.Assume.assumeThat;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.*;
import static org.junit.Assert.assertThat;
import static org.hamcrest.Matchers.is;

@Slf4j
@RunWith(PowerMockRunner.class)
@PowerMockRunnerDelegate(SpringJUnit4ClassRunner.class)
@PrepareForTest({UriParserUtils.class, XFTManager.class, Users.class, WorkflowUtils.class})
@PowerMockIgnore({"org.apache.*", "java.*", "javax.*", "org.w3c.*", "com.sun.*"})
@ContextConfiguration(classes = QueueConsumerTestConfig.class)
@Transactional
public class QueueConsumerTest {
    private UserI mockUser;
    private String buildDir;
    private String archiveDir;

    private final String FAKE_USER = "mockUser";
    private final String FAKE_ALIAS = "alias";
    private final String FAKE_SECRET = "secret";
    private final String FAKE_HOST = "mock://url";
    private FakeWorkflow fakeWorkflow = new FakeWorkflow();


    private CommandWrapper COMMAND_WRAPPER;
    private ConfiguredCommand mockConfiguredCommand;
    private ResolvedCommand RESOLVED_COMMAND;
    private final long WRAPPER_ID = 10L;
    private final long COMMAND_ID = 15L;
    private final String WRAPPER_NAME = "I don't know";
    private final String COMMAND_NAME = "command-to-launch";
    private final String INPUT_NAME = "stringInput";
    private final String INPUT_VALUE = "the super cool value";
    private final String REAL_IMAGE = "busybox:latest";

    @Autowired private CommandService mockCommandService;
    @Autowired private ContainerEntityService mockContainerEntityService;
    @Autowired private CommandResolutionService mockCommandResolutionService;
    @Autowired private ContainerService containerService;
    @Autowired private DockerControlApi mockDockerControlApi;
    @Autowired private AliasTokenService mockAliasTokenService;
    @Autowired private DockerServerService dockerServerService;
    @Autowired private SiteConfigPreferences mockSiteConfigPreferences;
    @Autowired private UserManagementServiceI mockUserManagementServiceI;
    @Autowired private PermissionsServiceI mockPermissionsServiceI;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder(new File(System.getProperty("user.dir") + "/build"));

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

        dockerServerService.setServer(DockerServer.create(0L, "Test server", containerHost, certPath, false, null, null, null, false, null, null));

        // Mock the userI
        mockUser = mock(UserI.class);
        when(mockUser.getLogin()).thenReturn(FAKE_USER);

        // Permissions
        when(mockPermissionsServiceI.canEdit(any(UserI.class), any(ItemI.class))).thenReturn(Boolean.TRUE);

        // Mock the user management service
        when(mockUserManagementServiceI.getUser(FAKE_USER)).thenReturn(mockUser);

        // Mock UriParserUtils using PowerMock. This allows us to mock out
        // the responses to its static method parseURI().
        mockStatic(UriParserUtils.class);

        // Mock the aliasTokenService
        final AliasToken mockAliasToken = new AliasToken();
        mockAliasToken.setAlias(FAKE_ALIAS);
        mockAliasToken.setSecret(FAKE_SECRET);
        when(mockAliasTokenService.issueTokenForUser(mockUser)).thenReturn(mockAliasToken);

        mockStatic(Users.class);
        when(Users.getUser(FAKE_USER)).thenReturn(mockUser);

        // Mock the site config preferences
        buildDir = folder.newFolder().getAbsolutePath();
        archiveDir = folder.newFolder().getAbsolutePath();
        when(mockSiteConfigPreferences.getSiteUrl()).thenReturn(FAKE_HOST);
        when(mockSiteConfigPreferences.getBuildPath()).thenReturn(buildDir); // transporter makes a directory under build
        when(mockSiteConfigPreferences.getArchivePath()).thenReturn(archiveDir); // container logs get stored under archive
        when(mockSiteConfigPreferences.getProperty("processingUrl", FAKE_HOST)).thenReturn(FAKE_HOST);

        // Mock command
        COMMAND_WRAPPER = CommandWrapper.builder()
                .id(WRAPPER_ID)
                .name(WRAPPER_NAME)
                .build();
        RESOLVED_COMMAND = ResolvedCommand.builder()
                .commandId(COMMAND_ID)
                .commandName(COMMAND_NAME)
                .wrapperId(WRAPPER_ID)
                .wrapperName(WRAPPER_NAME)
                .image(REAL_IMAGE)
                .commandLine("echo hello world")
                .addRawInputValue(INPUT_NAME, INPUT_VALUE)
                .build();
        mockConfiguredCommand = Mockito.mock(ConfiguredCommand.class);
        when(mockCommandService.getWrapper(WRAPPER_ID)).thenReturn(COMMAND_WRAPPER);
        when(mockCommandService.retrieveWrapper(WRAPPER_ID)).thenReturn(COMMAND_WRAPPER);
        when(mockCommandService.getAndConfigure(WRAPPER_ID)).thenReturn(mockConfiguredCommand);
        when(mockCommandService.getAndConfigure(null, 0L, null, WRAPPER_ID)).thenReturn(mockConfiguredCommand);

        when(mockCommandResolutionService.resolve(
                mockConfiguredCommand,
                Collections.<String, String>emptyMap(),
                mockUser
        )).thenReturn(RESOLVED_COMMAND);

        // Use powermock to mock out the static method XFTManager.isInitialized()
        mockStatic(XFTManager.class);
        when(XFTManager.isInitialized()).thenReturn(true);

        // Also mock out workflow operations to return our fake workflow object
        mockStatic(WorkflowUtils.class);
        when(WorkflowUtils.getUniqueWorkflow(mockUser, fakeWorkflow.getWorkflowId().toString()))
                .thenReturn(fakeWorkflow);
        doNothing().when(WorkflowUtils.class, "save", any(PersistentWorkflowI.class), isNull(EventMetaI.class));
    }

    @After
    public void cleanup() throws Exception {
        fakeWorkflow = new FakeWorkflow();
    }

    @Test
    @DirtiesContext
    public void testCommandResolutionException() throws Exception {
        final Map<String, String> input = Maps.newHashMap();
        final String badInputValue = "a bad value";
        input.put(INPUT_NAME, badInputValue);

        final String exceptionMessage = "uh oh - bad input!";
        when(mockCommandResolutionService.resolve(
                eq(mockConfiguredCommand),
                argThat(TestingUtils.isMapWithEntry(INPUT_NAME, badInputValue)),
                eq(mockUser)
        )).thenThrow(new CommandResolutionException(exceptionMessage));

        final String expectedWorkflowStatus = PersistentWorkflowUtils.FAILED + " (Command resolution)";
        containerService.consumeResolveCommandAndLaunchContainer(null, WRAPPER_ID, 0L,
                null, input, mockUser, fakeWorkflow.getWorkflowId().toString());
        assertThat(fakeWorkflow.getStatus(), is(expectedWorkflowStatus));
        assertThat(fakeWorkflow.getDetails(), is(exceptionMessage));
    }

    @Test
    @DirtiesContext
    public void testContainerLaunchException() throws Exception {
        assumeThat(SystemUtils.IS_OS_WINDOWS_7, is(false));
        final String exceptionMessage = "uh oh - issue launching container!";
        when(mockDockerControlApi.createContainerOrSwarmService(any(ResolvedCommand.class), eq(mockUser)))
                .thenThrow(new ContainerException(exceptionMessage));

        final String expectedWorkflowStatus = PersistentWorkflowUtils.FAILED + " (Container launch)";
        containerService.consumeResolveCommandAndLaunchContainer(null, WRAPPER_ID, 0L,
                null, Collections.<String, String>emptyMap(), mockUser, fakeWorkflow.getWorkflowId().toString());
        assertThat(fakeWorkflow.getStatus(), is(expectedWorkflowStatus));
        assertThat(fakeWorkflow.getDetails(), is(exceptionMessage));
    }

}
