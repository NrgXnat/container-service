package org.nrg.containers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.json.JsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import com.jayway.jsonpath.spi.mapper.MappingProvider;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.LoggingBuildHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.hamcrest.Matchers;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.ArgumentMatcher;
import org.nrg.containers.api.DockerControlApi;
import org.nrg.containers.config.EventPullingIntegrationTestConfig;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.auto.Command.CommandWrapper;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.auto.Container.ContainerMount;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.model.xnat.*;
import org.nrg.containers.services.*;
import org.nrg.containers.config.SpringJUnit4ClassRunnerFactory;
import org.nrg.containers.utils.TestingUtils;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xdat.entities.AliasToken;
import org.nrg.xdat.om.XnatResourcecatalog;
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
import org.nrg.xnat.helpers.uri.archive.impl.*;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeThat;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isNull;
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
public class CommandLaunchIntegrationTest {
    // Couldn't get the below to work
    //@ClassRule public static final SpringClassRule SPRING_CLASS_RULE = new SpringClassRule();
    //@Rule public final SpringMethodRule springMethodRule = new SpringMethodRule();

    @Parameterized.Parameters(name = "swarmMode={0}")
    public static Collection<Boolean> swarmModes() {
        return Arrays.asList(true, false);
    }
    @Parameterized.Parameter
    public boolean swarmMode;

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
    @Autowired private DockerService dockerService;
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
                false, null, true, null));

        CLIENT = controlApi.getClient();
        CLIENT.pull("busybox:latest");
    }

    @After
    public void cleanup() throws Exception {
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
    public void testFakeReconAll() throws Exception {
        assumeThat(SystemUtils.IS_OS_WINDOWS_7, is(false));
        assumeThat(TestingUtils.canConnectToDocker(CLIENT), is(true));

        CLIENT.pull("busybox:latest");

        final String dir = Paths.get(ClassLoader.getSystemResource("commandLaunchTest").toURI()).toString().replace("%20", " ");
        final String commandJsonFile = Paths.get(dir, "/fakeReconAllCommand.json").toString();
        final String sessionJsonFile = Paths.get(dir, "/session.json").toString();
        final String fakeResourceDir = Paths.get(dir, "/fakeResource").toString();
        final String commandWrapperName = "recon-all-session";

        final Command fakeReconAll = mapper.readValue(new File(commandJsonFile), Command.class);
        final Command fakeReconAllCreated = commandService.create(fakeReconAll);

        CommandWrapper commandWrapper = null;
        for (final CommandWrapper commandWrapperLoop : fakeReconAllCreated.xnatCommandWrappers()) {
            if (commandWrapperName.equals(commandWrapperLoop.name())) {
                commandWrapper = commandWrapperLoop;
                break;
            }
        }
        assertThat(commandWrapper, is(not(nullValue())));

        final Session session = mapper.readValue(new File(sessionJsonFile), Session.class);
        final Scan scan = session.getScans().get(0);
        final Resource resource = scan.getResources().get(0);
        resource.setDirectory(fakeResourceDir);
        final String sessionJson = mapper.writeValueAsString(session);
        final ArchivableItem mockSesItem = mock(ArchivableItem.class);
        final ExptURI mockUriObject = mock(ExptURI.class);
        when(UriParserUtils.parseURI("/archive" + session.getUri())).thenReturn(mockUriObject);
        when(mockUriObject.getSecurityItem()).thenReturn(mockSesItem);

        final String t1Scantype = "T1_TEST_SCANTYPE";

        final Map<String, String> runtimeValues = Maps.newHashMap();
        runtimeValues.put("session", sessionJson);
        runtimeValues.put("T1-scantype", t1Scantype);

        containerService.queueResolveCommandAndLaunchContainer(null, commandWrapper.id(),
                0L, null, runtimeValues, mockUser, fakeWorkflow);
        final Container execution = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        containersToCleanUp.add(swarmMode ? execution.serviceId() : execution.containerId());
        await().until(TestingUtils.containerIsRunning(CLIENT, swarmMode, execution), is(false));

        // Raw inputs
        assertThat(execution.getRawInputs(), is(runtimeValues));

        // xnat wrapper inputs
        final Map<String, String> expectedXnatInputValues = Maps.newHashMap();
        expectedXnatInputValues.put("session", session.getUri());
        expectedXnatInputValues.put("T1-scantype", t1Scantype);
        expectedXnatInputValues.put("label", session.getLabel());
        expectedXnatInputValues.put("T1", session.getScans().get(0).getUri());
        expectedXnatInputValues.put("resource", session.getScans().get(0).getResources().get(0).getUri());
        assertThat(execution.getWrapperInputs(), is(expectedXnatInputValues));

        // command inputs
        final Map<String, String> expectedCommandInputValues = Maps.newHashMap();
        expectedCommandInputValues.put("subject-id", session.getLabel());
        expectedCommandInputValues.put("other-recon-all-args", "-all");
        assertThat(execution.getCommandInputs(), is(expectedCommandInputValues));

        // Outputs
        // assertTrue(resolvedCommand.getOutputs().isEmpty());

        final List<String> outputNames = Lists.transform(execution.outputs(), new Function<Container.ContainerOutput, String>() {
            @Nullable
            @Override
            public String apply(@Nullable final Container.ContainerOutput output) {
                return output == null ? "" : output.name();
            }
        });
        assertThat(outputNames, contains("data:data-output", "text-file:text-file-output"));

        // Environment variables
        final Map<String, String> expectedEnvironmentVariables = Maps.newHashMap();
        expectedEnvironmentVariables.put("XNAT_USER", FAKE_ALIAS);
        expectedEnvironmentVariables.put("XNAT_PASS", FAKE_SECRET);
        expectedEnvironmentVariables.put("XNAT_HOST", FAKE_HOST);
        assertThat(execution.environmentVariables(), is(expectedEnvironmentVariables));


        final List<ContainerMount> mounts = execution.mounts();
        assertThat(mounts, hasSize(2));

        ContainerMount inputMount = null;
        ContainerMount outputMount = null;
        for (final ContainerMount mount : mounts) {
            if (mount.name().equals("input")) {
                inputMount = mount;
            } else if (mount.name().equals("output")) {
                outputMount = mount;
            } else {
                fail("We should not have a mount with name " + mount.name());
            }
        }

        assertThat(inputMount, is(not(nullValue())));
        assertThat(inputMount.containerPath(), is("/input"));
        assertThat(inputMount.xnatHostPath(), is(fakeResourceDir));

        assertThat(outputMount, is(not(nullValue())));
        assertThat(outputMount.containerPath(), is("/output"));
        final String outputPath = outputMount.xnatHostPath();

        printContainerLogs(execution);

        try {
            final String[] outputFileContents = TestingUtils.readFile(outputPath + "/out.txt");
            assertThat(outputFileContents.length, greaterThanOrEqualTo(2));
            assertThat(outputFileContents[0], is("recon-all -s session1 -all"));

            final File fakeResourceDirFile = new File(fakeResourceDir);
            assertThat(fakeResourceDirFile, is(not(nullValue())));
            assertThat(fakeResourceDirFile.listFiles(), is(not(nullValue())));
            final List<String> fakeResourceDirFileNames = Lists.newArrayList();
            for (final File file : fakeResourceDirFile.listFiles()) {
                fakeResourceDirFileNames.add(file.getName());

            }
            assertThat(Lists.newArrayList(outputFileContents[1].split(" ")), is(fakeResourceDirFileNames));
        } catch (IOException e) {
            log.warn("Failed to read output files. This is not a problem if you are using docker-machine and cannot mount host directories.", e);
        }
    }

    @Test
    @DirtiesContext
    public void testProjectMount() throws Exception {
        assumeThat(TestingUtils.canConnectToDocker(CLIENT), is(true));

        final String dir = Paths.get(ClassLoader.getSystemResource("commandLaunchTest").toURI()).toString().replace("%20", " ");
        final String commandJsonFile = dir + "/project-mount-command.json";
        final String projectJsonFile = dir + "/project.json";
        final String projectDir = dir + "/project";
        // final String commandWrapperName = "find-in-project";

        final Command command = mapper.readValue(new File(commandJsonFile), Command.class);
        final Command commandCreated = commandService.create(command);
        final CommandWrapper commandWrapper = commandCreated.xnatCommandWrappers().get(0);
        assertThat(commandWrapper, is(not(nullValue())));

        final Project project = mapper.readValue(new File(projectJsonFile), Project.class);
        project.setDirectory(projectDir);
        final String projectJson = mapper.writeValueAsString(project);

        // Create the mock objects we will need in order to verify permissions
        final ArchivableItem mockProjectItem = mock(ArchivableItem.class);
        final ExptURI mockUriObject = mock(ExptURI.class);
        when(UriParserUtils.parseURI("/archive" + project.getUri())).thenReturn(mockUriObject);
        when(mockUriObject.getSecurityItem()).thenReturn(mockProjectItem);

        final Map<String, String> runtimeValues = Maps.newHashMap();
        runtimeValues.put("project", projectJson);

        containerService.queueResolveCommandAndLaunchContainer(null, commandWrapper.id(),
                0L, null, runtimeValues, mockUser, fakeWorkflow);
        final Container execution = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        containersToCleanUp.add(swarmMode ? execution.serviceId() : execution.containerId());
        await().until(TestingUtils.containerIsRunning(CLIENT, swarmMode, execution), is(false));

        // Raw inputs
        assertThat(execution.getRawInputs(), is(runtimeValues));

        // xnat wrapper inputs
        final Map<String, String> expectedXnatInputValues = Maps.newHashMap();
        expectedXnatInputValues.put("project", project.getUri());
        assertThat(execution.getWrapperInputs(), is(expectedXnatInputValues));

        // command inputs
        final Map<String, String> expectedCommandInputValues = Maps.newHashMap();
        assertThat(execution.getCommandInputs(), is(expectedCommandInputValues));

        // Outputs by name. We will check the files later.
        final List<String> outputNames = Lists.transform(execution.outputs(), new Function<Container.ContainerOutput, String>() {
            @Override
            public String apply(final Container.ContainerOutput output) {
                return output.name();
            }
        });
        assertThat(outputNames, contains("outputs:file-and-dir-lists"));

        // Environment variables
        final Map<String, String> expectedEnvironmentVariables = Maps.newHashMap();
        expectedEnvironmentVariables.put("XNAT_USER", FAKE_ALIAS);
        expectedEnvironmentVariables.put("XNAT_PASS", FAKE_SECRET);
        expectedEnvironmentVariables.put("XNAT_HOST", FAKE_HOST);
        assertThat(execution.environmentVariables(), is(expectedEnvironmentVariables));

        // mounts
        final List<ContainerMount> mounts = execution.mounts();
        assertThat(mounts, hasSize(2));

        ContainerMount inputMount = null;
        ContainerMount outputMount = null;
        for (final ContainerMount mount : mounts) {
            if (mount.name().equals("input")) {
                inputMount = mount;
            } else if (mount.name().equals("output")) {
                outputMount = mount;
            } else {
                fail("We should not have a mount with name " + mount.name());
            }
        }

        assertThat(inputMount, is(not(nullValue())));
        assertThat(inputMount.containerPath(), is("/input"));
        assertThat(inputMount.xnatHostPath(), is(projectDir));

        assertThat(outputMount, is(not(nullValue())));
        assertThat(outputMount.containerPath(), is("/output"));
        final String outputPath = outputMount.xnatHostPath();

        printContainerLogs(execution);

        try {
            // Read two output files: files.txt and dirs.txt
            final String[] expectedFilesFileContents = {
                    "/input/project-file.txt",
                    "/input/resource/project-resource-file.txt",
                    "/input/session/resource/session-resource-file.txt",
                    "/input/session/scan/resource/scan-resource-file.txt",
                    "/input/session/scan/scan-file.txt",
                    "/input/session/session-file.txt"
            };
            final List<String> filesFileContents = Lists.newArrayList(TestingUtils.readFile(outputPath + "/files.txt"));
            assertThat(filesFileContents, containsInAnyOrder(expectedFilesFileContents));

            final String[] expectedDirsFileContents = {
                    "/input",
                    "/input/resource",
                    "/input/session",
                    "/input/session/resource",
                    "/input/session/scan",
                    "/input/session/scan/resource"
            };
            final List<String> dirsFileContents = Lists.newArrayList(TestingUtils.readFile(outputPath + "/dirs.txt"));
            assertThat(dirsFileContents, containsInAnyOrder(expectedDirsFileContents));
        } catch (IOException e) {
            log.warn("Failed to read output files. This is not a problem if you are using docker-machine and cannot mount host directories.", e);
        }
    }

    @Test
    @DirtiesContext
    public void testLaunchCommandWithSetupCommand() throws Exception {
        assumeThat(SystemUtils.IS_OS_WINDOWS_7, is(false));
        assumeThat(TestingUtils.canConnectToDocker(CLIENT), is(true));

        // This test fails on Circle CI because we cannot mount local directories into containers
        assumeThat(testIsOnCircleCi, is(false));

        CLIENT.pull("busybox:latest");

        final Path setupCommandDirPath = Paths.get(ClassLoader.getSystemResource("setupCommand").toURI());
        final String setupCommandDir = setupCommandDirPath.toString().replace("%20", " ");

        final String commandWithSetupCommandJsonFile = Paths.get(setupCommandDir, "/command-with-setup-command.json").toString();
        final Command commandWithSetupCommandToCreate = mapper.readValue(new File(commandWithSetupCommandJsonFile), Command.class);
        final Command commandWithSetupCommand = commandService.create(commandWithSetupCommandToCreate);

        // We could hard-code the name of the image we referenced in the "via-setup-command" property, or we could pull it out.
        // Let's do the latter, so in case we change it later this will not fail.
        assertThat(commandWithSetupCommand.xnatCommandWrappers(), hasSize(1));
        final CommandWrapper commandWithSetupCommandWrapper = commandWithSetupCommand.xnatCommandWrappers().get(0);
        assertThat(commandWithSetupCommandWrapper.externalInputs(), hasSize(1));
        assertThat(commandWithSetupCommandWrapper.externalInputs().get(0).viaSetupCommand(), not(isEmptyOrNullString()));
        final String setupCommandImageAndCommandName = commandWithSetupCommandWrapper.externalInputs().get(0).viaSetupCommand();
        final String[] setupCommandSplitOnColon = setupCommandImageAndCommandName.split(":");
        assertThat(setupCommandSplitOnColon, arrayWithSize(3));
        final String setupCommandImageName = setupCommandSplitOnColon[0] + ":" + setupCommandSplitOnColon[1];
        final String setupCommandName = setupCommandSplitOnColon[2];

        CLIENT.build(setupCommandDirPath, setupCommandImageName);
        imagesToCleanUp.add(setupCommandImageName);

        // Make the setup command from the json file.
        // Assert that its name and image are the same ones referred to in the "via-setup-command" property
        final String setupCommandJsonFile = Paths.get(setupCommandDir, "/setup-command.json").toString();
        final Command setupCommandToCreate = mapper.readValue(new File(setupCommandJsonFile), Command.class);
        final Command setupCommand = commandService.create(setupCommandToCreate);
        assertThat(setupCommand.name(), is(setupCommandName));
        assertThat(setupCommand.image(), is(setupCommandImageName));

        TestingUtils.commitTransaction();

        final String resourceInputJsonPath = setupCommandDir + "/resource.json";
        // I need to set the resource directory to a temp directory
        final String resourceDir = folder.newFolder("resource").getAbsolutePath();
        final Resource resourceInput = mapper.readValue(new File(resourceInputJsonPath), Resource.class);
        resourceInput.setDirectory(resourceDir);
        final Map<String, String> runtimeValues = Collections.singletonMap("resource", mapper.writeValueAsString(resourceInput));

        // Write a test file to the resource
        final String testFileContents = "contents of the file";
        Files.write(Paths.get(resourceDir, "test.txt"), testFileContents.getBytes());

        final ArchivableItem mockItem = mock(ArchivableItem.class);
        final ResourcesExptURI mockUriObject = mock(ResourcesExptURI.class);
        String uri = "/archive" + resourceInput.getUri();
        when(UriParserUtils.parseURI(uri)).thenReturn(mockUriObject);
        when(mockUriObject.getSecurityItem()).thenReturn(mockItem);
        fakeWorkflow.setId(uri);
        ResourceData mockRD = mock(ResourceData.class);
        when(mockRD.getItem()).thenReturn(mockItem);
        when(mockCatalogService.getResourceDataFromUri(uri)).thenReturn(mockRD);

        String id = "id";
        String xsiType = "type";
        String project = "project";
        when(mockItem.getId()).thenReturn(id);
        when(mockItem.getXSIType()).thenReturn(xsiType);
        when(mockItem.getProject()).thenReturn(project);

        // Setup workflow
        FakeWorkflow setupWrapupWorkflow = new FakeWorkflow();
        setupWrapupWorkflow.setWfid(111);
        setupWrapupWorkflow.setEventId(2);
        doReturn(setupWrapupWorkflow).when(PersistentWorkflowUtils.class, "getOrCreateWorkflowData", eq(2),
                eq(mockUser), any(XFTItem.class), any(EventDetails.class));
        when(WorkflowUtils.buildOpenWorkflow(eq(mockUser), eq(xsiType), eq(id), eq(project), any(EventDetails.class)))
                .thenReturn(setupWrapupWorkflow);

        when(WorkflowUtils.getUniqueWorkflow(mockUser, setupWrapupWorkflow.getWorkflowId().toString()))
                .thenReturn(setupWrapupWorkflow);

        // Time to launch this thing
        containerService.queueResolveCommandAndLaunchContainer(null, commandWithSetupCommandWrapper.id(),
                0L, null, runtimeValues, mockUser, fakeWorkflow);
        final Container mainContainerRightAfterLaunch = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        containersToCleanUp.add(swarmMode ? mainContainerRightAfterLaunch.serviceId() : mainContainerRightAfterLaunch.containerId());
        TestingUtils.commitTransaction();
        log.debug("Waiting until container is finalized or has failed");
        await().atMost(30L, TimeUnit.SECONDS)
                .until(TestingUtils.containerIsFinalized(containerService, mainContainerRightAfterLaunch), is(true));

        final Container mainContainerAWhileAfterLaunch = containerService.get(mainContainerRightAfterLaunch.databaseId());
        final List<Container> setupContainers = containerService.retrieveSetupContainersForParent(mainContainerAWhileAfterLaunch.databaseId());
        assertThat(setupContainers, hasSize(1));
        final Container setupContainer = setupContainers.get(0);
        containersToCleanUp.add(swarmMode ? setupContainer.serviceId() : setupContainer.containerId());

        // Print the logs for debugging in case weird stuff happened
        printContainerLogs(setupContainer, "setup");
        printContainerLogs(mainContainerAWhileAfterLaunch, "main");

        // Sanity Checks
        assertThat(setupContainer.parent(), is(mainContainerAWhileAfterLaunch));
        assertThat(setupContainer.status(), not(containsString("Failed")));

        // Check main container's input mount for contents
        final ContainerMount mainContainerMount = mainContainerAWhileAfterLaunch.mounts().get(0);
        final File mainContainerMountDir = new File(mainContainerMount.xnatHostPath());
        final File[] contentsOfMainContainerMountDir = mainContainerMountDir.listFiles();

        // This is what we will be testing, and why it validates that the setup container worked.
        // We wrote "test.txt" to the resource's directory.
        // The main container is set to mount an initially empty directory. Call this "main mount".
        // The setup container is set to mount the resource's directory as its input and the main mount as its output.
        // When the setup container runs, it copies "text.txt" from its input to its output. It also creates a new
        //     file "another-file" in its output, which we did not explicitly create in this test.
        // By verifying that the main container's mount sees both files, we have verified that the setup container
        //     put the files where they needed to go, and that all the mounts were hooked up correctly.
        assertThat(contentsOfMainContainerMountDir, hasItemInArray(TestingUtils.pathEndsWith("test.txt")));
        assertThat(contentsOfMainContainerMountDir, hasItemInArray(TestingUtils.pathEndsWith("another-file")));
    }

    @Test
    @DirtiesContext
    public void testLaunchCommandWithWrapupCommand() throws Exception {
        assumeThat(SystemUtils.IS_OS_WINDOWS_7, is(false));
        assumeThat(TestingUtils.canConnectToDocker(CLIENT), is(true));

        // This test fails on Circle CI because we cannot mount local directories into containers
        assumeThat(testIsOnCircleCi, is(false));

        CLIENT.pull("busybox:latest");

        final Path wrapupCommandDirPath = Paths.get(ClassLoader.getSystemResource("wrapupCommand").toURI());
        final String wrapupCommandDir = wrapupCommandDirPath.toString().replace("%20", " ");

        final String commandWithWrapupCommandJsonFile = Paths.get(wrapupCommandDir, "/command-with-wrapup-command.json").toString();
        final Command commandWithWrapupCommandToCreate = mapper.readValue(new File(commandWithWrapupCommandJsonFile), Command.class);
        final Command commandWithWrapupCommand = commandService.create(commandWithWrapupCommandToCreate);

        // We could hard-code the name of the image we referenced in the "via-wrapup-command" property, or we could pull it out.
        // Let's do the latter, so in case we change it later this will not fail.
        assertThat(commandWithWrapupCommand.xnatCommandWrappers(), hasSize(1));
        final CommandWrapper commandWithWrapupCommandWrapper = commandWithWrapupCommand.xnatCommandWrappers().get(0);
        assertThat(commandWithWrapupCommandWrapper.outputHandlers(), hasSize(1));
        assertThat(commandWithWrapupCommandWrapper.outputHandlers().get(0).viaWrapupCommand(), not(isEmptyOrNullString()));
        final String wrapupCommandImageAndCommandName = commandWithWrapupCommandWrapper.outputHandlers().get(0).viaWrapupCommand();
        final String[] wrapupCommandSplitOnColon = wrapupCommandImageAndCommandName.split(":");
        assertThat(wrapupCommandSplitOnColon, arrayWithSize(3));
        final String wrapupCommandImageName = wrapupCommandSplitOnColon[0] + ":" + wrapupCommandSplitOnColon[1];
        final String wrapupCommandName = wrapupCommandSplitOnColon[2];

        final String commandWithWrapupCommandImageName = commandWithWrapupCommand.image();

        // Build two images: the wrapup image and the main image
        CLIENT.build(wrapupCommandDirPath, wrapupCommandImageName, "Dockerfile.wrapup", new LoggingBuildHandler());
        CLIENT.build(wrapupCommandDirPath, commandWithWrapupCommandImageName, "Dockerfile.main", new LoggingBuildHandler());
        imagesToCleanUp.add(wrapupCommandImageName);
        imagesToCleanUp.add(commandWithWrapupCommandImageName);

        // Make the wrapup command from the json file.
        // Assert that its name and image are the same ones referred to in the "via-wrapup-command" property
        final String wrapupCommandJsonFile = Paths.get(wrapupCommandDir, "/wrapup-command.json").toString();
        final Command wrapupCommandToCreate = mapper.readValue(new File(wrapupCommandJsonFile), Command.class);
        final Command wrapupCommand = commandService.create(wrapupCommandToCreate);
        assertThat(wrapupCommand.name(), is(wrapupCommandName));
        assertThat(wrapupCommand.image(), is(wrapupCommandImageName));

        TestingUtils.commitTransaction();

        // Set up input object(s)
        final String sessionInputJsonPath = wrapupCommandDir + "/session.json";
        // I need to set the resource directory to a temp directory
        final String resourceDir = folder.newFolder("resource").getAbsolutePath();
        final Session sessionInput = mapper.readValue(new File(sessionInputJsonPath), Session.class);
        assertThat(sessionInput.getResources(), Matchers.<Resource>hasSize(1));
        final Resource resource = sessionInput.getResources().get(0);
        resource.setDirectory(resourceDir);
        final Map<String, String> runtimeValues = Collections.singletonMap("session", mapper.writeValueAsString(sessionInput));

        // Write a few test files to the resource
        final byte[] testFileContents = "contents of the file".getBytes();
        final String[] fileNames = new String[] {"a", "b", "c", "d", "e", "f", "g"};
        for (final String filename : fileNames) {
            Files.write(Paths.get(resourceDir, filename), testFileContents);
        }

        // Ensure the session XNAT object will be returned by the call to UriParserUtils.parseURI
        final ArchivableItem mockSessionItem = mock(ArchivableItem.class);
        final ExptURI mockUriObject = mock(ExptURI.class);
        String uri = "/archive" + sessionInput.getUri();
        when(UriParserUtils.parseURI(uri)).thenReturn(mockUriObject);
        when(mockUriObject.getSecurityItem()).thenReturn(mockSessionItem);
        String id = "id";
        String xsiType = "type";
        String project = "project";
        when(mockSessionItem.getId()).thenReturn(id);
        when(mockSessionItem.getXSIType()).thenReturn(xsiType);
        when(mockSessionItem.getProject()).thenReturn(project);
        fakeWorkflow.setId(uri);
        ResourceData mockRD = mock(ResourceData.class);
        when(mockRD.getItem()).thenReturn(mockSessionItem);
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

        // Time to launch this thing
        containerService.queueResolveCommandAndLaunchContainer(null, commandWithWrapupCommandWrapper.id(),
                0L, null, runtimeValues, mockUser, fakeWorkflow);
        final Container mainContainerRightAfterLaunch = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        containersToCleanUp.add(swarmMode ? mainContainerRightAfterLaunch.serviceId() : mainContainerRightAfterLaunch.containerId());

        TestingUtils.commitTransaction();

        log.debug("Waiting until container is finalized or has failed");
        await().atMost(20L, TimeUnit.SECONDS)
                .until(TestingUtils.containerIsFinalized(containerService, mainContainerRightAfterLaunch), is(true));
        Container mainContainerAWhileAfterLaunch = containerService.get(mainContainerRightAfterLaunch.databaseId()); //refresh it
        assertThat(mainContainerAWhileAfterLaunch.status(), not(containsString("Failed")));
        assertThat(fakeWorkflow.getStatus(), not(startsWith(PersistentWorkflowUtils.FAILED)));

        final List<Container> wrapupContainers = containerService.retrieveWrapupContainersForParent(mainContainerAWhileAfterLaunch.databaseId());
        assertThat(wrapupContainers, hasSize(1));
        final Container wrapupContainer = wrapupContainers.get(0);
        containersToCleanUp.add(swarmMode ? wrapupContainer.serviceId() : wrapupContainer.containerId());

        // Print the logs for debugging in case weird stuff happened
        printContainerLogs(wrapupContainer, "wrapup");
        printContainerLogs(mainContainerAWhileAfterLaunch, "main");

        // Sanity Checks
        assertThat(wrapupContainer.parent(), is(mainContainerAWhileAfterLaunch));
        assertThat(wrapupContainer.status(), not(containsString("Failed")));

        // This is what we will be testing, and why it validates that the wrapup container worked.
        // The wrapup container wrote "found-files.txt" to the output mount. The contents of the file
        // will be the locations (from find) of all the files in the input mount.

        final String[] expectedFileContentsByLine = new String[fileNames.length + 1];
        expectedFileContentsByLine[0] = "/input";
        for (int i = 0; i < fileNames.length; i++) {
            expectedFileContentsByLine[i+1] = "/input/" + fileNames[i];
        }

        // Check wrapup container's output mount for contents
        ContainerMount wrapupContainerOutputMount = null;
        for (final ContainerMount wrapupMount : wrapupContainer.mounts()) {
            if (wrapupMount.name().equals("output")) {
                wrapupContainerOutputMount = wrapupMount;
            }
        }
        assertThat(wrapupContainerOutputMount, notNullValue(ContainerMount.class));
        final File wrapupContainerOutputMountDir = new File(wrapupContainerOutputMount.xnatHostPath());
        final File[] contentsOfWrapupContainerOutputMountDir = wrapupContainerOutputMountDir.listFiles();

        assertThat(contentsOfWrapupContainerOutputMountDir, Matchers.<File>arrayWithSize(1));
        assertThat(contentsOfWrapupContainerOutputMountDir, hasItemInArray(TestingUtils.pathEndsWith("found-files.txt")));
        final File foundFilesDotTxt = contentsOfWrapupContainerOutputMountDir[0];
        final String[] foundFilesDotTxtContentByLine = TestingUtils.readFile(foundFilesDotTxt);
        assertThat(foundFilesDotTxtContentByLine, arrayContainingInAnyOrder(expectedFileContentsByLine));
    }

    @Test
    @DirtiesContext
    public void testFailedContainer() throws Exception {
        assumeThat(SystemUtils.IS_OS_WINDOWS_7, is(false));
        assumeThat(TestingUtils.canConnectToDocker(CLIENT), is(true));

        CLIENT.pull("busybox:latest");

        final Command willFail = commandService.create(Command.builder()
                .name("will-fail")
                .image("busybox:latest")
                .version("0")
                .commandLine("/bin/sh -c \"exit 1\"")
                .addCommandWrapper(CommandWrapper.builder()
                        .name("placeholder")
                        .build())
                .build());
        final CommandWrapper willFailWrapper = willFail.xnatCommandWrappers().get(0);

        TestingUtils.commitTransaction();


        containerService.queueResolveCommandAndLaunchContainer(null, willFailWrapper.id(), 0L,
                null, Collections.<String, String>emptyMap(), mockUser, fakeWorkflow);
        final Container container = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        containersToCleanUp.add(swarmMode ? container.serviceId() : container.containerId());

        TestingUtils.commitTransaction();

        log.debug("Waiting until task has started");
        await().until(TestingUtils.containerHasStarted(CLIENT, swarmMode, container), is(true));
        log.debug("Waiting until task has finished");
        await().until(TestingUtils.containerIsRunning(CLIENT, swarmMode, container), is(false));
        //Finalizing queue adds system history item, so this is no longer relevant
        //log.debug("Waiting until status updater has picked up finished task and added item to history");
        //await().until(containerHistoryHasItemFromSystem(container.databaseId()), is(true));
        log.debug("Waiting until container is finalized");
        await().until(TestingUtils.containerIsFinalized(containerService, container), is(true));

        final Container exited = containerService.get(container.databaseId());
        printContainerLogs(exited);
        assertThat(exited.exitCode(), is("1"));
        assertThat(exited.status(), is(PersistentWorkflowUtils.FAILED));
        assertThat(fakeWorkflow.getStatus(), is(PersistentWorkflowUtils.FAILED));
    }

    @Test
    @DirtiesContext
    public void testEntrypointIsPreserved() throws Exception {
        assumeThat(TestingUtils.canConnectToDocker(CLIENT), is(true));

        CLIENT.pull("busybox:latest");

        final String resourceDir = Paths.get(ClassLoader.getSystemResource("commandLaunchTest").toURI()).toString().replace("%20", " ");
        final Path testDir = Paths.get(resourceDir, "/testEntrypointIsPreserved");
        final String commandJsonFile = Paths.get(testDir.toString(), "/command.json").toString();

        final String imageName = "xnat/entrypoint-test:latest";
        CLIENT.build(testDir, imageName);
        imagesToCleanUp.add(imageName);

        final Command commandToCreate = mapper.readValue(new File(commandJsonFile), Command.class);
        final Command command = commandService.create(commandToCreate);
        final CommandWrapper wrapper = command.xnatCommandWrappers().get(0);

        TestingUtils.commitTransaction();


        containerService.queueResolveCommandAndLaunchContainer(null, wrapper.id(), 0L,
                null, Collections.<String, String>emptyMap(), mockUser, fakeWorkflow);
        final Container container = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        containersToCleanUp.add(swarmMode ? container.serviceId() : container.containerId());

        TestingUtils.commitTransaction();

        await().until(TestingUtils.containerIsRunning(CLIENT, swarmMode, container), is(false));
        await().until(TestingUtils.containerHasLogPaths(containerService, container.databaseId())); // Thus we know it has been finalized

        final Container exited = containerService.get(container.databaseId());
        printContainerLogs(exited);
        assertThat(exited.status(), not(startsWith(PersistentWorkflowUtils.FAILED)));
        assertThat(exited.exitCode(), is("0"));
    }

    @Test
    @DirtiesContext
    public void testEntrypointIsRemoved() throws Exception {
        assumeThat(TestingUtils.canConnectToDocker(CLIENT), is(true));

        CLIENT.pull("busybox:latest");

        final String resourceDir = Paths.get(ClassLoader.getSystemResource("commandLaunchTest").toURI()).toString().replace("%20", " ");
        final Path testDir = Paths.get(resourceDir, "/testEntrypointIsRemoved");
        final String commandJsonFile = Paths.get(testDir.toString(), "/command.json").toString();

        final String imageName = "xnat/entrypoint-test:latest";
        CLIENT.build(testDir, imageName);
        imagesToCleanUp.add(imageName);

        final Command commandToCreate = mapper.readValue(new File(commandJsonFile), Command.class);
        final Command command = commandService.create(commandToCreate);
        final CommandWrapper wrapper = command.xnatCommandWrappers().get(0);

        TestingUtils.commitTransaction();


        containerService.queueResolveCommandAndLaunchContainer(null, wrapper.id(),
                0L, null, Collections.<String, String>emptyMap(), mockUser, fakeWorkflow);
        final Container container = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        containersToCleanUp.add(swarmMode ? container.serviceId() : container.containerId());

        TestingUtils.commitTransaction();

        await().until(TestingUtils.containerIsRunning(CLIENT, swarmMode, container), is(false));
        await().until(TestingUtils.containerHasLogPaths(containerService, container.databaseId())); // Thus we know it has been finalized

        final Container exited = containerService.get(container.databaseId());
        printContainerLogs(exited);
        assertThat(exited.status(), not(containsString("Failed")));
        assertThat(exited.exitCode(), is("0"));
        assertThat(fakeWorkflow.getStatus(), not(startsWith(PersistentWorkflowUtils.FAILED)));
    }

    @Test
    @DirtiesContext
    public void testContainerWorkingDirectory() throws Exception {
        assumeThat(SystemUtils.IS_OS_WINDOWS_7, is(false));
        assumeThat(TestingUtils.canConnectToDocker(CLIENT), is(true));

        CLIENT.pull("busybox:latest");

        final String workingDirectory = "/usr/local/bin";
        final Command command = commandService.create(Command.builder()
                .name("command")
                .image("busybox:latest")
                .version("0")
                .commandLine("pwd")
                .workingDirectory(workingDirectory)
                .addCommandWrapper(CommandWrapper.builder()
                        .name("placeholder")
                        .build())
                .build());
        final CommandWrapper wrapper = command.xnatCommandWrappers().get(0);

        TestingUtils.commitTransaction();


        containerService.queueResolveCommandAndLaunchContainer(null, wrapper.id(),
                0L, null, Collections.<String, String>emptyMap(), mockUser, fakeWorkflow);
        final Container container = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        containersToCleanUp.add(swarmMode ? container.serviceId() : container.containerId());

        TestingUtils.commitTransaction();

        await().until(TestingUtils.containerIsRunning(CLIENT, swarmMode, container), is(false));

        final Container exited = containerService.get(container.databaseId());
        printContainerLogs(exited);
        assertThat(exited.workingDirectory(), is(workingDirectory));
    }

    @Test
    @DirtiesContext
    public void testDeleteCommandWhenDeleteImageAfterLaunchingContainer() throws Exception {
        assumeThat(TestingUtils.canConnectToDocker(CLIENT), is(true));

        final String imageName = "xnat/testy-test";
        final String resourceDir = Paths.get(ClassLoader.getSystemResource("commandLaunchTest").toURI()).toString().replace("%20", " ");
        final Path testDir = Paths.get(resourceDir, "/testDeleteCommandWhenDeleteImageAfterLaunchingContainer");

        final String imageId = CLIENT.build(testDir, imageName);

        final List<Command> commands = dockerService.saveFromImageLabels(imageName);

        TestingUtils.commitTransaction();

        final Command command = commands.get(0);
        final CommandWrapper wrapper = command.xnatCommandWrappers().get(0);


        containerService.queueResolveCommandAndLaunchContainer(null, wrapper.id(),
                0L, null, Collections.<String, String>emptyMap(), mockUser, fakeWorkflow);
        final Container container = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        containersToCleanUp.add(swarmMode ? container.serviceId() : container.containerId());

        TestingUtils.commitTransaction();

        await().until(TestingUtils.containerIsRunning(CLIENT, swarmMode, container), is(false));
        final Container exited = containerService.get(container.databaseId());
        printContainerLogs(exited);

        dockerService.removeImageById(imageId, true);

        TestingUtils.commitTransaction();

        try {
            dockerService.getImage(imageId);
            fail("We expect a NotFoundException to be thrown when getting an image that we have removed. If this line is executed it means no exception was thrown.");
        } catch (NotFoundException ignored) {
            // exception is expected
        } catch (Exception e) {
            fail("We expect a NotFoundException to be thrown when getting an image that we have removed. If this line is executed it means another exception type was thrown.\n" + e.getClass().getName() + ": " + e.getMessage());
        }

        final Command retrieved = commandService.retrieve(command.id());
        assertThat(retrieved, is(nullValue(Command.class)));

        final Command.CommandWrapper retrievedWrapper = commandService.retrieveWrapper(wrapper.id());
        assertThat(retrievedWrapper, is(nullValue(Command.CommandWrapper.class)));

    }

    @Test
    @DirtiesContext
    public void testScanUpload() throws Exception {
        // NOTE: this doesn't test the actual upload of the xml, which is xnat-web functionality

        assumeThat(SystemUtils.IS_OS_WINDOWS_7, is(false));
        assumeThat(TestingUtils.canConnectToDocker(CLIENT), is(true));

        CLIENT.pull("busybox:latest");

        final String dir = Paths.get(ClassLoader.getSystemResource("commandLaunchTest").toURI()).toString().replace("%20", " ");
        final String curDir = Paths.get(dir, "testScanUpload").toString();
        final String fakeDataDir = Paths.get(curDir, "fakeDirForCopy").toString();

        final String commandJsonFile = curDir + "/cmd.json";
        final Command tempCommand = mapper.readValue(new File(commandJsonFile), Command.class);
        Command cmd = commandService.create(tempCommand);
        CommandWrapper wrapper = cmd.xnatCommandWrappers().get(0);

        final String sessionJsonFile = Paths.get(dir, "/session.json").toString();
        final Session session = mapper.readValue(new File(sessionJsonFile), Session.class);
        session.setDirectory(fakeDataDir);
        final String sessionJson = mapper.writeValueAsString(session);

        final File[] dicomFiles = new File(fakeDataDir, "DICOM").listFiles();
        if (dicomFiles == null) {
            throw new Exception("Test dir not set up properly");
        }
        final List<File> fakeUpload = Arrays.asList(dicomFiles);
        final File scanXml = new File(fakeDataDir, "scan.xml");

        final ArchivableItem mockSessionItem = mock(ArchivableItem.class);
        final XFTItem mockScanItem = mock(XFTItem.class);
        final ExptURI mockSesUri = mock(ExptURI.class);
        final ExptScanURI mockScanUri = mock(ExptScanURI.class);
        final String uri = "/archive" + session.getUri() + "/scans/scanNew";
        when(UriParserUtils.getArchiveUri(mockScanItem)).thenReturn(uri);
        when(UriParserUtils.parseURI(uri)).thenReturn(mockScanUri);
        when(UriParserUtils.parseURI("/archive" + session.getUri())).thenReturn(mockSesUri);
        when(mockScanUri.getSecurityItem()).thenReturn(mockSessionItem);
        when(mockSesUri.getSecurityItem()).thenReturn(mockSessionItem);

        ArgumentMatcher<File> matchesXml = new ArgumentMatcher<File>() {
            @Override
            public boolean matches(Object arg) {
                if (!(arg instanceof File)) {
                    return false;
                }
                File file = (File) arg;
                try {
                    return file.getName().equals(scanXml.getName()) && FileUtils.contentEqualsIgnoreEOL(file, scanXml, null);
                } catch (IOException e) {
                    return false;
                }
            }
        };
        ArgumentMatcher<List<File>> matchesFileList = new ArgumentMatcher<List<File>>() {
            @Override
            public boolean matches(Object arg) {
                if (!(arg instanceof List)) {
                    return false;
                }
                List<?> files = (List<?>) arg;
                for (Object f : files) {
                    if (!(f instanceof File)) {
                        return false;
                    }
                    File file = (File) f;
                    boolean matches = false;
                    for (File uploadedFile : fakeUpload) {
                        try {
                            if (file.getName().equals(uploadedFile.getName()) &&
                                    FileUtils.contentEqualsIgnoreEOL(file, uploadedFile, null)) {

                                matches = true;
                                break;
                            }
                        } catch (IOException e) {
                            // Ignore
                        }
                    }
                    if (!matches) {
                        return false;
                    }
                }
                return true;
            }
        };

        when(mockCatalogService.insertXmlObject(eq(mockUser), argThat(matchesXml), eq(true),
                eq(Collections.<String, Object>emptyMap()), any(Integer.class))).thenReturn(mockScanItem);
        final String dicomLabel = "DICOM";
        final XnatResourcecatalog mockCatalog = mock(XnatResourcecatalog.class);
        when(mockCatalog.getLabel()).thenReturn(dicomLabel);
        when(mockCatalogService.insertResources(eq(mockUser), eq(uri), argThat(matchesFileList), any(Integer.class),
                eq(true), eq(true), eq(dicomLabel), any(String.class), any(String.class), any(String.class)))
                .thenReturn(mockCatalog);

        final Map<String, String> runtimeValues = Maps.newHashMap();
        runtimeValues.put("session", sessionJson);

        containerService.queueResolveCommandAndLaunchContainer(null, wrapper.id(),
                0L, null, runtimeValues, mockUser, fakeWorkflow);
        final Container execution = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        containersToCleanUp.add(swarmMode ? execution.serviceId() : execution.containerId());

        TestingUtils.commitTransaction();

        log.debug("Waiting until container is done finalizing");
        await().until(TestingUtils.containerIsFinalized(containerService, execution));

        // Assert that upload doesn't fail, this means that the proper files and params were passed to our mock methods
        assertThat(fakeWorkflow.getStatus(), not(startsWith(PersistentWorkflowUtils.FAILED)));
    }

    @Test
    @DirtiesContext
    public void testAssessorUpload() throws Exception {
        // NOTE: this doesn't test the actual upload of the xml, which is xnat-web functionality

        assumeThat(SystemUtils.IS_OS_WINDOWS_7, is(false));
        assumeThat(TestingUtils.canConnectToDocker(CLIENT), is(true));

        CLIENT.pull("busybox:latest");

        final String dir = Paths.get(ClassLoader.getSystemResource("commandLaunchTest").toURI()).toString().replace("%20", " ");
        final String curDir = Paths.get(dir, "testAssessorUpload").toString();
        final String fakeDataDir = Paths.get(curDir, "fakeDirForCopy").toString();

        // Get the outputs out of order by creating one command, then updating it to upload to an assessor
        // (since such a scenario is possible to create via webapp, we need to be sure CS handles it, see CS-578
        final String commandJsonFile = curDir + "/cmdTmp.json";
        final Command tempCommand = mapper.readValue(new File(commandJsonFile), Command.class);
        Command cmd = commandService.create(tempCommand);

        final String commandJsonFileNew = curDir + "/cmd.json";
        final Command tempCommandNew = mapper.readValue(new File(commandJsonFileNew), Command.class)
                .toBuilder().id(cmd.id()).build();
        cmd = commandService.update(tempCommandNew);
        CommandWrapper wrapper = cmd.xnatCommandWrappers().get(0);

        final String sessionJsonFile = Paths.get(dir, "/session.json").toString();
        final Session session = mapper.readValue(new File(sessionJsonFile), Session.class);
        session.setDirectory(fakeDataDir);
        final String sessionJson = mapper.writeValueAsString(session);

        final File[] assessorFiles = new File(fakeDataDir, "DATA").listFiles();
        if (assessorFiles == null) {
            throw new Exception("Test dir not set up properly");
        }
        final List<File> fakeUpload = Arrays.asList(assessorFiles);
        final File assessorXml = new File(fakeDataDir, "assessor.xml");

        final ArchivableItem mockSessionItem = mock(ArchivableItem.class);
        final XFTItem mockAssessorItem = mock(XFTItem.class);
        final ExptURI mockSesUri = mock(ExptURI.class);
        final ExptAssessorURI mockAssessorUri = mock(ExptAssessorURI.class);
        final String uri = "/archive" + session.getUri() + "/assessors/assessorNew";
        when(UriParserUtils.getArchiveUri(mockAssessorItem)).thenReturn(uri);
        when(UriParserUtils.parseURI(uri)).thenReturn(mockAssessorUri);
        when(UriParserUtils.parseURI("/archive" + session.getUri())).thenReturn(mockSesUri);
        when(mockAssessorUri.getSecurityItem()).thenReturn(mockSessionItem);
        when(mockSesUri.getSecurityItem()).thenReturn(mockSessionItem);

        ArgumentMatcher<File> matchesXml = new ArgumentMatcher<File>() {
            @Override
            public boolean matches(Object arg) {
                if (!(arg instanceof File)) {
                    return false;
                }
                File file = (File) arg;
                try {
                    return file.getName().equals(assessorXml.getName()) && FileUtils.contentEqualsIgnoreEOL(file, assessorXml, null);
                } catch (IOException e) {
                    return false;
                }
            }
        };
        ArgumentMatcher<List<File>> matchesFileList = new ArgumentMatcher<List<File>>() {
            @Override
            public boolean matches(Object arg) {
                if (!(arg instanceof List)) {
                    return false;
                }
                List<?> files = (List<?>) arg;
                for (Object f : files) {
                    if (!(f instanceof File)) {
                        return false;
                    }
                    File file = (File) f;
                    boolean matches = false;
                    for (File uploadedFile : fakeUpload) {
                        try {
                            if (file.getName().equals(uploadedFile.getName()) &&
                                    FileUtils.contentEqualsIgnoreEOL(file, uploadedFile, null)) {

                                matches = true;
                                break;
                            }
                        } catch (IOException e) {
                            // Ignore
                        }
                    }
                    if (!matches) {
                        return false;
                    }
                }
                return true;
            }
        };

        when(mockCatalogService.insertXmlObject(eq(mockUser), argThat(matchesXml), eq(true),
                eq(Collections.<String, Object>emptyMap()), any(Integer.class))).thenReturn(mockAssessorItem);
        final String label = "DATA";
        final XnatResourcecatalog mockCatalog = mock(XnatResourcecatalog.class);
        when(mockCatalog.getLabel()).thenReturn(label);
        when(mockCatalogService.insertResources(eq(mockUser), eq(uri), argThat(matchesFileList), any(Integer.class),
                eq(true), eq(true), eq(label), any(String.class), any(String.class), any(String.class)))
                .thenReturn(mockCatalog);

        final Map<String, String> runtimeValues = Maps.newHashMap();
        runtimeValues.put("session", sessionJson);

        containerService.queueResolveCommandAndLaunchContainer(null, wrapper.id(),
                0L, null, runtimeValues, mockUser, fakeWorkflow);
        final Container execution = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        containersToCleanUp.add(swarmMode ? execution.serviceId() : execution.containerId());

        TestingUtils.commitTransaction();

        log.debug("Waiting until container is done finalizing");
        await().until(TestingUtils.containerIsFinalized(containerService, execution));

        // Assert that upload doesn't fail, this means that the proper files and params were passed to our mock methods
        assertThat(fakeWorkflow.getStatus(),  not(startsWith(PersistentWorkflowUtils.FAILED)));
    }

    private void printContainerLogs(final Container container) throws IOException {
        printContainerLogs(container, "main");
    }

    private void printContainerLogs(final Container container, final String containerTypeForLogs) throws IOException {
        log.debug("Trying to print {} container logs.", containerTypeForLogs);
        if (container.logPaths().size() == 0) {
            log.debug("No logs.");
            return;
        }
        for (final String containerLogPath : container.logPaths()) {
            final String[] containerLogPathComponents = containerLogPath.split("/");
            final String containerLogName = containerLogPathComponents[containerLogPathComponents.length - 1];
            log.info("Displaying contents of {} for {} container {} {}.", containerLogName, containerTypeForLogs, container.databaseId(), container.containerId());
            final String[] logLines = TestingUtils.readFile(containerLogPath);
            for (final String logLine : logLines) {
                log.info("\t{}", logLine);
            }
        }
    }
}
