package org.nrg.containers;

import com.google.common.collect.Sets;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.json.JsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import com.jayway.jsonpath.spi.mapper.MappingProvider;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.messages.swarm.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.hibernate.NonUniqueObjectException;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.nrg.containers.api.DockerControlApi;
import org.nrg.containers.config.EventPullingIntegrationTestConfig;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.auto.Command.CommandWrapper;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.auto.ServiceTask;
import org.nrg.containers.model.container.entity.ContainerEntity;
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
import org.nrg.xnat.helpers.uri.UriParserUtils;
import org.nrg.xnat.services.XnatAppInfo;
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
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeThat;
import static org.mockito.Matchers.any;
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
public class SwarmRestartIntegrationTest {
    private boolean swarmMode = true;

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
    @Autowired private XnatAppInfo mockAppInfo;

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
        doReturn(fakeWorkflow).when(PersistentWorkflowUtils.class, "getOrCreateWorkflowData", eq(FakeWorkflow.eventId),
                eq(mockUser), any(XFTItem.class), any(EventDetails.class));

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
                swarmMode, null, null, null, false, null, null));

        CLIENT = controlApi.getClient();
        CLIENT.pull("busybox:latest");

        assumeThat(TestingUtils.canConnectToDocker(CLIENT), is(true));
        assumeThat(SystemUtils.IS_OS_WINDOWS_7, is(false));

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

        CLIENT.close();
    }

    @Test
    @DirtiesContext
    public void testRestartShutdown() throws Exception {
        containerService.queueResolveCommandAndLaunchContainer(null, sleeperWrapper.id(), 0L,
                null, Collections.<String, String>emptyMap(), mockUser, fakeWorkflow);
        final Container service = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        String serviceId = service.serviceId();
        containersToCleanUp.add(serviceId);

        TestingUtils.commitTransaction();

        log.debug("Waiting until task has started");
        await().until(TestingUtils.getServiceNode(CLIENT, service), is(notNullValue()));

        // Restart
        log.debug("Kill node on which service is running to cause a restart");
        String nodeId = TestingUtils.getServiceNode(CLIENT, service).call();
        NodeInfo nodeInfo = CLIENT.inspectNode(nodeId);
        ManagerStatus managerStatus = nodeInfo.managerStatus();
        Boolean isManager;
        if (managerStatus != null && (isManager = managerStatus.leader()) != null && isManager) {
            NodeSpec nodeSpec = NodeSpec.builder(nodeInfo.spec()).availability("drain").build();
            // drain the manager
            CLIENT.updateNode(nodeId, nodeInfo.version().index(), nodeSpec);
            Thread.sleep(1000L); // Sleep long enough for status updater to run
            // readd manager
            nodeInfo = CLIENT.inspectNode(nodeId);
            nodeSpec = NodeSpec.builder(nodeInfo.spec()).availability("active").build();
            CLIENT.updateNode(nodeId, nodeInfo.version().index(), nodeSpec);
        } else {
            // delete the node
            CLIENT.deleteNode(nodeId, true);
            Thread.sleep(500L); // Sleep long enough for status updater to run
        }

        // ensure that container restarted & status updates, etc
        final Container restartedService = containerService.get(service.databaseId());
        containersToCleanUp.add(restartedService.serviceId());
        assertThat(restartedService.countRestarts(), is(1));
        log.debug("Waiting until task has restarted");
        await().until(TestingUtils.serviceIsRunning(CLIENT, restartedService)); //Running again = success!
    }

    @Test
    @DirtiesContext
    public void testRestartClearedTask() throws Exception {
        containerService.queueResolveCommandAndLaunchContainer(null, sleeperWrapper.id(), 0L,
                null, Collections.<String, String>emptyMap(), mockUser, fakeWorkflow);
        final Container service = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        String serviceId = service.serviceId();
        containersToCleanUp.add(serviceId);

        TestingUtils.commitTransaction();

        log.debug("Waiting until task has started");
        await().until(TestingUtils.serviceIsRunning(CLIENT, service));

        // Restart
        log.debug("Removing service to throw a restart event");
        CLIENT.removeService(serviceId);
        Thread.sleep(500L); // Sleep long enough for status updater to run

        // ensure that container restarted & status updates, etc
        final Container restartedContainer = containerService.get(service.databaseId());
        containersToCleanUp.add(restartedContainer.serviceId());
        assertThat(restartedContainer.countRestarts(), is(1));
        await().atMost(30L, TimeUnit.SECONDS).until(TestingUtils.serviceIsRunning(CLIENT, restartedContainer)); //Running again = success!
    }

    @Test
    @DirtiesContext
    public void testRestartClearedBeforeRunTask() throws Exception {
        containerService.queueResolveCommandAndLaunchContainer(null, sleeperWrapper.id(), 0L,
                null, Collections.<String, String>emptyMap(), mockUser, fakeWorkflow);
        final Container service = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        containersToCleanUp.add(service.serviceId());
        TestingUtils.commitTransaction();

        // Restart
        log.debug("Removing service before it starts running to throw a restart event");
        CLIENT.removeService(service.serviceId());
        Thread.sleep(500L); // Sleep long enough for status updater to run

        // ensure that container restarted & status updates, etc
        final Container restartedContainer = containerService.get(service.databaseId());
        containersToCleanUp.add(restartedContainer.serviceId());
        assertThat(restartedContainer.countRestarts(), is(1));
        await().until(TestingUtils.serviceIsRunning(CLIENT, restartedContainer)); //Running = success!
    }


    @Test
    @DirtiesContext
    public void testRestartFailure() throws Exception {
        containerService.queueResolveCommandAndLaunchContainer(null, sleeperWrapper.id(), 0L,
                null, Collections.<String, String>emptyMap(), mockUser, fakeWorkflow);
        Container service = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);

        // Restart
        int i = 1;
        while (true) {
            String serviceId = service.serviceId();
            containersToCleanUp.add(serviceId);

            TestingUtils.commitTransaction();

            log.debug("Waiting until task has started");
            await().until(TestingUtils.serviceIsRunning(CLIENT, service));

            log.debug("Removing service to throw a restart event");
            CLIENT.removeService(serviceId);
            Thread.sleep(1000L); // Sleep long enough for status updater to run

            // ensure that container restarted & status updates, etc
            service = containerService.get(service.databaseId());
            if (i == 6) {
                containersToCleanUp.add(service.serviceId());
                break;
            }
            assertThat(service.countRestarts(), is(i++));
        }

        // ensure that container failed
        PersistentWorkflowI wrk = WorkflowUtils.getUniqueWorkflow(mockUser, service.workflowId());
        assertThat(wrk.getStatus(), is(PersistentWorkflowUtils.FAILED + " (Swarm)"));
        assertThat(wrk.getDetails().contains(ServiceTask.swarmNodeErrMsg), is(true));
    }

    @Test
    @DirtiesContext
    public void testNoRestartOnAPIKill() throws Exception {
        containerService.consumeResolveCommandAndLaunchContainer(null, sleeperWrapper.id(), 0L,
                null, Collections.<String, String>emptyMap(), mockUser, fakeWorkflow.getWorkflowId().toString());
        final Container service = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        containersToCleanUp.add(service.serviceId());
        TestingUtils.commitTransaction();

        log.debug("Kill service as if through API");
        try {
            containerService.kill(service.serviceId(), mockUser);
        } catch (NonUniqueObjectException e) {
            log.error(e.getMessage());
        }

        String failureStatus = ContainerEntity.STANDARD_STATUS_MAP.get(ContainerEntity.KILL_STATUS);
        assertThat(fakeWorkflow.getStatus(), is(failureStatus));
        assertThat(containerService.get(service.databaseId()).status(), is(failureStatus));
        // The below doesn't work, perhaps because of the NonUniqueObjectException
        //await().until(TestingUtils.containerHasStatus(containerService, service.databaseId(), failureStatus));

        // ensure that container did NOT restart
        Thread.sleep(500L); // Sleep long enough for status updater to run
        Container updatedService = containerService.get(service.databaseId());
        assertThat(updatedService.countRestarts(), is(0));
        assertThat(updatedService.status(), is(failureStatus));
    }
}
