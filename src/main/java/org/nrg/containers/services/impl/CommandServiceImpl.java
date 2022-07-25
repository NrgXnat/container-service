package org.nrg.containers.services.impl;

import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.exceptions.CommandValidationException;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.auto.Command.CommandWrapper;
import org.nrg.containers.model.command.auto.CommandSummaryForContext;
import org.nrg.containers.model.command.entity.CommandEntity;
import org.nrg.containers.model.command.entity.CommandWrapperEntity;
import org.nrg.containers.model.configuration.CommandConfiguration;
import org.nrg.containers.model.configuration.CommandConfigurationInternal;
import org.nrg.containers.model.configuration.ProjectEnabledReport;
import org.nrg.containers.services.CommandEntityService;
import org.nrg.containers.services.CommandService;
import org.nrg.containers.services.ContainerConfigService;
import org.nrg.containers.services.ContainerConfigService.CommandConfigurationException;
import org.nrg.containers.utils.ContainerServicePermissionUtils;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.framework.exceptions.NrgRuntimeException;
import org.nrg.framework.exceptions.NrgServiceRuntimeException;
import org.nrg.xdat.model.XnatImagescandataI;
import org.nrg.xdat.om.XnatImagescandata;
import org.nrg.xdat.om.XnatImagesessiondata;
import org.nrg.xft.exception.ElementNotFoundException;
import org.nrg.xft.security.UserI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CommandServiceImpl implements CommandService {

    private final CommandEntityService commandEntityService;
    private final ContainerConfigService containerConfigService;

    @Autowired
    public CommandServiceImpl(final CommandEntityService commandEntityService,
                              final ContainerConfigService containerConfigService) {
        this.commandEntityService = commandEntityService;
        this.containerConfigService = containerConfigService;
    }

    @Override
    @Nonnull
    public Command create(@Nonnull final Command command) throws CommandValidationException {
        final List<String> errors = command.validate();
        if (!errors.isEmpty()) {
            final StringBuilder sb = new StringBuilder("Cannot create command. Validation failed. Errors:");
            for (final String error : errors) {
                sb.append("\n\t");
                sb.append(error);
            }
            log.error(sb.toString());
            throw new CommandValidationException(errors);
        }
        return toPojo(commandEntityService.create(fromPojo(command)));
    }

    @Override
    @Nonnull
    public List<Command> getAll() {
        return toPojo(commandEntityService.getAll());
    }

    @Override
    @Nullable
    public Command retrieve(final long id) {
        final CommandEntity commandEntity = commandEntityService.retrieve(id);
        return commandEntity == null ? null : toPojo(commandEntity);
    }

    @Override
    @Nonnull
    public Command get(final long id) throws NotFoundException {
        return toPojo(commandEntityService.get(id));
    }

    @Override
    @Nonnull
    public List<Command> findByProperties(final Map<String, Object> properties) {
        return toPojo(commandEntityService.findByProperties(properties));
    }

    @Override
    @Nonnull
    @Transactional
    public Command update(final @Nonnull Command toUpdate) throws NotFoundException, CommandValidationException {
        final List<String> errors = toUpdate.validate();
        if (!errors.isEmpty()) {
            throw new CommandValidationException(errors);
        }
        final CommandEntity updatableEntity = fromPojo(toUpdate);
        commandEntityService.update(updatableEntity);
        return toPojo(updatableEntity);
    }

    @Override
    public void delete(final long id) {
        delete(retrieve(id));
    }

    @Override
    public void delete(final Command command) {
        for (final CommandWrapper commandWrapper : command.xnatCommandWrappers()) {
            commandEntityService.deleteWrapper(commandWrapper.id());
        }

        commandEntityService.delete(command.id());
    }

    @Override
    @Nonnull
    public List<Command> save(final List<Command> commands) {
        final List<Command> created = Lists.newArrayList();
        if (!(commands == null || commands.isEmpty())) {
            for (final Command command : commands) {
                try {
                    created.add(create(command));
                } catch (CommandValidationException | NrgServiceRuntimeException e) {
                    // TODO: should I "update" instead of erroring out if command already exists?
                    log.error("Could not save command " + command.name(), e);
                }
            }
        }
        return created;
    }

    @Override
    @Nonnull
    public List<Command> getByImage(final String image) {
        return toPojo(commandEntityService.getByImage(image));
    }

    @Override
    public void deleteByImage(final String image) {
        for (final Command command : getByImage(image)) {
            delete(command);
        }
    }

    @Override
    @Nonnull
    @Transactional
    public CommandWrapper addWrapper(final long commandId, final @Nonnull CommandWrapper wrapperToAdd) throws CommandValidationException, NotFoundException {
        return addWrapper(get(commandId), wrapperToAdd);
    }

    @Override
    @Nonnull
    @Transactional
    public CommandWrapper addWrapper(final @Nonnull Command command, final @Nonnull CommandWrapper wrapperToAdd) throws CommandValidationException, NotFoundException {
        final CommandWrapper created = toPojo(commandEntityService.addWrapper(fromPojo(command), fromPojo(wrapperToAdd)));

        final List<String> errors = get(command.id()).validate();
        if (!errors.isEmpty()) {
            throw new CommandValidationException(errors);
        }
        return created;
    }

    @Override
    @Nullable
    public CommandWrapper retrieveWrapper(final long wrapperId) {
        final CommandWrapperEntity commandWrapperEntity = commandEntityService.retrieveWrapper(wrapperId);
        return commandWrapperEntity == null ? null : toPojo(commandWrapperEntity);
    }

    @Override
    @Nullable
    public CommandWrapper retrieveWrapper(final long commandId, final String wrapperName) {
        final CommandWrapperEntity commandWrapperEntity = commandEntityService.retrieveWrapper(commandId, wrapperName);
        return commandWrapperEntity == null ? null : toPojo(commandWrapperEntity);
    }

    @Override
    @Nonnull
    public CommandWrapper getWrapper(final long wrapperId) throws NotFoundException {
        return toPojo(commandEntityService.getWrapper(wrapperId));
    }

    @Override
    @Nonnull
    public CommandWrapper getWrapper(final long commandId, final String wrapperName) throws NotFoundException {
        return toPojo(commandEntityService.getWrapper(commandId, wrapperName));
    }

    @Override
    @Nonnull
    @Transactional
    public CommandWrapper updateWrapper(final long commandId, final @Nonnull CommandWrapper toUpdate) throws CommandValidationException, NotFoundException {
        final CommandEntity commandEntity = commandEntityService.get(commandId);
        final CommandWrapperEntity template = commandEntityService.getWrapper(toUpdate.id());
        final CommandWrapper updated = toPojo(commandEntityService.update(template.update(toUpdate)));

        final List<String> errors = toPojo(commandEntity).validate();
        if (!errors.isEmpty()) {
            throw new CommandValidationException(errors);
        }
        return updated;
    }

    @Override
    @Transactional
    public void deleteWrapper(final long wrapperId) {
        commandEntityService.deleteWrapper(wrapperId);
    }

    @Override
    public void configureForSite(final CommandConfiguration commandConfiguration, final long wrapperId, final boolean enable, final String username, final String reason)
            throws CommandConfigurationException, NotFoundException {
        // If the "enable" param is true, we enable the configuration.
        // Otherwise, we leave the existing "enabled" setting alone (even if it is null).
        // We will never change "enabled" to "false" here.
        final Boolean enabledStatusToSet = enable ? Boolean.TRUE : isEnabledForSite(wrapperId);
        containerConfigService.configureForSite(
                CommandConfigurationInternal.create(enabledStatusToSet, commandConfiguration),
                wrapperId, username, reason);
    }

    @Override
    public void configureForSite(final CommandConfiguration commandConfiguration, final long commandId, final String wrapperName, final boolean enable, final String username, final String reason)
            throws CommandConfigurationException, NotFoundException {
        configureForSite(commandConfiguration, getWrapperId(commandId, wrapperName), enable, username, reason);
    }

    @Override
    public void configureForProject(final CommandConfiguration commandConfiguration, final String project, final long wrapperId, final boolean enable, final String username, final String reason) throws CommandConfigurationException, NotFoundException {
        // If the "enable" param is true, we enable the configuration.
        // Otherwise, we leave the existing "enabled" setting alone (even if it is null).
        // We will never change "enabled" to "false" here.
        final Boolean enabledStatusToSet = enable ? Boolean.TRUE : isEnabledForProject(project, wrapperId);
        containerConfigService.configureForProject(
                CommandConfigurationInternal.create(enabledStatusToSet, commandConfiguration),
                project, wrapperId, username, reason);
    }

    @Override
    public void configureForProject(final CommandConfiguration commandConfiguration, final String project, final long commandId, final String wrapperName, final boolean enable, final String username, final String reason) throws CommandConfigurationException, NotFoundException {
        configureForProject(commandConfiguration, project, getWrapperId(commandId, wrapperName), enable, username, reason);
    }

    @Override
    @Nonnull
    public CommandConfiguration getSiteConfiguration(final long wrapperId) throws NotFoundException {
        final CommandConfigurationInternal commandConfigurationInternal = containerConfigService.getSiteConfiguration(wrapperId);
        final Command command = getCommandWithOneWrapper(wrapperId);
        final CommandWrapper commandWrapper = command.xnatCommandWrappers().get(0);
        return CommandConfiguration.create(command, commandWrapper, commandConfigurationInternal);
    }

    @Override
    @Nonnull
    public CommandConfiguration getSiteConfiguration(final long commandId, final String wrapperName) throws NotFoundException {
        return getSiteConfiguration(getWrapperId(commandId, wrapperName));
    }

    @Override
    @Nonnull
    public CommandConfiguration getProjectConfiguration(final String project, final long wrapperId) throws NotFoundException {
        final CommandConfigurationInternal commandConfigurationInternal = containerConfigService.getProjectConfiguration(project, wrapperId);
        final Command command = getCommandWithOneWrapper(wrapperId);
        final CommandWrapper commandWrapper = command.xnatCommandWrappers().get(0);
        return CommandConfiguration.create(command, commandWrapper, commandConfigurationInternal);
    }

    @Override
    @Nonnull
    public CommandConfiguration getProjectConfiguration(final String project, final long commandId, final String wrapperName) throws NotFoundException {
        return getProjectConfiguration(project, getWrapperId(commandId, wrapperName));
    }

    @Override
    public CommandConfiguration getConfiguration(String project, long commandId, String wrapperName, long wrapperId) throws NotFoundException {
        if (wrapperId == 0 && (commandId != 0 || wrapperName != null)) {
            wrapperId = getWrapperId(commandId, wrapperName);
        }
        return StringUtils.isBlank(project) ?
                getSiteConfiguration(wrapperId) :
                getProjectConfiguration(project, wrapperId);
    }

    @Override
    @Nonnull
    public Command.ConfiguredCommand getAndConfigure(final long wrapperId) throws NotFoundException {
        return getAndConfigure(null, 0, null, wrapperId);
    }

    @Override
    @Nonnull
    public Command.ConfiguredCommand getAndConfigure(final long commandId, final String wrapperName) throws NotFoundException {
        return getAndConfigure(getWrapperId(commandId, wrapperName));
    }

    @Override
    @Nonnull
    public Command.ConfiguredCommand getAndConfigure(final String project, final long wrapperId) throws NotFoundException {
        return getAndConfigure(project, 0, null, wrapperId);
    }

    @Override
    @Nonnull
    public Command.ConfiguredCommand getAndConfigure(final String project, final long commandId, final String wrapperName) throws NotFoundException {
        return getAndConfigure(project, getWrapperId(commandId, wrapperName));
    }

    @Override
    @Nonnull
    public Command.ConfiguredCommand getAndConfigure(final String project,
                                                     final long commandId,
                                                     final String wrapperName,
                                                     long wrapperId) throws NotFoundException {
        final CommandConfiguration commandConfiguration = getConfiguration(project, commandId, wrapperName, wrapperId);
        final Command command = getCommandWithOneWrapper(wrapperId);
        return commandConfiguration.apply(command);
    }

    @Override
    public void deleteSiteConfiguration(final long wrapperId, final String username) throws CommandConfigurationException {
        containerConfigService.deleteSiteConfiguration(wrapperId, username);
    }

    @Override
    public void deleteSiteConfiguration(final long commandId, final String wrapperName, final String username) throws CommandConfigurationException, NotFoundException {
        containerConfigService.deleteSiteConfiguration(getWrapperId(commandId, wrapperName), username);
    }

    @Override
    public void deleteProjectConfiguration(final String project, final long wrapperId, final String username) throws CommandConfigurationException, NotFoundException {
        containerConfigService.deleteProjectConfiguration(project, wrapperId, username);
    }

    @Override
    public void deleteProjectConfiguration(final String project, final long commandId, final String wrapperName, final String username) throws CommandConfigurationException, NotFoundException {
        containerConfigService.deleteProjectConfiguration(project, getWrapperId(commandId, wrapperName), username);
    }

    @Override
    public void enableForSite(final long wrapperId, final String username, final String reason) throws CommandConfigurationException, NotFoundException {
        containerConfigService.enableForSite(wrapperId, username, reason);
    }

    @Override
    public void enableForSite(final long commandId, final String wrapperName, final String username, final String reason) throws CommandConfigurationException, NotFoundException {
        containerConfigService.enableForSite(getWrapperId(commandId, wrapperName), username, reason);
    }

    @Override
    public void disableForSite(final long wrapperId, final String username, final String reason) throws CommandConfigurationException, NotFoundException {
        containerConfigService.disableForSite(wrapperId, username, reason);
    }

    @Override
    public void disableForSite(final long commandId, final String wrapperName, final String username, final String reason) throws CommandConfigurationException, NotFoundException {
        containerConfigService.disableForSite(getWrapperId(commandId, wrapperName), username, reason);
    }

    @Override
    public boolean isEnabledForSite(final long wrapperId) throws NotFoundException {
        return containerConfigService.isEnabledForSite(wrapperId);
    }

    @Override
    public boolean isEnabledForSite(final long commandId, final String wrapperName) throws NotFoundException {
        return containerConfigService.isEnabledForSite(getWrapperId(commandId, wrapperName));
    }

    @Override
    public void enableForProject(final String project, final long wrapperId, final String username, final String reason) throws CommandConfigurationException, NotFoundException {
        containerConfigService.enableForProject(project, wrapperId, username, reason);
    }

    @Override
    public void enableForProject(final String project, final long commandId, final String wrapperName, final String username, final String reason) throws CommandConfigurationException, NotFoundException {
        containerConfigService.enableForProject(project, getWrapperId(commandId, wrapperName), username, reason);
    }

    @Override
    public void disableForProject(final String project, final long wrapperId, final String username, final String reason) throws CommandConfigurationException, NotFoundException {
        containerConfigService.disableForProject(project, wrapperId, username, reason);
    }

    @Override
    public void disableForProject(final String project, final long commandId, final String wrapperName, final String username, final String reason) throws CommandConfigurationException, NotFoundException {
        containerConfigService.disableForProject(project, getWrapperId(commandId, wrapperName), username, reason);
    }

    @Override
    public boolean isEnabledForProject(final String project, final long wrapperId) throws NotFoundException {
        return containerConfigService.isEnabledForProject(project, wrapperId);
    }

    @Override
    public boolean isEnabledForProject(final String project, final long commandId, final String wrapperName) throws NotFoundException {
        return containerConfigService.isEnabledForProject(project, getWrapperId(commandId, wrapperName));
    }

    @Override
    public ProjectEnabledReport isEnabledForProjectAsReport(final String project, final long wrapperId) throws NotFoundException {
        final boolean isEnabledForSite = isEnabledForSite(wrapperId);
        final boolean isEnabledForProject = isEnabledForProject(project, wrapperId);
        return ProjectEnabledReport.create(isEnabledForSite, isEnabledForProject, project);
    }

    @Override
    public ProjectEnabledReport isEnabledForProjectAsReport(final String project, final long commandId, final String wrapperName) throws NotFoundException {
        final boolean isEnabledForSite = isEnabledForSite(commandId, wrapperName);
        final boolean isEnabledForProject = isEnabledForProject(project, commandId, wrapperName);
        return ProjectEnabledReport.create(isEnabledForSite, isEnabledForProject, project);
    }

    @Override
    @Nonnull
    public List<CommandSummaryForContext> available(final String project,
                                                    final String context,
                                                    final UserI userI) throws ElementNotFoundException {
        if (StringUtils.isBlank(context)) {
            return Collections.emptyList();
        }

        final boolean isSiteWide = StringUtils.isBlank(project);

        // Are they able to read the project at all?
        if (!isSiteWide && !ContainerServicePermissionUtils.canReadProject(userI, project)) {
            log.debug("User \"{}\" cannot read project \"{}\"", userI.getUsername(), project);
            return Collections.emptyList();
        }

        final List<CommandSummaryForContext> available = new ArrayList<>();

        for (final Command command : getAll()) {
            for (final CommandWrapper wrapper : command.xnatCommandWrappers()) {

                // Can only launch if the user gave us an xsiType that matches
                // one of the wrapper's contexts
                if (!xsiTypesMatch(context, wrapper.contexts())) {
                    continue;
                }

                final Command.CommandWrapperExternalInput firstExternalInput = wrapper.firstExternalInput();
                final String externalInputName;
                if (firstExternalInput == null) {
                    if (!isSiteWide) {
                        // Only sitewide wrappers can have 0 external inputs
                        continue;
                    }
                    externalInputName = "";
                } else {
                    externalInputName = firstExternalInput.name();
                }

                if (isSiteWide) {
                    available.add(CommandSummaryForContext.create(command, wrapper,
                            containerConfigService.isEnabledForSite(wrapper.id()),
                            externalInputName));
                    continue;
                }

                // Can only launch if this user has permission
                if (!ContainerServicePermissionUtils.userHasRequiredPermissions(userI, project, context, wrapper)) {
                    continue;
                }

                available.add(CommandSummaryForContext.create(command, wrapper,
                        containerConfigService.isEnabled(project, wrapper.id()),
                        externalInputName));
            }
        }

        return available;
    }

    @Override
    @Nonnull
    public List<CommandSummaryForContext> available(final String xsiType,
                                                    final UserI userI) throws ElementNotFoundException {
        return available(null, xsiType, userI);
    }

    @Override
    public void throwExceptionIfCommandExists(@Nonnull Command command) throws NrgRuntimeException {
        commandEntityService.throwExceptionIfCommandExists(fromPojo(command));
    }

    /**
     * Check if the xsiType that the user gave us is equal to *or* *descended* *from*
     * one of the xsiTypes in the wrapper's contexts set.
     *
     * Example
     * If a wrapper can run on {"xnat:mrSessionData", "xnat:petSessionData"}, and
     * the user asks 'what can I run on an "xnat:mrSessionData"?' we return true.
     *
     * If a wrapper can run on {"xnat:imageSessionData", "xnat:imageAssessorData"}, and
     * the user asks 'what can I run on an "xnat:mrSessionData"?' we return true.

     * If a wrapper can run on {"xnat:mrSessionData"}, and
     * the user asks 'what can I run on an "xnat:imageSessionData"?' we return false.
     *
     * @param xsiType A user asked "what commands can run on this xsiType"?
     * @param wrapperXsiTypes For a particular command wrapper, there are the xsiTypes it can run on.
     *                        This may include "parent" xsiTypes. We want all the "child" types of that
     *                        "parent" type to match as well.
     * @return Can this wrapper run on this xsiType?
     */
    public boolean xsiTypesMatch(final @Nonnull String xsiType,
                                  final @Nonnull Set<String> wrapperXsiTypes) throws ElementNotFoundException {
        return ContainerServicePermissionUtils.xsiTypeEqualToOrInstanceOf(xsiType, wrapperXsiTypes);
    }


    @Nonnull
    private Command toPojo(@Nonnull final CommandEntity commandEntity) {
        return Command.create(commandEntity);
    }

    @Nonnull
    private List<Command> toPojo(final List<CommandEntity> commandEntityList) {
        return commandEntityList == null ? Collections.emptyList() :
                commandEntityList.stream()
                        .filter(Objects::nonNull)
                        .map(this::toPojo)
                        .collect(Collectors.toList());
    }

    @Nonnull
    private CommandEntity fromPojo(@Nonnull final Command command) {
        final CommandEntity template = commandEntityService.retrieve(command.id());
        return template == null ? CommandEntity.fromPojo(command) : template.update(command);
    }

    @Nonnull
    private CommandWrapperEntity fromPojo(@Nonnull final CommandWrapper commandWrapper) {
        return CommandWrapperEntity.fromPojo(commandWrapper);
    }

    @Nonnull
    private CommandWrapper toPojo(@Nonnull final CommandWrapperEntity commandWrapperEntity) {
        return CommandWrapper.create(commandWrapperEntity);
    }

    private long getWrapperId(final long commandId, final String wrapperName) throws NotFoundException {
        return commandEntityService.getWrapperId(commandId, wrapperName);
    }

    @Nonnull
    private Command getCommandWithOneWrapper(final long wrapperId) throws NotFoundException {
        final CommandEntity commandEntity = commandEntityService.getCommandByWrapperId(wrapperId);
        final List<CommandWrapperEntity> listWithOneWrapper = Lists.newArrayList();
        for (final CommandWrapperEntity wrapper : commandEntity.getCommandWrapperEntities()) {
            if (wrapper.getId() == wrapperId) {
                listWithOneWrapper.add(wrapper);
                break;
            }
        }
        commandEntity.setCommandWrapperEntities(listWithOneWrapper);
        return toPojo(commandEntity);

    }
}