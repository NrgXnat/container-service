package org.nrg.containers.services;

import org.nrg.containers.events.model.ContainerEvent;
import org.nrg.containers.model.container.entity.ContainerEntity;
import org.nrg.containers.model.container.entity.ContainerEntityHistory;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.framework.orm.hibernate.BaseHibernateService;
import org.nrg.xft.security.UserI;

import javax.annotation.Nonnull;
import java.util.List;

public interface ContainerEntityService extends BaseHibernateService<ContainerEntity> {
    ContainerEntity save(final ContainerEntity toCreate,
                         final UserI userI);

    ContainerEntity retrieve(final String containerId);
    ContainerEntity get(final String containerId) throws NotFoundException;
    void delete(final String containerId);

    List<ContainerEntity> getAll(Boolean nonfinalized, String project);
    List<ContainerEntity> getAll(Boolean nonfinalized);


    List<ContainerEntity> retrieveServices();
    List<ContainerEntity> retrieveNonfinalizedServices();
    List<ContainerEntity> retrieveServicesInFinalizingState();
    List<ContainerEntity> retrieveServicesInWaitingState();

    int howManyContainersAreBeingFinalized();

    List<ContainerEntity> retrieveSetupContainersForParent(long parentId);
    List<ContainerEntity> retrieveWrapupContainersForParent(long parentId);

    ContainerEntity addContainerEventToHistory(final ContainerEvent containerEvent, final UserI userI);
    ContainerEntityHistory addContainerHistoryItem(final ContainerEntity containerEntity,
                                                   final ContainerEntityHistory history, final UserI userI);

	int howManyContainersAreWaiting();
}
