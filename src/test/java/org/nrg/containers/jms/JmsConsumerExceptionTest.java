package org.nrg.containers.jms;

import lombok.extern.slf4j.Slf4j;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.nrg.containers.config.IntegrationTestConfig;
import org.nrg.containers.config.JmsConfig;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.xnat.FakeWorkflow;
import org.nrg.containers.services.CommandService;
import org.nrg.containers.services.ContainerService;
import org.nrg.containers.utils.TestingUtils;
import org.nrg.mail.services.MailService;
import org.nrg.xdat.entities.AliasToken;
import org.nrg.xdat.preferences.NotificationsPreferences;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.security.helpers.Users;
import org.nrg.xdat.security.services.PermissionsServiceI;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xdat.services.AliasTokenService;
import org.nrg.xft.ItemI;
import org.nrg.xft.XFTItem;
import org.nrg.xft.event.EventDetails;
import org.nrg.xft.event.persist.PersistentWorkflowI;
import org.nrg.xft.event.persist.PersistentWorkflowUtils;
import org.nrg.xft.schema.XFTManager;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.helpers.uri.UriParserUtils;
import org.nrg.xnat.services.archive.CatalogService;
import org.nrg.xnat.utils.WorkflowUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.when;

@Slf4j
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = {JmsConfig.class, IntegrationTestConfig.class})
@Transactional
public class JmsConsumerExceptionTest {
    private MockedStatic<WorkflowUtils> mockedWorkflowUtils;
    private MockedStatic<Users> mockedUsers;
    private MockedStatic<XFTManager> mockedXFTManager;
    private MockedStatic<UriParserUtils> mockedUriParserUtils;
    private MockedStatic<PersistentWorkflowUtils> mockedPersistentWorkflowUtils;

    @Autowired private SiteConfigPreferences mockSiteConfigPreferences;
    @Autowired private UserManagementServiceI mockUserManagementServiceI;
    @Autowired private PermissionsServiceI mockPermissionsServiceI;
    @Autowired private CatalogService mockCatalogService;
    @Autowired private CommandService commandService;
    @Autowired private ContainerService containerService;
    @Autowired private MailService mockMailService;
    @Autowired private AliasTokenService mockAliasTokenService;
    @Autowired private NotificationsPreferences mockNotificationsPreferences;

    private UserI mockUser;
    private FakeWorkflow fakeWorkflow;
    private Command.CommandWrapper wrapper;
    private final String FAKE_USER = "mockUser";
    private final String FAKE_EMAIL = "email";
    private final String FAKE_HOST = "mock://url";
    private final String FAKE_SITEID = "site";
    private final String FAKE_ID = "id";

    @Before
    public void setup() throws Exception {
        fakeWorkflow = new FakeWorkflow();

        mockedWorkflowUtils = Mockito.mockStatic(WorkflowUtils.class);
        mockedUsers = mockStatic(Users.class);
        mockedXFTManager = Mockito.mockStatic(XFTManager.class);
        mockedUriParserUtils = Mockito.mockStatic(UriParserUtils.class);
        mockedPersistentWorkflowUtils = Mockito.mockStatic(PersistentWorkflowUtils.class);

        mockUser = mock(UserI.class);
        when(mockUser.getLogin()).thenReturn(FAKE_USER);
        when(mockUser.getEmail()).thenReturn(FAKE_EMAIL);

//        mockStatic(Users.class);
//        when(Users.getUser(FAKE_USER)).thenReturn(mockUser);
        mockedUsers.when(() -> Users.getUser(FAKE_USER)).thenReturn(mockUser);

        // Mock the site config preferences
        when(mockSiteConfigPreferences.getProperty("processingUrl", FAKE_HOST)).thenReturn(FAKE_HOST);
        when(mockSiteConfigPreferences.getSiteUrl()).thenReturn(FAKE_HOST);
        when(mockSiteConfigPreferences.getSiteId()).thenReturn(FAKE_SITEID);
        when(mockSiteConfigPreferences.getAdminEmail()).thenReturn(FAKE_EMAIL);

        // Mock notifications preferences to allow JMS error notifications
        when(mockNotificationsPreferences.getSuppressJMSFailureNotifications()).thenReturn(false);

        // Permissions
        when(mockPermissionsServiceI.canEdit(any(UserI.class), any(ItemI.class))).thenReturn(Boolean.TRUE);

        // Mock the user management service
        when(mockUserManagementServiceI.getUser(FAKE_USER)).thenReturn(mockUser);

        // Mock the aliasTokenService
        final AliasToken mockAliasToken = new AliasToken();
        mockAliasToken.setAlias("alias");
        mockAliasToken.setSecret("secret");
        when(mockAliasTokenService.issueTokenForUser(mockUser)).thenReturn(mockAliasToken);

        // Use powermock to mock out the static method XFTManager.isInitialized()
//        PowerMockito.mockStatic(XFTManager.class);
//        when(XFTManager.isInitialized()).thenReturn(true);
        mockedXFTManager.when(XFTManager::isInitialized).thenReturn(true);

        // Also mock out workflow operations to return our fake workflow object
//        PowerMockito.mockStatic(WorkflowUtils.class);
//        when(WorkflowUtils.getUniqueWorkflow(mockUser, fakeWorkflow.getWorkflowId().toString()))
        mockedWorkflowUtils.when(() -> WorkflowUtils.getUniqueWorkflow(mockUser, fakeWorkflow.getWorkflowId().toString()))
                .thenReturn(fakeWorkflow);

//        PowerMockito.doNothing().when(WorkflowUtils.class, "save", any(PersistentWorkflowI.class), isNull(EventMetaI.class));
        mockedWorkflowUtils.when(() -> WorkflowUtils.save(Mockito.<PersistentWorkflowI>any(), Mockito.isNull())).thenAnswer(invocation -> null);

//        PowerMockito.spy(PersistentWorkflowUtils.class);
//        PowerMockito.doReturn(fakeWorkflow).when(PersistentWorkflowUtils.class, "getOrCreateWorkflowData", eq(FakeWorkflow.defaultEventId),
//                eq(mockUser), any(XFTItem.class), any(EventDetails.class));
        mockedPersistentWorkflowUtils.when(() -> PersistentWorkflowUtils.getOrCreateWorkflowData(
                eq(FakeWorkflow.defaultEventId), eq(mockUser), any(XFTItem.class), any(EventDetails.class)))
                .thenReturn(fakeWorkflow);

        PersistentWorkflowUtils.getOrCreateWorkflowData(
                FakeWorkflow.defaultEventId, mockUser, Mockito.mock(XFTItem.class), Mockito.mock(EventDetails.class));

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
        wrapper = command.xnatCommandWrappers().getFirst();
        TestingUtils.commitTransaction();

        fakeWorkflow.setId(FAKE_ID);
        fakeWorkflow.setPipelineName(wrapper.name());
    }

    @After
    public void tearDownStaticMocks() {
        mockedUriParserUtils.closeOnDemand();
        mockedXFTManager.closeOnDemand();
        mockedUsers.closeOnDemand();
        mockedWorkflowUtils.closeOnDemand();
    }


    @Test
    @DirtiesContext
    //CS-1031
    public void testStagingConsumeFailure() throws Exception {
        // Setup: Make userManagementServiceI throw a RuntimeException that won't be caught by the listener
        // This will propagate to the JMS error handler, unlike exceptions thrown from within
        // consumeResolveCommandAndLaunchContainer which are caught internally
        String exceptionMsg = "my tricky exception message";

        // Reset the user management mock to throw an uncaught exception
        Mockito.reset(mockUserManagementServiceI);
        when(mockUserManagementServiceI.getUser(FAKE_USER))
                .thenThrow(new RuntimeException(exceptionMsg));

        containerService.queueResolveCommandAndLaunchContainer(null, wrapper.id(), 0L, wrapper.name(),
                Collections.<String, String>emptyMap(), mockUser, fakeWorkflow);

        // Verify that the error handler sends an email notification
        Mockito.verify(mockMailService, timeout(5000).times(1)).sendHtmlMessage(
                eq(FAKE_EMAIL),
                eq(FAKE_EMAIL),
                eq(FAKE_SITEID + " JMS Error"),
                contains(exceptionMsg));
    }
}
