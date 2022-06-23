package org.nrg.containers;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mandas.docker.client.DockerClient;
import org.mandas.docker.client.exceptions.ContainerNotFoundException;
import org.mandas.docker.client.exceptions.NotFoundException;
import org.mandas.docker.client.exceptions.ServiceNotFoundException;
import org.nrg.containers.api.DockerControlApi;
import org.nrg.containers.config.EventPullingIntegrationTestConfig;
import org.nrg.containers.config.SpringJUnit4ClassRunnerFactory;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.auto.Command.CommandWrapper;
import org.nrg.containers.model.command.entity.CommandType;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.model.xnat.FakeWorkflow;
import org.nrg.containers.model.xnat.Session;
import org.nrg.containers.services.CommandService;
import org.nrg.containers.services.ContainerService;
import org.nrg.containers.services.DockerServerService;
import org.nrg.containers.utils.ContainerServicePermissionUtils;
import org.nrg.containers.utils.TestingUtils;
import org.nrg.xdat.entities.AliasToken;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.security.helpers.Users;
import org.nrg.xdat.security.services.PermissionsServiceI;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xdat.services.AliasTokenService;
import org.nrg.xdat.servlet.XDATServlet;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Nullable;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.nrg.containers.utils.TestingUtils.BUSYBOX;
import static org.powermock.api.mockito.PowerMockito.doNothing;
import static org.powermock.api.mockito.PowerMockito.doReturn;
import static org.powermock.api.mockito.PowerMockito.mockStatic;


@Slf4j
@RunWith(PowerMockRunner.class)
@PowerMockRunnerDelegate(Parameterized.class)
@PrepareForTest({UriParserUtils.class, XFTManager.class, Users.class, WorkflowUtils.class,
        PersistentWorkflowUtils.class, XDATServlet.class, Session.class, ContainerServicePermissionUtils.class})
@PowerMockIgnore({"org.apache.*", "java.*", "javax.*", "org.w3c.*", "com.sun.*"})
@ContextConfiguration(classes = EventPullingIntegrationTestConfig.class)
@Parameterized.UseParametersRunnerFactory(SpringJUnit4ClassRunnerFactory.class)
@Transactional
public class ContainerCleanupIntegrationTest {
    @Parameterized.Parameters(name = "autoCleanup={0} swarmMode={1}")
    public static Collection<Object[]> params() {
        return Arrays.asList(new Object[][] {
                {true, true}, {true, false}, {false, true}, {false, false}
        });
    }
    @Parameterized.Parameter
    public boolean autoCleanup;
    @Parameterized.Parameter(1)
    public boolean swarmMode;

    private UserI mockUser;
    private String buildDir;
    private String archiveDir;

    private final String FAKE_USER = "mockUser";
    private final String FAKE_ALIAS = "alias";
    private final String FAKE_SECRET = "secret";
    private final String FAKE_HOST = "mock://url";
    private FakeWorkflow fakeWorkflow = new FakeWorkflow();

    private final List<String> containersToCleanUp = new ArrayList<>();
    private final List<String> imagesToCleanUp = new ArrayList<>();

    private static DockerClient CLIENT;

    @Autowired private ObjectMapper mapper;
    @Autowired private CommandService commandService;
    @Autowired private ContainerService containerService;
    @Autowired private DockerControlApi controlApi;
    @Autowired private AliasTokenService mockAliasTokenService;
    @Autowired private DockerServerService dockerServerService;
    @Autowired private SiteConfigPreferences mockSiteConfigPreferences;
    @Autowired private UserManagementServiceI mockUserManagementServiceI;
    @Autowired private PermissionsServiceI mockPermissionsServiceI;
    @Autowired private CatalogService mockCatalogService;

    @Rule public TemporaryFolder folder = new TemporaryFolder(new File(System.getProperty("user.dir") + "/build"));

    @Rule
    public TestRule watcher = new TestWatcher() {
        protected void starting(Description description) {
            log.info("BEGINNING TEST " + description.getMethodName());
        }

        protected void finished(Description description) {
            log.info("ENDING TEST " + description.getMethodName());
        }
    };

    @Before
    public void setup() throws Exception {
        // Mock out the prefs bean
        // Mock the userI
        mockUser = mock(UserI.class);
        when(mockUser.getLogin()).thenReturn(FAKE_USER);
        when(mockUser.getUsername()).thenReturn(FAKE_USER);

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

        // Use powermock to mock out the static method XFTManager.isInitialized() and XDATServlet.isDatabasePopulateOrUpdateCompleted()
        mockStatic(XFTManager.class);
        when(XFTManager.isInitialized()).thenReturn(true);
        mockStatic(XDATServlet.class);
        when(XDATServlet.isDatabasePopulateOrUpdateCompleted()).thenReturn(true);

        // Also mock out workflow operations to return our fake workflow object
        mockStatic(WorkflowUtils.class);
        when(WorkflowUtils.getUniqueWorkflow(mockUser, fakeWorkflow.getWorkflowId().toString()))
                .thenReturn(fakeWorkflow);
        doNothing().when(WorkflowUtils.class, "save", any(PersistentWorkflowI.class), isNull(EventMetaI.class));
        PowerMockito.spy(PersistentWorkflowUtils.class);
        doReturn(fakeWorkflow).when(PersistentWorkflowUtils.class, "getOrCreateWorkflowData", eq(FakeWorkflow.defaultEventId),
                eq(mockUser), any(XFTItem.class), any(EventDetails.class));

        // mock external FS check
        when(mockCatalogService.hasRemoteFiles(eq(mockUser), any(String.class))).thenReturn(false);

        // We can't load the XFT item in the session, so don't try
        // This is only used to check the permissions, and we mock that response anyway, so we don't need a real value
        mockStatic(Session.class);
        when(Session.loadXnatImageSessionData(any(String.class), eq(mockUser)))
                .thenReturn(null);

        // Permissions checks
        mockStatic(ContainerServicePermissionUtils.class);
        when(ContainerServicePermissionUtils.canCreateOutputObject(
                eq(mockUser), any(String.class), any(String.class), any(Command.CommandWrapperOutput.class)
        )).thenReturn(true);

        setupServer();
    }

    @After
    public void cleanup() {
        fakeWorkflow = new FakeWorkflow();
        for (final String containerToCleanUp : containersToCleanUp) {
            try {
                if (swarmMode) {
                    CLIENT.removeService(containerToCleanUp);
                } else {
                    CLIENT.removeContainer(containerToCleanUp, DockerClient.RemoveContainerParam.forceKill());
                }
            } catch (Exception e) {
                // do nothing
            }
        }
        containersToCleanUp.clear();

        for (final String imageToCleanUp : imagesToCleanUp) {
            try {
                CLIENT.removeImage(imageToCleanUp, true, false);
            } catch (Exception e) {
                // do nothing
            }
        }
        imagesToCleanUp.clear();

        CLIENT.close();
    }

    private void setupServer() throws Exception {
        // Setup docker server
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
        dockerServerService.setServer(DockerServer.create(0L, "Test server", containerHost, certPath,
                swarmMode, null, null, null,
                false, null, autoCleanup, null, null, true));

        CLIENT = controlApi.getClient();

        assumeThat(SystemUtils.IS_OS_WINDOWS_7, is(false));
        assumeThat(TestingUtils.canConnectToDocker(CLIENT), is(true));

        TestingUtils.pullBusyBox(CLIENT);
    }

    @Test
    @DirtiesContext
    public void testSuccess() throws Exception {
        final Command willSucceed = commandService.create(Command.builder()
                .name("will-succeed")
                .image(BUSYBOX)
                .version("0")
                .commandLine("/bin/sh -c \"echo hi; exit 0\"")
                .addCommandWrapper(CommandWrapper.builder()
                        .name("placeholder")
                        .build())
                .build());
        final CommandWrapper willSucceedWrapper = willSucceed.xnatCommandWrappers().get(0);
        log.debug("Saving command wrapper");
        TestingUtils.commitTransaction();

        log.debug("Queuing command resolution + launch");
        containerService.queueResolveCommandAndLaunchContainer(null, willSucceedWrapper.id(),
                0L, null, Collections.emptyMap(), mockUser, fakeWorkflow);
        TestingUtils.commitTransaction();
        log.debug("Getting container from workflow comments...");
        final Container container = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        log.debug("Got container from workflow comments: {}", container);
        containersToCleanUp.add(swarmMode ? container.serviceId() : container.containerId());

        log.debug("Waiting until task has started...");
        await().until(TestingUtils.containerHasStarted(CLIENT, swarmMode, container), is(true));
        log.debug("Task started. Waiting until task has finished...");
        await().until(TestingUtils.containerIsRunning(CLIENT, swarmMode, container), is(false));
        log.debug("Task finished. Waiting until container is finalized...");
        await().until(TestingUtils.containerIsFinalized(containerService, container), is(true));
        log.debug("Container finalized. Asserting.");
        TestingUtils.commitTransaction();
        final Container exited = containerService.get(container.databaseId());
        assertThat(exited.exitCode(), is("0"));
        assertThat(exited.status(), is(PersistentWorkflowUtils.COMPLETE));
        assertThat(fakeWorkflow.getStatus(), is(PersistentWorkflowUtils.COMPLETE));

        checkContainerRemoval(exited);
    }

    @Test
    @DirtiesContext
    public void testFailed() throws Exception {
        final Command willFail = commandService.create(Command.builder()
                .name("will-fail")
                .image(BUSYBOX)
                .version("0")
                .commandLine("/bin/sh -c \"echo hi; exit 1\"")
                .addCommandWrapper(CommandWrapper.builder()
                        .name("placeholder")
                        .build())
                .build());
        final CommandWrapper willFailWrapper = willFail.xnatCommandWrappers().get(0);
        log.debug("Saving command wrapper");
        TestingUtils.commitTransaction();

        log.debug("Queuing command resolution + launch");
        containerService.queueResolveCommandAndLaunchContainer(null, willFailWrapper.id(),
                0L, null, Collections.emptyMap(), mockUser, fakeWorkflow);
        TestingUtils.commitTransaction();
        log.debug("Getting container from workflow comments...");
        final Container container = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        log.debug("Got container from workflow comments: {}", container);
        containersToCleanUp.add(swarmMode ? container.serviceId() : container.containerId());

        log.debug("Waiting until task has started...");
        await().until(TestingUtils.containerHasStarted(CLIENT, swarmMode, container), is(true));
        log.debug("Task started. Waiting until task has finished...");
        await().until(TestingUtils.containerIsRunning(CLIENT, swarmMode, container), is(false));
        log.debug("Task finished. Waiting until container is finalized...");
        await().until(TestingUtils.containerIsFinalized(containerService, container), is(true));
        log.debug("Container finalized. Asserting.");
        TestingUtils.commitTransaction();
        final Container exited = containerService.get(container.databaseId());
        assertThat(exited.exitCode(), is("1"));
        assertThat(exited.status(), is(PersistentWorkflowUtils.FAILED));
        assertThat(fakeWorkflow.getStatus(), is(PersistentWorkflowUtils.FAILED));

        checkContainerRemoval(exited);
    }

    @Test
    @DirtiesContext
    public void testSetupWrapup_success() throws Exception {
        final CommandWrapper mainWrapper = configureSetupWrapupCommands(null);

        Map<String, String> runtimeValues = new HashMap<>();
        String uri = TestingUtils.setupSessionMock(folder, mapper, runtimeValues);
        TestingUtils.setupMocksForSetupWrapupWorkflow("/archive" + uri, fakeWorkflow, mockCatalogService, mockUser);

        log.debug("Queuing command resolution + launch");
        containerService.queueResolveCommandAndLaunchContainer(null, mainWrapper.id(),
                0L, null, runtimeValues, mockUser, fakeWorkflow);
        TestingUtils.commitTransaction();
        log.debug("Getting container from workflow comments...");
        final Container container = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        log.debug("Got container from workflow comments: {}", container);
        TestingUtils.commitTransaction();

        log.debug("Waiting until container is finalized...");
        await().atMost(15L, TimeUnit.SECONDS)
                .until(TestingUtils.containerIsFinalized(containerService, container), is(true));
        TestingUtils.commitTransaction();

        log.debug("Container finalized. Asserting.");
        final long databaseId = container.databaseId();
        final Container exited = containerService.get(databaseId);
        assertThat(fakeWorkflow.getStatus(), is(PersistentWorkflowUtils.COMPLETE));

        List<Container> toCleanup = new ArrayList<>();
        toCleanup.add(exited);
        toCleanup.addAll(containerService.retrieveSetupContainersForParent(databaseId));
        toCleanup.addAll(containerService.retrieveWrapupContainersForParent(databaseId));
        for (Container ck : toCleanup) {
            containersToCleanUp.add(swarmMode ? ck.serviceId() : ck.containerId());
            assertThat(ck.exitCode(), is("0"));
            assertThat(ck.status(), is(PersistentWorkflowUtils.COMPLETE));
            checkContainerRemoval(ck);
        }
    }

    @Test
    @DirtiesContext
    public void testSetupWrapup_failureOnSetup() throws Exception {
        final CommandWrapper mainWrapper = configureSetupWrapupCommands(CommandType.DOCKER_SETUP);
        Map<String, String> runtimeValues = new HashMap<>();
        String uri = TestingUtils.setupSessionMock(folder, mapper, runtimeValues);
        TestingUtils.setupMocksForSetupWrapupWorkflow("/archive" + uri, fakeWorkflow, mockCatalogService, mockUser);

        log.debug("Queuing command resolution + launch");
        containerService.queueResolveCommandAndLaunchContainer(null, mainWrapper.id(),
                0L, null, runtimeValues, mockUser, fakeWorkflow);
        TestingUtils.commitTransaction();
        log.debug("Getting container from workflow comments...");
        final Container container = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        log.debug("Got container from workflow comments: {}", container);
        TestingUtils.commitTransaction();

        log.debug("Waiting until container is finalized");
        await().atMost(5L, TimeUnit.SECONDS)
                .until(TestingUtils.containerIsFinalized(containerService, container), is(true));
        TestingUtils.commitTransaction();

        log.debug("Container finalized. Asserting.");
        final long databaseId = container.databaseId();
        final Container exited = containerService.get(databaseId);
        assertThat(fakeWorkflow.getStatus(), startsWith(PersistentWorkflowUtils.FAILED));

        List<Container> toCleanup = new ArrayList<>();
        toCleanup.add(exited);
        toCleanup.addAll(containerService.retrieveSetupContainersForParent(databaseId));
        toCleanup.addAll(containerService.retrieveWrapupContainersForParent(databaseId));
        for (Container ck : toCleanup) {
            containersToCleanUp.add(swarmMode ? ck.serviceId() : ck.containerId());
            assertThat("Unexpected status for " + ck, ck.status(),
                    startsWith(PersistentWorkflowUtils.FAILED));
            checkContainerRemoval(ck, CommandType.DOCKER_WRAPUP.getName().equals(ck.subtype()));
        }
    }

    @Test
    @DirtiesContext
    public void testSetupWrapup_failure() throws Exception {
        final CommandWrapper mainWrapper = configureSetupWrapupCommands(CommandType.DOCKER);
        Map<String, String> runtimeValues = new HashMap<>();
        String uri = TestingUtils.setupSessionMock(folder, mapper, runtimeValues);
        TestingUtils.setupMocksForSetupWrapupWorkflow("/archive" + uri, fakeWorkflow, mockCatalogService, mockUser);

        log.debug("Queuing command resolution + launch");
        containerService.queueResolveCommandAndLaunchContainer(null, mainWrapper.id(),
                0L, null, runtimeValues, mockUser, fakeWorkflow);
        TestingUtils.commitTransaction();
        log.debug("Getting container from workflow comments...");
        final Container container = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        TestingUtils.commitTransaction();
        log.debug("Got container from workflow comments: {}", container);

        log.debug("Waiting until container is finalized");
        await().atMost(10L, TimeUnit.SECONDS)
                .until(TestingUtils.containerIsFinalized(containerService, container), is(true));
        TestingUtils.commitTransaction();

        log.debug("Container finalized. Asserting.");
        final long databaseId = container.databaseId();
        final Container exited = containerService.get(databaseId);
        assertThat(fakeWorkflow.getStatus(), startsWith(PersistentWorkflowUtils.FAILED));

        containersToCleanUp.add(swarmMode ? exited.serviceId() : exited.containerId());
        assertThat(exited.status(), startsWith(PersistentWorkflowUtils.FAILED));
        checkContainerRemoval(exited);

        for (Container ck : containerService.retrieveSetupContainersForParent(databaseId)) {
            containersToCleanUp.add(swarmMode ? ck.serviceId() : ck.containerId());
            checkContainerRemoval(ck);
        }
        for (Container ck : containerService.retrieveWrapupContainersForParent(databaseId)) {
            containersToCleanUp.add(swarmMode ? ck.serviceId() : ck.containerId());
            assertThat(ck.status(), startsWith(PersistentWorkflowUtils.FAILED));
            checkContainerRemoval(ck, CommandType.DOCKER_WRAPUP.getName().equals(ck.subtype()));
        }
    }

    @Test
    @DirtiesContext
    public void testSetupWrapup_failureOnWrapup() throws Exception {
        final CommandWrapper mainWrapper = configureSetupWrapupCommands(CommandType.DOCKER_WRAPUP);
        Map<String, String> runtimeValues = new HashMap<>();
        String uri = TestingUtils.setupSessionMock(folder, mapper, runtimeValues);
        TestingUtils.setupMocksForSetupWrapupWorkflow("/archive" + uri, fakeWorkflow, mockCatalogService, mockUser);

        log.debug("Queuing command resolution + launch");
        containerService.queueResolveCommandAndLaunchContainer(null, mainWrapper.id(),
                0L, null, runtimeValues, mockUser, fakeWorkflow);
        TestingUtils.commitTransaction();
        log.debug("Getting container from workflow comments...");
        final Container container = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        TestingUtils.commitTransaction();
        log.debug("Got container from workflow comments: {}", container);

        log.debug("Waiting until container is finalized");
        await().atMost(15L, TimeUnit.SECONDS)
                .until(TestingUtils.containerIsFinalized(containerService, container), is(true));
        TestingUtils.commitTransaction();

        log.debug("Container finalized. Asserting.");
        final long databaseId = container.databaseId();
        final Container exited = containerService.get(databaseId);
        assertThat(fakeWorkflow.getStatus(), startsWith(PersistentWorkflowUtils.FAILED));

        containersToCleanUp.add(swarmMode ? exited.serviceId() : exited.containerId());
        assertThat(exited.status(), startsWith(PersistentWorkflowUtils.FAILED));
        checkContainerRemoval(exited);

        for (Container ck : containerService.retrieveSetupContainersForParent(databaseId)) {
            containersToCleanUp.add(swarmMode ? ck.serviceId() : ck.containerId());
            checkContainerRemoval(ck);
        }
        for (Container ck : containerService.retrieveWrapupContainersForParent(databaseId)) {
            containersToCleanUp.add(swarmMode ? ck.serviceId() : ck.containerId());
            assertThat(ck.status(), startsWith(PersistentWorkflowUtils.FAILED));
            checkContainerRemoval(ck);
        }
    }

    private CommandWrapper configureSetupWrapupCommands(@Nullable CommandType failureLevel) throws Exception {
        log.debug("Configuring commands and wrappers...");
        String basecmd = "/bin/sh -c \"echo hi; exit 0\"";
        String failurecmd = "/bin/sh -c \"echo hi; exit 1\"";

        String setupCmd = basecmd;
        String cmd = basecmd;
        String wrapupCmd = basecmd;

        if (failureLevel != null) {
            switch (failureLevel) {
                case DOCKER:
                    cmd = failurecmd;
                    break;
                case DOCKER_SETUP:
                    setupCmd = failurecmd;
                    break;
                case DOCKER_WRAPUP:
                    wrapupCmd = failurecmd;
                    break;
                default:
                    throw new Exception("Invalid command type");
            }

        }

        final Command setup = commandService.create(Command.builder()
                .name("setup")
                .image(BUSYBOX)
                .version("0")
                .commandLine(setupCmd)
                .type("docker-setup")
                .build());
        TestingUtils.commitTransaction();

        final Command wrapup = commandService.create(Command.builder()
                .name("wrapup")
                .image(BUSYBOX)
                .version("0")
                .commandLine(wrapupCmd)
                .type("docker-wrapup")
                .build());
        TestingUtils.commitTransaction();

        final Command main = commandService.create(Command.builder()
                .name("main")
                .image(BUSYBOX)
                .version("0")
                .commandLine(cmd)
                .mounts(
                        Arrays.asList(
                                Command.CommandMount.create("in", false, "/input"),
                                Command.CommandMount.create("out", true, "/output")
                        )
                )
                .outputs(Command.CommandOutput.builder()
                        .name("output")
                        .mount("out")
                        .build())
                .addCommandWrapper(CommandWrapper.builder()
                        .name("placeholder")
                        .externalInputs(
                                Command.CommandWrapperExternalInput.builder()
                                        .name("session")
                                        .type("Session")
                                        .build()
                        )
                        .derivedInputs(Command.CommandWrapperDerivedInput.builder()
                                .name("resource")
                                .type("Resource")
                                .providesFilesForCommandMount("in")
                                .viaSetupCommand("busybox:latest:setup")
                                .derivedFromWrapperInput("session")
                                .build())
                        .outputHandlers(Command.CommandWrapperOutput.builder()
                                .name("output-handler")
                                .commandOutputName("output")
                                .targetName("session")
                                .label("label")
                                .viaWrapupCommand("busybox:latest:wrapup")
                                .build()
                        )
                        .build())
                .build());

        TestingUtils.commitTransaction();
        log.debug("Done configuring commands and wrappers...");
        return main.xnatCommandWrappers().get(0);
    }

    private void checkContainerRemoval(Container exited) throws Exception {
        checkContainerRemoval(exited, false);
    }

    private void checkContainerRemoval(Container exited, boolean okIfMissing) throws Exception {
        // Sleep to give time to remove the service or container; no way to check if this has happened since
        // status stops changing
        // Thread.sleep(2000L);

        String id = swarmMode ? exited.serviceId() : exited.containerId();
        if (id == null) {
            if (okIfMissing) {
                return;
            }
            fail("No ID for " + exited);
        }

        if (autoCleanup) {
            if (swarmMode) {
                await().atMost(2L, TimeUnit.SECONDS).until(() -> {try {
                    CLIENT.inspectService(id);
                } catch (ServiceNotFoundException e) {
                    // This is what we expect
                    return true;
                } catch (Exception ignored) {
                    // ignored
                }
                return false;
                });
            } else {
                await().atMost(2L, TimeUnit.SECONDS).until(() -> {try {
                    CLIENT.inspectContainer(id);
                } catch (ContainerNotFoundException e) {
                    // This is what we expect
                    return true;
                } catch (Exception ignored) {
                    // ignore
                }
                return false;
                });
            }
        } else {
            if (swarmMode) {
                try {
                    CLIENT.inspectService(id);
                } catch (ServiceNotFoundException e) {
                    fail("Service " + exited + " was improperly removed");
                }
            } else {
                try {
                    CLIENT.inspectContainer(id);
                } catch (NotFoundException e) {
                    fail("Container " + exited + " was improperly removed");
                }
            }
        }
    }
}
