package org.nrg.containers.services.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.InvalidJsonException;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.TypeRef;
import com.jayway.jsonpath.spi.mapper.MappingException;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.nrg.config.services.ConfigService;
import org.nrg.containers.exceptions.CommandInputResolutionException;
import org.nrg.containers.exceptions.CommandResolutionException;
import org.nrg.containers.exceptions.ContainerException;
import org.nrg.containers.exceptions.ContainerMountResolutionException;
import org.nrg.containers.exceptions.UnauthorizedException;
import org.nrg.containers.model.command.auto.Command.CommandInput;
import org.nrg.containers.model.command.auto.Command.CommandMount;
import org.nrg.containers.model.command.auto.Command.CommandOutput;
import org.nrg.containers.model.command.auto.Command.CommandWrapper;
import org.nrg.containers.model.command.auto.Command.CommandWrapperDerivedInput;
import org.nrg.containers.model.command.auto.Command.CommandWrapperExternalInput;
import org.nrg.containers.model.command.auto.Command.CommandWrapperInput;
import org.nrg.containers.model.command.auto.Command.CommandWrapperOutput;
import org.nrg.containers.model.command.auto.Command.ConfiguredCommand;
import org.nrg.containers.model.command.auto.Command.Input;
import org.nrg.containers.model.command.auto.ResolvedCommand;
import org.nrg.containers.model.command.auto.ResolvedCommand.PartiallyResolvedCommand;
import org.nrg.containers.model.command.auto.ResolvedCommand.PartiallyResolvedCommandMount;
import org.nrg.containers.model.command.auto.ResolvedCommand.ResolvedCommandMount;
import org.nrg.containers.model.command.auto.ResolvedCommand.ResolvedCommandMountFiles;
import org.nrg.containers.model.command.auto.ResolvedCommand.ResolvedCommandOutput;
import org.nrg.containers.model.command.auto.ResolvedInputValue;
import org.nrg.containers.model.command.auto.PreresolvedInputTreeNode;
import org.nrg.containers.model.command.auto.ResolvedInputTreeNode;
import org.nrg.containers.model.command.auto.ResolvedInputTreeNode.ResolvedInputTreeValueAndChildren;
import org.nrg.containers.model.xnat.Assessor;
import org.nrg.containers.model.xnat.Project;
import org.nrg.containers.model.xnat.Resource;
import org.nrg.containers.model.xnat.Scan;
import org.nrg.containers.model.xnat.Session;
import org.nrg.containers.model.xnat.Subject;
import org.nrg.containers.model.xnat.XnatFile;
import org.nrg.containers.model.xnat.XnatModelObject;
import org.nrg.containers.services.CommandResolutionService;
import org.nrg.containers.services.CommandService;
import org.nrg.framework.constants.Scope;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.security.helpers.Permissions;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.helpers.uri.URIManager;
import org.nrg.xnat.helpers.uri.URIManager.ArchiveItemURI;
import org.nrg.xnat.helpers.uri.UriParserUtils;
import org.nrg.xnat.turbine.utils.ArchivableItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.nrg.containers.model.command.entity.CommandWrapperInputType.ASSESSOR;
import static org.nrg.containers.model.command.entity.CommandWrapperInputType.BOOLEAN;
import static org.nrg.containers.model.command.entity.CommandWrapperInputType.CONFIG;
import static org.nrg.containers.model.command.entity.CommandWrapperInputType.DIRECTORY;
import static org.nrg.containers.model.command.entity.CommandWrapperInputType.FILE;
import static org.nrg.containers.model.command.entity.CommandWrapperInputType.FILES;
import static org.nrg.containers.model.command.entity.CommandWrapperInputType.NUMBER;
import static org.nrg.containers.model.command.entity.CommandWrapperInputType.PROJECT;
import static org.nrg.containers.model.command.entity.CommandWrapperInputType.RESOURCE;
import static org.nrg.containers.model.command.entity.CommandWrapperInputType.SCAN;
import static org.nrg.containers.model.command.entity.CommandWrapperInputType.SESSION;
import static org.nrg.containers.model.command.entity.CommandWrapperInputType.STRING;
import static org.nrg.containers.model.command.entity.CommandWrapperInputType.SUBJECT;

@Service
public class CommandResolutionServiceImpl implements CommandResolutionService {
    private final Logger log = LoggerFactory.getLogger(CommandResolutionServiceImpl.class);

    private final CommandService commandService;
    private final ConfigService configService;
    private final SiteConfigPreferences siteConfigPreferences;
    private final ObjectMapper mapper;

    @Autowired
    public CommandResolutionServiceImpl(final CommandService commandService,
                                        final ConfigService configService,
                                        final SiteConfigPreferences siteConfigPreferences,
                                        final ObjectMapper mapper) {
        this.commandService = commandService;
        this.configService = configService;
        this.siteConfigPreferences = siteConfigPreferences;
        this.mapper = mapper;
    }

    @Override
    public PartiallyResolvedCommand preResolve(final long wrapperId,
                                               final Map<String, String> inputValues,
                                               final UserI userI)
            throws NotFoundException, CommandResolutionException, UnauthorizedException {
        return preResolve(commandService.getAndConfigure(wrapperId), inputValues, userI);
    }

    @Override
    public PartiallyResolvedCommand preResolve(final long commandId,
                                               final String wrapperName,
                                               final Map<String, String> inputValues,
                                               final UserI userI)
            throws NotFoundException, CommandResolutionException, UnauthorizedException {
        return preResolve(commandService.getAndConfigure(commandId, wrapperName), inputValues, userI);
    }

    @Override
    public PartiallyResolvedCommand preResolve(final String project,
                                               final long wrapperId,
                                               final Map<String, String> inputValues,
                                               final UserI userI)
            throws NotFoundException, CommandResolutionException, UnauthorizedException {
        return preResolve(commandService.getAndConfigure(project, wrapperId), inputValues, userI);
    }

    @Override
    public PartiallyResolvedCommand preResolve(final String project,
                                               final long commandId,
                                               final String wrapperName,
                                               final Map<String, String> inputValues,
                                               final UserI userI)
            throws NotFoundException, CommandResolutionException, UnauthorizedException {
        return preResolve(commandService.getAndConfigure(project, commandId, wrapperName), inputValues, userI);
    }

    @Override
    public PartiallyResolvedCommand preResolve(final ConfiguredCommand configuredCommand, final Map<String, String> inputValues, final UserI userI)
            throws CommandResolutionException, UnauthorizedException {
        final CommandResolutionHelper helper = new CommandResolutionHelper(configuredCommand, inputValues, userI);
        return helper.preResolve();
    }

    @Override
    public ResolvedCommand resolve(final long commandId,
                                   final String wrapperName,
                                   final Map<String, String> inputValues,
                                   final UserI userI)
            throws NotFoundException, CommandResolutionException, UnauthorizedException {
        return resolve(commandService.getAndConfigure(commandId, wrapperName), inputValues, userI);
    }

    @Override
    @Nonnull
    public ResolvedCommand resolve(final long wrapperId,
                                   final Map<String, String> inputValues,
                                   final UserI userI)
            throws NotFoundException, CommandResolutionException, UnauthorizedException {
        return resolve(commandService.getAndConfigure(wrapperId), inputValues, userI);
    }

    @Override
    @Nonnull
    public ResolvedCommand resolve(final String project,
                                   final long wrapperId,
                                   final Map<String, String> inputValues,
                                   final UserI userI)
            throws NotFoundException, CommandResolutionException, UnauthorizedException {
        return resolve(commandService.getAndConfigure(project, wrapperId), inputValues, userI);
    }

    @Override
    public ResolvedCommand resolve(final String project,
                                   final long commandId,
                                   final String wrapperName,
                                   final Map<String, String> inputValues,
                                   final UserI userI)
            throws NotFoundException, CommandResolutionException, UnauthorizedException {
        return resolve(commandService.getAndConfigure(project, commandId, wrapperName), inputValues, userI);
    }

    // @Override
    // @Nonnull
    // public PartiallyResolvedCommand resolve(final Command command,
    //                                                final Map<String, String> runtimeInputValues,
    //                                                final UserI userI)
    //         throws NotFoundException, CommandResolutionException {
    //     // I was not given a wrapper.
    //     // TODO what should I do here? Should I...
    //     //  1. Use the "passthrough" wrapper, no matter what
    //     //  2. Use the "passthrough" wrapper only if the command has no outputs
    //     //  3. check if the command has any wrappers, and use one if it exists
    //     //  4. Something else
    //     //
    //     // I guess for now I'll do 2.
    //
    //     if (!command.outputs().isEmpty()) {
    //         throw new CommandResolutionException("Cannot resolve command without an XNAT wrapper. Command has outputs that will not be handled.");
    //     }
    //
    //     final CommandWrapper commandWrapperToResolve = CommandWrapper.passthrough(command);
    //
    //     return resolve(commandWrapperToResolve, command, runtimeInputValues, userI);
    // }

    @Override
    @Nonnull
    public ResolvedCommand resolve(final ConfiguredCommand configuredCommand,
                                   final Map<String, String> inputValues,
                                   final UserI userI)
            throws NotFoundException, CommandResolutionException, UnauthorizedException {
        final CommandResolutionHelper helper = new CommandResolutionHelper(configuredCommand, inputValues, userI);
        return helper.resolve();
    }

    private class CommandResolutionHelper {
        private final String JSONPATH_SUBSTRING_REGEX = "\\^(wrapper:)?(.+)\\^";

        private final CommandWrapper commandWrapper;
        private final ConfiguredCommand command;

        private final UserI userI;
        private final Pattern jsonpathSubstringPattern;
        private final DocumentContext commandJsonpathSearchContext;
        private final DocumentContext commandWrapperJsonpathSearchContext;
        private String containerHost;

        // Caches
        private Map<String, String> inputValues;

        private CommandResolutionHelper(final ConfiguredCommand configuredCommand,
                                        final Map<String, String> inputValues,
                                        final UserI userI) throws CommandResolutionException {
            this.commandWrapper = configuredCommand.wrapper();
            this.command = configuredCommand;

            // Set up JSONPath search contexts
            final Configuration c = Configuration.defaultConfiguration().addOptions(Option.ALWAYS_RETURN_LIST);
            try {
                final String commandJson = mapper.writeValueAsString(command);
                commandJsonpathSearchContext = JsonPath.using(c).parse(commandJson);
            } catch (JsonProcessingException e) {
                throw new CommandResolutionException("Could not serialize command to JSON.", e);
            }

            try {
                final String commandWrapperJson = mapper.writeValueAsString(commandWrapper);
                commandWrapperJsonpathSearchContext = JsonPath.using(c).parse(commandWrapperJson);
            } catch (JsonProcessingException e) {
                throw new CommandResolutionException("Could not serialize command to JSON.", e);
            }

            this.userI = userI;
            this.jsonpathSubstringPattern = Pattern.compile(JSONPATH_SUBSTRING_REGEX);

            this.inputValues = inputValues == null ?
                    Collections.<String, String>emptyMap() :
                    inputValues;

        }

        @Nonnull
        private List<ResolvedInputTreeNode<? extends Input>> resolveInputTrees()
                throws CommandResolutionException, UnauthorizedException {
            return resolveInputTrees(Maps.<String, String>newHashMap());
        }

        @Nonnull
        private List<ResolvedInputTreeNode<? extends Input>> resolveInputTrees(final Map<String, String> resolvedValuesByReplacementKey)
                throws CommandResolutionException, UnauthorizedException {
            final List<PreresolvedInputTreeNode<? extends Input>> rootNodes = initializePreresolvedInputTree();

            final List<ResolvedInputTreeNode<? extends Input>> resolvedInputTrees = Lists.newArrayList();
            for (final PreresolvedInputTreeNode<? extends Input> rootNode : rootNodes) {
                log.debug("Resolving input tree with root input \"{}\".", rootNode.input().name());
                final ResolvedInputTreeNode<? extends Input> resolvedRootNode =
                        resolveNode(rootNode, null, resolvedValuesByReplacementKey);
                log.debug("Done resolving input tree with root input \"{}\".", rootNode.input().name());
                resolvedInputTrees.add(resolvedRootNode);

                log.debug("Searching input tree for uniquely resolved values.");
                resolvedValuesByReplacementKey.putAll(findUniqueResolvedValues(resolvedRootNode));
                log.debug("Done searching input tree for uniquely resolved values.");

            }

            // TODO turn the input trees into something manageable
            return resolvedInputTrees;
        }

        @Nonnull
        private PartiallyResolvedCommand preResolve() throws CommandResolutionException, UnauthorizedException {
            log.info("Resolving command wrapper inputs.");
            log.debug("{}", commandWrapper);

            final List<ResolvedInputTreeNode<? extends Input>> resolvedInputTrees = resolveInputTrees();

            return PartiallyResolvedCommand.builder()
                    .wrapperId(commandWrapper.id())
                    .wrapperName(commandWrapper.name())
                    .wrapperDescription(commandWrapper.description())
                    .commandId(command.id())
                    .commandName(command.name())
                    .commandDescription(command.description())
                    .image(command.image())
                    .rawInputValues(inputValues)
                    .resolvedInputTrees(resolvedInputTrees)
                    .build();
        }

        @Nonnull
        private ResolvedCommand resolve() throws CommandResolutionException, UnauthorizedException {
            log.info("Resolving command.");
            log.debug("{}", command);

            final Map<String, String> resolvedInputValuesByReplacementKey = Maps.newHashMap();
            final List<ResolvedInputTreeNode<? extends Input>> resolvedInputTrees = resolveInputTrees(resolvedInputValuesByReplacementKey);

            log.debug("Checking for missing required inputs.");
            final List<String> missingRequiredInputs = findMissingRequiredInputs(resolvedInputTrees);
            if (!missingRequiredInputs.isEmpty()) {
                throw new CommandResolutionException(
                        String.format("Missing values for required input%s: %s.",
                                missingRequiredInputs.size() == 1 ? "" : "s",
                                StringUtils.join(missingRequiredInputs, ", "))
                );
            }

            // TODO this is temporary, until we figure out a better way to store the input trees
            // Read out all the input trees into Map<String, String>s
            final List<ResolvedInputTreeNode<? extends Input>> flatTree = Lists.newArrayList();
            for (final ResolvedInputTreeNode<? extends Input> rootNode : resolvedInputTrees) {
                log.debug("Flattening tree starting with root node {}.", rootNode.input().name());
                flatTree.addAll(flattenTree(rootNode));
                log.debug("Done flattening tree starting with root node {}.", rootNode.input().name());
            }
            final Map<String, String> wrapperInputValues = Maps.newHashMap();
            final Map<String, String> commandInputValues = Maps.newHashMap();
            for (final ResolvedInputTreeNode<? extends Input> node : flatTree) {
                final List<ResolvedInputTreeValueAndChildren> valuesAndChildren = node.valuesAndChildren();
                final String value = (valuesAndChildren != null && !valuesAndChildren.isEmpty()) ?
                        valuesAndChildren.get(0).resolvedValue().value() :
                        null;
                if (node.input() instanceof CommandWrapperInput) {
                    wrapperInputValues.put(node.input().name(), value == null ? "null" : value);
                } else {
                    commandInputValues.put(node.input().name(), value == null ? "null" : value);
                }
            }

            final ResolvedCommand resolvedCommand = ResolvedCommand.builder()
                    .wrapperId(commandWrapper.id())
                    .wrapperName(commandWrapper.name())
                    .wrapperDescription(commandWrapper.description())
                    .commandId(command.id())
                    .commandName(command.name())
                    .commandDescription(command.description())
                    .image(command.image())
                    .rawInputValues(inputValues)
                    .wrapperInputValues(wrapperInputValues) // TODO remove this property
                    .commandInputValues(commandInputValues) // TODO remove this property
                    .outputs(resolveOutputs(resolvedInputTrees, resolvedInputValuesByReplacementKey))
                    .commandLine(resolveCommandLine(resolvedInputTrees))
                    .environmentVariables(resolveEnvironmentVariables(resolvedInputValuesByReplacementKey))
                    .workingDirectory(resolveWorkingDirectory(resolvedInputValuesByReplacementKey))
                    .ports(resolvePorts(resolvedInputValuesByReplacementKey))
                    .mounts(resolveCommandMounts(resolvedInputTrees, resolvedInputValuesByReplacementKey))
                    .build();

            log.info("Done resolving command.");
            log.debug("Resolved command: \n{}", resolvedCommand);
            return resolvedCommand;
        }

        @Nonnull
        private List<ResolvedInputTreeNode<? extends Input>> flattenTree(final ResolvedInputTreeNode<? extends Input> node) {
            final List<ResolvedInputTreeNode<? extends Input>> flatTree = Lists.newArrayList();
            log.debug("Adding input \"{}\" to flattened tree.", node.input().name());
            flatTree.add(node);

            final List<ResolvedInputTreeValueAndChildren> resolvedValueAndChildren = node.valuesAndChildren();
            if (resolvedValueAndChildren.size() == 1) {
                // This node has a single value, so we can attempt to flatten its children
                final ResolvedInputTreeValueAndChildren singleValue = resolvedValueAndChildren.get(0);
                final List<ResolvedInputTreeNode<? extends Input>> children = singleValue.children();
                if (!(children == null || children.isEmpty())) {
                    log.debug("Input \"{}\" has a uniquely resolved value. Adding children.", node.input().name());
                    for (final ResolvedInputTreeNode<? extends Input> child : children) {
                        flatTree.addAll(flattenTree(child));
                    }
                    log.debug("Done adding children of input \"{}\".", node.input().name());
                } else {
                    log.debug("Input \"{}\" has a uniquely resolved value, but no children.", node.input().name());
                }
            } else {
                // This node has multiple values, so we can't flatten its children
                log.debug("Input \"{}\" does not have a uniquely resolved value. Not checking children.", node.input().name());
            }
            log.debug("Done adding input \"{}\" to flattened tree.", node.input().name());
            return flatTree;
        }

        @Nonnull
        private ResolvedInputValue resolveExternalWrapperInput(final CommandWrapperExternalInput input,
                                                               final Map<String, String> resolvedInputValuesByReplacementKey)
                throws CommandResolutionException, UnauthorizedException {
            log.info("Resolving input \"{}\".", input.name());

            XnatModelObject resolvedModelObject = null;
            String resolvedValue = null;

            // Give the input its default value
            log.debug("Default value: \"{}\".", input.defaultValue());
            if (input.defaultValue() != null) {
                log.debug("Setting resolved value to \"{}\".", input.defaultValue());
                resolvedValue = input.defaultValue();
            }

            // If a value was provided at runtime, use that over the default
            log.debug("Runtime value: \"{}\"", inputValues.get(input.name()));
            if (inputValues.containsKey(input.name()) && inputValues.get(input.name()) != null) {
                log.debug("Setting resolved value to \"{}\".", inputValues.get(input.name()));
                resolvedValue = inputValues.get(input.name());
            }

            // Check for JSONPath substring in input value
            log.debug("Checking resolved value for JSONPath substring.");
            final String resolvedValueAfterResolvingJsonpath = resolveJsonpathSubstring(resolvedValue);
            if (resolvedValue != null && !resolvedValue.equals(resolvedValueAfterResolvingJsonpath)) {
                log.debug("Setting resolved value to \"{}\".", resolvedValueAfterResolvingJsonpath);
                resolvedValue = resolvedValueAfterResolvingJsonpath;
            }

            // Resolve the matcher, if one was provided
            log.debug("Matcher: \"{}\".", input.matcher());
            final String resolvedMatcher = input.matcher() != null ? resolveTemplate(input.matcher(), resolvedInputValuesByReplacementKey) : null;
            log.debug("Resolved matcher: \"{}\".", resolvedMatcher);

            if (StringUtils.isNotBlank(resolvedValue)) {
                // Process the input based on its type
                log.debug("Processing input value as a {}.", input.type());

                final String type = input.type();
                if (type.equals(PROJECT.getName()) || type.equals(SUBJECT.getName()) || type.equals(SESSION.getName()) || type.equals(SCAN.getName())
                        || type.equals(ASSESSOR.getName()) || type.equals(RESOURCE.getName())) {

                    final XnatModelObject xnatModelObject;
                    final boolean preload = input.loadChildren();
                    try {
                        if (type.equals(PROJECT.getName())) {
                            xnatModelObject = resolveXnatObject(resolvedValue, resolvedMatcher,
                                    Project.class, Project.uriToModelObject(preload), Project.idToModelObject(userI, preload));
                        } else if (type.equals(SUBJECT.getName())) {
                            xnatModelObject = resolveXnatObject(resolvedValue, resolvedMatcher,
                                    Subject.class, Subject.uriToModelObject(), Subject.idToModelObject(userI));
                        } else if (type.equals(SESSION.getName())) {
                            xnatModelObject = resolveXnatObject(resolvedValue, resolvedMatcher,
                                    Session.class, Session.uriToModelObject(), Session.idToModelObject(userI));
                        } else if (type.equals(SCAN.getName())) {
                            xnatModelObject = resolveXnatObject(resolvedValue, resolvedMatcher,
                                    Scan.class, Scan.uriToModelObject(), Scan.idToModelObject(userI));
                        } else if (type.equals(ASSESSOR.getName())) {
                            xnatModelObject = resolveXnatObject(resolvedValue, resolvedMatcher,
                                    Assessor.class, Assessor.uriToModelObject(), Assessor.idToModelObject(userI));
                        } else {
                            xnatModelObject = resolveXnatObject(resolvedValue, resolvedMatcher,
                                    Resource.class, Resource.uriToModelObject(), Resource.idToModelObject(userI));
                        }
                    } catch (CommandInputResolutionException e) {
                        // When resolveXnatObject throws this, it does not have the input object in scope
                        // So we just add the input and throw a new exception with everything else the same.
                        throw new CommandInputResolutionException(e.getMessage(), input, e.getValue(), e.getCause());
                    }

                    if (xnatModelObject == null) {
                        log.debug("Could not instantiate XNAT object from value.");
                    } else {
                        resolvedModelObject = xnatModelObject;
                        final String resolvedXnatObjectUri = xnatModelObject.getUri();
                        if (resolvedXnatObjectUri != null) {
                            log.debug("Setting resolved value to \"{}\".", resolvedXnatObjectUri);
                            resolvedValue = resolvedXnatObjectUri;
                        }
                    }
                } else if (type.equals(CONFIG.getName())) {
                    final String[] configProps = resolvedValue != null ? resolvedValue.split("/") : null;
                    if (configProps == null || configProps.length != 2) {
                        log.debug("Config inputs must have a value that can be interpreted as a config_toolname/config_filename string. Input value: {}", resolvedValue);
                    }

                    final Scope configScope;
                    final String entityId;
                    // TODO Figure out how to resolve project config inputs vs sitewide
                    // final CommandInput.Type parentType = parent == null ? CommandInput.Type.STRING : parent.getType();
                    // switch (parentType) {
                    //     case PROJECT:
                    //         configScope = Scope.Project;
                    //         entityId = JsonPath.parse(getJsonValue(parent)).read("$.id");
                    //         break;
                    //     case SUBJECT:
                    //         // Intentional fallthrough
                    //     case SESSION:
                    //         // Intentional fallthrough
                    //     case SCAN:
                    //         // Intentional fallthrough
                    //     case ASSESSOR:
                    //         // TODO Is there any way to make this work? Can we find the project ID for these other input types?
                    //         //configScope = Scope.Project;
                    //         //final List<String> projectIds = JsonPath.parse(getJsonValue(parent)).read("$..projectId");
                    //         //entityId = (projectIds != null && !projectIds.isEmpty()) ? projectIds.get(0) : "";
                    //         //if (StringUtils.isBlank(entityId)) {
                    //         //    throw new CommandInputResolutionException("Could not determine project when resolving config value.", input);
                    //         //}
                    //         //break;
                    //         throw new XnatCommandInputResolutionException("Config inputs may only have parents of type Project.", input);
                    //     default:
                    //         configScope = Scope.Site;
                    //         entityId = null;
                    // }
                    //
                    // if (log.isDebugEnabled()) {
                    //     log.debug(String.format("Attempting to read config %s/%s from %s.", configProps[0], configProps[1],
                    //             configScope.equals(Scope.Site) ? "site" : "project " + entityId));
                    // }
                    // final String configContents = configService.getConfigContents(configProps[0], configProps[1], configScope, entityId);
                    // if (configContents == null) {
                    //     throw new XnatCommandInputResolutionException("Could not read config " + resolvedValue, input);
                    // }
                    //
                    // if (log.isDebugEnabled()) {
                    //     log.debug("Setting resolvedValue to config contents " + configContents);
                    // }
                    // resolvedValue = configContents;
                } else {
                    log.debug("Nothing to do for simple types.");
                }
            }

            log.info("Done resolving input \"{}\". Resolved value: \"{}\".", input.name(), resolvedValue);

            String jsonValue = resolvedValue;
            String valueLabel = resolvedValue;
            if (resolvedModelObject != null) {
                valueLabel = resolvedModelObject.getLabel();
                try {
                    jsonValue = mapper.writeValueAsString(resolvedModelObject);
                } catch (JsonProcessingException e) {
                    log.error("Could not serialize model object to json.", e);
                }
            }

            return ResolvedInputValue.builder()
                    .type(input.type())
                    .value(resolvedValue)
                    .valueLabel(valueLabel)
                    .xnatModelObject(resolvedModelObject)
                    .jsonValue(jsonValue)
                    .build();
        }

        @Nonnull
        private List<ResolvedInputValue> resolveDerivedWrapperInput(final CommandWrapperDerivedInput input,
                                                                    final @Nonnull ResolvedInputValue parent,
                                                                    final Map<String, String> resolvedInputValuesByReplacementKey)
                throws CommandResolutionException {
            log.info("Resolving input \"{}\".", input.name());

            // Resolve the matcher, if one was provided
            log.debug("Matcher: \"{}\".", input.matcher());
            final String resolvedMatcher = input.matcher() != null ? resolveTemplate(input.matcher(), resolvedInputValuesByReplacementKey) : null;

            // Process the input based on its type
            final String type = input.type();
            log.debug("Processing input value as a \"{}\".", type);

            // TODO move these initializations to wherever we use them
            final String defaultValue = input.defaultValue();
            final String runtimeValue = inputValues.get(input.name());
            final String valueCouldContainId = runtimeValue != null ? runtimeValue : defaultValue;

            final XnatModelObject parentXnatObject = parent.xnatModelObject();
            final String parentJson = parent.jsonValue();
            final String parentType = parent.type();

            final List<XnatModelObject> resolvedXnatObjects;
            final List<String> resolvedValues;

            if (type.equals(STRING.getName())) {
                final String propertyToGet = input.derivedFromXnatObjectProperty();

                if (StringUtils.isBlank(parentJson)) {
                    log.error("Cannot derive input \"{}\". Parent input's JSON representation is blank.", input.name());
                    resolvedXnatObjects = Collections.emptyList();
                    resolvedValues = Collections.emptyList();
                } else if (parentType.equals(PROJECT.getName()) || parentType.equals(SUBJECT.getName()) || parentType.equals(SESSION.getName()) ||
                        parentType.equals(SCAN.getName()) || parentType.equals(ASSESSOR.getName()) || parentType.equals(FILE.getName()) || parentType.equals(RESOURCE.getName())) {
                    final String parentValue = pullStringFromParentJson("$." + propertyToGet, resolvedMatcher, parentJson);
                    resolvedXnatObjects = null;
                    resolvedValues = parentValue != null ? Collections.singletonList(parentValue) : Collections.<String>emptyList();
                } else {
                    logIncompatibleTypes(input.type(), parentType);
                    resolvedXnatObjects = null;
                    resolvedValues = Collections.emptyList();
                }
            } else if (type.equals(BOOLEAN.getName())) {
                // TODO
                resolvedXnatObjects = null;
                resolvedValues = Collections.emptyList();
            } else if (type.equals(NUMBER.getName())) {
                // TODO
                resolvedXnatObjects = null;
                resolvedValues = Collections.emptyList();
            } else if (type.equals(DIRECTORY.getName())) {
                if (StringUtils.isBlank(parentJson)) {
                    log.error("Cannot derive input \"{}\". Parent input's JSON representation is blank.", input.name());
                    resolvedXnatObjects = Collections.emptyList();
                    resolvedValues = Collections.emptyList();
                } else if (parentType.equals(RESOURCE.getName())) {
                    final String parentValue = pullStringFromParentJson("$.directory", resolvedMatcher, parentJson);
                    resolvedXnatObjects = null;
                    resolvedValues = parentValue != null ? Collections.singletonList(parentValue) : Collections.<String>emptyList();
                    // TODO Need to store the root archive directory for these objects
                    // } else if (parentType.equals(PROJECT.getName()) || parentType.equals(SUBJECT.getName()) || parentType.equals(SESSION.getName()) ||
                    //         parentType.equals(SCAN.getName()) || parentType.equals(ASSESSOR.getName())) {
                } else {
                    logIncompatibleTypes(input.type(), parentType);
                    resolvedXnatObjects = null;
                    resolvedValues = Collections.emptyList();
                }
            } else if (type.equals(FILES.getName()) || type.equals(FILE.getName())) {
                if (StringUtils.isBlank(parentJson)) {
                    log.error("Cannot derive input \"{}\". Parent input's JSON representation is blank.", input.name());
                    resolvedXnatObjects = Collections.emptyList();
                    resolvedValues = Collections.emptyList();
                } else if (parentType.equals(RESOURCE.getName())) {
                    final List<XnatFile> files = matchChildFromParent(
                            parentJson,
                            valueCouldContainId,
                            "files",
                            "name",
                            resolvedMatcher,
                            new TypeRef<List<XnatFile>>() {});
                    if (files == null) {
                        resolvedXnatObjects = Collections.emptyList();
                        resolvedValues = Collections.emptyList();
                    } else {
                        resolvedXnatObjects = Lists.<XnatModelObject>newArrayList(files);
                        resolvedValues = Lists.newArrayList(Lists.transform(files,
                                new Function<XnatFile, String>() {
                                    @Override
                                    public String apply(final XnatFile xnatFile) {
                                        return xnatFile.getUri();
                                    }
                                }));
                    }
                } else {
                    logIncompatibleTypes(input.type(), parentType);
                    resolvedXnatObjects = null;
                    resolvedValues = Collections.emptyList();
                }
            } else if (type.equals(PROJECT.getName())) {
                if (parentXnatObject == null) {
                    log.error("Cannot derive input \"{}\". Parent input's XNAT object is null.", input.name());
                    resolvedXnatObjects = Collections.emptyList();
                    resolvedValues = Collections.emptyList();
                } else if (!(parentType.equals(SUBJECT.getName()) || parentType.equals(SESSION.getName())) ||
                        parentType.equals(SCAN.getName()) || parentType.equals(ASSESSOR.getName())) {
                    logIncompatibleTypes(input.type(), parentType);
                    resolvedXnatObjects = Collections.emptyList();
                    resolvedValues = Collections.emptyList();
                } else {
                    final Project project;
                    if (parentType.equals(SUBJECT.getName())) {
                        project = ((Subject)parentXnatObject).getProject(userI);
                    } else if (parentType.equals(SESSION.getName())) {
                        project = ((Session)parentXnatObject).getProject(userI);
                    } else if (parentType.equals(SCAN.getName())) {
                        project = ((Scan)parentXnatObject).getProject(userI);
                    } else {
                        project = ((Assessor)parentXnatObject).getProject(userI);
                    }
                    resolvedXnatObjects = Collections.<XnatModelObject>singletonList(project);
                    resolvedValues = Collections.singletonList(project.getUri());
                }
            } else if (type.equals(SUBJECT.getName())) {
                if (parentXnatObject == null) {
                    log.error("Cannot derive input \"{}\". Parent input's XNAT object is null.", input.name());
                    resolvedXnatObjects = Collections.emptyList();
                    resolvedValues = Collections.emptyList();
                } else if (!(parentType.equals(PROJECT.getName()) || parentType.equals(SESSION.getName()))) {
                    logIncompatibleTypes(input.type(), parentType);
                    resolvedXnatObjects = Collections.emptyList();
                    resolvedValues = Collections.emptyList();
                } else {
                    if (parentType.equals(PROJECT.getName())) {
                        final List<Subject> childList = matchChildFromParent(
                                parentJson,
                                valueCouldContainId,
                                "subjects",
                                "id",
                                resolvedMatcher,
                                new TypeRef<List<Subject>>() {});
                        if (childList == null) {
                            resolvedXnatObjects = Collections.emptyList();
                            resolvedValues = Collections.emptyList();
                        } else {
                            resolvedXnatObjects = Lists.<XnatModelObject>newArrayList(childList);
                            resolvedValues = Lists.newArrayList(Lists.transform(childList, new Function<Subject, String>() {
                                @Override
                                public String apply(final Subject subject) {
                                    return subject.getUri();
                                }
                            }));
                        }
                    } else {
                        // Parent is session
                        final Subject subject = ((Session)parentXnatObject).getSubject(userI);
                        resolvedXnatObjects = Collections.<XnatModelObject>singletonList(subject);
                        resolvedValues = Collections.singletonList(subject.getUri());
                    }
                }
            } else if (type.equals(SESSION.getName())) {
                if (parentXnatObject == null) {
                    log.error("Cannot derive input \"{}\". Parent input's XNAT object is null.", input.name());
                    resolvedXnatObjects = Collections.emptyList();
                    resolvedValues = Collections.emptyList();
                } else if (!(parentType.equals(SUBJECT.getName()) || parentType.equals(SCAN.getName()))) {
                    logIncompatibleTypes(input.type(), parentType);
                    resolvedXnatObjects = Collections.emptyList();
                    resolvedValues = Collections.emptyList();
                } else {
                    if (parentType.equals(SUBJECT.getName())) {
                        final List<Session> childList = matchChildFromParent(
                                parentJson,
                                valueCouldContainId,
                                "sessions",
                                "id",
                                resolvedMatcher,
                                new TypeRef<List<Session>>() {});
                        if (childList == null) {
                            resolvedXnatObjects = Collections.emptyList();
                            resolvedValues = Collections.emptyList();
                        } else {
                            resolvedXnatObjects = Lists.<XnatModelObject>newArrayList(childList);
                            resolvedValues = Lists.newArrayList(Lists.transform(childList, new Function<Session, String>() {
                                @Override
                                public String apply(final Session session) {
                                    return session.getUri();
                                }
                            }));
                        }
                    } else {
                        // Parent is scan
                        final Session session = ((Scan)parentXnatObject).getSession(userI);
                        resolvedXnatObjects = Collections.<XnatModelObject>singletonList(session);
                        resolvedValues = Collections.singletonList(session.getUri());
                    }
                }
            } else if (type.equals(SCAN.getName())) {
                if (parentXnatObject == null) {
                    log.error("Cannot derive input \"{}\". Parent input's XNAT object is null.", input.name());
                    resolvedXnatObjects = Collections.emptyList();
                    resolvedValues = Collections.emptyList();
                } else if (!(parentType.equals(SESSION.getName()))) {
                    logIncompatibleTypes(input.type(), parentType);
                    resolvedXnatObjects = Collections.emptyList();
                    resolvedValues = Collections.emptyList();
                } else {
                    final List<Scan> childList = matchChildFromParent(
                            parentJson,
                            valueCouldContainId,
                            "scans",
                            "id",
                            resolvedMatcher,
                            new TypeRef<List<Scan>>() {});
                    if (childList == null) {
                        resolvedXnatObjects = Collections.emptyList();
                        resolvedValues = Collections.emptyList();
                    } else {
                        resolvedXnatObjects = Lists.<XnatModelObject>newArrayList(childList);
                        resolvedValues = Lists.newArrayList(Lists.transform(childList, new Function<Scan, String>() {
                            @Override
                            public String apply(final Scan scan) {
                                return scan.getUri();
                            }
                        }));
                    }
                }
            } else if (type.equals(ASSESSOR.getName())) {
                if (parentXnatObject == null) {
                    log.error("Cannot derive input \"{}\". Parent input's XNAT object is null.", input.name());
                    resolvedXnatObjects = Collections.emptyList();
                    resolvedValues = Collections.emptyList();
                } else if (!(parentType.equals(SESSION.getName()))) {
                    logIncompatibleTypes(input.type(), parentType);
                    resolvedXnatObjects = Collections.emptyList();
                    resolvedValues = Collections.emptyList();
                } else {
                    final List<Assessor> childList = matchChildFromParent(
                            parentJson,
                            valueCouldContainId,
                            "assessors",
                            "id",
                            resolvedMatcher,
                            new TypeRef<List<Assessor>>() {});
                    if (childList == null) {
                        resolvedXnatObjects = Collections.emptyList();
                        resolvedValues = Collections.emptyList();
                    } else {
                        resolvedXnatObjects = Lists.<XnatModelObject>newArrayList(childList);
                        resolvedValues = Lists.newArrayList(Lists.transform(childList, new Function<Assessor, String>() {
                            @Override
                            public String apply(final Assessor assessor) {
                                return assessor.getUri();
                            }
                        }));
                    }
                }
            } else if (type.equals(RESOURCE.getName())) {
                if (parentXnatObject == null) {
                    log.error("Cannot derive input \"{}\". Parent input's XNAT object is null.", input.name());
                    resolvedXnatObjects = Collections.emptyList();
                    resolvedValues = Collections.emptyList();
                } else if (!(parentType.equals(PROJECT.getName()) || parentType.equals(SUBJECT.getName()) ||
                                parentType.equals(SESSION.getName()) || parentType.equals(SCAN.getName()) ||
                                parentType.equals(ASSESSOR.getName()))) {
                    logIncompatibleTypes(input.type(), parentType);
                    resolvedXnatObjects = Collections.emptyList();
                    resolvedValues = Collections.emptyList();
                } else {
                    // Try matching the value they gave us against the resource URI.
                    // That's what the UI will send.
                    List<Resource> childList = matchChildFromParent(
                            parentJson,
                            valueCouldContainId,
                            "resources",
                            "uri",
                            resolvedMatcher,
                            new TypeRef<List<Resource>>() {});
                    if (childList == null) {
                        // It is also possible that the value they gave us contains an ID
                        childList = matchChildFromParent(
                                parentJson,
                                valueCouldContainId,
                                "resources",
                                "id",
                                resolvedMatcher,
                                new TypeRef<List<Resource>>() {
                                });
                    }
                    if (childList == null) {
                        resolvedXnatObjects = Collections.emptyList();
                        resolvedValues = Collections.emptyList();
                    } else {
                        resolvedXnatObjects = Lists.<XnatModelObject>newArrayList(childList);
                        resolvedValues = Lists.newArrayList(Lists.transform(childList, new Function<Resource, String>() {
                            @Override
                            public String apply(final Resource resource) {
                                return resource.getUri();
                            }
                        }));
                    }
                }
            } else if (type.equals(CONFIG.getName())) {
                log.error("Config inputs are not yet supported.");
                resolvedXnatObjects = Collections.emptyList();
                resolvedValues = Collections.emptyList();
            } else {
                // This shouldn't be possible, but just in case.
                resolvedXnatObjects = Collections.emptyList();
                resolvedValues = Collections.emptyList();
            }

            log.info("Done resolving input \"{}\". Values: {}.", input.name(), resolvedValues);

            // Create a ResolvedInputValue object for each String resolvedValue
            final List<ResolvedInputValue> resolvedInputs = Lists.newArrayList();
            for (int i = 0; i < resolvedValues.size(); i++) {
                final String resolvedValue = resolvedValues.get(i);
                final XnatModelObject xnatModelObject = resolvedXnatObjects == null ? null : resolvedXnatObjects.get(i);
                String jsonValue = resolvedValue;
                String valueLabel = resolvedValue;
                if (xnatModelObject != null) {
                    valueLabel = xnatModelObject.getLabel();
                    try {
                        jsonValue = mapper.writeValueAsString(xnatModelObject);
                    } catch (JsonProcessingException e) {
                        log.error("Could not serialize model object to json.", e);
                    }
                }

                resolvedInputs.add(ResolvedInputValue.builder()
                        .type(input.type())
                        .value(resolvedValue)
                        .valueLabel(valueLabel)
                        .xnatModelObject(xnatModelObject)
                        .jsonValue(jsonValue)
                        .build());
            }

            return resolvedInputs;
        }

        @Nonnull
        private ResolvedInputValue resolveCommandInput(final CommandInput input,
                                                       final String providedValue,
                                                       final Map<String, String> resolvedInputValuesByReplacementKey)
                throws CommandResolutionException {
            log.info("Resolving command input \"{}\".", input.name());

            String resolvedValue = null;

            // Give the input its default value
            log.debug("Default value: \"{}\".", input.defaultValue());
            if (input.defaultValue() != null) {
                resolvedValue = input.defaultValue();
            }

            // If a value was provided at runtime, use that over the default
            log.debug("Runtime value: \"{}\"", inputValues.get(input.name()));
            if (inputValues.containsKey(input.name()) && inputValues.get(input.name()) != null) {
                log.debug("Setting resolved value to \"{}\".", inputValues.get(input.name()));
                resolvedValue = inputValues.get(input.name());
            }

            log.debug("Provided value: \"{}\".", providedValue);
            if (providedValue != null) {
                resolvedValue = providedValue;
            }

            // Check for JSONPath substring in input value
            resolvedValue = resolveJsonpathSubstring(resolvedValue);

            log.debug("Matcher: \"{}\".", input.matcher());
            final String resolvedMatcher = input.matcher() != null ? resolveTemplate(input.matcher(), resolvedInputValuesByReplacementKey) : null;
            // TODO apply matcher to input value

            final String type = input.type();
            log.debug("Processing input value as a {}.", type);
            if (type.equals(BOOLEAN.getName())) {
                // Parse the value as a boolean, and use the trueValue/falseValue
                // If those haven't been set, just pass the value through
                if (Boolean.parseBoolean(resolvedValue)) {
                    resolvedValue = input.trueValue() != null ? input.trueValue() : resolvedValue;
                } else {
                    resolvedValue = input.falseValue() != null ? input.falseValue() : resolvedValue;
                }
            } else if (type.equals(NUMBER.getName())) {
                // TODO
            } else {
                // TODO anything to do?
            }

            // If resolved value is null, and input is required, that is an error
            // if (resolvedValue == null && input.required()) {
            //     final String message = String.format("No value could be resolved for required input \"%s\".", input.name());
            //     log.debug(message);
            //     throw new CommandInputResolutionException(message, input);
            // }
            log.info("Done resolving input \"{}\". Value: \"{}\".", input.name(), resolvedValue);

            // Only substitute the input into the command line if a replacementKey is set
            // final String replacementKey = input.replacementKey();
            // if (StringUtils.isBlank(replacementKey)) {
            //     continue;
            // }
            // resolvedInputValuesByReplacementKey.put(replacementKey, resolvedValue);
            // resolvedInputCommandLineValuesByReplacementKey.put(replacementKey, getValueForCommandLine(input, resolvedValue));

            return ResolvedInputValue.builder()
                    .type(input.type())
                    .value(resolvedValue)
                    .valueLabel(resolvedValue)
                    .jsonValue(resolvedValue)
                    .build();
        }

        private void logIncompatibleTypes(final String inputType, final String parentType) {
            log.error("An input of type \"{}\" cannot be derived from an input of type \"{}\".",
                    inputType,
                    parentType);
        }

        private List<PreresolvedInputTreeNode<? extends Input>> initializePreresolvedInputTree() throws CommandResolutionException {
            log.debug("Initializing tree of wrapper input parent-child relationships.");
            final Map<String, PreresolvedInputTreeNode<? extends Input>> nodesThatProvideValueForCommandInputs = Maps.newHashMap();
            final Map<String, PreresolvedInputTreeNode<? extends Input>> nodesByName = Maps.newHashMap();
            final List<PreresolvedInputTreeNode<? extends Input>> rootNodes = Lists.newArrayList();
            for (final CommandWrapperExternalInput input : commandWrapper.externalInputs()) {
                // External inputs have no parents, so they are all root nodes
                final PreresolvedInputTreeNode<? extends Input> externalInputNode =
                        PreresolvedInputTreeNode.create(input);
                rootNodes.add(externalInputNode);
                nodesByName.put(input.name(), externalInputNode);

                // If this input provides a value for a command input, cache that now
                final String providesValueForCommandInput = input.providesValueForCommandInput();
                if (StringUtils.isNotBlank(providesValueForCommandInput)) {
                    nodesThatProvideValueForCommandInputs.put(providesValueForCommandInput, externalInputNode);
                }
            }
            for (final CommandWrapperDerivedInput input : commandWrapper.derivedInputs()) {
                // Derived inputs must have a non-blank parent name
                final String parentName = input.derivedFromXnatInput();
                if (StringUtils.isBlank(parentName)) {
                    // This is unlikely to happen. This should be caught by command validation.
                    final String message = String.format("Derived input \"%s\" needs a parent.", input);
                    log.error(message);
                    throw new CommandResolutionException(message);
                }

                // Make sure that we have already made a node for the parent.
                final PreresolvedInputTreeNode<? extends Input> parent = nodesByName.get(parentName);
                if (parent == null) {
                    // This is unlikely to happen. This should be caught by command validation.
                    final String message = String.format(
                            "Derived input \"%1$s\" claims parent \"%2$s\", but I couldn't find \"%2$s\". Are the inputs out of order?",
                            input, parentName);
                    log.error(message);
                    throw new CommandResolutionException(message);
                }

                final PreresolvedInputTreeNode<? extends Input> derivedInputNode =
                        PreresolvedInputTreeNode.create(input, parent);
                nodesByName.put(input.name(), derivedInputNode);

                // If this input provides a value for a command input, cache that now
                final String providesValueForCommandInput = input.providesValueForCommandInput();
                if (StringUtils.isNotBlank(providesValueForCommandInput)) {
                    nodesThatProvideValueForCommandInputs.put(providesValueForCommandInput, derivedInputNode);
                }
            }

            for (final CommandInput input : command.inputs()) {
                // Command inputs can be root nodes if no wrapper inputs provide values for them,
                // otherwise they are child nodes
                final PreresolvedInputTreeNode<? extends Input> commandInputNode;
                if (nodesThatProvideValueForCommandInputs.containsKey(input.name())) {
                    final PreresolvedInputTreeNode<? extends Input> parent = nodesThatProvideValueForCommandInputs.get(input.name());
                    commandInputNode = PreresolvedInputTreeNode.create(input, parent);
                } else {
                    commandInputNode = PreresolvedInputTreeNode.create(input);
                    rootNodes.add(commandInputNode);
                }
                nodesByName.put(input.name(), commandInputNode);
            }

            log.debug("Done initializing tree of wrapper input parent-child relationships.");
            return rootNodes;
        }

        @Nonnull
        private ResolvedInputTreeNode<? extends Input> resolveNode(final PreresolvedInputTreeNode<? extends Input> preresolvedInputNode,
                                                                   final @Nullable ResolvedInputValue parentValue,
                                                                   final Map<String, String> resolvedInputValuesByReplacementKey)
                throws CommandResolutionException, UnauthorizedException {
            if (log.isDebugEnabled()) {
                log.debug("Resolving input \"" + preresolvedInputNode.input().name() + "\"" +
                        (parentValue == null ? "" : " for parent value \"" + parentValue.value() + "\"") + ".");
            }
            final ResolvedInputTreeNode<? extends Input> thisNode =
                    ResolvedInputTreeNode.create(preresolvedInputNode);

            // Resolve a value for this node
            final List<ResolvedInputValue> resolvedInputValues;
            if (thisNode.input() instanceof CommandWrapperExternalInput) {
                resolvedInputValues = Collections.singletonList(
                        resolveExternalWrapperInput((CommandWrapperExternalInput)thisNode.input(),
                                resolvedInputValuesByReplacementKey)
                );
            } else if (thisNode.input() instanceof CommandWrapperDerivedInput) {
                if (parentValue == null) {
                    // This should never happen. We should only call this with null parent values for root nodes, never derived nodes
                    log.error("resolveNode called on derived input \"{}\" with null parent value.", preresolvedInputNode.input().name());
                    resolvedInputValues = Collections.emptyList();
                } else {
                    resolvedInputValues = resolveDerivedWrapperInput((CommandWrapperDerivedInput) thisNode.input(),
                            parentValue, resolvedInputValuesByReplacementKey);
                }
            } else {
                resolvedInputValues = Collections.singletonList(
                        resolveCommandInput((CommandInput) thisNode.input(),
                                parentValue != null ? parentValue.value() : null,
                                resolvedInputValuesByReplacementKey)
                );
            }


            // Recursively resolve values for child nodes, using each of this node's resolved values
            final List<ResolvedInputTreeValueAndChildren> resolvedValuesAndChildren = Lists.newArrayList();
            for (final ResolvedInputValue resolvedInputValue : resolvedInputValues) {
                if (preresolvedInputNode.children() != null && !preresolvedInputNode.children().isEmpty()) {
                    final List<ResolvedInputTreeNode<? extends Input>> resolvedChildNodes = Lists.newArrayList();

                    for (final PreresolvedInputTreeNode<? extends Input> child : preresolvedInputNode.children()) {
                        log.debug("Resolving input \"{}\" child \"{}\" using value \"{}\".",
                                thisNode.input().name(),
                                child.input().name(),
                                resolvedInputValue.value());

                        final Map<String, String> copyOfResolvedInputValuesByReplacementKey = Maps.newHashMap(resolvedInputValuesByReplacementKey);
                        copyOfResolvedInputValuesByReplacementKey.put(thisNode.input().replacementKey(), resolvedInputValue.value());
                        resolvedChildNodes.add(resolveNode(child, resolvedInputValue, copyOfResolvedInputValuesByReplacementKey));
                    }
                    resolvedValuesAndChildren.add(ResolvedInputTreeNode.ResolvedInputTreeValueAndChildren.create(resolvedInputValue, resolvedChildNodes));
                } else {
                    log.debug("Input \"{}\" (no children) has resolved value \"{}\".",
                            thisNode.input().name(),
                            resolvedInputValue.value());
                    resolvedValuesAndChildren.add(ResolvedInputTreeNode.ResolvedInputTreeValueAndChildren.create(resolvedInputValue));
                }
            }

            thisNode.valuesAndChildren().addAll(resolvedValuesAndChildren);
            log.debug("Done resolving node for input \"{}\".", preresolvedInputNode.input().name());
            return thisNode;
        }

        @Nonnull
        private Map<String, String> findUniqueResolvedValues(final ResolvedInputTreeNode<? extends Input> node) {
            // Collect any unique values into the resolvedValuesByReplacementKey map
            final Map<String, String> resolvedValuesByReplacementKey = Maps.newHashMap();

            final List<ResolvedInputTreeValueAndChildren> resolvedValueAndChildren = node.valuesAndChildren();
            if (resolvedValueAndChildren.size() == 1) {
                // This node has a single value, so we can add it to the map of resolved values by replacement key
                final ResolvedInputTreeValueAndChildren singleValue = resolvedValueAndChildren.get(0);
                log.debug("Input \"{}\" has a unique resolved value: \"{}\".",
                        node.input().name(), singleValue.resolvedValue().value());

                final String valueNotNull = singleValue.resolvedValue().value() == null ? "" : singleValue.resolvedValue().value();
                log.debug("Storing value \"{}\" by replacement key \"{}\".", valueNotNull, node.input().replacementKey());
                resolvedValuesByReplacementKey.put(node.input().replacementKey(), valueNotNull);

                // Recursively check if child values are unique, and bubble up their maps.
                final List<ResolvedInputTreeNode<? extends Input>> children = singleValue.children();
                if (children != null) {
                    for (final ResolvedInputTreeNode<? extends Input> child : children) {
                        log.debug("Checking child input \"{}\".", child.input().name());
                        resolvedValuesByReplacementKey.putAll(findUniqueResolvedValues(child));
                    }
                }
            } else {
                // This node has multiple values, so we can't add any uniquely resolved values to the map
                log.debug("Input \"{}\" does not have a uniquely resolved value.", node.input().name());
            }

            return resolvedValuesByReplacementKey;
        }

        @Nonnull
        private Map<String, String> findUniqueResolvedCommandLineValues(final ResolvedInputTreeNode<? extends Input> node) {
            // Collect any unique values into the resolvedValuesByReplacementKey map
            final Map<String, String> resolvedValuesByReplacementKey = Maps.newHashMap();

            final List<ResolvedInputTreeValueAndChildren> resolvedValueAndChildren = node.valuesAndChildren();
            if (resolvedValueAndChildren.size() == 1) {
                // This node has a single value, so we can add it to the map of resolved values by replacement key
                final ResolvedInputTreeValueAndChildren singleValue = resolvedValueAndChildren.get(0);
                log.debug("Input \"{}\" has a unique resolved value: \"{}\".",
                        node.input().name(), singleValue.resolvedValue().value());

                if (node.input() instanceof CommandInput) {
                    final String commandLineValue = getValueForCommandLine((CommandInput) node.input(), singleValue.resolvedValue().value());
                    log.debug("Storing command-line value \"{}\" by replacement key \"{}\".", commandLineValue, node.input().replacementKey());
                    resolvedValuesByReplacementKey.put(node.input().replacementKey(), commandLineValue);
                } else {
                    log.debug("Input \"{}\" is not a command input. Not getting command-line value.", node.input().name());
                }

                // Recursively check if child values are unique, and bubble up their maps.
                final List<ResolvedInputTreeNode<? extends Input>> children = singleValue.children();
                if (children != null) {
                    for (final ResolvedInputTreeNode<? extends Input> child : children) {
                        log.debug("Checking child input \"{}\".", child.input().name());
                        resolvedValuesByReplacementKey.putAll(findUniqueResolvedCommandLineValues(child));
                    }
                }
            } else {
                // This node has multiple values, so we can't add any uniquely resolved values to the map
                log.debug("Input \"{}\" does not have a uniquely resolved value.", node.input().name());
            }

            return resolvedValuesByReplacementKey;
        }

        @Nonnull
        private String getValueForCommandLine(final CommandInput input, final String resolvedInputValue) {
            log.debug("Resolving command-line value.");
            if (resolvedInputValue == null) {
                log.debug("Input value is null. Using value \"\" on the command line.");
                return "";
            }
            if (StringUtils.isBlank(input.commandLineFlag())) {
                log.debug("Input flag is null. Using value \"{}\" on the command line.", resolvedInputValue);
                return resolvedInputValue;
            } else {
                final String value = input.commandLineFlag() +
                        (input.commandLineSeparator() == null ? " " : input.commandLineSeparator()) +
                        resolvedInputValue;
                log.debug("Using value \"{}\" on the command line.", value);
                return value;
            }
        }

        private List<String> findMissingRequiredInputs(final List<ResolvedInputTreeNode<? extends Input>> resolvedInputTrees) {
            final List<String> missingRequiredInputNames = Lists.newArrayList();
            for (final ResolvedInputTreeNode<? extends Input> resolvedRootNode : resolvedInputTrees) {
                log.debug("Checking for missing required inputs in input tree starting with input \"{}\".", resolvedRootNode.input().name());
                missingRequiredInputNames.addAll(findMissingRequiredInputs(resolvedRootNode));
            }
            return missingRequiredInputNames;
        }

        private List<String> findMissingRequiredInputs(final ResolvedInputTreeNode<? extends Input> resolvedInputTreeNode) {
            final List<String> missingRequiredInputNames = Lists.newArrayList();

            final Input input = resolvedInputTreeNode.input();
            final List<ResolvedInputTreeValueAndChildren> valuesAndChildren = resolvedInputTreeNode.valuesAndChildren();

            boolean hasNonNullValue = false;
            for (final ResolvedInputTreeValueAndChildren valueAndChildren : valuesAndChildren) {
                hasNonNullValue = hasNonNullValue || valueAndChildren.resolvedValue().value() != null;

                // While we're looping, check the children as well.
                for (final ResolvedInputTreeNode<? extends Input> child : valueAndChildren.children()) {
                    log.debug("Checking child input \"{}\".", child.input().name());
                    missingRequiredInputNames.addAll(findMissingRequiredInputs(child));
                }
            }

            if (input.required()) {
                if (hasNonNullValue) {
                    log.debug("Input \"{}\" is required and has a non-null value.", input.name());
                } else {
                    log.debug("Input \"{}\" is required and has a null value. Adding to the list.", input.name());
                    missingRequiredInputNames.add(input.name());
                }
            } else {
                log.debug("Input \"{}\" is not required.", input.name());
            }

            return missingRequiredInputNames;
        }

        @Nullable
        private String pullStringFromParentJson(final @Nonnull String rootJsonPathSearch,
                                                final String resolvedMatcher,
                                                final String parentJson) {
            final String jsonPathSearch = rootJsonPathSearch +
                    (StringUtils.isNotBlank(resolvedMatcher) ? "[?(" + resolvedMatcher + ")]" : "");
            if (log.isInfoEnabled()) {
                log.info(String.format("Attempting to pull value from parent using matcher \"%s\".", jsonPathSearch));
            }

            return jsonPathSearch(parentJson, jsonPathSearch, new TypeRef<String>() {});
        }

        @Nullable
        private <T> T jsonPathSearch(final String parentJson,
                                     final String jsonPathSearch,
                                     final TypeRef<T> typeRef) {
            try {
                return JsonPath.parse(parentJson).read(jsonPathSearch, typeRef);
            } catch (InvalidPathException | InvalidJsonException | MappingException e) {
                log.error(String.format("Error searching through json with search string \"%s\".", jsonPathSearch), e);
                log.debug("json: {}", parentJson);
            }
            return null;
        }

        @Nullable
        private <T extends XnatModelObject> List<T> matchChildFromParent(final String parentJson,
                                                                         final String value,
                                                                         final String childKey,
                                                                         final String valueMatchProperty,
                                                                         final String matcherFromInput,
                                                                         final TypeRef<List<T>> typeRef) {
            final String matcherFromValue = StringUtils.isNotBlank(value) ?
                    String.format("@.%s == '%s'", valueMatchProperty, value) :
                    "";
            final boolean hasValueMatcher = StringUtils.isNotBlank(matcherFromValue);
            final boolean hasInputMatcher = StringUtils.isNotBlank(matcherFromInput);
            final String fullMatcher;
            if (hasValueMatcher && hasInputMatcher) {
                fullMatcher = matcherFromValue + " && " + matcherFromInput;
            } else if (hasValueMatcher) {
                fullMatcher = matcherFromValue;
            } else if (hasInputMatcher) {
                fullMatcher = matcherFromInput;
            } else {
                fullMatcher = "";
            }

            final String jsonPathSearch = String.format(
                    "$.%s[%s]",
                    childKey,
                    StringUtils.isNotBlank(fullMatcher) ? "?(" + fullMatcher + ")" : "*"
            );

            log.info("Attempting to pull value from parent using matcher \"{}\".", jsonPathSearch);

            return jsonPathSearch(parentJson, jsonPathSearch, typeRef);
        }

        @Nullable
        private <T extends XnatModelObject> T resolveXnatObject(final @Nonnull String value,
                                                                final @Nullable String matcher,
                                                                final @Nonnull Class<T> model,
                                                                final @Nonnull Function<ArchiveItemURI, T> uriToModelObject,
                                                                final @Nullable Function<String, T> idToModelObject)
                throws CommandInputResolutionException, UnauthorizedException {
            final String modelName = model.getSimpleName();

            if (StringUtils.isBlank(value)) {
                log.debug("Not attempting to resolve a {} from blank value.", modelName);
                return null;
            }

            log.info("Resolving {} from value.", modelName);
            log.debug("Value: \"{}\"", value);

            T newModelObject = null;
            if (value.startsWith("/")) {
                log.debug("Attempting to initialize a {} using value as URI.", modelName);

                URIManager.DataURIA uri = null;
                try {
                    uri = UriParserUtils.parseURI(value.startsWith("/archive") ? value : "/archive" + value);
                } catch (MalformedURLException ignored) {
                    // ignored
                }

                if (uri == null || !(uri instanceof ArchiveItemURI)) {
                    log.debug("Cannot interpret \"{}\" as a URI.", value);
                } else {
                    try {
                        newModelObject = uriToModelObject.apply((ArchiveItemURI) uri);
                    } catch (Throwable e) {
                        final String message = String.format("Could not instantiate %s with URI %s.", modelName, value);
                        log.error(message);
                        throw new CommandInputResolutionException(message, value);
                    }

                    // TODO This is a workaround for CS-263 and XXX-55. Once XXX-55 is fixed, this can (hopefully) be removed.
                    try {
                        if (!Permissions.canRead(userI, ((ArchiveItemURI) uri).getSecurityItem())) {
                            final String message = String.format("User does not have permission to read %s with URI %s.", modelName, value);
                            log.error(message);
                            throw new UnauthorizedException(message);
                        }
                    } catch (UnauthorizedException e) {
                        throw e;
                    } catch (Exception e) {  // Need to catch this here because Permissions.canRead() can throw whatever
                        final String message = String.format("Could not verify read permissions for user %s with URI %s.", userI.getLogin(), value);
                        log.error(message);
                        throw new CommandInputResolutionException(message, value);
                    }
                }

            } else if (value.startsWith("{")) {
                try {
                    log.debug("Attempting to deserialize {} from value as JSON.", modelName);
                    newModelObject = mapper.readValue(value, model);
                } catch (IOException e) {
                    log.debug("Could not deserialize {} from value as JSON.", modelName);
                }
            } else if (idToModelObject != null) {
                log.info("Attempting to initialize a {} using value as ID string.", modelName);
                newModelObject = idToModelObject.apply(value);
            }

            if (newModelObject == null) {
                return null;
            }

            T aMatch = null;
            if (StringUtils.isNotBlank(matcher)) {
                // To apply the JSONPath matcher, we have to serialize our object to JSON.
                String newModelObjectJson = null;
                try {
                    newModelObjectJson = mapper.writeValueAsString(newModelObject);
                } catch (JsonProcessingException ignored) {
                    // ignored
                }

                if (StringUtils.isBlank(newModelObjectJson)) {
                    log.debug("Could not serialize object to JSON: {}", newModelObject);
                } else {
                    // We have our JSON-serialized object. Now we can apply the matcher.
                    final List<T> doMatch;
                    final String jsonPathSearch = String.format(
                            "$[?(%s)]", matcher
                    );

                    log.debug("Using JSONPath matcher \"{}\" to search for matching items.", jsonPathSearch);
                    doMatch = JsonPath.parse(newModelObjectJson).read(jsonPathSearch, new TypeRef<List<T>>() {});

                    if (doMatch != null && !doMatch.isEmpty()) {
                        // We found a match!
                        // The JSONPath search syntax we used will always return a list. But we know that,
                        // since we started with one serialized object, we will only get back a list with
                        // that one object in it.
                        aMatch = doMatch.get(0);
                    } else {
                        log.debug("Object did not match matcher \"{}\".", modelName, matcher);
                    }
                }
            } else {
                // We have no matcher, so any object we have is a match
                aMatch = newModelObject;
            }

            if (aMatch == null) {
                log.info("Failed to instantiate matching {}.", modelName);
                return null;
            } else {
                log.info("Successfully instantiated matching {}.", modelName);
                log.debug("Match: {}", aMatch);
                return aMatch;
            }
        }

        @Nonnull
        private List<ResolvedCommandOutput> resolveOutputs(final List<ResolvedInputTreeNode<? extends Input>> resolvedInputTrees,
                                                           final Map<String, String> resolvedInputValuesByReplacementKey)
                throws CommandResolutionException {
            log.info("Resolving command outputs.");
            final List<ResolvedCommandOutput> resolvedOutputs = Lists.newArrayList();
            if (command.outputs() == null) {
                return resolvedOutputs;
            }

            final Map<String, CommandWrapperOutput> xnatCommandOutputsByCommandOutputName = Maps.newHashMap();
            if (commandWrapper.outputHandlers() != null) {
                for (final CommandWrapperOutput commandWrapperOutput : commandWrapper.outputHandlers()) {
                    xnatCommandOutputsByCommandOutputName.put(commandWrapperOutput.commandOutputName(), commandWrapperOutput);
                }
            }

            for (final CommandOutput commandOutput : command.outputs()) {
                log.info("Resolving command output \"{}\".", commandOutput.name());
                log.debug("{}", commandOutput);

                // TODO fix this in validation
                final CommandWrapperOutput commandOutputHandler = xnatCommandOutputsByCommandOutputName.get(commandOutput.name());
                if (commandOutputHandler == null) {
                    throw new CommandResolutionException(String.format("No wrapper output handler was configured to handle command output \"%s\".", commandOutput.name()));
                }
                log.debug("Found Output Handler \"{}\" for Command output \"{}\".", commandOutputHandler.name(), commandOutput.name());

                // Fail fast: if we will not be able to create the output, either throw or log that now and don't try later.
                // First check that the handler input has a unique value
                final ResolvedInputValue parentInputResolvedValue = getInputValueByName(commandOutputHandler.xnatInputName(), resolvedInputTrees);
                if (parentInputResolvedValue == null) {
                    final String message = String.format("Cannot resolve output \"%s\". " +
                                    "Input \"%s\" is supposed to handle the output, but it does not have a uniquely resolved value. " +
                                    "Either there is no value, or there are multiple values." +
                                    "(We can't loop over input values yet, so the latter is an error as much as the former.)",
                            commandOutput.name(), commandOutputHandler.xnatInputName());
                    if (Boolean.TRUE.equals(commandOutput.required())) {
                        throw new CommandResolutionException(message);
                    } else {
                        log.error("Skipping output \"{}\".", commandOutput.name());
                        log.error(message);
                        continue;
                    }
                }

                // Next check that the handler input's value is an XNAT object
                final String parentValue = parentInputResolvedValue.value() != null ? parentInputResolvedValue.value() : "";
                URIManager.DataURIA uri = null;
                try {
                    uri = UriParserUtils.parseURI(parentValue.startsWith("/archive") ? parentValue : "/archive" + parentValue);
                } catch (MalformedURLException ignored) {
                    // ignored
                }

                if (uri == null || !(uri instanceof ArchiveItemURI)) {
                    final String message = String.format("Cannot resolve output \"%s\". " +
                                    "Input \"%s\" is supposed to handle the output, but it does not have an XNAT object value.",
                            commandOutput.name(), commandOutputHandler.xnatInputName());
                    if (Boolean.TRUE.equals(commandOutput.required())) {
                        throw new CommandResolutionException(message);
                    } else {
                        log.error("Skipping output \"{}\".", commandOutput.name());
                        log.error(message);
                        continue;
                    }
                }

                // Next check that the user has edit permissions on the handler input's XNAT object
                final URIManager.ArchiveItemURI resourceURI = (URIManager.ArchiveItemURI) uri;
                final ArchivableItem item = resourceURI.getSecurityItem();
                boolean canEdit;
                try {
                    canEdit = Permissions.canEdit(userI, item);
                } catch (Exception ignored) {
                    canEdit = false;
                }
                if (!canEdit) {
                    final String message = String.format("Cannot resolve output \"%s\". " +
                                    "Input \"%s\" is supposed to handle the output, but user \"%s\" does not have permission " +
                                    "to edit the XNAT object \"%s\".",
                            commandOutput.name(), commandOutputHandler.xnatInputName(),
                            userI.getLogin(), parentValue);
                    if (Boolean.TRUE.equals(commandOutput.required())) {
                        throw new CommandResolutionException(message);
                    } else {
                        log.error("Skipping output \"{}\".", commandOutput.name());
                        log.error(message);
                        continue;
                    }
                }

                final ResolvedCommandOutput resolvedOutput = ResolvedCommandOutput.builder()
                        .name(commandOutput.name())
                        .required(commandOutput.required())
                        .mount(commandOutput.mount())
                        .glob(commandOutput.glob())
                        .type(commandOutputHandler.type())
                        .handledByXnatCommandInput(commandOutputHandler.xnatInputName())
                        .path(resolveTemplate(commandOutput.path(), resolvedInputValuesByReplacementKey))
                        .label(resolveTemplate(commandOutputHandler.label(), resolvedInputValuesByReplacementKey))
                        .build();

                log.debug("Adding resolved output \"{}\" to resolved command.", resolvedOutput.name());

                resolvedOutputs.add(resolvedOutput);
            }

            log.info("Done resolving command outputs.");
            if (log.isDebugEnabled()) {
                String message = "Outputs: ";
                if (resolvedOutputs.size() >= 2) {
                    message += "\n";
                }
                message += resolvedOutputs;
                log.debug(message);
            }
            return resolvedOutputs;
        }

        @Nonnull
        private String resolveCommandLine(final @Nonnull List<ResolvedInputTreeNode<? extends Input>> resolvedInputTrees)
                throws CommandResolutionException {
            log.info("Resolving command-line string.");

            // Look through the input tree, and find any command inputs that have uniquely resolved values
            final Map<String, String> resolvedInputCommandLineValuesByReplacementKey = Maps.newHashMap();
            for (final ResolvedInputTreeNode<? extends Input> node : resolvedInputTrees) {
                log.debug("Finding command-line values for input tree with root \"{}\".", node.input().name());
                resolvedInputCommandLineValuesByReplacementKey.putAll(findUniqueResolvedCommandLineValues(node));
                log.debug("Done finding command-line values for input tree with root \"{}\".", node.input().name());
            }

            // Resolve the command-line string using the resolved command-line values
            log.debug("Using resolved command-line values to resolve command-line template string.");
            final String resolvedCommandLine = resolveTemplate(command.commandLine(), resolvedInputCommandLineValuesByReplacementKey);

            log.info("Done resolving command-line string.");
            log.debug("Command-line string: {}", resolvedCommandLine);
            return resolvedCommandLine;
        }

        @Nonnull
        private Map<String, String> resolveEnvironmentVariables(final Map<String, String> resolvedInputValuesByReplacementKey)
                throws CommandResolutionException {
            log.info("Resolving environment variables.");

            final Map<String, String> resolvedMap = Maps.newHashMap();
            final Map<String, String> envTemplates = command.environmentVariables();
            if (envTemplates == null || envTemplates.isEmpty()) {
                log.info("No environment variables to resolve.");
                return resolvedMap;
            }

            resolvedMap.putAll(resolveTemplateMap(envTemplates, resolvedInputValuesByReplacementKey));

            log.info("Done resolving environment variables.");
            if (log.isDebugEnabled()) {
                log.debug(mapDebugString("Environment variables: ", resolvedMap));
            }
            return resolvedMap;
        }

        @Nonnull
        private String resolveWorkingDirectory(final Map<String, String> resolvedInputValuesByReplacementKey)
                throws CommandResolutionException {
            return resolveTemplate(command.workingDirectory(), resolvedInputValuesByReplacementKey);
        }

        @Nonnull
        private Map<String, String> resolvePorts(final Map<String, String> resolvedInputValuesByReplacementKey)
                throws CommandResolutionException {
            log.info("Resolving ports.");

            final Map<String, String> resolvedMap = Maps.newHashMap();
            final Map<String, String> portTemplates = command.ports();
            if (portTemplates == null || portTemplates.isEmpty()) {
                log.info("No ports to resolve.");
                return resolvedMap;
            }

            resolvedMap.putAll(resolveTemplateMap(portTemplates, resolvedInputValuesByReplacementKey));

            log.info("Done resolving ports.");
            if (log.isDebugEnabled()) {
                log.debug(mapDebugString("Ports: ", resolvedMap));
            }
            return resolvedMap;
        }

        private String mapDebugString(final String title, final Map<String, String> map) {
            final StringBuilder messageBuilder = new StringBuilder(title);
            for (Map.Entry<String, String> entry : map.entrySet()) {
                messageBuilder.append(entry.getKey());
                messageBuilder.append(": ");
                messageBuilder.append(entry.getValue());
                messageBuilder.append(", ");
            }
            return messageBuilder.substring(0, messageBuilder.length() - 2);
        }

        @Nonnull
        private Map<String, String> resolveTemplateMap(final Map<String, String> templateMap,
                                                       final Map<String, String> resolvedInputValuesByReplacementKey)
                throws CommandResolutionException {
            final Map<String, String> resolvedMap = Maps.newHashMap();
            if (templateMap == null || templateMap.isEmpty()) {
                return resolvedMap;
            }
            for (final Map.Entry<String, String> templateEntry : templateMap.entrySet()) {
                final String resolvedKey = resolveTemplate(templateEntry.getKey(), resolvedInputValuesByReplacementKey);
                final String resolvedValue = resolveTemplate(templateEntry.getValue(), resolvedInputValuesByReplacementKey);
                resolvedMap.put(resolvedKey, resolvedValue);
                if (!templateEntry.getKey().equals(resolvedKey) || !templateEntry.getValue().equals(resolvedValue)) {
                    if (log.isDebugEnabled()) {
                        final String message = String.format("Map %s: %s -> %s: %s",
                                templateEntry.getKey(), templateEntry.getValue(),
                                resolvedKey, resolvedValue);
                        log.debug(message);
                    }
                }
            }
            return resolvedMap;
        }

        @Nonnull
        private List<ResolvedCommandMount> resolveCommandMounts(final @Nonnull List<ResolvedInputTreeNode<? extends Input>> resolvedInputTrees,
                                                                final @Nonnull Map<String, String> resolvedInputValuesByReplacementKey)
                throws CommandResolutionException {
            log.info("Resolving mounts.");
            final List<CommandMount> commandMounts = command.mounts();
            if (commandMounts == null || commandMounts.isEmpty()) {
                log.info("No mounts.");
                return Lists.newArrayList();
            }

            log.debug("Search input trees to find inputs that provide files to mounts.");
            Map<String, List<ResolvedInputTreeNode<? extends Input>>> mountSourceInputs = Maps.newHashMap();
            for (final ResolvedInputTreeNode<? extends Input> rootNode : resolvedInputTrees) {
                mountSourceInputs = combineMaps(mountSourceInputs, findMountSourceInputs(rootNode));
            }

            final List<ResolvedCommandMount> resolvedMounts = Lists.newArrayList();
            for (final CommandMount commandMount : commandMounts) {
                resolvedMounts.add(
                        resolveCommandMount(
                                commandMount,
                                mountSourceInputs.get(commandMount.name()),
                                resolvedInputValuesByReplacementKey
                        )
                );
            }

            log.info("Done resolving mounts.");
            if (log.isDebugEnabled()) {
                for (final ResolvedCommandMount mount : resolvedMounts) {
                    log.debug(mount.toString());
                }
            }
            return resolvedMounts;
        }

        @Nonnull
        private Map<String, List<ResolvedInputTreeNode<? extends Input>>> findMountSourceInputs(final ResolvedInputTreeNode<? extends Input> node) {
            Map<String, List<ResolvedInputTreeNode<? extends Input>>> mountSourceInputs = Maps.newHashMap();

            final Input input = node.input();
            log.debug("Checking if input \"{}\" provides files to a mount.", input.name());
            if (input instanceof CommandWrapperInput) {
                final CommandWrapperInput commandWrapperInput = (CommandWrapperInput) input;
                if (StringUtils.isNotBlank(commandWrapperInput.providesFilesForCommandMount())) {
                    log.debug("Input \"{}\" provides files to mount \"{}\".",
                            input.name(), commandWrapperInput.providesFilesForCommandMount());
                    mountSourceInputs.put(commandWrapperInput.providesFilesForCommandMount(),
                            Lists.<ResolvedInputTreeNode<? extends Input>>newArrayList(node));
                } else {
                    log.debug("Input \"{}\" does not provide files to mounts.", input.name());
                }
            } else {
                log.debug("Input \"{}\" is a command input, and cannot provide files to mounts.", input.name());
            }

            if (node.valuesAndChildren() != null && node.valuesAndChildren().size() == 1) {
                log.debug("Input \"{}\" has a unique value. Checking children.", input.name());
                final ResolvedInputTreeValueAndChildren singleValue = node.valuesAndChildren().get(0);
                if (singleValue.children() == null || singleValue.children().isEmpty()) {
                    log.debug("Input \"{}\" has no children.", input.name());
                } else {
                    for (final ResolvedInputTreeNode<? extends Input> child : singleValue.children()) {
                        final Map<String, List<ResolvedInputTreeNode<? extends Input>>> childMountSourceInputs = findMountSourceInputs(child);
                        if (childMountSourceInputs.size() > 0) {
                            log.debug("Input \"{}\" has child \"{}\" that provides files to mounts. Combining mount lists.",
                                    input.name(), child.input().name());
                            mountSourceInputs = combineMaps(mountSourceInputs, childMountSourceInputs);
                        }
                    }
                }
            }
            log.debug("Done checking input \"{}\".", input.name());
            return mountSourceInputs;
        }

        @Nonnull
        private Map<String, List<ResolvedInputTreeNode<? extends Input>>> combineMaps(final @Nonnull Map<String, List<ResolvedInputTreeNode<? extends Input>>> sourceMap,
                                                                                              final @Nonnull Map<String, List<ResolvedInputTreeNode<? extends Input>>> mapToAdd) {
            for (final Map.Entry<String, List<ResolvedInputTreeNode<? extends Input>>> entryToAdd : mapToAdd.entrySet()) {
                if (sourceMap.containsKey(entryToAdd.getKey())) {
                    sourceMap.get(entryToAdd.getKey()).addAll(entryToAdd.getValue());
                } else {
                    sourceMap.put(entryToAdd.getKey(), entryToAdd.getValue());
                }
            }
            return sourceMap;
        }

        @Nonnull
        private ResolvedCommandMount resolveCommandMount(final @Nonnull CommandMount commandMount,
                                                         final @Nullable List<ResolvedInputTreeNode<? extends Input>> resolvedSourceInputs,
                                                         final @Nonnull Map<String, String> resolvedInputValuesByReplacementKey)
                throws CommandResolutionException {
            log.debug("Resolving command mount \"{}\".", commandMount.name());

            final PartiallyResolvedCommandMount.Builder partiallyResolvedMountBuilder = PartiallyResolvedCommandMount.builder()
                    .name(commandMount.name())
                    .writable(commandMount.writable())
                    .containerPath(resolveTemplate(commandMount.path(), resolvedInputValuesByReplacementKey));

            if (resolvedSourceInputs == null || resolvedSourceInputs.isEmpty()) {
                log.debug("Command mount \"{}\" has no inputs that provide it files. Assuming it is an output mount.", commandMount.name());
                partiallyResolvedMountBuilder.writable(true);
            } else {
                for (final ResolvedInputTreeNode<? extends Input> sourceInput : resolvedSourceInputs) {
                    final String inputName = sourceInput.input().name();
                    final String inputType = sourceInput.input().type();
                    log.debug("Mount \"{}\" has source input \"{}\" with type \"{}\".",
                            commandMount.name(),
                            inputName,
                            inputType);

                    final List<ResolvedInputTreeValueAndChildren> valuesAndChildren = sourceInput.valuesAndChildren();
                    if (valuesAndChildren.size() > 1) {
                        log.debug("Input \"{}\" has multiple resolved values. Adding them all to the mount. (This may be a bad idea.)");
                    }
                    for (final ResolvedInputTreeValueAndChildren resolvedInputTreeValueAndChildren : valuesAndChildren) {
                        final ResolvedInputValue resolvedInputValue = resolvedInputTreeValueAndChildren.resolvedValue();

                        String rootDirectory = null;
                        String uri = null;
                        if (inputType.equals(DIRECTORY.getName())) {
                            // TODO
                        } else if (inputType.equals(FILES.getName())) {
                            // TODO
                        } else if (inputType.equals(FILE.getName())) {
                            // TODO
                        } else if (inputType.equals(PROJECT.getName()) || inputType.equals(SESSION.getName()) || inputType.equals(SCAN.getName())
                                || inputType.equals(ASSESSOR.getName()) || inputType.equals(RESOURCE.getName())) {
                            log.debug("Looking for directory on source input.");
                            final XnatModelObject xnatModelObject = resolvedInputValue.xnatModelObject();
                            if (xnatModelObject == null) {
                                final String message = "Cannot resolve mount URI. Resolved XnatModelObject is null.";
                                log.error(message);
                                throw new CommandResolutionException(message);
                            }

                            rootDirectory = JsonPath.parse(resolvedInputValue.jsonValue()).read("directory", String.class);
                            uri = xnatModelObject.getUri();

                        } else {
                            final String message = String.format("I don't know how to provide files to a mount from an input of type \"%s\".", inputType);
                            log.error(message);
                        }


                        if (StringUtils.isBlank(rootDirectory)) {
                            String message = "Source input has no directory.";
                            if (log.isDebugEnabled()) {
                                message += "\ninput: " + sourceInput;
                            }
                            log.error(message);
                        }

                        log.debug("Done resolving mount \"{}\", source input \"{}\".",
                                commandMount.name(),
                                inputName);
                        partiallyResolvedMountBuilder.addInputFiles(
                                ResolvedCommandMountFiles.create(inputName, uri, rootDirectory, null)
                        );
                    }
                }

            }

            final ResolvedCommandMount resolvedCommandMount = transportMount(partiallyResolvedMountBuilder.build());

            log.debug("Done resolving command mount \"{}\".", commandMount.name());
            return resolvedCommandMount;
        }

        private ResolvedCommandMount transportMount(final PartiallyResolvedCommandMount partiallyResolvedCommandMount) throws ContainerMountResolutionException {
            final ResolvedCommandMount.Builder resolvedCommandMountBuilder = partiallyResolvedCommandMount.toResolvedCommandMountBuilder();

            // First, figure out what we have.
            // Do we have source files? A source directory?
            // Can we mount a directory directly, or should we copy the contents to a build directory?
            final List<ResolvedCommandMountFiles> filesList = partiallyResolvedCommandMount.inputFiles();
            final String localDirectory;
            if (filesList != null && filesList.size() > 1) {
                // We have multiple sources of files. We must copy them into one common location to mount.
                localDirectory = getBuildDirectory(partiallyResolvedCommandMount);
                log.debug("Mount \"{}\" has multiple sources of files.", partiallyResolvedCommandMount.name());

                // TODO figure out what to do with multiple sources of files
                log.debug("TODO");
            } else if (filesList != null && filesList.size() == 1) {
                // We have one source of files. We may need to copy, or may be able to mount directly.
                final ResolvedCommandMountFiles files = filesList.get(0);
                final String path = files.path();
                final boolean hasPath = StringUtils.isNotBlank(path);

                if (StringUtils.isNotBlank(files.rootDirectory())) {
                    // That source of files does have a directory set.

                    if (hasPath || partiallyResolvedCommandMount.writable()) {
                        // In both of these conditions, we must copy some things to a build directory.
                        // Now we must find out what.
                        if (hasPath) {
                            // The source of files also has one or more paths set

                            localDirectory = getBuildDirectory(partiallyResolvedCommandMount);
                            log.debug("Mount \"{}\" has a root directory and a file. Copying the file from the root directory to build directory.", partiallyResolvedCommandMount.name());

                            // TODO CS-54 copy the file in "path", relative to the root directory, to the build directory
                            log.debug("TODO");
                        } else {
                            // The mount is set to "writable".
                            localDirectory = getBuildDirectory(partiallyResolvedCommandMount);
                            log.debug("Mount \"{}\" has a root directory, and is set to \"writable\". Copying all files from the root directory to build directory.", partiallyResolvedCommandMount.name());

                            // TODO CS-54 We must copy all files out of the root directory to a build directory.
                            log.debug("TODO");
                        }
                    } else {
                        // The source of files can be directly mounted
                        log.debug("Mount \"{}\" has a root directory, and is not set to \"writable\". The root directory can be mounted directly into the container.", partiallyResolvedCommandMount.name());
                        localDirectory = files.rootDirectory();
                    }
                } else if (hasPath) {
                    log.debug("Mount \"{}\" has a file. Copying it to build directory.", partiallyResolvedCommandMount.name());
                    localDirectory = getBuildDirectory(partiallyResolvedCommandMount);
                    // TODO CS-54 copy the file to the build directory
                    log.debug("TODO");

                } else {
                    final String message = String.format("Mount \"%s\" should have a file path or a directory or both but it does not.", partiallyResolvedCommandMount.name());
                    log.error(message);
                    throw new ContainerMountResolutionException(message, partiallyResolvedCommandMount);
                }

            } else {
                log.debug("Mount \"{}\" has no input files. Ensuring mount is set to \"writable\" and creating new build directory.", partiallyResolvedCommandMount.name());
                localDirectory = getBuildDirectory(partiallyResolvedCommandMount);
                if (!partiallyResolvedCommandMount.writable()) {
                    resolvedCommandMountBuilder.writable(true);
                }
            }

            log.debug("Setting mount \"{}\" xnat host path to \"{}\".", partiallyResolvedCommandMount.name(), localDirectory);
            resolvedCommandMountBuilder.xnatHostPath(localDirectory);

            log.debug("Transporting mount \"{}\".", partiallyResolvedCommandMount.name());
            // final Path pathOnContainerHost = transportService.transport(containerHost, Paths.get(buildDirectory));
            // TODO transporting is currently a no-op, and the code is simpler if we don't pretend that we are doing something here.
            final String containerHostPath = localDirectory;

            log.debug("Setting mount \"{}\" container host path to \"{}\".", partiallyResolvedCommandMount.name(), localDirectory);
            resolvedCommandMountBuilder.containerHostPath(containerHostPath);

            return resolvedCommandMountBuilder.build();
        }

        /**
         * Resolves a templated string by replacing its template substrings.
         *
         * Many fields in the command definition may contain templated strings. These
         * strings are allowed to contain placeholder values, which are intended to be replaced
         * by real values at resolution time.
         *
         * A templatized string may draw its value from anywhere in the command or wrapper by encoding the
         * value that it needs as a JSONPath expression. This JSONPath expression will be extracted from
         * the templatized string, used to search through the command or wrapper, and the result replaced into
         * the templatized string. See {@link #resolveJsonpathSubstring(String)}.
         *
         * If the templatized string needs a command or wrapper input value, then the full JSONPath search
         * syntax is not required. Simply use the input's replacement key (by default the input's name
         * pre- and postfixed by '#' characters) as the template, and this method will replace it
         * by the input's value.
         *
         * @param template A string that may or may not contain replaceable template substrings
         * @param valuesMap A Map with keys that are replaceable template strings, and values that
         *                  are the strings that will replace those templates.
         * @return The templatized string with all template values replaced
         */
        @Nonnull
        private String resolveTemplate(final String template, Map<String, String> valuesMap)
                throws CommandResolutionException {
            log.debug("Resolving template: \"{}\".", template);

            if (StringUtils.isBlank(template)) {
                log.debug("Template is blank.");
                return template;
            }

            // First find any JSONPath strings in the template
            String toResolve = resolveJsonpathSubstring(template);

            // Look through the provided map of cached replacement values, and replace any that are found.
            for (final String replacementKey : valuesMap.keySet()) {
                final String replacementValue = valuesMap.get(replacementKey);
                final String copyForLogging = toResolve;

                toResolve = toResolve.replace(replacementKey, replacementValue == null ? "" : replacementValue);
                if (!toResolve.equals(copyForLogging)) {
                    // If the replacement operation changed the template, log the replacement
                    log.debug("{} -> {}", replacementKey, replacementValue);
                }
            }

            log.debug("Resolved template: \"{}\".", toResolve);
            return toResolve;
        }

        /**
         * Checks an input string for a JSONPath substring, extracts it,
         * and uses it to search the command or wrapper for the value.
         *
         * The JSONPath search string can search through the runtime values of the command or the command wrapper
         * (as far as they are determined).
         * The JSONPath substrings should be surrounded by caret characters ('^')
         *
         * @param stringThatMayContainJsonpathSubstring A string that may or may not contain a JSONPath search as a substring.
         * @return The input string, with any JSONPath substrings resolved into values.
         */
        @Nonnull
        private String resolveJsonpathSubstring(final String stringThatMayContainJsonpathSubstring) throws CommandResolutionException {
            if (StringUtils.isNotBlank(stringThatMayContainJsonpathSubstring)) {
                log.debug("Checking for JSONPath substring in \"{}\".", stringThatMayContainJsonpathSubstring);

                final Matcher jsonpathSubstringMatcher = jsonpathSubstringPattern.matcher(stringThatMayContainJsonpathSubstring);

                // TODO - Consider this: should I be looking for multiple JSONPath substrings and replacing them all?
                if (jsonpathSubstringMatcher.find()) {

                    final String jsonpathSearchWithMarkers = jsonpathSubstringMatcher.group(0);
                    final String useWrapper = jsonpathSubstringMatcher.group(1);
                    final String jsonpathSearchWithoutMarkers = jsonpathSubstringMatcher.group(2);

                    log.debug("Found possible JSONPath substring \"{}\".", jsonpathSearchWithMarkers);

                    if (StringUtils.isNotBlank(jsonpathSearchWithoutMarkers)) {

                        final List<String> searchResult;
                        if (StringUtils.isNotBlank(useWrapper)) {
                            log.debug("Performing JSONPath search through command wrapper with search string \"{}\".", jsonpathSearchWithoutMarkers);
                            searchResult = commandWrapperJsonpathSearchContext.read(jsonpathSearchWithoutMarkers);
                        } else {
                            log.debug("Performing JSONPath search through command with search string \"{}\".", jsonpathSearchWithoutMarkers);
                            searchResult = commandJsonpathSearchContext.read(jsonpathSearchWithoutMarkers);
                        }

                        if (searchResult != null && !searchResult.isEmpty() && searchResult.get(0) != null) {
                            log.debug("JSONPath search result: {}", searchResult);
                            if (searchResult.size() == 1) {
                                final String result = searchResult.get(0);
                                final String replacement = stringThatMayContainJsonpathSubstring.replace(jsonpathSearchWithMarkers, result);
                                log.debug("Replacing \"{}\" with \"{}\" in \"{}\".", jsonpathSearchWithMarkers, result, stringThatMayContainJsonpathSubstring);
                                log.debug("Result: \"{}\".", replacement);
                                return replacement;
                            } else {
                                final String message =
                                        String.format(
                                                "JSONPath search \"%s\" returned multiple results: %s. Cannot determine value to replace.",
                                                jsonpathSearchWithoutMarkers,
                                                searchResult.toString());
                                log.error(message);
                                throw new CommandResolutionException(message);
                            }
                        } else {
                            log.debug("No result");
                        }
                    }
                }

                log.debug("No jsonpath substring found.");
            }
            return stringThatMayContainJsonpathSubstring;
        }

        @Nullable
        private ResolvedInputValue getInputValueByName(final String name, final List<ResolvedInputTreeNode<? extends Input>> resolvedInputTrees) {
            for (final ResolvedInputTreeNode<? extends Input> root : resolvedInputTrees) {
                log.debug("Looking for input {} on input tree rooted on input {}.", name, root.input().name());
                final ResolvedInputValue resolvedInputValue = getInputValueByName(name, root);

                if (resolvedInputValue != null) {
                    return resolvedInputValue;
                }
            }

            log.debug("Did not find unique value for input {}.", name);
            return null;
        }

        @Nullable
        private ResolvedInputValue getInputValueByName(final String name, final ResolvedInputTreeNode<? extends Input> resolvedInputTreeNode) {
            log.debug("Checking input node with input \"{}\".", resolvedInputTreeNode.input().name());
            final List<ResolvedInputTreeValueAndChildren> valuesAndChildren = resolvedInputTreeNode.valuesAndChildren();
            if (valuesAndChildren.size() != 1) {
                log.debug("Input \"{}\" does not have a uniquely resolved value. There is no hope of its children having unique values. Returning null.", resolvedInputTreeNode.input().name());
                return null;
            }

            log.debug("Input \"{}\" has a uniquely resolved value.", resolvedInputTreeNode.input().name());
            final ResolvedInputTreeValueAndChildren valueAndChildren = valuesAndChildren.get(0);
            if (resolvedInputTreeNode.input().name() != null && resolvedInputTreeNode.input().name().equals(name)) {
                log.debug("Found target input \"{}\".", name);
                return valueAndChildren.resolvedValue();
            } else {
                log.debug("Input \"{}\" not found. Checking children.", name);
                for (final ResolvedInputTreeNode<? extends Input> child : valueAndChildren.children()) {
                    final ResolvedInputValue resolvedInputValue = getInputValueByName(name, child);
                    if (resolvedInputValue != null) {
                        return resolvedInputValue;
                    }
                }
            }
            return null;
        }
    }

    private String getBuildDirectory(final PartiallyResolvedCommandMount mount) throws ContainerMountResolutionException {
        final String rootBuildPath = siteConfigPreferences.getBuildPath();
        final String uuid = UUID.randomUUID().toString();
        final String buildDir = FilenameUtils.concat(rootBuildPath, uuid);
        final Path created;
        try {
            created = Files.createDirectory(Paths.get(buildDir));
        } catch (IOException e) {
            throw new ContainerMountResolutionException("Could not create build directory " + buildDir, mount, e);
        }
        created.toFile().setWritable(true);
        return buildDir;
    }
}
