package org.nrg.containers;

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
import org.mandas.docker.client.DockerClient;
import org.mandas.docker.client.messages.swarm.ManagerStatus;
import org.mandas.docker.client.messages.swarm.NodeInfo;
import org.mandas.docker.client.messages.swarm.NodeSpec;
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
        TestingUtils.pullBusyBox(controlApi.getDockerClient());

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
    public void cleanup() {
        fakeWorkflow = new FakeWorkflow();
        for (final String containerToCleanUp : containersToCleanUp) {
            try {
                if (swarmMode) {
                    controlApi.getDockerClient().removeService(containerToCleanUp);
                } else {
                    controlApi.getDockerClient().removeContainer(containerToCleanUp, DockerClient.RemoveContainerParam.forceKill());
                }
            } catch (Exception e) {
                // do nothing
            }
        }
        containersToCleanUp.clear();

        for (final String imageToCleanUp : imagesToCleanUp) {
            try {
                controlApi.getDockerClient().removeImage(imageToCleanUp, true, false);
            } catch (Exception e) {
                // do nothing
            }
        }
        imagesToCleanUp.clear();
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
        await().until(TestingUtils.getServiceNode(controlApi.getDockerClient(), service), is(notNullValue()));

        // Restart
        log.debug("Kill node on which service is running to cause a restart");
        String nodeId = TestingUtils.getServiceNode(controlApi.getDockerClient(), service).call();
        NodeInfo nodeInfo = controlApi.getDockerClient().inspectNode(nodeId);
        ManagerStatus managerStatus = nodeInfo.managerStatus();
        Boolean isManager;
        if (managerStatus != null && (isManager = managerStatus.leader()) != null && isManager) {
            NodeSpec nodeSpec = NodeSpec.builder(nodeInfo.spec()).availability("drain").build();
            // drain the manager
            controlApi.getDockerClient().updateNode(nodeId, nodeInfo.version().index(), nodeSpec);
            Thread.sleep(1000L); // Sleep long enough for status updater to run
            // readd manager
            nodeInfo = controlApi.getDockerClient().inspectNode(nodeId);
            nodeSpec = NodeSpec.builder(nodeInfo.spec()).availability("active").build();
            controlApi.getDockerClient().updateNode(nodeId, nodeInfo.version().index(), nodeSpec);
        } else {
            // delete the node
            controlApi.getDockerClient().deleteNode(nodeId, true);
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
        controlApi.getDockerClient().removeService(serviceId);

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
                        Long replicas = controlApi.getDockerClient().inspectService(service.serviceId()).spec().mode().replicated().replicas();
                        log.debug("Service {} replicas {}", service.serviceId(), replicas);
                        return replicas;
                    } catch (Exception e) {
                        return 0;
                    }
                }, is(equalTo(1L)));

        // Restart
        log.debug("Removing service {} before it starts running to throw a restart event", service.serviceId());
        controlApi.getDockerClient().removeService(service.serviceId());

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
            controlApi.getDockerClient().removeService(serviceId);
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
