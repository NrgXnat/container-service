package org.nrg.containers.services.impl;

import com.google.common.util.concurrent.Striped;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.daos.ContainerEntityRepository;
import org.nrg.containers.events.model.ContainerEvent;
import org.nrg.containers.model.command.entity.CommandType;
import org.nrg.containers.model.container.entity.ContainerEntity;
import org.nrg.containers.model.container.entity.ContainerEntityHistory;
import org.nrg.containers.services.ContainerEntityService;
import org.nrg.containers.utils.ContainerUtils;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.framework.orm.hibernate.AbstractHibernateEntityService;
import org.nrg.xft.security.UserI;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.locks.Lock;

@Slf4j
@Service
@Transactional
public class HibernateContainerEntityService
        extends AbstractHibernateEntityService<ContainerEntity, ContainerEntityRepository>
        implements ContainerEntityService {

    private static final int NUMBER_LOCKS_FOR_EVENTS = 4096; //value here is somewhat arbitrary - erring on the side of too many locks (we think - not sure how to test effectively)
    @SuppressWarnings("UnstableApiUsage") // Striped is marked as @Beta in our guava version, but not in current version
    private static final Striped<Lock> containerEventLocks = Striped.lazyWeakLock(NUMBER_LOCKS_FOR_EVENTS);


    @Override
    @Nonnull
    public ContainerEntity save(final ContainerEntity toCreate, final UserI userI) {
        final ContainerEntity created = create(toCreate);
        final ContainerEntityHistory historyItem = ContainerEntityHistory.fromUserAction(ContainerServiceImpl.CREATED, userI.getLogin(), created);
        addContainerHistoryItem(created, historyItem, userI);
        return created;
    }

    @Override
    @Nullable
    public ContainerEntity retrieve(final String containerId) {
        if (StringUtils.isBlank(containerId)) {
            return null;
        }
        try {
            // This will allow the higher-level API to request the container by database id or docker hash id
            final long containerDatabaseId = Long.parseLong(containerId);
            return retrieve(containerDatabaseId);
        } catch (NumberFormatException e) {
            final ContainerEntity containerEntity = getDao().retrieveByContainerOrServiceId(containerId);
            initialize(containerEntity);
            return containerEntity;
        }
    }

    @Override
    @Nonnull
    public ContainerEntity get(final String containerId) throws NotFoundException {
        final ContainerEntity containerEntity = retrieve(containerId);
        if (containerEntity == null) {
            //Could be service id - try by service id
            final ContainerEntity containerEntityByServiceId = getDao().retrieveByServiceId(containerId);
            if (containerEntityByServiceId == null) {
                throw new NotFoundException("No container with ID " + containerId);
            }else {
                initialize(containerEntityByServiceId);
                return containerEntityByServiceId;
            }
        }
        return containerEntity;
    }



    @Override
    public void delete(final String containerId) {
        try {
            final ContainerEntity toDelete = get(containerId);
            delete(toDelete.getId());
        } catch (NotFoundException e) {
            // pass
        }
    }

    @Override
    public List<ContainerEntity> getAll(final Boolean nonfinalized, final String project) {
        return (nonfinalized == null || !nonfinalized) ? getDao().getAll(project) : getDao().getAllNonfinalized(project);
    }

    @Override
    public List<ContainerEntity> getAll(final Boolean nonfinalized) {
        return (nonfinalized == null || !nonfinalized) ? getAll() : getDao().getAllNonfinalized();
    }

  
    @Override
    @Nonnull
    public List<ContainerEntity> retrieveServices() {
        return getDao().retrieveServices();
    }

    @Override
    @Nonnull
    public List<ContainerEntity> retrieveNonfinalizedServices() {
        return getDao().retrieveNonfinalizedServices();
    }

    @Override
    @Nonnull
    public List<ContainerEntity> retrieveContainersInFinalizingState() {
        return getDao().retrieveContainersInFinalizingState();
    }

    @Override
    @Nonnull
    public List<ContainerEntity> retrieveServicesInWaitingState() {
        return getDao().retrieveServicesInWaitingState();
    }
    
    @Override
    public int howManyContainersAreBeingFinalized() {
        return getDao().howManyContainersAreBeingFinalized();
    }

    @Override
    public int howManyContainersAreWaiting() {
        return getDao().howManyContainersAreWaiting();
    }
    
    @Override
    @Nonnull
    public List<ContainerEntity> retrieveSetupContainersForParent(final long parentId) {
        return getDao().retrieveContainersForParentWithSubtype(parentId, CommandType.DOCKER_SETUP.getName());
    }

    @Override
    @Nonnull
    public List<ContainerEntity> retrieveWrapupContainersForParent(final long parentId) {
        return getDao().retrieveContainersForParentWithSubtype(parentId, CommandType.DOCKER_WRAPUP.getName());
    }

    @Override
    @Nullable
    public ContainerEntity addContainerEventToHistory(final ContainerEvent containerEvent, final UserI userI) {
        final ContainerEntity containerEntity = retrieve(containerEvent.backendId());
        if (containerEntity == null) {
            log.debug("This event is not about a container we are interested in.");
            return null;
        }

        final ContainerEntityHistory added = addContainerHistoryItem(containerEntity,
                ContainerEntityHistory.fromContainerEvent(containerEvent, containerEntity), userI);
        return added == null ? null : containerEntity; // Return null if we've already added the history item
    }

    @Override
    @Nullable
    public ContainerEntityHistory addContainerHistoryItem(ContainerEntity containerEntity,
                                                          final ContainerEntityHistory history,
                                                          final UserI userI) {
        if (containerEntity.isItemInHistory(history)) {
            log.debug("Event has already been recorded {}", containerEntity.getId());
            return null;
        }

        log.info("Adding new history item to container entity {}", containerEntity.getId());
        getDao().addHistoryItem(containerEntity, history);

        @SuppressWarnings("UnstableApiUsage")
        final Lock containerLock = containerEventLocks.get(String.valueOf(containerEntity.getId()));

        log.debug("Acquiring lock for the container {}", containerEntity.getId());
        containerLock.lock();
        log.debug("Acquired lock for the container {}", containerEntity.getId());

        try {
            containerEntity = retrieve(containerEntity.getId());
            final boolean historyEntryIsMoreRecentThanContainerStatus =
                    history.getTimeRecorded() != null &&
                            (containerEntity.getStatusTime() == null ||
                                    history.getTimeRecorded().getTime() > containerEntity.getStatusTime().getTime());

            if (historyEntryIsMoreRecentThanContainerStatus &&
                    (!ContainerUtils.statusIsTerminal(containerEntity.getStatus()) ||  // Don't overwrite a terminal status
                            ContainerUtils.statusIsTerminal(history.getStatus()))  // ...except with a newer terminal status (i.e. Complete -> Failed)
            ) {
                containerEntity.setStatusTime(history.getTimeRecorded());
                containerEntity.setStatus(history.getStatus());
                log.debug("Setting container entity {} status to \"{}\", based on history entry status \"{}\".",
                        containerEntity.getId(),
                        containerEntity.getStatus(),
                        history.getStatus());
            }

            update(containerEntity);

            ContainerUtils.updateWorkflowStatus(containerEntity.getWorkflowId(), containerEntity.getStatus(),
                    userI, history.getMessage());
        } finally {
            log.debug("Releasing lock for the container {}", containerEntity.getId());
            containerLock.unlock();
        }

        return history;
    }
}
