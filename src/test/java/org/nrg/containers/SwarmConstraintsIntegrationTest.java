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
import com.spotify.docker.client.messages.swarm.ManagerStatus;
import com.spotify.docker.client.messages.swarm.Node;
import com.spotify.docker.client.messages.swarm.NodeInfo;
import com.spotify.docker.client.messages.swarm.NodeSpec;
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
import org.mockito.Mockito;
import org.nrg.containers.api.DockerControlApi;
import org.nrg.containers.config.EventPullingIntegrationTestConfig;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.auto.Command.CommandWrapper;
import org.nrg.containers.model.command.auto.LaunchUi;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.auto.ServiceTask;
import org.nrg.containers.model.server.docker.DockerServerBase;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.model.server.docker.DockerServerEntitySwarmConstraint;
import org.nrg.containers.model.xnat.FakeWorkflow;
import org.nrg.containers.services.CommandResolutionService;
import org.nrg.containers.services.CommandService;
import org.nrg.containers.services.ContainerService;
import org.nrg.containers.services.DockerServerService;
import org.nrg.containers.services.impl.CommandResolutionServiceImpl;
import org.nrg.containers.services.impl.ContainerServiceImpl;
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
import org.nrg.xnat.helpers.uri.UriParserUtils;
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
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.util.*;
import java.util.regex.Pattern;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeThat;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.*;

@Slf4j
@RunWith(PowerMockRunner.class)
@PowerMockRunnerDelegate(SpringJUnit4ClassRunner.class)
@PrepareForTest({UriParserUtils.class, XFTManager.class, Users.class, WorkflowUtils.class, PersistentWorkflowUtils.class})
@PowerMockIgnore({"org.apache.*", "java.*", "javax.*", "org.w3c.*", "com.sun.*"})
@ContextConfiguration(classes = EventPullingIntegrationTestConfig.class)
@Transactional
public class SwarmConstraintsIntegrationTest {
    private boolean swarmMode = true;

    private String certPath;
    private String containerHost;
    private Node managerNode = null;
    private Map<String, String> managerNodeLabels = null;

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

    @Autowired private CommandService commandService;
    @Autowired private ContainerService containerService;
    @Autowired private DockerControlApi controlApi;
    @Autowired private AliasTokenService mockAliasTokenService;
    @Autowired private DockerServerService dockerServerService;
    @Autowired private SiteConfigPreferences mockSiteConfigPreferences;
    @Autowired private UserManagementServiceI mockUserManagementServiceI;
    @Autowired private PermissionsServiceI mockPermissionsServiceI;
    @Autowired private ObjectMapper mapper;

    private CommandWrapper sleeperWrapper;

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

        // Mock out the prefs bean
        // Mock the userI
        mockUser = mock(UserI.class);
        when(mockUser.getLogin()).thenReturn(FAKE_USER);

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

        // Use powermock to mock out the static method XFTManager.isInitialized()
        mockStatic(XFTManager.class);
        when(XFTManager.isInitialized()).thenReturn(true);

        // Also mock out workflow operations to return our fake workflow object
        mockStatic(WorkflowUtils.class);
        when(WorkflowUtils.getUniqueWorkflow(mockUser, fakeWorkflow.getWorkflowId().toString()))
                .thenReturn(fakeWorkflow);
        doNothing().when(WorkflowUtils.class, "save", Mockito.any(PersistentWorkflowI.class), isNull(EventMetaI.class));
        PowerMockito.spy(PersistentWorkflowUtils.class);
        doReturn(fakeWorkflow).when(PersistentWorkflowUtils.class, "getOrCreateWorkflowData", eq(FakeWorkflow.eventId),
                eq(mockUser), Mockito.any(XFTItem.class), Mockito.any(EventDetails.class));

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

        if (managerNode != null) {
            NodeInfo nodeInfo = CLIENT.inspectNode(managerNode.id());
            NodeSpec origSpec = NodeSpec.builder(managerNode.spec())
                    .labels(managerNodeLabels)
                    .build();
            CLIENT.updateNode(managerNode.id(), nodeInfo.version().index(), origSpec);
        }
        managerNode = null;
        managerNodeLabels = null;

        CLIENT.close();
    }

    private void setClient() throws Exception {
        CLIENT = controlApi.getClient();
        CLIENT.pull("busybox:latest");
        assumeThat(TestingUtils.canConnectToDocker(CLIENT), is(true));
        assumeThat(SystemUtils.IS_OS_WINDOWS_7, is(false));
    }

    @Test
    @DirtiesContext
    public void testThatServicesRunWithoutConstraints() throws Exception {
        DockerServer server = DockerServer.create(0L, "Test server", containerHost, certPath,
                swarmMode, null, null, null, false, null, null);
        dockerServerService.setServer(server);
        setClient();

        containerService.queueResolveCommandAndLaunchContainer(null, sleeperWrapper.id(), 0L,
                null, Collections.<String, String>emptyMap(), mockUser, fakeWorkflow);
        TestingUtils.commitTransaction();
        Container service = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        containersToCleanUp.add(service.serviceId());
        await().until(TestingUtils.serviceIsRunning(CLIENT, service)); //Running = success!
    }

    @Test
    @DirtiesContext
    public void testThatServicesRunWithoutConstraintsAlt() throws Exception {
        DockerServer server = DockerServer.create(0L, "Test server", containerHost, certPath,
                swarmMode, null, null, null, false, null, Collections.<DockerServerBase.DockerServerSwarmConstraint>emptyList());
        dockerServerService.setServer(server);
        setClient();

        containerService.queueResolveCommandAndLaunchContainer(null, sleeperWrapper.id(), 0L,
                null, Collections.<String, String>emptyMap(), mockUser, fakeWorkflow);
        TestingUtils.commitTransaction();
        Container service = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        containersToCleanUp.add(service.serviceId());
        await().until(TestingUtils.serviceIsRunning(CLIENT, service)); //Running = success!
    }

    @Test
    @DirtiesContext
    public void testThatServicesRunWithCorrectConstraintsAndNotOtherwise() throws Exception {
        // We need a client so we have to create a server, we'll update it shortly
        DockerServer server = DockerServer.create(0L, "Test server", containerHost, certPath,
                swarmMode, null, null, null, false, null, null);
        DockerServerBase.DockerServer curServer = dockerServerService.setServer(server);
        setClient();

        // target manager bc every swarm has one and we want to test a non-label constraint
        DockerServerBase.DockerServerSwarmConstraint constraintNotSettable = DockerServerBase.DockerServerSwarmConstraint.builder()
                .id(0L)
                .attribute("node.role")
                .comparator("==")
                .values(Collections.singletonList("manager"))
                .userSettable(false)
                .build();

        DockerServerBase.DockerServerSwarmConstraint constraintSettable = DockerServerBase.DockerServerSwarmConstraint.builder()
                .id(0L)
                .attribute("node.labels.cstesttype")
                .comparator("==")
                .values(Arrays.asList("Fun","Boring"))
                .userSettable(true)
                .build();

        List<DockerServerBase.DockerServerSwarmConstraint> constraints = Arrays.asList(constraintNotSettable, constraintSettable);

        // target manager bc every swarm has one, some test ones may not have workers
        List<Node> nodes = CLIENT.listNodes(Node.Criteria.builder().nodeRole("manager").build());
        assertThat(nodes.size(), greaterThan(0));
        managerNode = nodes.get(0);
        managerNodeLabels = managerNode.spec().labels();

        if (nodes.size() > 1) {
            // we have to add a new criterion to isolate this particular node
            DockerServerBase.DockerServerSwarmConstraint addlConstr = DockerServerBase.DockerServerSwarmConstraint.builder()
                    .id(0L)
                    .attribute("node.labels.addllabeltest")
                    .comparator("==")
                    .values(Collections.singletonList("iamatester"))
                    .userSettable(false)
                    .build();
            constraints.add(addlConstr);

            NodeSpec addlSpec = NodeSpec.builder(managerNode.spec())
                    .addLabel(addlConstr.attribute().replace("node.labels.", ""),
                            addlConstr.values().get(0))
                    .build();

            // Update single manager node so we can isolate it
            CLIENT.updateNode(managerNode.id(), managerNode.version().index(), addlSpec);
        }

        dockerServerService.update(curServer.toBuilder().swarmConstraints(constraints).build());
        TestingUtils.commitTransaction();

        Map<String, String> userInputs = new HashMap<>();
        LaunchUi.LaunchUiServerConstraintSelected selConstr = LaunchUi.LaunchUiServerConstraintSelected.builder()
                .attribute(constraintSettable.attribute())
                .value(constraintSettable.values().get(0))
                .build();
        userInputs.put(CommandResolutionServiceImpl.swarmConstraintsTag,
                mapper.writeValueAsString(Collections.singletonList(selConstr)));

        NodeSpec runSpec = NodeSpec.builder(managerNode.spec())
                .addLabel(selConstr.attribute().replace("node.labels.", ""),
                        selConstr.value())
                .build();

        // Update manager node to match constraints
        CLIENT.updateNode(managerNode.id(), managerNode.version().index(), runSpec);

        containerService.queueResolveCommandAndLaunchContainer(null, sleeperWrapper.id(), 0L,
                null, userInputs, mockUser, fakeWorkflow);
        TestingUtils.commitTransaction();
        Container service = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        containersToCleanUp.add(service.serviceId());
        await().until(TestingUtils.serviceIsRunning(CLIENT, service)); //Running = success!

        // Now update it so that it fails
        NodeInfo nodeInfo = CLIENT.inspectNode(managerNode.id());
        NodeSpec noRunSpec = NodeSpec.builder(managerNode.spec())
                .addLabel(selConstr.attribute().replace("node.labels.", ""),
                        "NOT" + selConstr.value())
                .build();
        CLIENT.updateNode(managerNode.id(), nodeInfo.version().index(), noRunSpec);

        containerService.queueResolveCommandAndLaunchContainer(null, sleeperWrapper.id(), 0L,
                null, userInputs, mockUser, fakeWorkflow);
        TestingUtils.commitTransaction();
        Container service2 = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        containersToCleanUp.add(service2.serviceId());
        Thread.sleep(11000L); // > 10s since that seems to be enough for a service to get running
        assertThat(TestingUtils.serviceIsRunning(CLIENT, service2, true).call(), is(false));
        assertThat(containerService.get(service2.serviceId()).status(), is(ContainerServiceImpl.CREATED));
    }

    @Test
    @DirtiesContext
    public void testThatStandaloneContainersRunRegardlessOfConstraints() throws Exception {
        // target manager bc every swarm has one and we want to test a non-label constraint
        DockerServerBase.DockerServerSwarmConstraint constraintNotSettable = DockerServerBase.DockerServerSwarmConstraint.builder()
                .id(0L)
                .attribute("node.role")
                .comparator("==")
                .values(Collections.singletonList("manager"))
                .userSettable(false)
                .build();

        DockerServerBase.DockerServerSwarmConstraint constraintSettable = DockerServerBase.DockerServerSwarmConstraint.builder()
                .id(0L)
                .attribute("node.labels.type")
                .comparator("==")
                .values(Arrays.asList("Fun","Boring"))
                .userSettable(true)
                .build();

        List<DockerServerBase.DockerServerSwarmConstraint> constraints = Arrays.asList(constraintNotSettable, constraintSettable);

        DockerServer server = DockerServer.create(0L, "Test server", containerHost, certPath,
                false, null, null, null, false, null, constraints);
        dockerServerService.setServer(server);
        setClient();

        Map<String, String> userInputs = new HashMap<>();
        LaunchUi.LaunchUiServerConstraintSelected selConstr = LaunchUi.LaunchUiServerConstraintSelected.builder()
                .attribute(constraintSettable.attribute())
                .value(constraintSettable.values().get(0))
                .build();
        userInputs.put(CommandResolutionServiceImpl.swarmConstraintsTag,
                mapper.writeValueAsString(Collections.singletonList(selConstr)));

        containerService.queueResolveCommandAndLaunchContainer(null, sleeperWrapper.id(), 0L,
                null, userInputs, mockUser, fakeWorkflow);
        TestingUtils.commitTransaction();
        Container container = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        containersToCleanUp.add(container.containerId());
        await().until(TestingUtils.containerIsRunning(CLIENT, false, container)); //Running = success!
    }

}
