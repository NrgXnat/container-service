package org.nrg.containers.services.impl;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

@Slf4j
@Service
@Transactional
public class HibernateContainerEntityService
        extends AbstractHibernateEntityService<ContainerEntity, ContainerEntityRepository>
        implements ContainerEntityService {

    @Override
    @Nonnull
    public ContainerEntity save(final ContainerEntity toCreate, final UserI userI) {
        final ContainerEntity created = create(toCreate);
        final ContainerEntityHistory historyItem = ContainerEntityHistory.fromUserAction("Created", userI.getLogin(), created);
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
            final Long containerDatabaseId = Long.parseLong(containerId);
            return retrieve(containerDatabaseId);
        } catch (NumberFormatException e) {
            final ContainerEntity containerEntity = getDao().retrieveByContainerId(containerId);
            initialize(containerEntity);
            return containerEntity;
        }
    }

    @Override
    @Nonnull
    public ContainerEntity get(final String containerId) throws NotFoundException {
        final ContainerEntity containerEntity = retrieve(containerId);
        if (containerEntity == null) {
            throw new NotFoundException("No container with ID " + containerId);
        }
        return containerEntity;
    }

    @Override
    public void delete(final String containerId) throws NotFoundException {
        final ContainerEntity toDelete = get(containerId);
        delete(toDelete.getId());
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
        final ContainerEntity containerEntity = retrieve(containerEvent.containerId());
        if (containerEntity == null) {
            if (log.isDebugEnabled()) {
                log.debug("This event is not about a container we are interested in.");
            }
            return null;
        }

        final ContainerEntityHistory added = addContainerHistoryItem(containerEntity,
                ContainerEntityHistory.fromContainerEvent(containerEvent, containerEntity), userI);
        return added == null ? null : containerEntity; // Return null if we've already added the history item
    }

    @Override
    @Nullable
    public ContainerEntityHistory addContainerHistoryItem(final ContainerEntity containerEntity,
                                                          final ContainerEntityHistory history,
                                                          final UserI userI) {
        if (containerEntity.isItemInHistory(history)) {
            if (log.isDebugEnabled()) {
                log.debug("Event has already been recorded.");
            }
            return null;
        }

        log.info("Adding new history item to container entity " + containerEntity.getId() + ".");
        if (log.isDebugEnabled()) {
            log.debug("" + history);
        }
        getDao().addHistoryItem(containerEntity, history);

        ContainerUtils.updateWorkflowStatus(containerEntity.getWorkflowId(), containerEntity.getStatus(), userI);

        return history;
    }
}
