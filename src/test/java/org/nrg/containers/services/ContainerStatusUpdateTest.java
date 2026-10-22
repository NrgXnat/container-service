package org.nrg.containers.services;

import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.nrg.containers.daos.ContainerEntityRepository;
import org.nrg.containers.events.model.ContainerEvent;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.entity.ContainerEntity;
import org.nrg.containers.model.container.entity.ContainerEntityHistory;
import org.nrg.containers.services.impl.HibernateContainerEntityService;
import org.nrg.containers.utils.ContainerUtils;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xft.security.UserI;

import java.util.Date;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

@RunWith(MockitoJUnitRunner.class)
@Slf4j
public class ContainerStatusUpdateTest {

    @Mock
    UserI user;

    @Mock
    ContainerEntityRepository containerEntityRepository;

    static class HibernateContainerEntityServiceMock extends HibernateContainerEntityService {
        @Override
        public ContainerEntityRepository getDao() {
            return null;
        }
    }

    @Test
    public void testContainerStatusRaceConditionForStatusUpdate() throws NotFoundException {
        long containerId = 8888;
        String completeStateString = ContainerUtils.TerminalState.COMPLETE.value;
        Container container = Container.builder().databaseId(containerId).commandId(0).wrapperId(0).userId("").dockerImage("").commandLine("")
                .containerName("ContainerName").build();
        ContainerEntity containerEntity = ContainerEntity.fromPojo(container);
        Container.ContainerHistory historyEntry = Container.ContainerHistory.builder().status("Running").entityType("").timeRecorded(new Date()).build();
        ContainerEntityHistory historyEntryEntity = ContainerEntityHistory.fromPojo(historyEntry);

        Container terminalContainer = Container.builder().databaseId(containerId).commandId(0).wrapperId(0).userId("").dockerImage("").commandLine("")
                .containerName("ContainerName").status(completeStateString).build();
        ContainerEntity terminalContainerEntity = ContainerEntity.fromPojo(terminalContainer);

        HibernateContainerEntityServiceMock containerEntityService = Mockito.mock(HibernateContainerEntityServiceMock.class);
        Mockito.when(containerEntityService.retrieve(containerId)).thenReturn(terminalContainerEntity);
        Mockito.when(containerEntityService.addContainerHistoryItem(containerEntity, historyEntryEntity, user)).thenCallRealMethod();
        Mockito.when(containerEntityService.getDao()).thenReturn(containerEntityRepository);

        containerEntityService.addContainerHistoryItem(containerEntity, historyEntryEntity, user);
        final ArgumentCaptor<ContainerEntity> containerEntityArgumentCaptor = ArgumentCaptor.forClass(ContainerEntity.class);

        Mockito.verify(containerEntityService).update(containerEntityArgumentCaptor.capture());

        final ContainerEntity updatedContainerEntity = containerEntityArgumentCaptor.getValue();

        assertThat(updatedContainerEntity.getStatus(), is(completeStateString));
    }

    /**
     * The caller hands in a detached copy (e.g. ContainerServiceImpl.fromPojo), while the status is updated on the
     * managed entity re-retrieved inside the service. The caller decides how to finalize from the returned entity,
     * so it must carry the new status, not the stale one of the copy it passed in.
     */
    @Test
    public void addContainerEventToHistoryReturnsTheEntityWithTheNewStatus() throws NotFoundException {
        final long containerId = 9999;
        final Container running = Container.builder().databaseId(containerId).commandId(0).wrapperId(0).userId("").dockerImage("").commandLine("")
                .containerName("ContainerName").status("Running").statusTime(new Date(0)).build();
        final ContainerEntity detachedCopy = ContainerEntity.fromPojo(running);
        final ContainerEntity managedEntity = ContainerEntity.fromPojo(running);

        final ContainerEvent succeeded = Mockito.mock(ContainerEvent.class);
        Mockito.when(succeeded.status()).thenReturn("Succeeded");
        Mockito.when(succeeded.exitCode()).thenReturn("0");

        final HibernateContainerEntityServiceMock containerEntityService = Mockito.mock(HibernateContainerEntityServiceMock.class);
        Mockito.when(containerEntityService.retrieve(containerId)).thenReturn(managedEntity);
        Mockito.when(containerEntityService.addContainerEventToHistory(detachedCopy, succeeded, user)).thenCallRealMethod();
        Mockito.when(containerEntityService.addContainerHistoryItem(Mockito.any(ContainerEntity.class), Mockito.any(ContainerEntityHistory.class), Mockito.eq(user))).thenCallRealMethod();
        Mockito.when(containerEntityService.getDao()).thenReturn(containerEntityRepository);
        Mockito.doAnswer(invocation -> {
            ((ContainerEntity) invocation.getArguments()[0]).addToHistory((ContainerEntityHistory) invocation.getArguments()[1]);
            return null;
        }).when(containerEntityRepository).addHistoryItem(Mockito.any(ContainerEntity.class), Mockito.any(ContainerEntityHistory.class));

        final ContainerEntity returned = containerEntityService.addContainerEventToHistory(detachedCopy, succeeded, user);

        assertThat(returned, is(notNullValue()));
        assertThat(returned.getStatus(), is("Succeeded"));
        assertThat(returned.getHistory().stream().map(ContainerEntityHistory::getStatus).collect(Collectors.toList()), hasItem("Succeeded"));
        // Recording happens on the managed instance, not on the detached copy
        assertThat(detachedCopy.getHistory().isEmpty(), is(true));
    }

    /** A container deleted since the caller read it is not one we track any more: nothing is recorded or updated. */
    @Test
    public void addContainerEventToHistoryReturnsNullWhenTheContainerNoLongerExists() throws NotFoundException {
        final long containerId = 7777;
        final ContainerEntity detachedCopy = ContainerEntity.fromPojo(Container.builder().databaseId(containerId).commandId(0).wrapperId(0)
                .userId("").dockerImage("").commandLine("").containerName("ContainerName").status("Running").build());
        final ContainerEvent succeeded = Mockito.mock(ContainerEvent.class);

        final HibernateContainerEntityServiceMock containerEntityService = Mockito.mock(HibernateContainerEntityServiceMock.class);
        Mockito.when(containerEntityService.retrieve(containerId)).thenReturn(null);
        Mockito.when(containerEntityService.addContainerEventToHistory(detachedCopy, succeeded, user)).thenCallRealMethod();
        Mockito.lenient().when(containerEntityService.addContainerHistoryItem(Mockito.any(ContainerEntity.class), Mockito.any(ContainerEntityHistory.class), Mockito.eq(user))).thenCallRealMethod();
        Mockito.lenient().when(containerEntityService.getDao()).thenReturn(containerEntityRepository);

        final ContainerEntity returned = containerEntityService.addContainerEventToHistory(detachedCopy, succeeded, user);

        assertThat(returned, is(nullValue()));
        Mockito.verify(containerEntityService, Mockito.never()).update(Mockito.any(ContainerEntity.class));
        Mockito.verifyNoInteractions(containerEntityRepository);
    }

}
