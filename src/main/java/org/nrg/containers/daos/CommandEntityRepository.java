package org.nrg.containers.daos;

import com.google.common.collect.Maps;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.FlushMode;
import org.hibernate.Hibernate;
import org.hibernate.NonUniqueObjectException;
import org.nrg.containers.model.command.entity.CommandEntity;
import org.nrg.containers.model.command.entity.CommandInputEntity;
import org.nrg.containers.model.command.entity.CommandWrapperEntity;
import org.nrg.containers.model.command.entity.DockerCommandEntity;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.framework.orm.hibernate.AbstractHibernateDAO;
import org.springframework.stereotype.Repository;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

@Slf4j
@Repository
public class CommandEntityRepository extends AbstractHibernateDAO<CommandEntity> {

    @Override
    public void initialize(final CommandEntity commandEntity) {
        if (commandEntity == null) {
            return;
        }
        Hibernate.initialize(commandEntity);
        Hibernate.initialize(commandEntity.getEnvironmentVariables());
        Hibernate.initialize(commandEntity.getMounts());
        Hibernate.initialize(commandEntity.getOutputs());
        Hibernate.initialize(commandEntity.getInputs());
        if (commandEntity.getInputs() != null) {
            for (final CommandInputEntity commandInputEntity : commandEntity.getInputs()) {
                Hibernate.initialize(commandInputEntity.getSelectValues());
            }
        }
        Hibernate.initialize(commandEntity.getCommandWrapperEntities());
        if (commandEntity.getCommandWrapperEntities() != null) {
            for (final CommandWrapperEntity commandWrapperEntity : commandEntity.getCommandWrapperEntities()) {
                initialize(commandWrapperEntity);
            }
        }

        switch (commandEntity.getType()) {
            case DOCKER:
                Hibernate.initialize(((DockerCommandEntity) commandEntity).getPorts());
                break;
        }
    }

    public void initialize(final CommandWrapperEntity commandWrapperEntity) {
        if (commandWrapperEntity == null) {
            return;
        }
        Hibernate.initialize(commandWrapperEntity.getContexts());
        Hibernate.initialize(commandWrapperEntity.getExternalInputs());
        Hibernate.initialize(commandWrapperEntity.getDerivedInputs());
        Hibernate.initialize(commandWrapperEntity.getOutputHandlers());
    }

    @Nullable
    public CommandEntity retrieve(final String name, final String dockerImageId) {
        if (StringUtils.isBlank(name) || StringUtils.isBlank(dockerImageId)) {
            return null;
        }

        final Map<String, Object> properties = Maps.newHashMap();
        properties.put("name", name);
        properties.put("dockerImage", dockerImageId);

        final List<CommandEntity> commandEntities = findByProperties(properties);

        if (commandEntities == null || commandEntities.isEmpty()) {
            return null;
        } else if (commandEntities.size() > 1) {
            if (log.isErrorEnabled()) {
                StringBuilder message = new StringBuilder("Somehow the database contains more than one Command with the same name + docker image id: ");
                for (final CommandEntity commandEntity : commandEntities) {
                    message.append(commandEntity.getId());
                    message.append(", ");
                }
                message.delete(message.lastIndexOf(","), message.length());
                log.error(message.toString());
            }
        }
        final CommandEntity commandEntity = commandEntities.get(0);
        initialize(commandEntity);
        return commandEntities.get(0);
    }

    @Override
    @Nullable
    public List<CommandEntity> findByProperties(@Nonnull final Map<String, Object> properties) {
        log.debug("Searching for command entities by properties: {}", properties);
        final List<CommandEntity> commandEntityList = super.findByProperties(properties);
        if (commandEntityList == null || commandEntityList.size() == 0) {
            log.debug("No command entities found with properties: {}", properties);
            return null;
        }
        log.debug("Found {} command entities. Initializing.", commandEntityList.size());
        for (final CommandEntity commandEntity : commandEntityList) {
            initialize(commandEntity);
        }
        return commandEntityList;
    }

    @Override
    public void update(@Nonnull final CommandEntity commandEntity) {
        try {
            getSession().update(commandEntity);
        } catch (NonUniqueObjectException e) {
            getSession().merge(commandEntity);
        }
    }

    public void addWrapper(final @Nonnull CommandEntity commandEntity,
                           final @Nonnull CommandWrapperEntity commandWrapperEntity) {
        commandEntity.addWrapper(commandWrapperEntity);
        getSession().persist(commandWrapperEntity);
        update(commandEntity);
    }

    public CommandWrapperEntity retrieveWrapper(long wrapperId) {
        final CommandWrapperEntity commandWrapperEntity = (CommandWrapperEntity) getSession().get(CommandWrapperEntity.class, wrapperId);
        initialize(commandWrapperEntity);
        return commandWrapperEntity;
    }

    public boolean commandExists(final long commandId) {
        return getSession().createQuery("select 1 from CommandEntity as command where command.id = :commandId")
                .setLong("commandId", commandId)
                .uniqueResult() != null;
    }

    public CommandWrapperEntity retrieveWrapper(long commandId, String wrapperName) {
        if (!commandExists(commandId)) {
            return null;
        }
        final CommandWrapperEntity commandWrapperEntity = (CommandWrapperEntity) getSession()
                .createQuery("select wrapper from CommandWrapperEntity as wrapper where wrapper.name = :wrapperName and wrapper.commandEntity.id = :commandId")
                .setString("wrapperName", wrapperName)
                .setLong("commandId", commandId)
                .setFlushMode(FlushMode.MANUAL)
                .uniqueResult();
        initialize(commandWrapperEntity);
        return commandWrapperEntity;
    }

    public void update(final CommandWrapperEntity toUpdate) {
        try {
            getSession().update(toUpdate);
        } catch (NonUniqueObjectException ignored) {
            getSession().merge(toUpdate);
        }
    }

    public void refresh(final CommandWrapperEntity wrapper) {
        getSession().refresh(wrapper);
    }

    public void delete(final @Nonnull CommandWrapperEntity commandWrapperEntity) {
        commandWrapperEntity.getCommandEntity().getCommandWrapperEntities().remove(commandWrapperEntity);
        getSession().delete(commandWrapperEntity);
    }

    public long getWrapperId(final long commandId, final String wrapperName) throws NotFoundException {

        if (!commandExists(commandId)) {
            throw new NotFoundException("No command with id " + String.valueOf(commandId));
        }

        final Object wrapperId = getSession().createQuery("select wrapper.id from CommandWrapperEntity as wrapper where wrapper.name = :wrapperName and wrapper.commandEntity.id = :commandId")
                .setString("wrapperName", wrapperName)
                .setLong("commandId", commandId)
                .uniqueResult();
        if (wrapperId != null) {
            return (Long) wrapperId;
        }

        // We didn't find the wrapper by name. Maybe we accidentally got the id as a string?
        try {
            final Long possibleWrapperId = Long.parseLong(wrapperName);
            final boolean wrapperExists = getSession()
                    .createQuery("select 1 from CommandWrapperEntity as wrapper where wrapper.id = :wrapperId and wrapper.commandEntity.id = :commandId")
                    .setLong("wrapperId", possibleWrapperId)
                    .setLong("commandId", commandId)
                    .uniqueResult() != null;
            if (wrapperExists) {
                return possibleWrapperId;
            }
        } catch (NumberFormatException ignored) {
            // No, wrapperName is not a number
        }

        throw new NotFoundException("Command " + String.valueOf(commandId) + " has no wrapper " + wrapperName);
    }

    public CommandEntity getCommandByWrapperId(final long wrapperId) throws NotFoundException {
        final Object commandEntityResult = getSession()
                .createQuery("select wrapper.commandEntity from CommandWrapperEntity as wrapper where wrapper.id = :wrapperId")
                .setLong("wrapperId", wrapperId)
                .setFlushMode(FlushMode.MANUAL)
                .uniqueResult();
        if (commandEntityResult == null) {
            throw new NotFoundException("No command with wrapper with id " + String.valueOf(wrapperId));
        }

        final CommandEntity commandEntity = (CommandEntity) commandEntityResult;
        initialize(commandEntity);
        return commandEntity;
    }
}
