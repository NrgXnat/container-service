package org.nrg.containers.services;

import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;
import org.nrg.containers.daos.ContainerEntityRepository;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.entity.ContainerEntity;
import org.nrg.containers.model.container.entity.ContainerEntityHistory;
import org.nrg.containers.services.impl.HibernateContainerEntityService;
import org.nrg.containers.utils.ContainerUtils;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xft.security.UserI;

import java.util.Date;

import static org.hamcrest.Matchers.is;
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

}
