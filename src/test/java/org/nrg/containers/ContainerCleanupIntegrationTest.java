package org.nrg.containers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Sets;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.json.JsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import com.jayway.jsonpath.spi.mapper.MappingProvider;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.exceptions.NotFoundException;
import com.spotify.docker.client.exceptions.ServiceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.nrg.containers.api.DockerControlApi;
import org.nrg.containers.config.EventPullingIntegrationTestConfig;
import org.nrg.containers.config.SpringJUnit4ClassRunnerFactory;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.auto.Command.CommandWrapper;
import org.nrg.containers.model.command.entity.CommandType;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.model.xnat.*;
import org.nrg.containers.services.CommandService;
import org.nrg.containers.services.ContainerService;
import org.nrg.containers.services.DockerServerService;
import org.nrg.containers.utils.TestingUtils;
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
import org.nrg.xnat.archive.ResourceData;
import org.nrg.xnat.helpers.uri.UriParserUtils;
import org.nrg.xnat.helpers.uri.archive.impl.ExptURI;
import org.nrg.xnat.services.archive.CatalogService;
import org.nrg.xnat.turbine.utils.ArchivableItem;
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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.*;


@Slf4j
@RunWith(PowerMockRunner.class)
@PowerMockRunnerDelegate(Parameterized.class)
@PrepareForTest({UriParserUtils.class, XFTManager.class, Users.class, WorkflowUtils.class,
        PersistentWorkflowUtils.class})
@PowerMockIgnore({"org.apache.*", "java.*", "javax.*", "org.w3c.*", "com.sun.*"})
@ContextConfiguration(classes = EventPullingIntegrationTestConfig.class)
@Parameterized.UseParametersRunnerFactory(SpringJUnit4ClassRunnerFactory.class)
@Transactional
public class ContainerCleanupIntegrationTest {
    // Sadly, we can't have 2 parameters and use powermock/spring, so we have to double-up our tests
    public boolean swarmMode;

    @Parameterized.Parameters(name = "autoCleanup={0}")
    public static Collection<Boolean> autoCleanups() {
        return Arrays.asList(true, false);
    }
    @Parameterized.Parameter
    public boolean autoCleanup;

    private UserI mockUser;
    private String buildDir;
    private String archiveDir;

    private final String FAKE_USER = "mockUser";
    private final String FAKE_ALIAS = "alias";
    private final String FAKE_SECRET = "secret";
    private final String FAKE_HOST = "mock://url";
    private FakeWorkflow fakeWorkflow = new FakeWorkflow();

    private boolean testIsOnCircleCi;

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

    @Before
    public void setup() throws Exception {
        Configuration.setDefaults(new Configuration.Defaults() {

            private final JsonProvider jsonProvider = new JacksonJsonProvider();
            private final MappingProvider mappingProvider = new JacksonMappingProvider();

            @Override
            public JsonProvider jsonProvider() {
                return jsonProvider;
            }

            @Override
            public MappingProvider mappingProvider() {
                return mappingProvider;
            }

            @Override
            public Set<Option> options() {
                return Sets.newHashSet(Option.DEFAULT_PATH_LEAF_TO_NULL);
            }
        });

        final String circleCiEnv = System.getenv("CIRCLECI");
        testIsOnCircleCi = StringUtils.isNotBlank(circleCiEnv) && Boolean.parseBoolean(circleCiEnv);

        // Mock out the prefs bean
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

        // Use powermock to mock out the static method XFTManager.isInitialized()
        mockStatic(XFTManager.class);
        when(XFTManager.isInitialized()).thenReturn(true);

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

    @Test
    @DirtiesContext
    public void testSuccessSwarm() throws Exception {
        this.swarmMode = true;
        testSuccess();
    }

    @Test
    @DirtiesContext
    public void testSuccessNoSwarm() throws Exception {
        this.swarmMode = false;
        testSuccess();
    }

    @Test
    @DirtiesContext
    public void testFailureSwarm() throws Exception {
        this.swarmMode = true;
        testFailed();
    }

    @Test
    @DirtiesContext
    public void testFailureNoSwarm() throws Exception {
        this.swarmMode = false;
        testFailed();
    }

    @Test
    @DirtiesContext
    public void testSetupWrapupSuccessSwarm() throws Exception {
        this.swarmMode = true;
        testSetupWrapup();
    }

    @Test
    @DirtiesContext
    public void testSetupWrapupSuccessNoSwarm() throws Exception {
        this.swarmMode = false;
        testSetupWrapup();
    }

    @Test
    @DirtiesContext
    public void testSetupWrapupFailureOnSetupSwarm() throws Exception {
        this.swarmMode = true;
        testSetupWrapupFailureOnSetup();
    }

    @Test
    @DirtiesContext
    public void testSetupWrapupFailureOnSetupNoSwarm() throws Exception {
        this.swarmMode = false;
        testSetupWrapupFailureOnSetup();
    }

    @Test
    @DirtiesContext
    public void testSetupWrapupFailureSwarm() throws Exception {
        this.swarmMode = true;
        testSetupWrapupFailure();
    }

    @Test
    @DirtiesContext
    public void testSetupWrapupFailureNoSwarm() throws Exception {
        this.swarmMode = false;
        testSetupWrapupFailure();
    }

    @Test
    @DirtiesContext
    public void testSetupWrapupFailureOnWrapupSwarm() throws Exception {
        this.swarmMode = true;
        testSetupWrapupFailureOnWrapup();
    }

    @Test
    @DirtiesContext
    public void testSetupWrapupFailureOnWrapupNoSwarm() throws Exception {
        this.swarmMode = false;
        testSetupWrapupFailureOnWrapup();
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
                false, null, autoCleanup, null));

        CLIENT = controlApi.getClient();
        CLIENT.pull("busybox:latest");

        assumeThat(SystemUtils.IS_OS_WINDOWS_7, is(false));
        assumeThat(TestingUtils.canConnectToDocker(CLIENT), is(true));
    }

    private void testSuccess() throws Exception {
        setupServer();
        final Command willSucceed = commandService.create(Command.builder()
                .name("will-succeed")
                .image("busybox:latest")
                .version("0")
                .commandLine("/bin/sh -c \"echo hi; exit 0\"")
                .addCommandWrapper(CommandWrapper.builder()
                        .name("placeholder")
                        .build())
                .build());
        final CommandWrapper willSucceedWrapper = willSucceed.xnatCommandWrappers().get(0);

        TestingUtils.commitTransaction();

        containerService.queueResolveCommandAndLaunchContainer(null, willSucceedWrapper.id(),
                0L, null, Collections.<String, String>emptyMap(), mockUser, fakeWorkflow);
        final Container container = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        containersToCleanUp.add(swarmMode ? container.serviceId() : container.containerId());

        TestingUtils.commitTransaction();

        log.debug("Waiting until task has started");
        await().until(TestingUtils.containerHasStarted(CLIENT, swarmMode, container), is(true));
        log.debug("Waiting until task has finished");
        await().until(TestingUtils.containerIsRunning(CLIENT, swarmMode, container), is(false));
        log.debug("Waiting until container is finalized");
        await().until(TestingUtils.containerIsFinalized(containerService, container), is(true));
        final Container exited = containerService.get(container.databaseId());
        assertThat(exited.exitCode(), is("0"));
        assertThat(exited.status(), is(PersistentWorkflowUtils.COMPLETE));
        assertThat(fakeWorkflow.getStatus(), is(PersistentWorkflowUtils.COMPLETE));

        checkContainerRemoval(exited);
    }

    private void testFailed() throws Exception {
        setupServer();
        final Command willFail = commandService.create(Command.builder()
                .name("will-fail")
                .image("busybox:latest")
                .version("0")
                .commandLine("/bin/sh -c \"echo hi; exit 1\"")
                .addCommandWrapper(CommandWrapper.builder()
                        .name("placeholder")
                        .build())
                .build());
        final CommandWrapper willFailWrapper = willFail.xnatCommandWrappers().get(0);

        TestingUtils.commitTransaction();


        containerService.queueResolveCommandAndLaunchContainer(null, willFailWrapper.id(),
                0L, null, Collections.<String, String>emptyMap(), mockUser, fakeWorkflow);
        final Container container = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        containersToCleanUp.add(swarmMode ? container.serviceId() : container.containerId());

        TestingUtils.commitTransaction();

        log.debug("Waiting until task has started");
        await().until(TestingUtils.containerHasStarted(CLIENT, swarmMode, container), is(true));
        log.debug("Waiting until task has finished");
        await().until(TestingUtils.containerIsRunning(CLIENT, swarmMode, container), is(false));
        log.debug("Waiting until container is finalized");
        await().until(TestingUtils.containerIsFinalized(containerService, container), is(true));
        final Container exited = containerService.get(container.databaseId());
        assertThat(exited.exitCode(), is("1"));
        assertThat(exited.status(), is(PersistentWorkflowUtils.FAILED));
        assertThat(fakeWorkflow.getStatus(), is(PersistentWorkflowUtils.FAILED));

        checkContainerRemoval(exited);
    }

    private void testSetupWrapup() throws Exception {
        setupServer();
        final CommandWrapper mainWrapper = configureSetupWrapupCommands(null);

        Map<String, String> runtimeValues = new HashMap<>();
        String uri = setupSessionMock(runtimeValues);
        setupMocksForSetupWrapupWorkflow("/archive" + uri);

        containerService.queueResolveCommandAndLaunchContainer(null, mainWrapper.id(),
                0L, null, runtimeValues, mockUser, fakeWorkflow);
        final Container container = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        TestingUtils.commitTransaction();

        log.debug("Waiting until container is finalized");
        await().atMost(20L, TimeUnit.SECONDS)
                .until(TestingUtils.containerIsFinalized(containerService, container), is(true));

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

    private void testSetupWrapupFailureOnSetup() throws Exception {
        setupServer();
        final CommandWrapper mainWrapper = configureSetupWrapupCommands(CommandType.DOCKER_SETUP);
        Map<String, String> runtimeValues = new HashMap<>();
        String uri = setupSessionMock(runtimeValues);
        setupMocksForSetupWrapupWorkflow("/archive" + uri);

        containerService.queueResolveCommandAndLaunchContainer(null, mainWrapper.id(),
                0L, null, runtimeValues, mockUser, fakeWorkflow);
        final Container container = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        TestingUtils.commitTransaction();

        log.debug("Waiting until container is finalized");
        await().atMost(20L, TimeUnit.SECONDS)
                .until(TestingUtils.containerIsFinalized(containerService, container), is(true));

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

    private void testSetupWrapupFailure() throws Exception {
        setupServer();
        final CommandWrapper mainWrapper = configureSetupWrapupCommands(CommandType.DOCKER);
        Map<String, String> runtimeValues = new HashMap<>();
        String uri = setupSessionMock(runtimeValues);
        setupMocksForSetupWrapupWorkflow("/archive" + uri);

        containerService.queueResolveCommandAndLaunchContainer(null, mainWrapper.id(),
                0L, null, runtimeValues, mockUser, fakeWorkflow);
        final Container container = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        TestingUtils.commitTransaction();

        log.debug("Waiting until container is finalized");
        await().atMost(20L, TimeUnit.SECONDS)
                .until(TestingUtils.containerIsFinalized(containerService, container), is(true));

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

    private void testSetupWrapupFailureOnWrapup() throws Exception {
        setupServer();
        final CommandWrapper mainWrapper = configureSetupWrapupCommands(CommandType.DOCKER_WRAPUP);
        Map<String, String> runtimeValues = new HashMap<>();
        String uri = setupSessionMock(runtimeValues);
        setupMocksForSetupWrapupWorkflow("/archive" + uri);

        containerService.queueResolveCommandAndLaunchContainer(null, mainWrapper.id(),
                0L, null, runtimeValues, mockUser, fakeWorkflow);
        final Container container = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        TestingUtils.commitTransaction();

        log.debug("Waiting until container is finalized");
        await().atMost(20L, TimeUnit.SECONDS)
                .until(TestingUtils.containerIsFinalized(containerService, container), is(true));

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

    private String setupSessionMock(Map<String, String> runtimeValues) throws Exception {
        final Path wrapupCommandDirPath = Paths.get(ClassLoader.getSystemResource("wrapupCommand").toURI());
        final String wrapupCommandDir = wrapupCommandDirPath.toString().replace("%20", " ");
        // Set up input object(s)
        final String sessionInputJsonPath = wrapupCommandDir + "/session.json";
        // I need to set the resource directory to a temp directory
        final String resourceDir = folder.newFolder("resource").getAbsolutePath();
        final Session sessionInput = mapper.readValue(new File(sessionInputJsonPath), Session.class);
        assertThat(sessionInput.getResources(), Matchers.<Resource>hasSize(1));
        final Resource resource = sessionInput.getResources().get(0);
        resource.setDirectory(resourceDir);
        runtimeValues.put("session", mapper.writeValueAsString(sessionInput));
        return sessionInput.getUri();
    }

    private CommandWrapper configureSetupWrapupCommands(@Nullable CommandType failureLevel) throws Exception {
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
                .image("busybox:latest")
                .version("0")
                .commandLine(setupCmd)
                .type("docker-setup")
                .build());
        TestingUtils.commitTransaction();

        final Command wrapup = commandService.create(Command.builder()
                .name("wrapup")
                .image("busybox:latest")
                .version("0")
                .commandLine(wrapupCmd)
                .type("docker-wrapup")
                .build());
        TestingUtils.commitTransaction();

        final Command main = commandService.create(Command.builder()
                .name("main")
                .image("busybox:latest")
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
        return main.xnatCommandWrappers().get(0);
    }


    private void setupMocksForSetupWrapupWorkflow(String uri) throws Exception {
        final ArchivableItem mockItem = mock(ArchivableItem.class);
        String id = "id";
        String xsiType = "type";
        String project = "project";
        when(mockItem.getId()).thenReturn(id);
        when(mockItem.getXSIType()).thenReturn(xsiType);
        when(mockItem.getProject()).thenReturn(project);
        final ExptURI mockUriObject = mock(ExptURI.class);
        when(UriParserUtils.parseURI(uri)).thenReturn(mockUriObject);
        when(mockUriObject.getSecurityItem()).thenReturn(mockItem);
        fakeWorkflow.setId(uri);
        ResourceData mockRD = mock(ResourceData.class);
        when(mockRD.getItem()).thenReturn(mockItem);
        when(mockCatalogService.getResourceDataFromUri(uri)).thenReturn(mockRD);

        FakeWorkflow setupWrapupWorkflow = new FakeWorkflow();
        setupWrapupWorkflow.setWfid(111);
        setupWrapupWorkflow.setEventId(2);
        doReturn(setupWrapupWorkflow).when(PersistentWorkflowUtils.class, "getOrCreateWorkflowData", eq(2),
                eq(mockUser), any(XFTItem.class), any(EventDetails.class));
        when(WorkflowUtils.buildOpenWorkflow(eq(mockUser), eq(xsiType), eq(id), eq(project), any(EventDetails.class)))
                .thenReturn(setupWrapupWorkflow);

        when(WorkflowUtils.getUniqueWorkflow(mockUser, setupWrapupWorkflow.getWorkflowId().toString()))
                .thenReturn(setupWrapupWorkflow);
    }

    private void checkContainerRemoval(Container exited) throws Exception {
        checkContainerRemoval(exited, false);
    }

    private void checkContainerRemoval(Container exited, boolean okIfMissing) throws Exception {
        // Sleep to give time to remove the service or container; no way to check if this has happened since
        // status stops changing
        Thread.sleep(2000L);

        String id = swarmMode ? exited.serviceId() : exited.containerId();
        if (id == null) {
            if (okIfMissing) {
                return;
            }
            fail("No ID for " + exited);
        }

        if (autoCleanup) {
            if (swarmMode) {
                try {
                    CLIENT.inspectService(id);
                } catch (ServiceNotFoundException e) {
                    // This is what we expect
                    return;
                }
                fail("Service " + exited + " was not removed");
            } else {
                try {
                    CLIENT.inspectContainer(id);
                } catch (NotFoundException e) {
                    // This is what we expect
                    return;
                }
                fail("Container " + exited + " was not removed");
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
