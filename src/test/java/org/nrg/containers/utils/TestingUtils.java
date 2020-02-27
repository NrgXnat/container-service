package org.nrg.containers.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Maps;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.exceptions.ContainerNotFoundException;
import com.spotify.docker.client.exceptions.DockerException;
import com.spotify.docker.client.messages.ContainerInfo;
import com.spotify.docker.client.messages.swarm.Service;
import com.spotify.docker.client.messages.swarm.Task;
import com.spotify.docker.client.messages.swarm.TaskStatus;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.hamcrest.CustomTypeSafeMatcher;
import org.hamcrest.Matchers;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentMatcher;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.auto.ServiceTask;
import org.nrg.containers.model.xnat.FakeWorkflow;
import org.nrg.containers.model.xnat.Resource;
import org.nrg.containers.model.xnat.Session;
import org.nrg.containers.services.ContainerService;
import org.nrg.containers.services.impl.ContainerServiceImpl;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xft.XFTItem;
import org.nrg.xft.event.EventDetails;
import org.nrg.xft.event.persist.PersistentWorkflowI;
import org.nrg.xft.event.persist.PersistentWorkflowUtils;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.archive.ResourceData;
import org.nrg.xnat.helpers.uri.UriParserUtils;
import org.nrg.xnat.helpers.uri.archive.impl.ExptURI;
import org.nrg.xnat.services.archive.CatalogService;
import org.nrg.xnat.turbine.utils.ArchivableItem;
import org.nrg.xnat.utils.WorkflowUtils;
import org.powermock.api.mockito.PowerMockito;
import org.springframework.test.context.transaction.TestTransaction;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TestingUtils {
    public static void commitTransaction() {
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();
    }

    public static boolean canConnectToDocker(DockerClient client) throws InterruptedException, DockerException{
        return client.ping().equals("OK");
    }

    @SuppressWarnings("unchecked")
    public static ArgumentMatcher<Map<String, String>> isMapWithEntry(final String key, final String value) {
        return new ArgumentMatcher<Map<String, String>>() {
            @Override
            public boolean matches(final Object argument) {
                if (argument == null || !Map.class.isAssignableFrom(argument.getClass())) {
                    return false;
                }
                final Map<String, String> argumentMap = Maps.newHashMap();
                try {
                    argumentMap.putAll((Map)argument);
                } catch (ClassCastException e) {
                    return false;
                }

                for (final Map.Entry<String, String> entry : argumentMap.entrySet()) {
                    if (entry.getKey().equals(key) && entry.getValue().equals(value)) {
                        return true;
                    }
                }
                return false;
            }
        };
    }



    @SuppressWarnings("deprecation")
    public static String[] readFile(final String outputFilePath) throws IOException {
        return readFile(new File(outputFilePath));
    }

    @SuppressWarnings("deprecation")
    public static String[] readFile(final File file) throws IOException {
        if (!file.canRead()) {
            throw new IOException("Cannot read output file " + file.getAbsolutePath());
        }
        return FileUtils.readFileToString(file).split("\\n");
    }

    public static CustomTypeSafeMatcher<File> pathEndsWith(final String filePathEnd) {
        final String description = "Match a file path if it ends with " + filePathEnd;
        return new CustomTypeSafeMatcher<File>(description) {
            @Override
            protected boolean matchesSafely(final File item) {
                return item == null && filePathEnd == null ||
                        item != null && item.getAbsolutePath().endsWith(filePathEnd);
            }
        };
    }

    public static Callable<Boolean> containerHasStarted(final DockerClient CLIENT, final boolean swarmMode,
                                                        final Container container) {
        return new Callable<Boolean>() {
            public Boolean call() throws Exception {
                try {
                    if (swarmMode) {
                        String id = container.serviceId();
                        if (id == null) {
                            return false;
                        }
                        final Service serviceResponse = CLIENT.inspectService(id);
                        final List<Task> tasks = CLIENT.listTasks(Task.Criteria.builder().serviceName(serviceResponse.spec().name()).build());
                        if (tasks.size() == 0) {
                            return false;
                        }
                        for (final Task task : tasks) {
                            final ServiceTask serviceTask = ServiceTask.create(task, id);
                            if (!serviceTask.hasNotStarted()) {
                                // if it's not a "before running" status (aka running or some exit status)
                                return true;
                            }
                        }
                        return false;
                    } else {
                        String id = container.containerId();
                        if (id == null) {
                            return false;
                        }
                        final ContainerInfo containerInfo = CLIENT.inspectContainer(id);
                        String status = containerInfo.state().status();
                        return !"CREATED".equals(status);
                    }
                } catch (ContainerNotFoundException ignored) {
                    // Ignore exception. If container is not found, it is not running.
                    return false;
                }
            }
        };
    }

    public static Callable<Boolean> containerIsRunning(final DockerClient CLIENT, final boolean swarmMode,
                                                       final Container container) {
        return new Callable<Boolean>() {
            public Boolean call() throws Exception {
                try {
                    if (swarmMode) {
                        final Service serviceResponse = CLIENT.inspectService(container.serviceId());
                        final List<Task> tasks = CLIENT.listTasks(Task.Criteria.builder().serviceName(serviceResponse.spec().name()).build());
                        for (final Task task : tasks) {
                            final ServiceTask serviceTask = ServiceTask.create(task, container.serviceId());
                            if (serviceTask.isExitStatus()) {
                                return false;
                            }
                        }
                        return true; // consider it "running" until it's an exit status
                    } else {
                        final ContainerInfo containerInfo = CLIENT.inspectContainer(container.containerId());
                        return containerInfo.state().running();
                    }
                } catch (ContainerNotFoundException ignored) {
                    // Ignore exception. If container is not found, it is not running.
                    return false;
                }
            }
        };
    }

    public static Callable<Boolean> serviceIsRunning(final DockerClient CLIENT, final Container container) {
        return serviceIsRunning(CLIENT, container, false);
    }

    public static Callable<Boolean> serviceIsRunning(final DockerClient CLIENT, final Container container,
                                                     boolean rtnForNoServiceId) {
        return new Callable<Boolean>() {
            public Boolean call() throws Exception {
                try {
                    String servicdId = container.serviceId();
                    if (StringUtils.isBlank(servicdId)) {
                        // Want this to be the value we aren't waiting for
                        return rtnForNoServiceId;
                    }
                    final Service serviceResponse = CLIENT.inspectService(servicdId);
                    final List<Task> tasks = CLIENT.listTasks(Task.Criteria.builder().serviceName(serviceResponse.spec().name()).build());
                    if (tasks.size() == 0) {
                        return false;
                    }
                    for (final Task task : tasks) {
                        if (task.status().state().equals(TaskStatus.TASK_STATE_RUNNING)) {
                            return true;
                        }
                    }
                    return false;
                } catch (ContainerNotFoundException ignored) {
                    // Ignore exception. If container is not found, it is not running.
                    return false;
                }
            }
        };
    }

    //public static Callable<Boolean> containerHistoryHasItemFromSystem(final long containerDatabaseId) {
    //    return new Callable<Boolean>() {
    //        public Boolean call() throws Exception {
    //            try {
    //                final Container container = containerService.get(containerDatabaseId);
    //                for (final Container.ContainerHistory historyItem : container.history()) {
    //                    if (historyItem.entityType() != null && historyItem.entityType().equals("system")) {
    //                        return true;
    //                    }
    //                }
    //            } catch (Exception ignored) {
    //                // ignored
    //            }
    //            return false;
    //        }
    //    };
    //}

    public static Callable<String> getServiceNode(final DockerClient CLIENT, final Container container) {
        return new Callable<String>() {
            public String call() {
                try {
                    final Service serviceResponse = CLIENT.inspectService(container.serviceId());
                    final List<Task> tasks = CLIENT.listTasks(Task.Criteria.builder().serviceName(serviceResponse.spec().name()).build());
                    if (tasks.size() == 0) {
                        return null;
                    }
                    for (final Task task : tasks) {
                        if (task.status().state().equals(TaskStatus.TASK_STATE_RUNNING)) {
                            return task.nodeId();
                        }
                    }
                    return null;
                } catch (Exception ignored) {
                    // Ignore exceptions
                    return null;
                }
            }
        };
    }

    public static Container getContainerFromWorkflow(final ContainerService containerService,
                                                     final PersistentWorkflowI workflow) throws NotFoundException {
        await().until(new Callable<String>(){
            public String call() {
                return workflow.getComments();
            }
        }, is(not(isEmptyOrNullString())));
        return containerService.get(workflow.getComments());
    }

    public static Callable<Boolean> containerIsFinalizingOrFailed(final ContainerService containerService,
                                                                  final Container container) {
        return new Callable<Boolean>() {
            public Boolean call() throws Exception {
                String status = containerService.get(container.databaseId()).status();
                return status != null &&
                        (status.equals(ContainerServiceImpl.FINALIZING) ||
                                status.startsWith(PersistentWorkflowUtils.FAILED));
            }
        };
    }

    public static Callable<Boolean> containerIsFinalized(final ContainerService containerService,
                                                         final Container container) {
        return new Callable<Boolean>() {
            public Boolean call() throws Exception {
                String status = containerService.get(container.databaseId()).status();
                return status != null &&
                        (status.equals(PersistentWorkflowUtils.COMPLETE) ||
                                status.startsWith(PersistentWorkflowUtils.FAILED));
            }
        };
    }

    public static Callable<Boolean> containerHasLogPaths(final ContainerService containerService,
                                                         final long containerDbId) {
        return new Callable<Boolean>() {
            public Boolean call() throws Exception {
                final Container container = containerService.get(containerDbId);
                return container.logPaths().size() > 0;
            }
        };
    }

    public static Callable<Boolean> containerHasStatus(final ContainerService containerService,
                                                       final long databaseId, final String status) {
        return new Callable<Boolean>() {
            public Boolean call() throws Exception {
                final Container container = containerService.get(databaseId);
                return container.status().equals(status);
            }
        };
    }

    public static Callable<Boolean> workflowHasStatus(final PersistentWorkflowI workflow, final String status) {
        return new Callable<Boolean>() {
            public Boolean call() throws Exception {
                return workflow.getStatus().equals(status);
            }
        };
    }

    public static String setupSessionMock(TemporaryFolder folder, ObjectMapper mapper, Map<String, String> runtimeValues) throws Exception {
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

    public static void setupMocksForSetupWrapupWorkflow(String uri, FakeWorkflow fakeWorkflow, CatalogService mockCatalogService, UserI mockUser) throws Exception {
        // NOTE YOU MUST HAVE POWERMOCKITO PREPARED ON UriParserUtils, WorkflowUtils and PersistentWorkflowUtils
        // to use this method!!
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
        PowerMockito.doReturn(setupWrapupWorkflow).when(PersistentWorkflowUtils.class, "getOrCreateWorkflowData", eq(2),
                eq(mockUser), any(XFTItem.class), any(EventDetails.class));
        when(WorkflowUtils.buildOpenWorkflow(eq(mockUser), eq(xsiType), eq(id), eq(project), any(EventDetails.class)))
                .thenReturn(setupWrapupWorkflow);

        when(WorkflowUtils.getUniqueWorkflow(mockUser, setupWrapupWorkflow.getWorkflowId().toString()))
                .thenReturn(setupWrapupWorkflow);
    }
}

