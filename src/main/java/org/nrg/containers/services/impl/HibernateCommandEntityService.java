package org.nrg.containers.services.impl;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.nrg.containers.daos.CommandEntityRepository;
import org.nrg.containers.model.command.entity.CommandEntity;
import org.nrg.containers.model.command.entity.CommandWrapperEntity;
import org.nrg.containers.model.orchestration.entity.OrchestrationEntity;
import org.nrg.containers.services.CommandEntityService;
import org.nrg.containers.services.OrchestrationEntityService;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.framework.exceptions.NrgRuntimeException;
import org.nrg.framework.exceptions.NrgServiceRuntimeException;
import org.nrg.framework.orm.hibernate.AbstractHibernateEntityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@Transactional
public class HibernateCommandEntityService extends AbstractHibernateEntityService<CommandEntity, CommandEntityRepository>
        implements CommandEntityService {

    private final OrchestrationEntityService orchestrationEntityService;

    @Autowired
    public HibernateCommandEntityService(final OrchestrationEntityService orchestrationEntityService) {
        this.orchestrationEntityService = orchestrationEntityService;
    }

    @Override
    @Nonnull
    public List<CommandEntity> getAll() {
        final List<CommandEntity> commandEntities = super.getAll();
        return commandEntities == null ? Lists.<CommandEntity>newArrayList() : commandEntities;
    }

    @Override
    public void throwExceptionIfCommandExists(final CommandEntity commandEntity) throws NrgServiceRuntimeException {
        final Map<String, Object> properties = Maps.newHashMap();
        properties.put("name", commandEntity.getName());
        properties.put("image", commandEntity.getImage());
        properties.put("version", commandEntity.getVersion());
        final List<CommandEntity> existingCommandsThatMatch = findByProperties(properties);
        if(existingCommandsThatMatch.size() > 0){
            throw new NrgServiceRuntimeException("This command duplicates a command already in the database.");
        }
    }

    @Override
    @Nonnull
    public CommandEntity create(@Nonnull final CommandEntity commandEntity) throws NrgRuntimeException {
        throwExceptionIfCommandExists(commandEntity);
        try {
            if (log.isDebugEnabled()) {
                log.debug("Saving command " + commandEntity.getName());
            }
            if (commandEntity.getCommandWrapperEntities() != null) {
                for (final CommandWrapperEntity commandWrapperEntity : commandEntity.getCommandWrapperEntities()) {
                    commandWrapperEntity.setCommandEntity(commandEntity);
                }
            }
            return super.create(commandEntity);
        } catch (ConstraintViolationException e) {
            throw new NrgServiceRuntimeException("This command duplicates a command already in the database.", e);//Though throwExceptionIfCommandExists should have prevented creation from even being attempted.
        }
    }

    @Override
    @Nonnull
    public List<CommandEntity> findByProperties(@Nonnull final Map<String, Object> properties) {
        final List<CommandEntity> commandEntities = getDao().findByProperties(properties);
        return commandEntities == null ? Lists.<CommandEntity>newArrayList() : commandEntities;
    }

    @Override
    public CommandWrapperEntity addWrapper(final @Nonnull CommandEntity commandEntity, final @Nonnull CommandWrapperEntity wrapperToAdd) {
        getDao().addWrapper(commandEntity, wrapperToAdd);
        return wrapperToAdd;
    }

    @Override
    @Nullable
    public CommandWrapperEntity retrieveWrapper(final long wrapperId) {
        return getDao().retrieveWrapper(wrapperId);
    }

    @Override
    @Nullable
    public CommandWrapperEntity retrieveWrapper(final long commandId, final String wrapperName) {
        return getDao().retrieveWrapper(commandId, wrapperName);
    }

    @Override
    @Nonnull
    public CommandWrapperEntity getWrapper(final long wrapperId) throws NotFoundException {
        final CommandWrapperEntity commandWrapperEntity = retrieveWrapper(wrapperId);
        if (commandWrapperEntity == null) {
            throw new NotFoundException(String.format("No command wrapper for id %d", wrapperId));
        }
        return commandWrapperEntity;
    }

    @Override
    @Nonnull
    public CommandWrapperEntity getWrapper(final long commandId, final String wrapperName) throws NotFoundException {
        final CommandWrapperEntity commandWrapperEntity = retrieveWrapper(commandId, wrapperName);
        if (commandWrapperEntity == null) {
            throw new NotFoundException(String.format("No command wrapper for command id %d, wrapper name %s", commandId, wrapperName));
        }
        return commandWrapperEntity;
    }

    @Override
    public CommandWrapperEntity update(final CommandWrapperEntity updates) throws NotFoundException {
        getDao().update(updates);
        return updates;
    }

    @Override
    public void deleteWrapper(final long wrapperId) {
        final CommandWrapperEntity commandWrapperEntity = retrieveWrapper(wrapperId);
        if (commandWrapperEntity != null) {
            commandWrapperEntity.getOrchestrations().forEach(owe -> {
                OrchestrationEntity oe = owe.getOrchestrationEntity();
                oe.removeWrapper(owe);
                if (oe.getWrapperList().isEmpty()) {
                    orchestrationEntityService.delete(oe);
                } else {
                    orchestrationEntityService.setEnabled(oe, false);
                }
            });
            getDao().delete(commandWrapperEntity);
        }
    }

    @Override
    public long getWrapperId(final long commandId, final String wrapperName) throws NotFoundException {
        return getDao().getWrapperId(commandId, wrapperName);
    }

    @Override
    public CommandEntity getCommandByWrapperId(final long wrapperId) throws NotFoundException {
        return getDao().getCommandByWrapperId(wrapperId);
    }

    @Override
    public List<CommandEntity> getByImage(final String image) {
        final List<CommandEntity> commandEntities = findByProperties(Collections.<String, Object>singletonMap("image", image));
        for (final CommandEntity commandEntity : commandEntities) {
            initialize(commandEntity);
        }
        return commandEntities;
    }

    @Override
    public void deleteByImage(final String image) {
        for (final CommandEntity commandEntity : getByImage(image)) {
            delete(commandEntity);
        }
    }
}
