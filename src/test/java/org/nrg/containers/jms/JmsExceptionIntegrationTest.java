package org.nrg.containers.jms;

import lombok.extern.slf4j.Slf4j;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Matchers;
import org.mockito.Mockito;
import org.nrg.containers.api.DockerControlApi;
import org.nrg.containers.config.EventPullingIntegrationTestConfig;
import org.nrg.containers.jms.requests.ContainerStagingRequest;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.xnat.FakeWorkflow;
import org.nrg.containers.services.CommandService;
import org.nrg.containers.services.ContainerService;
import org.nrg.containers.services.DockerServerService;
import org.nrg.containers.utils.TestingUtils;
import org.nrg.mail.services.MailService;
import org.nrg.xdat.entities.AliasToken;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.security.helpers.Users;
import org.nrg.xdat.security.services.PermissionsServiceI;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xdat.services.AliasTokenService;
import org.nrg.xft.ItemI;
import org.nrg.xft.XFTItem;
import org.nrg.xft.event.EventDetails;
import org.nrg.xft.event.EventMetaI;
import org.nrg.xft.event.persist.PersistentWorkflowI;
import org.nrg.xft.event.persist.PersistentWorkflowUtils;
import org.nrg.xft.schema.XFTManager;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.helpers.uri.UriParserUtils;
import org.nrg.xnat.services.archive.CatalogService;
import org.nrg.xnat.utils.WorkflowUtils;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.modules.junit4.PowerMockRunnerDelegate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessagePostProcessor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.transaction.annotation.Transactional;

import javax.jms.Destination;
import javax.jms.JMSRuntimeException;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyMapOf;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.mockStatic;

@Slf4j
@RunWith(PowerMockRunner.class)
@PowerMockRunnerDelegate(SpringJUnit4ClassRunner.class)
@PrepareForTest({UriParserUtils.class, XFTManager.class, Users.class, WorkflowUtils.class,
        PersistentWorkflowUtils.class})
@PowerMockIgnore({"org.apache.*", "java.*", "javax.*", "org.w3c.*", "com.sun.*"})
@ContextConfiguration(classes = EventPullingIntegrationTestConfig.class)
@Transactional
public class JmsExceptionIntegrationTest {
    @Autowired private JmsTemplate mockJmsTemplate;
    @Autowired private SiteConfigPreferences mockSiteConfigPreferences;
    @Autowired private UserManagementServiceI mockUserManagementServiceI;
    @Autowired private PermissionsServiceI mockPermissionsServiceI;
    @Autowired private CatalogService mockCatalogService;
    @Autowired private CommandService commandService;
    @Autowired private ContainerService containerService;
    @Autowired private MailService mockMailService;
    @Autowired private DockerControlApi controlApi;
    @Autowired private DockerServerService dockerServerService;
    @Autowired private AliasTokenService mockAliasTokenService;

    @Autowired private Destination containerStagingRequest;
    @Autowired private Destination containerFinalizingRequest;

    private UserI mockUser;
    private FakeWorkflow fakeWorkflow;
    private Command.CommandWrapper wrapper;
    private final String FAKE_USER = "mockUser";
    private final String FAKE_EMAIL = "email";
    private final String FAKE_HOST = "mock://url";
    private final String FAKE_SITEID = "site";
    private final String FAKE_ID = "id";

    private final List<String> containersToCleanUp = new ArrayList<>();
    private final List<String> imagesToCleanUp = new ArrayList<>();

    @Rule
    public TemporaryFolder folder = new TemporaryFolder(new File(System.getProperty("user.dir") + "/build"));

    @BeforeClass
    public static void setupClass() {
        TestingUtils.skipIfNotRunningIntegrationTests();
    }

    @Before
    public void setup() throws Exception {
        fakeWorkflow = new FakeWorkflow();

        mockUser = mock(UserI.class);
        when(mockUser.getLogin()).thenReturn(FAKE_USER);
        when(mockUser.getEmail()).thenReturn(FAKE_EMAIL);
        mockStatic(Users.class);
        when(Users.getUser(FAKE_USER)).thenReturn(mockUser);

        // Mock the site config preferences
        String buildDir = folder.newFolder().getAbsolutePath();
        String archiveDir = folder.newFolder().getAbsolutePath();
        when(mockSiteConfigPreferences.getBuildPath()).thenReturn(buildDir); // transporter makes a directory under build
        when(mockSiteConfigPreferences.getArchivePath()).thenReturn(archiveDir); // container logs get stored under archive
        when(mockSiteConfigPreferences.getProperty("processingUrl", FAKE_HOST)).thenReturn(FAKE_HOST);
        when(mockSiteConfigPreferences.getSiteUrl()).thenReturn(FAKE_HOST);
        when(mockSiteConfigPreferences.getSiteId()).thenReturn(FAKE_SITEID);
        when(mockSiteConfigPreferences.getAdminEmail()).thenReturn(FAKE_EMAIL);

        // Permissions
        when(mockPermissionsServiceI.canEdit(any(UserI.class), any(ItemI.class))).thenReturn(Boolean.TRUE);

        // Mock the user management service
        when(mockUserManagementServiceI.getUser(FAKE_USER)).thenReturn(mockUser);

        // Mock the aliasTokenService
        final AliasToken mockAliasToken = new AliasToken();
        mockAliasToken.setAlias("alias");
        mockAliasToken.setSecret("secret");
        when(mockAliasTokenService.issueTokenForUser(mockUser)).thenReturn(mockAliasToken);

        // Mock UriParserUtils using PowerMock. This allows us to mock out
        // the responses to its static method parseURI().
        PowerMockito.mockStatic(UriParserUtils.class);

        // Use powermock to mock out the static method XFTManager.isInitialized()
        PowerMockito.mockStatic(XFTManager.class);
        when(XFTManager.isInitialized()).thenReturn(true);

        // Also mock out workflow operations to return our fake workflow object
        PowerMockito.mockStatic(WorkflowUtils.class);
        when(WorkflowUtils.getUniqueWorkflow(mockUser, fakeWorkflow.getWorkflowId().toString()))
                .thenReturn(fakeWorkflow);
        PowerMockito.doNothing().when(WorkflowUtils.class, "save", any(PersistentWorkflowI.class), isNull(EventMetaI.class));
        PowerMockito.spy(PersistentWorkflowUtils.class);
        PowerMockito.doReturn(fakeWorkflow).when(PersistentWorkflowUtils.class, "getOrCreateWorkflowData", eq(FakeWorkflow.defaultEventId),
                eq(mockUser), any(XFTItem.class), any(EventDetails.class));

        // mock external FS check
        when(mockCatalogService.hasRemoteFiles(eq(mockUser), any(String.class))).thenReturn(false);

        // Used in all tests
        final Command command = commandService.create(Command.builder()
                .name("will-succeed")
                .image("busybox:latest")
                .version("0")
                .commandLine("/bin/sh -c \"echo hi; exit 0\"")
                .addCommandWrapper(Command.CommandWrapper.builder()
                        .name("placeholder")
                        .build())
                .build());
        wrapper = command.xnatCommandWrappers().get(0);
        TestingUtils.commitTransaction();

        fakeWorkflow.setId(FAKE_ID);
        fakeWorkflow.setPipelineName(wrapper.name());
    }

    @Test
    @DirtiesContext
    public void testStagingQueueFailure() throws Exception {
        // setup jmsTemplate to throw exception
        String exceptionMsg = "exception";
        Mockito.doThrow(new JMSRuntimeException(exceptionMsg)).when(mockJmsTemplate)
                .convertAndSend(eq(containerStagingRequest), any(ContainerStagingRequest.class), any(MessagePostProcessor.class));

        containerService.queueResolveCommandAndLaunchContainer(null, wrapper.id(), 0L, null, Collections.<String, String>emptyMap(), mockUser, fakeWorkflow
        );

        assertThat(fakeWorkflow.getStatus(), is(PersistentWorkflowUtils.FAILED + " (JMS)"));
        assertThat(fakeWorkflow.getDetails(), is(exceptionMsg));

        Mockito.verify(mockMailService, times(1)).sendHtmlMessage(eq(FAKE_EMAIL),
                aryEq(new String[]{FAKE_EMAIL}), aryEq(new String[]{FAKE_EMAIL}), Matchers.<String[]>eq(null),
                Mockito.matches(".*" + wrapper.name() + ".*Failed.*"),
                Mockito.matches(".*" + wrapper.name() + ".*" + FAKE_ID + ".*failed.*"),
                Mockito.matches(".*" + wrapper.name() + ".*" + FAKE_ID + ".*failed.*"),
                anyMapOf(String.class, File.class));
    }
}
