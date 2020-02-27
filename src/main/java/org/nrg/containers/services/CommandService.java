package org.nrg.containers.services;

import org.nrg.containers.exceptions.CommandValidationException;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.auto.Command.CommandWrapper;
import org.nrg.containers.model.command.auto.Command.ConfiguredCommand;
import org.nrg.containers.model.command.auto.CommandSummaryForContext;
import org.nrg.containers.model.configuration.CommandConfiguration;
import org.nrg.containers.model.configuration.ProjectEnabledReport;
import org.nrg.containers.services.ContainerConfigService.CommandConfigurationException;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.framework.exceptions.NrgRuntimeException;
import org.nrg.xft.exception.ElementNotFoundException;
import org.nrg.xft.security.UserI;

import java.util.List;
import java.util.Map;

public interface CommandService {
    Command create(Command command) throws CommandValidationException;
    List<Command> getAll();
    Command retrieve(long id);
    Command get(long id) throws NotFoundException;
    List<Command> findByProperties(Map<String, Object> properties);
    Command update(Command updates) throws NotFoundException, CommandValidationException;
    void delete(long id);
    void delete(Command command);
    List<Command> save(final List<Command> commands);
    List<Command> getByImage(String image);
    void deleteByImage(String image);

    CommandWrapper addWrapper(long commandId, CommandWrapper commandWrapper) throws CommandValidationException, NotFoundException;
    CommandWrapper addWrapper(Command command, CommandWrapper commandWrapper) throws CommandValidationException, NotFoundException;
    CommandWrapper retrieveWrapper(long wrapperId);
    CommandWrapper retrieveWrapper(long commandId, String wrapperName);
    CommandWrapper getWrapper(long wrapperId) throws NotFoundException;
    CommandWrapper getWrapper(long commandId, String wrapperName) throws NotFoundException;
    CommandWrapper updateWrapper(long commandId, CommandWrapper updates) throws CommandValidationException, NotFoundException;
    void deleteWrapper(long wrapperId);

    void configureForSite(CommandConfiguration commandConfiguration, long wrapperId, boolean enable, String username, String reason) throws CommandConfigurationException, NotFoundException;
    void configureForSite(CommandConfiguration commandConfiguration, long commandId, String wrapperName, boolean enable, String username, String reason) throws CommandConfigurationException, NotFoundException;
    void configureForProject(CommandConfiguration commandConfiguration, String project, long wrapperId, boolean enable, String username, String reason) throws CommandConfigurationException, NotFoundException;
    void configureForProject(CommandConfiguration commandConfiguration, String project, long commandId, String wrapperName, boolean enable, String username, String reason) throws CommandConfigurationException, NotFoundException;

    CommandConfiguration getSiteConfiguration(long wrapperId) throws NotFoundException;
    CommandConfiguration getSiteConfiguration(long commandId, String wrapperName) throws NotFoundException;
    CommandConfiguration getProjectConfiguration(String project, long wrapperId) throws NotFoundException;
    CommandConfiguration getProjectConfiguration(String project, long commandId, String wrapperName) throws NotFoundException;
    ConfiguredCommand getAndConfigure(long wrapperId) throws NotFoundException;
    ConfiguredCommand getAndConfigure(long commandId, String wrapperName) throws NotFoundException;
    ConfiguredCommand getAndConfigure(String project, long wrapperId) throws NotFoundException;
    ConfiguredCommand getAndConfigure(String project, long commandId, String wrapperName) throws NotFoundException;
    ConfiguredCommand getAndConfigure(final String project,
                                      final long commandId,
                                      final String wrapperName,
                                      final long wrapperId) throws NotFoundException;

    void deleteSiteConfiguration(long wrapperId, final String username) throws CommandConfigurationException, NotFoundException;
    void deleteSiteConfiguration(long commandId, String wrapperName, final String username) throws CommandConfigurationException, NotFoundException;
    void deleteProjectConfiguration(String project, long wrapperId, final String username) throws CommandConfigurationException, NotFoundException;
    void deleteProjectConfiguration(String project, long commandId, String wrapperName, final String username) throws CommandConfigurationException, NotFoundException;

    void enableForSite(long wrapperId, final String username, final String reason) throws CommandConfigurationException, NotFoundException;
    void enableForSite(long commandId, String wrapperName, final String username, final String reason) throws CommandConfigurationException, NotFoundException;
    void disableForSite(long wrapperId, final String username, final String reason) throws CommandConfigurationException, NotFoundException;
    void disableForSite(long commandId, String wrapperName, final String username, final String reason) throws CommandConfigurationException, NotFoundException;
    boolean isEnabledForSite(long wrapperId) throws NotFoundException;
    boolean isEnabledForSite(long commandId, String wrapperName) throws NotFoundException;
    void enableForProject(String project, long wrapperId, final String username, final String reason) throws CommandConfigurationException, NotFoundException;
    void enableForProject(String project, long commandId, String wrapperName, final String username, final String reason) throws CommandConfigurationException, NotFoundException;
    void disableForProject(String project, long wrapperId, final String username, final String reason) throws CommandConfigurationException, NotFoundException;
    void disableForProject(String project, long commandId, String wrapperName, final String username, final String reason) throws CommandConfigurationException, NotFoundException;
    boolean isEnabledForProject(String project, long wrapperId) throws NotFoundException;
    boolean isEnabledForProject(String project, long commandId, String wrapperName) throws NotFoundException;
    ProjectEnabledReport isEnabledForProjectAsReport(String project, long wrapperId) throws NotFoundException;
    ProjectEnabledReport isEnabledForProjectAsReport(String project, long commandId, String wrapperName) throws NotFoundException;

    List<CommandSummaryForContext> available(String project,
                                             String xsiType,
                                             UserI userI) throws ElementNotFoundException;
    List<CommandSummaryForContext> available(String xsiType,
                                             UserI userI) throws ElementNotFoundException;

    void throwExceptionIfCommandExists(Command command) throws NrgRuntimeException;
}
