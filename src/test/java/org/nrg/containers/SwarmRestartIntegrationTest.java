package org.nrg.containers;

import com.github.dockerjava.api.model.SwarmNode;
import com.github.dockerjava.api.model.SwarmNodeAvailability;
import com.github.dockerjava.api.model.SwarmNodeManagerStatus;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.SystemUtils;
import org.hibernate.NonUniqueObjectException;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.nrg.containers.api.DockerControlApi;
import org.nrg.containers.config.EventPullingIntegrationTestConfig;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.auto.Command.CommandWrapper;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.auto.ServiceTask;
import org.nrg.containers.model.container.entity.ContainerEntity;
import org.nrg.containers.model.server.docker.Backend;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.model.xnat.FakeWorkflow;
import org.nrg.containers.services.CommandService;
import org.nrg.containers.services.ContainerEntityService;
import org.nrg.containers.services.ContainerService;
import org.nrg.containers.services.DockerServerService;
import org.nrg.containers.utils.BackendConfig;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;
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
@PowerMockRunnerDelegate(SpringJUnit4ClassRunner.class)
@PrepareForTest({UriParserUtils.class, XFTManager.class, Users.class, WorkflowUtils.class,
        PersistentWorkflowUtils.class, XDATServlet.class})
@PowerMockIgnore({"org.apache.*", "java.*", "javax.*", "org.w3c.*", "com.sun.*"})
@ContextConfiguration(classes = EventPullingIntegrationTestConfig.class)
@Transactional
public class SwarmRestartIntegrationTest {
    private Backend backend = Backend.SWARM;
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

    @Autowired private CommandService commandService;
    @Autowired private ContainerService containerService;
    @Autowired private DockerControlApi controlApi;
    @Autowired private AliasTokenService mockAliasTokenService;
    @Autowired private DockerServerService dockerServerService;
    @Autowired private SiteConfigPreferences mockSiteConfigPreferences;
    @Autowired private UserManagementServiceI mockUserManagementServiceI;
    @Autowired private PermissionsServiceI mockPermissionsServiceI;
    @Autowired private ContainerEntityService containerEntityService;
    @Autowired private XnatAppInfo mockAppInfo;

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

    @BeforeClass
    public static void setupClass() {
        TestingUtils.skipIfNotRunningIntegrationTests();
    }

    @Before
    public void setup() throws Exception {
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

        // Setup docker server
        final BackendConfig backendConfig = TestingUtils.getBackendConfig();
        dockerServerService.setServer(DockerServer.builder()
                .name("Test server")
                .host(backendConfig.getContainerHost())
                .certPath(backendConfig.getCertPath())
                .backend(backend)
                .lastEventCheckTime(new Date())
                .build());

        TestingUtils.skipIfCannotConnectToSwarm(controlApi.getDockerClient());
        assumeThat(SystemUtils.IS_OS_WINDOWS_7, is(false));
        controlApi.pullImage(BUSYBOX);

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
        TestingUtils.commitTransaction();
    }

    @After
    public void cleanup() throws Exception {
        fakeWorkflow = new FakeWorkflow();
        if (swarmMode) {
            TestingUtils.cleanSwarmServices(controlApi.getDockerClient(), containersToCleanUp);
        } else {
            TestingUtils.cleanDockerContainers(controlApi.getDockerClient(), containersToCleanUp);
        }

        TestingUtils.cleanDockerImages(controlApi.getDockerClient(), imagesToCleanUp);
    }

    @Test
    @DirtiesContext
    public void testRestartShutdown() throws Exception {
        containerService.queueResolveCommandAndLaunchContainer(null, sleeperWrapper.id(),
                0L, null, Collections.emptyMap(), mockUser, fakeWorkflow);
        TestingUtils.commitTransaction();
        final Container service = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        String serviceId = service.serviceId();
        containersToCleanUp.add(serviceId);

        log.debug("Waiting until task has started");
        final String[] nodeIdHolder = new String[1];
        await().until(() -> {
            try {
                nodeIdHolder[0] = TestingUtils.getServiceNode(controlApi.getDockerClient(), service);
                return nodeIdHolder[0] != null;
            } catch (Exception ignored) {
                return false;
            }
        });
        final String nodeId = nodeIdHolder[0];

        // Restart
        log.debug("Kill node on which service is running to cause a restart");
        SwarmNode nodeInfo = controlApi.getDockerClient()
                .listSwarmNodesCmd()
                .withIdFilter(Collections.singletonList(nodeId))
                .exec()
                .stream()
                .findAny()
                .orElseThrow(() -> new Exception("Node not found"));
        SwarmNodeManagerStatus managerStatus = nodeInfo.getManagerStatus();
        if (managerStatus != null && managerStatus.isLeader()) {
            // drain the manager
            controlApi.getDockerClient().updateSwarmNodeCmd()
                    .withSwarmNodeId(nodeId)
                    .withVersion(nodeInfo.getVersion().getIndex())
                    .withSwarmNodeSpec(nodeInfo.getSpec().withAvailability(SwarmNodeAvailability.DRAIN))
                    .exec();
            Thread.sleep(1000L); // Sleep long enough for status updater to run
            // readd manager
            nodeInfo = controlApi.getDockerClient()
                    .listSwarmNodesCmd()
                    .withIdFilter(Collections.singletonList(nodeId))
                    .exec()
                    .stream()
                    .findAny()
                    .orElseThrow(() -> new Exception("Node not found"));
            controlApi.getDockerClient().updateSwarmNodeCmd()
                    .withSwarmNodeId(nodeId)
                    .withVersion(nodeInfo.getVersion().getIndex())
                    .withSwarmNodeSpec(nodeInfo.getSpec().withAvailability(SwarmNodeAvailability.ACTIVE))
                    .exec();
        } else {
            // delete the node
            controlApi.getDockerClient().removeSwarmNodeCmd(nodeId).withForce(true).exec();
            Thread.sleep(500L); // Sleep long enough for status updater to run
        }

        // ensure that container restarted & status updates, etc
        TestingUtils.commitTransaction();
        final Container restartedService = containerService.get(service.databaseId());
        containersToCleanUp.add(restartedService.serviceId());
        assertThat(restartedService.countRestarts(), is(1));
        log.debug("Waiting until task has restarted");
        await().until(TestingUtils.serviceIsRunning(controlApi.getDockerClient(), restartedService)); //Running again = success!
    }

    @Test
    @DirtiesContext
    @Ignore("Test does not reliably detect when service is running or not")
    public void testRestartClearedTask() throws Exception {
        containerService.queueResolveCommandAndLaunchContainer(null, sleeperWrapper.id(),
                0L, null, Collections.emptyMap(), mockUser, fakeWorkflow);
        TestingUtils.commitTransaction();
        final Container service = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        String serviceId = service.serviceId();
        containersToCleanUp.add(serviceId);

        log.debug("Waiting until task has started");
        await().until(TestingUtils.serviceIsRunning(controlApi.getDockerClient(), service));

        // Restart
        log.debug("Removing service to throw a restart event");
        controlApi.getDockerClient().removeServiceCmd(serviceId).exec();

        // Ensure the restart request has gone through
        await().until(TestingUtils.serviceHasTaskId(containerService, service.databaseId()), is(false));
        // Ensure the restart request has been consumed and the new task id has been picked up
        await().until(TestingUtils.serviceHasTaskId(containerService, service.databaseId()));

        // ensure that container restarted & status updates, etc
        TestingUtils.commitTransaction();
        final Container restartedContainer = containerService.get(service.databaseId());
        containersToCleanUp.add(restartedContainer.serviceId());
        assertThat(restartedContainer.countRestarts(), is(1));
        await().until(TestingUtils.serviceIsRunning(controlApi.getDockerClient(), restartedContainer)); //Running again = success!
    }

    @Test
    @DirtiesContext
    public void testRestartClearedBeforeRunTask() throws Exception {
        containerService.queueResolveCommandAndLaunchContainer(null, sleeperWrapper.id(),
                0L, null, Collections.emptyMap(), mockUser, fakeWorkflow);
        TestingUtils.commitTransaction();
        final Container service = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        containersToCleanUp.add(service.serviceId());

        // Wait until we have "start"ed the service by setting its replicas to 1
        log.debug("Waiting for container service to start container {} for service {}...",
                service.databaseId(), service.serviceId());
        await().atMost(1, TimeUnit.SECONDS)
                .pollInterval(50, TimeUnit.MILLISECONDS)
                .until(() -> {
                    try {
                        long replicas = controlApi.getDockerClient()
                                .inspectServiceCmd(service.serviceId())
                                .exec()
                                .getSpec()
                                .getMode()
                                .getReplicated()
                                .getReplicas();
                        log.debug("Service {} replicas {}", service.serviceId(), replicas);
                        return replicas;
                    } catch (Exception e) {
                        return 0;
                    }
                }, is(equalTo(1L)));

        // Restart
        log.debug("Removing service {} before it starts running to throw a restart event", service.serviceId());
        controlApi.getDockerClient().removeServiceCmd(service.serviceId()).exec();

        // ensure that container restarted & status updates, etc
        log.debug("Waiting for container service to restart container {}...", service.databaseId());
        await().atMost(2, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .until(() -> {
                    try {
                        return containerService.get(service.databaseId()).countRestarts();
                    } catch (Exception e) {
                        return 0;
                    }
                }, is(1));
        log.debug("Waiting for restarted container {} to get a new service id...", service.databaseId());
        await().atMost(500, TimeUnit.MILLISECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .until(() -> {
                    try {
                        return containerService.get(service.databaseId()).serviceId();
                    } catch (Exception e) {
                        return null;
                    }
                }, is(notNullValue()));
        TestingUtils.commitTransaction();
        final Container restartedContainer = containerService.get(service.databaseId());
        containersToCleanUp.add(restartedContainer.serviceId());
        assertThat(restartedContainer.countRestarts(), is(1));

        log.debug("Waiting until restarted container {} service {} is running...",
                restartedContainer.databaseId(), restartedContainer.serviceId());
        await().until(TestingUtils.serviceIsRunning(controlApi.getDockerClient(), restartedContainer)); //Running = success!
    }


    @Test
    @DirtiesContext
    public void testRestartFailure() throws Exception {
        containerService.queueResolveCommandAndLaunchContainer(null, sleeperWrapper.id(),
                0L, null, Collections.emptyMap(), mockUser, fakeWorkflow);
        TestingUtils.commitTransaction();
        Container service = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);

        // Restart
        int i = 1;
        while (true) {
            String serviceId = service.serviceId();
            containersToCleanUp.add(serviceId);

            TestingUtils.commitTransaction();

            log.debug("Waiting until task has started");
            await().until(TestingUtils.serviceIsRunning(controlApi.getDockerClient(), service));

            log.debug("Removing service to throw a restart event");
            controlApi.getDockerClient().removeServiceCmd(serviceId).exec();
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
    @Ignore("Test has some kind of error with a NonUniqueObjectException that I can't figure out")
    public void testNoRestartOnAPIKill() throws Exception {
        log.debug("Queueing command for launch");
        containerService.queueResolveCommandAndLaunchContainer(null, sleeperWrapper.id(), 0L,
                null, Collections.emptyMap(), mockUser, fakeWorkflow);
        TestingUtils.commitTransaction();
        final Container service = TestingUtils.getContainerFromWorkflow(containerService, fakeWorkflow);
        containersToCleanUp.add(service.serviceId());

        log.debug("Kill service as if through API");
        try {
            containerService.kill(service.serviceId(), mockUser);
        } catch (NonUniqueObjectException e) {
            log.error(e.getMessage());
        }

        ContainerEntity entity = containerEntityService.get(service.databaseId());
        String failureStatus = entity.mapStatus(ContainerEntity.KILL_STATUS);
        assertThat(entity.getStatus(), is(failureStatus));
        assertThat(fakeWorkflow.getStatus(), is(failureStatus));
        // The below doesn't work because of the NonUniqueObjectException...
        // does this mean the whole test is pointless? Probably :(
        //await().until(TestingUtils.containerHasStatus(containerService, service.databaseId(), failureStatus));

        // ensure that container did NOT restart
        Thread.sleep(500L); // Sleep long enough for status updater to run
        Container updatedService = containerService.get(service.databaseId());
        assertThat(updatedService.countRestarts(), is(0));
        assertThat(updatedService.status(), is(failureStatus));
    }
}
