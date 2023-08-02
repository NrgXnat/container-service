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
import org.mandas.docker.client.DockerClient;
import org.mandas.docker.client.messages.swarm.Node;
import org.mandas.docker.client.messages.swarm.NodeInfo;
import org.mandas.docker.client.messages.swarm.NodeSpec;
import org.mockito.Mockito;
import org.nrg.containers.api.DockerControlApi;
import org.nrg.containers.config.EventPullingIntegrationTestConfig;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.auto.Command.CommandWrapper;
import org.nrg.containers.model.command.auto.LaunchUi;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.server.docker.Backend;
import org.nrg.containers.model.server.docker.DockerServerBase;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.model.xnat.FakeWorkflow;
import org.nrg.containers.model.xnat.Session;
import org.nrg.containers.model.xnat.XnatModelObject;
import org.nrg.containers.services.CommandService;
import org.nrg.containers.services.ContainerService;
import org.nrg.containers.services.DockerServerService;
import org.nrg.containers.services.impl.CommandResolutionServiceImpl;
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
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.either;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.nrg.containers.model.command.entity.CommandType.DOCKER_SETUP;
import static org.nrg.containers.model.command.entity.CommandType.DOCKER_WRAPUP;
import static org.powermock.api.mockito.PowerMockito.doNothing;
import static org.powermock.api.mockito.PowerMockito.doReturn;
import static org.powermock.api.mockito.PowerMockito.mockStatic;

@Slf4j
@RunWith(PowerMockRunner.class)
@PowerMockRunnerDelegate(SpringJUnit4ClassRunner.class)
@PrepareForTest({UriParserUtils.class, XFTManager.class, Users.class, WorkflowUtils.class,
        PersistentWorkflowUtils.class, XDATServlet.class, Session.class, ContainerServicePermissionUtils.class})
@PowerMockIgnore({"org.apache.*", "java.*", "javax.*", "org.w3c.*", "com.sun.*"})
@ContextConfiguration(classes = EventPullingIntegrationTestConfig.class)
@Transactional
public class SwarmConstraintsIntegrationTest {
    private String certPath;
    private String containerHost;

    private UserI mockUser;
    private String buildDir;
    private String archiveDir;

    private final String FAKE_USER = "mockUser";
    private final String FAKE_ALIAS = "alias";
    private final String FAKE_SECRET = "secret";
    private final String FAKE_HOST = "mock://url";
    private FakeWorkflow fakeWorkflow = new FakeWorkflow();

    private final static String LABEL_NAME = "cstesttype";
    private final static String CONSTRAINT_LABEL_ATTRIBUTE = "node.labels." + LABEL_NAME;
    private final static String CORRECT_LABEL_VALUE = "Fun";
    private final static String INCORRECT_LABEL_VALUE = "NOT " + CORRECT_LABEL_VALUE;
    private final static String RED_HERRING_LABEL_VALUE = "Boring";

    private final static DockerServerBase.DockerServerSwarmConstraint IS_MANAGER_NODE_UNSETTABLE_CONSTRAINT =
            DockerServerBase.DockerServerSwarmConstraint.builder()
                    .id(0L)
                    .attribute("node.role")
                    .comparator("==")
                    .values(Collections.singletonList("manager"))
                    .userSettable(false)
                    .build();
    private final static DockerServerBase.DockerServerSwarmConstraint SETTABLE_CONSTRAINT =
            DockerServerBase.DockerServerSwarmConstraint.builder()
                    .id(0L)
                    .attribute(CONSTRAINT_LABEL_ATTRIBUTE)
                    .comparator("==")
                    .values(Arrays.asList(CORRECT_LABEL_VALUE, INCORRECT_LABEL_VALUE, RED_HERRING_LABEL_VALUE))
                    .userSettable(true)
                    .build();

    private final static List<DockerServerBase.DockerServerSwarmConstraint> DEFAULT_SWARM_SERVER_CONSTRAINTS =
            Arrays.asList(IS_MANAGER_NODE_UNSETTABLE_CONSTRAINT, SETTABLE_CONSTRAINT);
    private final static Map<String, String> DEFAULT_MANAGER_NODE_LABELS = new HashMap<>();
    static {
        DEFAULT_MANAGER_NODE_LABELS.put(LABEL_NAME, CORRECT_LABEL_VALUE);
    }

    private final static LaunchUi.LaunchUiServerConstraintSelected CORRECT_SELECTED_CONSTRAINT =
            LaunchUi.LaunchUiServerConstraintSelected.builder()
                    .attribute(CONSTRAINT_LABEL_ATTRIBUTE)
                    .value(CORRECT_LABEL_VALUE)
                    .build();
    private final static LaunchUi.LaunchUiServerConstraintSelected INCORRECT_SELECTED_CONSTRAINT =
            LaunchUi.LaunchUiServerConstraintSelected.builder()
                    .attribute(CONSTRAINT_LABEL_ATTRIBUTE)
                    .value(INCORRECT_LABEL_VALUE)
                    .build();

    private final List<String> containersToCleanUp = new ArrayList<>();
    private final List<String> imagesToCleanUp = new ArrayList<>();
    private final Map<String, Map<String, String>> nodeLabelsToReset = new HashMap<>();

    private DockerServer.Builder dockerServerBuilder;
    private static DockerClient CLIENT;

    @Autowired private CommandService commandService;
    @Autowired private ContainerService containerService;
    @Autowired private DockerControlApi controlApi;
    @Autowired private AliasTokenService mockAliasTokenService;
    @Autowired private DockerServerService dockerServerService;
    @Autowired private SiteConfigPreferences mockSiteConfigPreferences;
    @Autowired private UserManagementServiceI mockUserManagementServiceI;
    @Autowired private PermissionsServiceI mockPermissionsServiceI;
    @Autowired private CatalogService mockCatalogService;
    @Autowired private ObjectMapper mapper;

    private CommandWrapper sleeperWrapper;

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
        when(mockPermissionsServiceI.canEdit(Mockito.any(UserI.class), Mockito.any(ItemI.class))).thenReturn(Boolean.TRUE);

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
        doNothing().when(WorkflowUtils.class, "save", Mockito.any(PersistentWorkflowI.class), isNull(EventMetaI.class));
        PowerMockito.spy(PersistentWorkflowUtils.class);
        doReturn(fakeWorkflow).when(PersistentWorkflowUtils.class, "getOrCreateWorkflowData", eq(FakeWorkflow.defaultEventId),
                eq(mockUser), Mockito.any(XFTItem.class), Mockito.any(EventDetails.class));

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
                eq(mockUser), any(String.class), any(XnatModelObject.class), any(Command.CommandWrapperOutput.class)
        )).thenReturn(true);

        // Setup docker server
        final String defaultHost = "unix:///var/run/docker.sock";
        final String hostEnv = System.getenv("DOCKER_HOST");
        final String certPathEnv = System.getenv("DOCKER_CERT_PATH");
        final String tlsVerify = System.getenv("DOCKER_TLS_VERIFY");

        final boolean useTls = tlsVerify != null && tlsVerify.equals("1");
        if (useTls) {
            if (StringUtils.isBlank(certPathEnv)) {
                throw new Exception("Must set DOCKER_CERT_PATH if DOCKER_TLS_VERIFY=1.");
            }
            certPath = certPathEnv;
        } else {
            certPath = "";
        }

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

        final Command sleeper = commandService.create(Command.builder()
                .name("long-running")
                .image("busybox:latest")
                .version("0")
                .commandLine("/bin/sh -c \"sleep 30\"")
                .addCommandWrapper(CommandWrapper.builder()
                        .name("placeholder")
                        .build())
                .build());
        sleeperWrapper = sleeper.xnatCommandWrappers().get(0);

        dockerServerBuilder = DockerServer.builder()
                .name("Test server")
                .host(containerHost)
                .certPath(certPath)
                .lastEventCheckTime(new Date())
                .backend(Backend.SWARM);

        // Start with no constraints
        dockerServerService.setServer(dockerServerBuilder.swarmConstraints(Collections.emptyList()).build());
        TestingUtils.commitTransaction();

        CLIENT = controlApi.getDockerClient();
        TestingUtils.skipIfCannotConnectToSwarm(CLIENT);
        assumeThat(SystemUtils.IS_OS_WINDOWS_7, is(false));
    }

    @After
    public void cleanup() throws Exception {
        fakeWorkflow = new FakeWorkflow();
        for (final String containerToCleanUp : containersToCleanUp) {
            try {
                CLIENT.removeService(containerToCleanUp);
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

        for (Map.Entry<String, Map<String, String>> entry : nodeLabelsToReset.entrySet()) {
            String nodeId = entry.getKey();
            Map<String, String> originalLabels = entry.getValue();
            NodeInfo nodeInfo = CLIENT.inspectNode(nodeId);
            NodeSpec current = nodeInfo.spec();
            NodeSpec original = NodeSpec.builder()
                    .name(current.name())
                    .role(current.role())
                    .availability(current.availability())
                    .labels(originalLabels)
                    .build();
            CLIENT.updateNode(nodeId, nodeInfo.version().index(), original);
        }
        nodeLabelsToReset.clear();

        CLIENT.close();
    }

    private List<DockerServerBase.DockerServerSwarmConstraint> setUpServerWithConstraints() throws Exception {
        // We will do two things here:
        //  1. Add labels to a manager node.
        //     (We target manager bc every swarm has one, some test ones may not have workers.)
        //  2. Add constraint values to our DockerServer instance that are capable of matching the manager node.
        // The various tests will launch containers with inputs that either do or don't match the constraints.

        // The server constraints
        final List<DockerServerBase.DockerServerSwarmConstraint> constraints = new ArrayList<>(DEFAULT_SWARM_SERVER_CONSTRAINTS);

        // Find manager
        List<Node> managerNodes = CLIENT.listNodes(Node.Criteria.builder().nodeRole("manager").build());
        assertThat(managerNodes.size(), greaterThan(0));
        Node managerNode = managerNodes.get(0);

        // Add test labels
        Map<String, String> oldLabels = managerNode.spec().labels();
        Map<String, String> labelsToAdd = new HashMap<>(DEFAULT_MANAGER_NODE_LABELS);

        if (managerNodes.size() > 1) {
            // We have multiple manager nodes
            // We only want to target one, so we need to isolate it.
            // Add a new label to the node + a criterion on our settings that matches the label
            final String uniqueLabelName = UUID.randomUUID().toString();
            final String uniqueLabelValue = UUID.randomUUID().toString();

            // Add this unique value to our swarm constraints
            constraints.add(DockerServerBase.DockerServerSwarmConstraint.builder()
                    .id(0L)
                    .attribute("node.labels." + uniqueLabelName)
                    .comparator("==")
                    .values(Collections.singletonList(uniqueLabelValue))
                    .userSettable(false)
                    .build()
            );

            // Add label to manager node so we can isolate it
            labelsToAdd.put(uniqueLabelName, uniqueLabelValue);
        }

        // Ensure we clean up node label changes
        nodeLabelsToReset.put(managerNode.id(), oldLabels);
        Map<String, String> newLabels = new HashMap<>(oldLabels);
        newLabels.putAll(labelsToAdd);

        // Update manager node to match constraints
        CLIENT.updateNode(managerNode.id(), managerNode.version().index(),
                NodeSpec.builder(managerNode.spec()).labels(newLabels).build()
        );

        // Add constraints to DockerServer settings
        DockerServer server = dockerServerBuilder
                .swarmConstraints(constraints)
                .build();

        dockerServerService.setServer(server);
        TestingUtils.commitTransaction();

        return constraints;
    }

    @Test
    @DirtiesContext
    public void testAlwaysRunIfNoConstraints() throws Exception {
        // Server currently has no constraints defined
        assertThat(dockerServerService.getServer().swarmConstraints(), is(either(empty()).or(nullValue())));

        log.debug("Launching with no constraints");
        containerService.queueResolveCommandAndLaunchContainer(null, sleeperWrapper.id(),
                0L, null, Collections.emptyMap(), mockUser, fakeWorkflow);
        TestingUtils.commitTransaction();
        Container service = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        containersToCleanUp.add(service.serviceId());

        log.debug("Waiting for service to start running...");
        await().until(TestingUtils.serviceIsRunning(CLIENT, service)); //Running = success!
        log.debug("SUCCESS: Service is running");
    }

    @Test
    @DirtiesContext
    public void testRunIfConstraintsAreSatisfied() throws Exception {
        // Set up DockerService constraints + node labels
        setUpServerWithConstraints();

        // Launch with a matching constraint
        Map<String, String> userInputs = new HashMap<>();
        userInputs.put(CommandResolutionServiceImpl.swarmConstraintsTag,
                mapper.writeValueAsString(Collections.singletonList(CORRECT_SELECTED_CONSTRAINT)));

        log.debug("Launching with satisfiable constraints");
        containerService.queueResolveCommandAndLaunchContainer(null, sleeperWrapper.id(),
                0L, null, userInputs, mockUser, fakeWorkflow);
        TestingUtils.commitTransaction();
        Container service = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        containersToCleanUp.add(service.serviceId());

        // Check that service does run
        log.debug("Waiting for service to start running...");
        await().until(TestingUtils.serviceIsRunning(CLIENT, service));
        log.debug("SUCCESS: Service is running");
    }

    @Test
    @DirtiesContext
    public void testDoNotRunIfConstraintsAreNotSatisfied() throws Exception {
        // Set up DockerService constraints + node labels
        setUpServerWithConstraints();

        // Launch with a non-matching constraint
        Map<String, String> userInputs = new HashMap<>();
        userInputs.put(CommandResolutionServiceImpl.swarmConstraintsTag,
                mapper.writeValueAsString(Collections.singletonList(INCORRECT_SELECTED_CONSTRAINT)));

        log.debug("Launching with non-satisfiable constraints");
        containerService.queueResolveCommandAndLaunchContainer(null, sleeperWrapper.id(),
                0L, null, userInputs, mockUser, fakeWorkflow);
        TestingUtils.commitTransaction();
        Container service = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        containersToCleanUp.add(service.serviceId());

        // Check that service is created
        log.debug("Waiting for service to be created...");
        await().until(() -> {
            try {
                return Objects.equals(containerService.get(service.serviceId()).status(), "pending");
            } catch (Exception e) {
                return false;
            }
        });
        // Check that service is not running
        log.debug("Service was created. Checking that service is not running");
        assertThat(TestingUtils.serviceIsRunning(CLIENT, service, true).call(), is(false));
        log.debug("SUCCESS: Service is not running");
    }

    @Test
    @DirtiesContext
    public void testConstraintsWithSetupAndWrapup() throws Exception {
        // Set up DockerService constraints + node labels
        final List<DockerServerBase.DockerServerSwarmConstraint> constraints = setUpServerWithConstraints();

        // make a list for comparison
        String[] expectedConstraints = constraints.stream().map(c -> {
            if (c.attribute().equals(CONSTRAINT_LABEL_ATTRIBUTE)) {
                return c.asStringConstraint(CORRECT_LABEL_VALUE);
            } else {
                return c.asStringConstraint();
            }
        }).toArray(String[]::new);

        // Define command and wrapper with setup + wrapup
        String cmd = "/bin/sh -c \"echo hi; exit 0\"";
        String img = "busybox:latest";
        String setup = "setup";
        String wrapup = "wrapup";
        commandService.create(Command.builder()
                .name(setup)
                .image(img)
                .version("0")
                .commandLine(cmd)
                .type(DOCKER_SETUP.getName())
                .build());

        commandService.create(Command.builder()
                .name(wrapup)
                .image(img)
                .version("0")
                .commandLine(cmd)
                .type(DOCKER_WRAPUP.getName())
                .build());

        final Command main = commandService.create(Command.builder()
                .name("main")
                .image(img)
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
                                .viaSetupCommand(img + ":" + setup)
                                .derivedFromWrapperInput("session")
                                .build())
                        .outputHandlers(Command.CommandWrapperOutput.builder()
                                .name("output-handler")
                                .commandOutputName("output")
                                .targetName("session")
                                .label("label")
                                .viaWrapupCommand(img + ":" + wrapup)
                                .build()
                        )
                        .build())
                .build());
        TestingUtils.commitTransaction();
        CommandWrapper wrapper = main.xnatCommandWrappers().get(0);

        // Launch with correct constraint
        Map<String, String> userInputs = new HashMap<>();
        userInputs.put(CommandResolutionServiceImpl.swarmConstraintsTag,
                mapper.writeValueAsString(Collections.singletonList(CORRECT_SELECTED_CONSTRAINT)));

        String uri = TestingUtils.setupSessionMock(folder, mapper, userInputs);
        TestingUtils.setupMocksForSetupWrapupWorkflow("/archive" + uri, fakeWorkflow, mockCatalogService, mockUser);

        log.debug("Launching with satisfiable constraints + setup + wrapup");
        containerService.queueResolveCommandAndLaunchContainer(null, wrapper.id(),
                0L, null, userInputs, mockUser, fakeWorkflow);
        Container service = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        TestingUtils.commitTransaction();

        log.debug("Waiting until container is finalized");
        await().atMost(90L, TimeUnit.SECONDS)
                .until(TestingUtils.containerIsFinalized(containerService, service), is(true));
        log.debug("Service was finalized");

        final long databaseId = service.databaseId();
        final Container exited = containerService.get(databaseId);
        assertThat(fakeWorkflow.getStatus(), is(PersistentWorkflowUtils.COMPLETE));

        List<Container> toCleanup = new ArrayList<>();
        toCleanup.add(exited);
        toCleanup.addAll(containerService.retrieveSetupContainersForParent(databaseId));
        toCleanup.addAll(containerService.retrieveWrapupContainersForParent(databaseId));
        containersToCleanUp.addAll(toCleanup.stream().map(Container::serviceId)
                .collect(Collectors.toList()));

        // For the main container + setup + wrapup...
        for (Container ck : toCleanup) {
            log.debug("Checking {} container {} {}", ck.subtype(), ck.databaseId(), ck.serviceId());
            // Each container got the same constraints
            assertThat(ck.swarmConstraints(), containsInAnyOrder(expectedConstraints));

            // Each exited successfully
            assertThat(ck.exitCode(), is("0"));

            // Each workflow was marked complete
            assertThat(ck.status(), is(PersistentWorkflowUtils.COMPLETE));
        }
        log.debug("SUCCESS: All constraints were as expected and containers exited successfully");
    }

    @Test
    @DirtiesContext
    public void testDockerBackendIgnoresConstraints() throws Exception {
        // Add constraints to DockerServer settings, but with Docker backend instead of Swarm
        DockerServer server = dockerServerBuilder
                .backend(Backend.DOCKER)
                .swarmConstraints(DEFAULT_SWARM_SERVER_CONSTRAINTS)
                .build();
        dockerServerService.setServer(server);
        TestingUtils.commitTransaction();

        // We do not set labels on manager node, which means constraints should be unsatisfiable

        // In addition, we pass inputs that also make constraints unsatisfiable
        Map<String, String> userInputs = new HashMap<>();
        userInputs.put(CommandResolutionServiceImpl.swarmConstraintsTag,
                mapper.writeValueAsString(Collections.singletonList(INCORRECT_SELECTED_CONSTRAINT)));

        // Launch on local docker
        log.debug("Launching on local docker with unsatisfiable constraints");
        containerService.queueResolveCommandAndLaunchContainer(null, sleeperWrapper.id(),
                0L, null, userInputs, mockUser, fakeWorkflow);
        TestingUtils.commitTransaction();
        Container container = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        containersToCleanUp.add(container.containerId());

        // Expect that constraints are ignored and container runs
        log.debug("Waiting for container to run...");
        await().until(TestingUtils.containerIsRunning(CLIENT, false, container)); //Running = success!
        log.debug("SUCCESS: Container is running");
    }
}
