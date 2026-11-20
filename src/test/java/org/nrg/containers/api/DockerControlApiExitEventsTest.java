package org.nrg.containers.api;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.ListContainersCmd;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Answers;
import org.nrg.containers.events.model.DockerContainerEvent;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.services.DockerHubService;
import org.nrg.containers.services.DockerServerService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DockerControlApi#getExitEventsForContainers}: the "die" events the status updater throws itself for
 * containers whose own "die" event Docker no longer had.
 */
public class DockerControlApiExitEventsTest {
    private static final String EXITED_ID = "e15c8d624765288484a25fb8843ae9f4cf6314993c8c3eee068ef14640216f9d";
    private static final String JUST_EXITED_ID = "82ca9ce0fdb7a23d89f322240fe53a6bae7a7a5fd6c6d4c8214e14c73ce005e5";
    private static final String RUNNING_ID = "8482fcd947b22b0680d6ef295fae81827b9c70a86c637e488b24254547e4f986";

    private static final Instant FINISHED_BEFORE = Instant.parse("2026-11-20T01:10:30Z");

    private DockerClient client;
    private ListContainersCmd listContainersCmd;
    private DockerControlApi dockerControlApi;

    @Before
    public void setUp() throws Exception {
        client = mock(DockerClient.class);
        listContainersCmd = mock(ListContainersCmd.class, Answers.RETURNS_SELF);
        when(client.listContainersCmd()).thenReturn(listContainersCmd);

        final DockerServerService dockerServerService = mock(DockerServerService.class);
        when(dockerServerService.getServer()).thenReturn(mock(DockerServer.class));
        dockerControlApi = spy(new DockerControlApi(dockerServerService, mock(DockerHubService.class),
                mock(KubernetesClientFactory.class)));
        doReturn(client).when(dockerControlApi).getDockerClient(any(DockerServer.class));
    }

    @Test
    public void exitedContainerGetsADieEventWithItsFinishTimeAndExitCode() throws Exception {
        dockerLists(exited(EXITED_ID, "2026-11-20T01:10:25.778357966Z", 3L));

        final List<DockerContainerEvent> events = dockerControlApi.getExitEventsForContainers(
                Arrays.asList(EXITED_ID, RUNNING_ID), Date.from(FINISHED_BEFORE));

        assertThat(events.size(), is(1));
        final DockerContainerEvent event = events.get(0);
        assertThat(event.backendId(), is(EXITED_ID));
        assertThat(event.isExitStatus(), is(true));
        assertThat(event.exitCode(), is("3"));
        // The finish time, in nanoseconds like Docker's own events. Asking again gives an equal event, which
        // is what lets the history recognize it as already recorded.
        assertThat(event.externalTimestamp(), is("1795137025778357966"));

        // Docker is asked only about our containers, and only for the ones that have exited
        verify(listContainersCmd).withShowAll(true);
        verify(listContainersCmd).withStatusFilter(Arrays.asList("exited", "dead"));
        verify(listContainersCmd).withIdFilter(Arrays.asList(EXITED_ID, RUNNING_ID));
        verify(client, never()).inspectContainerCmd(RUNNING_ID);
    }

    @Test
    public void containerThatFinishedTooRecentlyIsLeftForItsOwnEvent() throws Exception {
        dockerLists(exited(JUST_EXITED_ID, "2026-11-20T01:10:30Z", 0L));

        final List<DockerContainerEvent> events = dockerControlApi.getExitEventsForContainers(
                Collections.singletonList(JUST_EXITED_ID), Date.from(FINISHED_BEFORE));

        assertThat(events.isEmpty(), is(true));
    }

    @Test
    public void noContainersMeansNoCallToDocker() throws Exception {
        assertThat(dockerControlApi.getExitEventsForContainers(Collections.emptyList(), Date.from(FINISHED_BEFORE)).isEmpty(), is(true));

        verify(client, never()).listContainersCmd();
    }

    @Test
    public void manyContainersAreAskedAboutInBatches() throws Exception {
        dockerLists();
        final List<String> ids = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            ids.add(String.format("%064x", i));
        }

        dockerControlApi.getExitEventsForContainers(ids, Date.from(FINISHED_BEFORE));

        verify(listContainersCmd, times(3)).withIdFilter(anyCollection());
        verify(listContainersCmd).withIdFilter(ids.subList(0, 100));
        verify(listContainersCmd).withIdFilter(ids.subList(100, 200));
        verify(listContainersCmd).withIdFilter(ids.subList(200, 250));
    }

    private void dockerLists(final com.github.dockerjava.api.model.Container... containers) {
        when(listContainersCmd.exec()).thenReturn(Arrays.asList(containers));
    }

    private com.github.dockerjava.api.model.Container exited(final String id, final String finishedAt, final Long exitCode) {
        final InspectContainerResponse.ContainerState state = mock(InspectContainerResponse.ContainerState.class);
        when(state.getFinishedAt()).thenReturn(finishedAt);
        when(state.getExitCodeLong()).thenReturn(exitCode);
        final InspectContainerResponse response = mock(InspectContainerResponse.class);
        when(response.getState()).thenReturn(state);
        final InspectContainerCmd inspectContainerCmd = mock(InspectContainerCmd.class);
        when(inspectContainerCmd.exec()).thenReturn(response);
        when(client.inspectContainerCmd(id)).thenReturn(inspectContainerCmd);

        final com.github.dockerjava.api.model.Container container = mock(com.github.dockerjava.api.model.Container.class);
        when(container.getId()).thenReturn(id);
        return container;
    }
}
