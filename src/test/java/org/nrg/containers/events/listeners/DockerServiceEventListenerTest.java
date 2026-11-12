package org.nrg.containers.events.listeners;

import com.github.dockerjava.api.model.TaskState;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;
import org.nrg.containers.events.model.ServiceTaskEvent;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.auto.ServiceTask;
import org.nrg.containers.model.server.docker.Backend;
import org.nrg.containers.services.ContainerService;
import org.nrg.xdat.security.helpers.Users;
import org.nrg.xft.security.UserI;
import org.springframework.jms.core.JmsTemplate;
import reactor.bus.EventBus;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A Waiting event's copy of the service may be stale after the event bus and JMS hops, and {@code queueFinalize} may
 * save history, so the listener must finalize the reloaded row.
 */
@RunWith(MockitoJUnitRunner.class)
public class DockerServiceEventListenerTest {
    private static final long DATABASE_ID = 42L;
    private static final String SERVICE_ID = "swarm-service-abc123";

    @Mock private EventBus eventBus;
    @Mock private ContainerService containerService;
    @Mock private JmsTemplate jmsTemplate;
    @Mock private UserI adminUser;

    private DockerServiceEventListener listener;
    private MockedStatic<Users> mockedUsers;

    @Before
    public void setUp() {
        mockedUsers = mockStatic(Users.class);
        listener = new DockerServiceEventListener(eventBus, containerService, jmsTemplate);
    }

    @After
    public void tearDown() {
        mockedUsers.closeOnDemand();
    }

    @Test
    public void waitingEventFinalizesTheReloadedRow() {
        mockedUsers.when(Users::getAdminUser).thenReturn(adminUser);
        final Container reloaded = service("busybox:latest");
        when(containerService.retrieve(DATABASE_ID)).thenReturn(reloaded);

        listener.onRequest(waitingEvent());

        verify(containerService).queueFinalize(nullable(String.class), eq(true), same(reloaded), same(adminUser));
    }

    @Test
    public void waitingEventSkipsARowDeletedSinceTheEventWasSent() {
        when(containerService.retrieve(DATABASE_ID)).thenReturn(null);

        listener.onRequest(waitingEvent());

        verify(containerService, never()).queueFinalize(nullable(String.class), anyBoolean(), nullable(Container.class), nullable(UserI.class));
    }

    private static ServiceTaskEvent waitingEvent() {
        final ServiceTask task = ServiceTask.builder()
                .serviceId(SERVICE_ID)
                .status(TaskState.COMPLETE.getValue())
                .swarmNodeError(false)
                .exitCode(0L)
                .build();
        return ServiceTaskEvent.create(task, service(""), ServiceTaskEvent.EventType.Waiting);
    }

    private static Container service(final String image) {
        return Container.builder()
                .databaseId(DATABASE_ID)
                .commandId(7L)
                .wrapperId(8L)
                .userId("someuser")
                .backend(Backend.SWARM)
                .serviceId(SERVICE_ID)
                .status("Waiting")
                .dockerImage(image)
                .commandLine("echo hello")
                .build();
    }
}
