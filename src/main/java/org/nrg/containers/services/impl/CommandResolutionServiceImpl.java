package org.nrg.containers.services.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.nrg.action.ClientException;
import org.nrg.action.ServerException;
import org.nrg.config.services.ConfigService;
import org.nrg.containers.exceptions.CommandInputResolutionException;
import org.nrg.containers.exceptions.CommandMountResolutionException;
import org.nrg.containers.exceptions.CommandResolutionException;
import org.nrg.containers.exceptions.ContainerMountResolutionException;
import org.nrg.containers.exceptions.IllegalInputException;
import org.nrg.containers.exceptions.UnauthorizedException;
import org.nrg.containers.model.command.auto.*;
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
import org.nrg.containers.model.command.auto.ResolvedCommand.PartiallyResolvedCommand;
import org.nrg.containers.model.command.auto.ResolvedCommand.PartiallyResolvedCommandMount;
import org.nrg.containers.model.command.auto.ResolvedCommand.ResolvedCommandOutput;
import org.nrg.containers.model.command.auto.ResolvedInputTreeNode.ResolvedInputTreeValueAndChildren;
import org.nrg.containers.model.command.entity.CommandInputEntity;
import org.nrg.containers.model.command.entity.CommandType;
import org.nrg.containers.model.command.entity.CommandWrapperInputType;
import org.nrg.containers.model.command.entity.CommandWrapperOutputEntity;
import org.nrg.containers.model.server.docker.DockerServerBase;
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
import org.nrg.containers.services.DockerServerService;
import org.nrg.containers.services.DockerService;
import org.nrg.framework.constants.Scope;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.security.helpers.Permissions;
import org.nrg.xdat.security.helpers.Users;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.helpers.uri.URIManager;
import org.nrg.xnat.helpers.uri.URIManager.ArchiveItemURI;
import org.nrg.xnat.helpers.uri.UriParserUtils;
import org.nrg.xnat.services.archive.CatalogService;
import org.nrg.xnat.turbine.utils.ArchivableItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

@Slf4j
@Service
public class CommandResolutionServiceImpl implements CommandResolutionService {

    private final CommandService commandService;

    private final DockerServerService dockerServerService;
    private final ConfigService configService;
    private final SiteConfigPreferences siteConfigPreferences;
    private final ObjectMapper mapper;
    private final DockerService dockerService;
    private final CatalogService catalogService;

    public static final String swarmConstraintsTag = "swarm-constraints";

    @Autowired
    public CommandResolutionServiceImpl(final CommandService commandService,
                                        final ConfigService configService,
                                        final DockerServerService dockerServerService,
                                        final SiteConfigPreferences siteConfigPreferences,
                                        final ObjectMapper mapper,
                                        final DockerService dockerService,
                                        final CatalogService catalogService) {
        this.commandService = commandService;
        this.configService = configService;
        this.dockerServerService = dockerServerService;
        this.siteConfigPreferences = siteConfigPreferences;
        this.mapper = mapper;
        this.dockerService = dockerService;
        this.catalogService = catalogService;
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
        return preResolve(commandService.getAndConfigure(project, wrapperId), inputValues, userI)
                .toBuilder()
                .project(project)
                .build();
    }

    @Override
    public PartiallyResolvedCommand preResolve(final String project,
                                               final long commandId,
                                               final String wrapperName,
                                               final Map<String, String> inputValues,
                                               final UserI userI)
            throws NotFoundException, CommandResolutionException, UnauthorizedException {
        return preResolve(commandService.getAndConfigure(project, commandId, wrapperName), inputValues, userI)
                .toBuilder()
                .project(project)
                .build();
    }

    @Override
    public PartiallyResolvedCommand preResolve(final ConfiguredCommand configuredCommand,
                                               final Map<String, String> inputValues,
                                               final UserI userI)
            throws CommandResolutionException, UnauthorizedException {
        final CommandResolutionHelper helper = new CommandResolutionHelper(configuredCommand, inputValues, userI);
        return helper.preResolve();
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
        private Map<String, Set<String>> loadTypesMap;

        private final UserI userI;
        private final Pattern jsonpathSubstringPattern;
        private final DocumentContext commandJsonpathSearchContext;
        private final DocumentContext commandWrapperJsonpathSearchContext;
        private String containerHost;

        private String pathTranslationXnatPrefix = null;
        private String pathTranslationContainerHostPrefix = null;

        private List<ResolvedCommand> resolvedSetupCommands;

        // Caches
        private Map<String, String> inputValues;

        private CommandResolutionHelper(final ConfiguredCommand configuredCommand,
                                        final Map<String, String> inputValues,
                                        final UserI userI) throws CommandResolutionException {
            this.commandWrapper = configuredCommand.wrapper();
            this.command = configuredCommand;

            try {
                log.debug("Getting docker server to read path prefixes.");
                final DockerServerBase.DockerServerWithPing dockerServer = dockerService.getServer();
                pathTranslationXnatPrefix = dockerServer.pathTranslationXnatPrefix();
                pathTranslationContainerHostPrefix = dockerServer.pathTranslationDockerPrefix();
            } catch (NotFoundException e) {
                log.debug("Could not get docker server. I'll keep going, but this is likely to cause other problems down the line.");
            }

            // Set up JSONPath search contexts
            final Configuration c = Configuration.defaultConfiguration().addOptions(Option.ALWAYS_RETURN_LIST);
            commandJsonpathSearchContext = serializeToJson(command, c);
            commandWrapperJsonpathSearchContext = serializeToJson(commandWrapper, c);

            this.userI = userI;
            this.jsonpathSubstringPattern = Pattern.compile(JSONPATH_SUBSTRING_REGEX);

            this.inputValues = inputValues == null ?
                    Collections.<String, String>emptyMap() :
                    inputValues;

            this.resolvedSetupCommands = new ArrayList<>();

            // During preresolution, we want to work as quickly as possible (user is waiting for UI form). As such,
            // we determine how deeply we need to resolve the XNAT objects for JSON serialization.
            this.loadTypesMap = getTypeLoadMapForWrapper();
        }

        private DocumentContext serializeToJson(Object command, Configuration c)
                throws CommandResolutionException {
            try {
                final String commandJson = mapper.writeValueAsString(command);
                return JsonPath.using(c).parse(commandJson);
            } catch (JsonProcessingException e) {
                throw new CommandResolutionException("Could not serialize command to JSON.", e);
            }
        }

        @Nonnull
        private List<ResolvedInputTreeNode<? extends Input>> preResolveInputTrees()
                throws CommandResolutionException, UnauthorizedException {
            return resolveInputTrees(Maps.<String, String>newHashMap(), null, false);
        }

        @Nonnull
        private List<ResolvedInputTreeNode<? extends Input>> resolveInputTrees(final Map<String, String> resolvedInputValuesByReplacementKey,
                                                                               @Nullable final Map<String, String> resolvedCommandLineValuesByReplacementKey,
                                                                               boolean resolveFully)
                throws CommandResolutionException, UnauthorizedException {
            final List<PreresolvedInputTreeNode<? extends Input>> rootNodes = initializePreresolvedInputTree(resolvedCommandLineValuesByReplacementKey);

            final List<ResolvedInputTreeNode<? extends Input>> resolvedInputTrees = Lists.newArrayList();
            for (final PreresolvedInputTreeNode<? extends Input> rootNode : rootNodes) {
                log.debug("Resolving input tree with root input \"{}\".", rootNode.input().name());
                final ResolvedInputTreeNode<? extends Input> resolvedRootNode =
                        resolveNode(rootNode, null, resolvedInputValuesByReplacementKey, resolveFully);
                log.debug("Done resolving input tree with root input \"{}\".", rootNode.input().name());
                resolvedInputTrees.add(resolvedRootNode);

                log.debug("Searching input tree for resolved values.");
                findResolvedValues(resolvedRootNode, resolvedInputValuesByReplacementKey,
                        resolvedCommandLineValuesByReplacementKey, resolveFully);
                log.debug("Done searching input tree for resolved values.");

            }

            // TODO turn the input trees into something manageable
            return resolvedInputTrees;
        }

        @Nonnull
        private PartiallyResolvedCommand preResolve() throws CommandResolutionException, UnauthorizedException {
            log.info("Resolving command wrapper inputs.");
            log.debug("{}", commandWrapper);

            final List<ResolvedInputTreeNode<? extends Input>> resolvedInputTrees = preResolveInputTrees();

            return PartiallyResolvedCommand.builder()
                    .wrapperId(commandWrapper.id())
                    .wrapperName(commandWrapper.name())
                    .wrapperLabel(commandWrapper.label())
                    .wrapperDescription(commandWrapper.description())
                    .commandId(command.id())
                    .commandName(command.name())
                    .commandLabel(command.label())
                    .commandDescription(command.description())
                    .image(command.image())
                    .type(command.type())
                    .overrideEntrypoint(command.overrideEntrypoint() == null ? Boolean.FALSE : command.overrideEntrypoint())
                    .rawInputValues(inputValues)
                    .resolvedInputTrees(resolvedInputTrees)
                    .build();
        }

        @Nonnull
        private ResolvedCommand resolve() throws CommandResolutionException, UnauthorizedException {
            log.info("Resolving command.");
            log.debug("{}", command);

            final Map<String, String> resolvedInputValuesByReplacementKey = Maps.newHashMap();
            final Map<String, String> resolvedCommandLineValuesByReplacementKey = Maps.newHashMap();
            final List<ResolvedInputTreeNode<? extends Input>> resolvedInputTrees =
                    resolveInputTrees(resolvedInputValuesByReplacementKey, resolvedCommandLineValuesByReplacementKey, true);

            log.debug("Checking for missing required inputs.");
            final List<String> missingRequiredInputs = findMissingRequiredInputs(resolvedInputTrees);
            if (!missingRequiredInputs.isEmpty()) {
                throw new CommandResolutionException(
                        String.format("Missing values for required input%s: %s.",
                                missingRequiredInputs.size() == 1 ? "" : "s",
                                StringUtils.join(missingRequiredInputs, ", "))
                );
            }
            final List<ResolvedCommandOutput> resolvedCommandOutputs = resolveOutputs(resolvedInputTrees, resolvedInputValuesByReplacementKey);
            final String resolvedCommandLine = resolveCommandLine(resolvedCommandLineValuesByReplacementKey);
            final Map<String, String> resolvedEnvironmentVariables = resolveEnvironmentVariables(resolvedInputValuesByReplacementKey);
            final String resolvedWorkingDirectory = resolveWorkingDirectory(resolvedInputValuesByReplacementKey);
            final Map<String, String> resolvedPorts = resolvePorts(resolvedInputValuesByReplacementKey);
            final List<ResolvedCommandMount> resolvedCommandMounts = resolveCommandMounts(resolvedInputTrees, resolvedInputValuesByReplacementKey);
            final List<ResolvedCommand> resolvedWrapupCommands = resolveWrapupCommands(resolvedCommandOutputs, resolvedCommandMounts);

            // Populate setup & wrap-up commands with environment variables from parent command
            List<ResolvedCommand> populatedSetupCommands = new ArrayList<>();
            List<ResolvedCommand> populatedWrapupCommands = new ArrayList<>();
            for(ResolvedCommand setup : resolvedSetupCommands){
                populatedSetupCommands.add(
                        setup.toBuilder()
                             .addEnvironmentVariables(resolvedEnvironmentVariables)
                             .commandLine(resolveCommandLine(resolvedCommandLineValuesByReplacementKey, setup.commandLine()))
                             .build());
            }
            for(ResolvedCommand wrapup : resolvedWrapupCommands){
                populatedWrapupCommands.add(
                        wrapup.toBuilder()
                              .addEnvironmentVariables(resolvedEnvironmentVariables)
                              .commandLine(resolveCommandLine(resolvedCommandLineValuesByReplacementKey, wrapup.commandLine()))
                              .build());
            }

            final List<String> swarmConstraints = resolveSwarmConstraints();

            final ResolvedCommand resolvedCommand = ResolvedCommand.builder()
                    .wrapperId(commandWrapper.id())
                    .wrapperName(commandWrapper.name())
                    .wrapperDescription(commandWrapper.description())
                    .commandId(command.id())
                    .commandName(command.name())
                    .commandDescription(command.description())
                    .image(command.image())
                    .type(command.type())
                    .overrideEntrypoint(command.overrideEntrypoint() == null ? Boolean.FALSE : command.overrideEntrypoint())
                    .rawInputValues(inputValues)
                    .resolvedInputTrees(resolvedInputTrees)
                    .outputs(resolvedCommandOutputs)
                    .commandLine(resolvedCommandLine)
                    .environmentVariables(resolvedEnvironmentVariables)
                    .workingDirectory(resolvedWorkingDirectory)
                    .ports(resolvedPorts)
                    .mounts(resolvedCommandMounts)
                    .setupCommands(populatedSetupCommands)
                    .wrapupCommands(populatedWrapupCommands)
                    .reserveMemory(command.reserveMemory())
                    .limitMemory(command.limitMemory())
                    .limitCpu(command.limitCpu())
                    .swarmConstraints(swarmConstraints)
                    .build();

            log.info("Done resolving command.");
            log.debug("Resolved command: \n{}", resolvedCommand);
            return resolvedCommand;
        }

        private ResolvedCommand resolveSpecialCommandType(final CommandType type,
                                                          final String image,
                                                          final String inputMountXnatHostPath,
                                                          final String outputMountXnatHostPath,
                                                          final String parentSourceObjectName)
                throws CommandResolutionException {
            final String typeStringForLog;
            switch (type) {
                case DOCKER_SETUP:
                    typeStringForLog = "setup";
                    break;
                case DOCKER_WRAPUP:
                    typeStringForLog = "wrapup";
                    break;
                default:
                    throw new CommandResolutionException("A method intended to resolve only special command types was called with a command of type " + type.getName());
            }
            log.debug("Resolving {} command from image {}.", typeStringForLog, image);
            final Command command;
            try {
                command = dockerService.getCommandByImage(image);
            } catch (NotFoundException e) {
                throw new CommandResolutionException(String.format("Could not resolve %s command with image %s.", typeStringForLog, image), e);
            }

            if (!command.type().equals(type.getName())) {
                throw new CommandResolutionException(
                        String.format("Command %s from image %s has type %s, but I expected it to have type %s.",
                                command.name(), image, command.type(), type.getName()));
            }

            log.debug("Done resolving {} command {} from image {}.", typeStringForLog, command.name(), image);

            return ResolvedCommand.fromSpecialCommandType(command, inputMountXnatHostPath, getMountContainerHostPath(inputMountXnatHostPath),
                    outputMountXnatHostPath, getMountContainerHostPath(outputMountXnatHostPath), parentSourceObjectName);
        }

        private String getMountContainerHostPath(final String mountXnatHostPath) {
            return (pathTranslationXnatPrefix != null && pathTranslationContainerHostPrefix != null) ?
                    mountXnatHostPath.replace(pathTranslationXnatPrefix, pathTranslationContainerHostPrefix) :
                    mountXnatHostPath;
        }

        private void checkForIllegalInputValue(final String inputName,
                                               final String inputValue) throws IllegalInputException {
            if (inputValue == null) {
                return;
            }

            for (final String illegalString : ILLEGAL_INPUT_STRINGS) {
                if (inputValue.contains(illegalString)) {
                    final String message = String.format("Input \"%s\" has a value containing illegal string \"%s\".",
                            inputName, illegalString);
                    log.info(message);
                    throw new IllegalInputException(message);
                }
            }
        }

        /**
         * Determine depth needed for json serialization of input
         * @param typesMap  the current map
         * @param input     the input
         * @param childToParentsMap child input name -> list of parents (parent, grandparent, etc)
         */
        private void addTypeDependencies(Map<String, Set<String>> typesMap, CommandWrapperInput input,
                                         @Nonnull Map<String, Set<String>> childToParentsMap) {

            String name = input.name();
            Set<String> typesUsed = typesMap.computeIfAbsent(name, k -> new HashSet<>());
            Set<String> typesUsedByInput = findTypesUsed(input);
            typesUsed.addAll(typesUsedByInput);

            if (input instanceof CommandWrapperDerivedInput) {
                // add child types to parents, too
                for (String parentName : childToParentsMap.get(input.name())) {
                    Set<String> typesUsedByParent = typesMap.computeIfAbsent(parentName, k -> new HashSet<>());
                    typesUsedByParent.addAll(typesUsedByInput);
                }
            }
        }

        /**
         * Get types used by input
         * @param input     the input
         */
        private Set<String> findTypesUsed(CommandWrapperInput input) {
            Set<String> typesUsed  = new HashSet<>();
            typesUsed.add(input.type());

            // Very hacky way to determine if the JSON matcher needs any deeper object types
            String matcher = input.matcher();
            if (matcher == null) {
                return typesUsed;
            }
            matcher = matcher.toLowerCase();
            Set<String> matcherTypes = new HashSet<>();
            for (String type : CommandWrapperInputType.names()) {
                if (matcher.contains(type.toLowerCase())) {
                    matcherTypes.add(type);
                }
            }
            typesUsed.addAll(matcherTypes);

            return typesUsed;
        }

        /**
         * In order to speed up command pre-resolution, we create a map of which XNAT object types we need in the
         * serialized JSON to build the UI.
         * @return the map
         */
        @Nonnull
        private Map<String, Set<String>> getTypeLoadMapForWrapper() {
            // quick resolve to determine hierarchy
            Map<String, CommandWrapperInput> inputsByName = Stream.concat(commandWrapper.externalInputs().stream(),
                    commandWrapper.derivedInputs().stream()).collect(Collectors.toMap(CommandWrapperInput::name, i -> i));

            Map<String, Set<String>> childToParentsMap = new HashMap<>();
            for (CommandWrapperDerivedInput input : commandWrapper.derivedInputs()) {
                if (!childToParentsMap.containsKey(input.name())) {
                    getParents(childToParentsMap, input, inputsByName);
                }
            }

            // Determine the input types for the wrapper
            Map<String, Set<String>> typesMap = new HashMap<>();
            for (CommandWrapperExternalInput input : commandWrapper.externalInputs()) {
                addTypeDependencies(typesMap, input, childToParentsMap);
            }
            for (CommandWrapperDerivedInput input : commandWrapper.derivedInputs()) {
                addTypeDependencies(typesMap, input, childToParentsMap);
            }
            return typesMap;
        }

        /**
         * Get all parents (parent, grandparent, etc) for input
         *
         * @param childToParentsMap map input name -> parent names
         * @param input             the input
         * @param inputsByName      map of input names to input POJOs
         */
        private void getParents(Map<String, Set<String>> childToParentsMap, CommandWrapperDerivedInput input,
                                Map<String, CommandWrapperInput> inputsByName) {
            String derivedFrom = input.derivedFromWrapperInput();
            Set<String> parents = new HashSet<>();
            if (StringUtils.isNotBlank(derivedFrom)) {
                CommandWrapperInput parent = inputsByName.get(derivedFrom);
                if (parentIsAboveInHierarchy(input, parent)) {
                    // We only need to add load type dependencies from parents who are above us in the tree
                    // (project > subject > session > [assessor/scan] > resource > file); we don't need to pass
                    // loadtypes back down the tree
                    if (parent instanceof CommandWrapperDerivedInput) {
                        getParents(childToParentsMap, (CommandWrapperDerivedInput) parent, inputsByName);
                        parents.addAll(childToParentsMap.get(parent.name()));
                    }
                    parents.add(parent.name());
                }
            }
            childToParentsMap.put(input.name(), parents);
        }

        /**
         * "Parent" element can actually be below child in XNAT hierarchy.
         * @param input the input
         * @param parent the parent
         * @return true if parent is above input in XNAT hierarchy
         */
        private boolean parentIsAboveInHierarchy(CommandWrapperDerivedInput input, CommandWrapperInput parent) {
            CommandWrapperInputType type = CommandWrapperInputType.fromName(input.type());
            CommandWrapperInputType parentType = CommandWrapperInputType.fromName(parent.type());
            if (parentType == null || type == null) {
                log.warn("Unable to determine type for parent {} and/or input {}", parent, input);
                return false;
            }
            switch (type) {
                case PROJECT:
                    return false;
                case SUBJECT:
                    return parentType == PROJECT;
                case SESSION:
                    return Arrays.asList(PROJECT, SUBJECT).contains(parentType);
                case ASSESSOR:
                case SCAN:
                    return Arrays.asList(PROJECT, SUBJECT, SESSION).contains(parentType);
                case RESOURCE:
                    return Arrays.asList(PROJECT, SUBJECT, SESSION, ASSESSOR, SCAN).contains(parentType);
                case FILE:
                case FILES:
                case DIRECTORY:
                    return true;
                default:
                    return false;
            }
        }


        @Nonnull
        private ResolvedInputValue resolveExternalWrapperInput(final CommandWrapperExternalInput input,
                                                               final Map<String, String> resolvedInputValuesByReplacementKey,
                                                               final boolean loadFiles)
                throws CommandResolutionException, UnauthorizedException, IllegalInputException {
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

                    Set<String> typesNeeded = loadTypesMap.get(input.name());

                    final XnatModelObject xnatModelObject;
                    final boolean preload = input.loadChildren();
                    try {
                        if (type.equals(PROJECT.getName())) {
                            xnatModelObject = resolveXnatObject(resolvedValue, resolvedMatcher,
                                    Project.class, Project.uriToModelObject(loadFiles, typesNeeded, preload),
                                    Project.idToModelObject(userI, loadFiles, typesNeeded, preload));
                        } else if (type.equals(SUBJECT.getName())) {
                            xnatModelObject = resolveXnatObject(resolvedValue, resolvedMatcher,
                                    Subject.class, Subject.uriToModelObject(loadFiles, typesNeeded),
                                    Subject.idToModelObject(userI, loadFiles, typesNeeded));
                        } else if (type.equals(SESSION.getName())) {
                            xnatModelObject = resolveXnatObject(resolvedValue, resolvedMatcher,
                                    Session.class, Session.uriToModelObject(loadFiles, typesNeeded),
                                    Session.idToModelObject(userI, loadFiles, typesNeeded));
                        } else if (type.equals(SCAN.getName())) {
                            xnatModelObject = resolveXnatObject(resolvedValue, resolvedMatcher,
                                    Scan.class, Scan.uriToModelObject(loadFiles, typesNeeded),
                                    Scan.idToModelObject(userI, loadFiles, typesNeeded));
                        } else if (type.equals(ASSESSOR.getName())) {
                            xnatModelObject = resolveXnatObject(resolvedValue, resolvedMatcher,
                                    Assessor.class, Assessor.uriToModelObject(loadFiles, typesNeeded),
                                    Assessor.idToModelObject(userI, loadFiles, typesNeeded));
                        } else {
                            xnatModelObject = resolveXnatObject(resolvedValue, resolvedMatcher,
                                    Resource.class, Resource.uriToModelObject(loadFiles, typesNeeded),
                                    Resource.idToModelObject(userI, loadFiles, typesNeeded));
                        }
                    } catch (CommandInputResolutionException e) {
                        // When resolveXnatObject throws this, it does not have the input object in scope
                        // So we just add the input and throw a new exception with everything else the same.
                        throw new CommandInputResolutionException(e.getMessage(), input, e.getValue(), e.getCause());
                    }

                    if (xnatModelObject == null) {
                        log.debug("Could not instantiate XNAT object from value.");
                        resolvedValue = null;
                    } else {
                        resolvedModelObject = xnatModelObject;
                        final String resolvedXnatObjectValue = xnatModelObject.getUri();
                        if (resolvedXnatObjectValue != null) {
                            log.debug("Setting resolved value to \"{}\".", resolvedXnatObjectValue);
                            resolvedValue = resolvedXnatObjectValue;
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

            checkForIllegalInputValue(input.name(), resolvedValue);

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
                throws CommandResolutionException, IllegalInputException {
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
            final boolean multiple = input.multiple();

            final XnatModelObject parentXnatObject = parent.xnatModelObject();
            final String parentJson = parent.jsonValue();
            final String parentType = parent.type();

            final List<XnatModelObject> resolvedXnatObjects;
            final List<String> resolvedValues;

            Set<String> typesNeeded = loadTypesMap.get(input.name());

            final String propertyToGet = input.derivedFromXnatObjectProperty();
            if (type.equals(STRING.getName())) {
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
                            "uri",
                            resolvedMatcher,
                            new TypeRef<List<XnatFile>>() {},
                            multiple);
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
                } else if (!(parentType.equals(SUBJECT.getName()) || parentType.equals(SESSION.getName()) ||
                        parentType.equals(SCAN.getName()) || parentType.equals(ASSESSOR.getName()))) {
                    logIncompatibleTypes(input.type(), parentType);
                    resolvedXnatObjects = Collections.emptyList();
                    resolvedValues = Collections.emptyList();
                } else {
                    final Project project;
                    if (parentType.equals(SUBJECT.getName())) {
                        project = ((Subject)parentXnatObject).getProject(userI, false, typesNeeded);
                    } else if (parentType.equals(SESSION.getName())) {
                        project = ((Session)parentXnatObject).getProject(userI, false, typesNeeded);
                    } else if (parentType.equals(SCAN.getName())) {
                        project = ((Scan)parentXnatObject).getProject(userI, false, typesNeeded);
                    } else {
                        project = ((Assessor)parentXnatObject).getProject(userI, false, typesNeeded);
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
                        List<Subject> childList = matchChildFromParent(
                                parentJson,
                                valueCouldContainId,
                                "subjects",
                                "uri",
                                resolvedMatcher,
                                new TypeRef<List<Subject>>() {},
                                multiple);
                        if (childList == null || childList.isEmpty()) {
                            // It is also possible that the value they gave us contains an id
                            childList = matchChildFromParent(
                                    parentJson,
                                    valueCouldContainId,
                                    "subjects",
                                    "id",
                                    resolvedMatcher,
                                    new TypeRef<List<Subject>>() {},
                                    multiple);
                        }
                        if (childList == null || childList.isEmpty()) {
                            // It is also possible that the value they gave us contains a label
                            childList = matchChildFromParent(
                                    parentJson,
                                    valueCouldContainId,
                                    "subjects",
                                    "label",
                                    resolvedMatcher,
                                    new TypeRef<List<Subject>>() {},
                                    multiple);
                        }
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
                        final Subject subject = ((Session)parentXnatObject).getSubject(userI, false, typesNeeded);
                        resolvedXnatObjects = Collections.<XnatModelObject>singletonList(subject);
                        resolvedValues = Collections.singletonList(subject.getUri());
                    }
                }
            } else if (type.equals(SESSION.getName())) {
                if (parentXnatObject == null) {
                    log.error("Cannot derive input \"{}\". Parent input's XNAT object is null.", input.name());
                    resolvedXnatObjects = Collections.emptyList();
                    resolvedValues = Collections.emptyList();
                } else if (!(parentType.equals(SUBJECT.getName()) || parentType.equals(SCAN.getName()) || parentType.equals(ASSESSOR.getName()))) {
                    logIncompatibleTypes(input.type(), parentType);
                    resolvedXnatObjects = Collections.emptyList();
                    resolvedValues = Collections.emptyList();
                } else {
                    if (parentType.equals(SUBJECT.getName())) {
                        List<Session> childList = matchChildFromParent(
                                parentJson,
                                valueCouldContainId,
                                "sessions",
                                "uri",
                                resolvedMatcher,
                                new TypeRef<List<Session>>() {},
                                multiple);
                        if (childList == null || childList.isEmpty()) {
                            // It is also possible that the value they gave us contains an id
                            childList = matchChildFromParent(
                                    parentJson,
                                    valueCouldContainId,
                                    "sessions",
                                    "id",
                                    resolvedMatcher,
                                    new TypeRef<List<Session>>() {},
                                    multiple);
                        }
                        if (childList == null || childList.isEmpty()) {
                            // It is also possible that the value they gave us contains a label
                            childList = matchChildFromParent(
                                    parentJson,
                                    valueCouldContainId,
                                    "sessions",
                                    "label",
                                    resolvedMatcher,
                                    new TypeRef<List<Session>>() {},
                                    multiple);
                        }
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
                    } else if (parentType.equals(ASSESSOR.getName())) {
                        final Session session = ((Assessor)parentXnatObject).getSession(userI, false, typesNeeded);
                        resolvedXnatObjects = Collections.<XnatModelObject>singletonList(session);
                        resolvedValues = Collections.singletonList(session.getUri());
                    } else {
                        // Parent is scan
                        final Session session = ((Scan)parentXnatObject).getSession(userI, false, typesNeeded);
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
                    List<Scan> childList = matchChildFromParent(
                            parentJson,
                            valueCouldContainId,
                            "scans",
                            "uri",
                            resolvedMatcher,
                            new TypeRef<List<Scan>>() {},
                            multiple);
                    if (childList == null || childList.isEmpty()) {
                        // It is also possible that the value they gave us contains an id
                        childList = matchChildFromParent(
                                parentJson,
                                valueCouldContainId,
                                "scans",
                                "id",
                                resolvedMatcher,
                                new TypeRef<List<Scan>>() {},
                                multiple);
                    }
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
                    List<Assessor> childList = matchChildFromParent(
                            parentJson,
                            valueCouldContainId,
                            "assessors",
                            "uri",
                            resolvedMatcher,
                            new TypeRef<List<Assessor>>() {},
                            multiple);
                    if (childList == null || childList.isEmpty()) {
                        // It is also possible that the value they gave us contains an ID
                        childList = matchChildFromParent(
                                parentJson,
                                valueCouldContainId,
                                "assessors",
                                "id",
                                resolvedMatcher,
                                new TypeRef<List<Assessor>>() {},
                                multiple);
                    }
                    if (childList == null || childList.isEmpty()) {
                        // It is also possible that the value they gave us contains a label
                        childList = matchChildFromParent(
                                parentJson,
                                valueCouldContainId,
                                "assessors",
                                "label",
                                resolvedMatcher,
                                new TypeRef<List<Assessor>>() {},
                                multiple);
                    }
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
                    // Try matching the value they gave us against the resource uri.
                    // That's what the UI will send.
                    List<Resource> childList = matchChildFromParent(
                            parentJson,
                            valueCouldContainId,
                            "resources",
                            "uri",
                            resolvedMatcher,
                            new TypeRef<List<Resource>>() {},
                            multiple);
                    if (childList == null || childList.isEmpty()) {
                        // It is also possible that the value they gave us contains an ID
                        childList = matchChildFromParent(
                                parentJson,
                                valueCouldContainId,
                                "resources",
                                "id",
                                resolvedMatcher,
                                new TypeRef<List<Resource>>() {},
                                multiple);
                    }
                    if (childList == null || childList.isEmpty()) {
                        // It is also possible that the value they gave us contains a label
                        childList = matchChildFromParent(
                                parentJson,
                                valueCouldContainId,
                                "resources",
                                "label",
                                resolvedMatcher,
                                new TypeRef<List<Resource>>() {},
                                multiple);
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
                String resolvedValue = resolvedValues.get(i);
                final XnatModelObject xnatModelObject = resolvedXnatObjects == null ? null : resolvedXnatObjects.get(i);
                String jsonValue = resolvedValue;
                String valueLabel = resolvedValue;
                if (xnatModelObject != null) {
                    valueLabel = xnatModelObject.getLabel();
                    try {
                        jsonValue = mapper.writeValueAsString(xnatModelObject);
                        if (StringUtils.isNotBlank(propertyToGet)) {
                            resolvedValue = pullStringFromParentJson("$." + propertyToGet,
                                    null, jsonValue);
                        }
                    } catch (JsonProcessingException e) {
                        log.error("Could not serialize model object to json.", e);
                    }
                }
                checkForIllegalInputValue(input.name(), resolvedValue);
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
                throws CommandResolutionException, IllegalInputException {
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
            // TODO apply matcher to input value
            //final String resolvedMatcher = input.matcher() != null ? resolveTemplate(input.matcher(), resolvedInputValuesByReplacementKey) : null;

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

            checkForIllegalInputValue(input.name(), resolvedValue);

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

        private List<PreresolvedInputTreeNode<? extends Input>> initializePreresolvedInputTree(@Nullable final Map<String, String> resolvedCommandLineValuesByReplacementKey)
                throws CommandResolutionException {
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
                final String parentName = input.derivedFromWrapperInput();
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
                    if (!parent.input().required() && resolvedCommandLineValuesByReplacementKey != null) {
                        // Add a default to remove command line replacement if parent is not required
                        // (if parent doesn't resolve to anything, this replacement doesn't occur and we wind up with
                        // a replacement key like #SCANID# in the commandline string
                        resolvedCommandLineValuesByReplacementKey.put(input.replacementKey(), "");
                    }
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
                                                                   final Map<String, String> resolvedInputValuesByReplacementKey,
                                                                   final boolean loadFiles)
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
                                resolvedInputValuesByReplacementKey, loadFiles)
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
                        resolvedChildNodes.add(resolveNode(child, resolvedInputValue, copyOfResolvedInputValuesByReplacementKey, loadFiles));
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

        private void findResolvedValues(final ResolvedInputTreeNode<? extends Input> node,
                                        final Map<String, String> resolvedInputValuesByReplacementKey,
                                        @Nullable final Map<String, String> resolvedCommandLineValuesByReplacementKey,
                                        final boolean resolveFully)
                throws CommandResolutionException {

            final List<ResolvedInputTreeValueAndChildren> resolvedValueAndChildren = node.valuesAndChildren();
            Input input = node.input();
            if (resolvedValueAndChildren.size() == 1) {
                // This node has a single value, so we can add it to the map of resolved values by replacement key
                final ResolvedInputTreeValueAndChildren singleValue = resolvedValueAndChildren.get(0);
                log.debug("Input \"{}\" has a unique resolved value: \"{}\".",
                        node.input().name(), singleValue.resolvedValue().value());

                final String valueNotNull = singleValue.resolvedValue().value() == null ? "" : singleValue.resolvedValue().value();
                final String replacementKey = node.input().replacementKey();
                log.debug("Storing value \"{}\" by replacement key \"{}\".", valueNotNull, node.input().replacementKey());
                resolvedInputValuesByReplacementKey.put(replacementKey, valueNotNull);

                if (resolvedCommandLineValuesByReplacementKey != null) {
                    if (input instanceof CommandInput) {
                        final String commandLineValue = getValueForCommandLine((CommandInput) input, valueNotNull);
                        log.debug("Storing command-line value \"{}\" by replacement key \"{}\".", commandLineValue, input.replacementKey());
                        resolvedCommandLineValuesByReplacementKey.put(replacementKey, commandLineValue);
                    } else {
                        log.debug("Input \"{}\" is not a command input. Not getting command-line value.", input.name());
                    }
                }

                // Recursively check child values, and bubble up their maps.
                final List<ResolvedInputTreeNode<? extends Input>> children = singleValue.children();
                if (children != null) {
                    for (final ResolvedInputTreeNode<? extends Input> child : children) {
                        log.debug("Checking child input \"{}\".", child.input().name());
                        findResolvedValues(child, resolvedInputValuesByReplacementKey,
                                resolvedCommandLineValuesByReplacementKey, resolveFully);
                    }
                }
            } else if (input instanceof CommandWrapperDerivedInput && ((CommandWrapperDerivedInput) input).multiple()) {
                // Collect DerivedInput values and values for its CommandInput commandInputName children,
                // throw error if other types of children or otherwise invalid (which shouldn't happen bc of
                // command.json validation)
                final List<String> commandInputChildrenValues = new ArrayList<>();
                CommandInput ci = ResolvedCommand.collectCommandInputChildrenOfMultipleDerivedInput((CommandWrapperDerivedInput) input,
                        resolvedValueAndChildren, commandInputChildrenValues, resolveFully);

                String valString = commandInputChildrenValues.toString();
                if (ci != null) resolvedInputValuesByReplacementKey.put(ci.replacementKey(), valString);
                resolvedInputValuesByReplacementKey.put(input.replacementKey(), valString);

                if (resolvedCommandLineValuesByReplacementKey != null && ci != null) {
                    resolvedCommandLineValuesByReplacementKey.put(ci.replacementKey(),
                            getMultipleValuesForCommandLine(ci, commandInputChildrenValues));
                }
            } else {
                String message = "Input \"" + node.input().name() + "\" ";
                if (resolvedValueAndChildren.size() == 0) {
                    // This node has no values, so we can't add any uniquely resolved values to the map
                    message += "could not be resolved for this item. You might check the JSONPath matchers in " +
                            "command.json and confirm that this item has " + node.input().type() + " data.";
                } else {
                    // This node has multiple or no values, so we can't add any uniquely resolved values to the map
                    message += " does not have a unique, resolved value and multiple = true is not set in command.json.";
                }

                if (resolveFully) {
                    // we're resolving for real, throw exception
                    throw new CommandResolutionException(message);
                } else {
                    // we're preresolving
                    log.debug(message);
                }
            }
        }

        @Nonnull
        private String getMultipleValuesForCommandLine(final CommandInput ci,
                                                       @Nonnull final List<String> commandInputChildrenValues)
                throws CommandResolutionException {

            if (commandInputChildrenValues.isEmpty()) {
                log.debug("Input value is empty. Using value \"\" on the command line.");
                return "";
            }

            // Prefix contains commandline flag + separator if we have them, empty string if not
            String prefix = StringUtils.defaultIfBlank(StringUtils.defaultIfBlank(ci.commandLineFlag(), " ") +
                            StringUtils.defaultIfBlank(ci.commandLineSeparator(), " "), "");
            String suffix = "";
            String delimiter;
            CommandInputEntity.MultipleDelimiter multipleDelimiter =
                    CommandInputEntity.MultipleDelimiter.getByName(ci.multipleDelimiter());
            switch (multipleDelimiter) {
                case QUOTED_SPACE:
                    delimiter = " ";
                    prefix += "'";
                    suffix = "'";
                    break;
                case SPACE:
                    delimiter = " ";
                    break;
                case COMMA:
                    delimiter = ",";
                    break;
                case FLAG:
                    delimiter = " " + prefix;
                    break;
                default:
                    // Should never happen per CommandInputEntity.MultipleDelimiter.getByName
                    throw new CommandResolutionException("Invalid multiple-delimiter for \"" + ci.name() + "\"");
            }

            String value = prefix + String.join(delimiter, commandInputChildrenValues) + suffix;
            log.debug("Using value \"{}\" on the command line.", value);
            return value;
        }

        @Nonnull
        private String getValueForCommandLine(final CommandInput input, final String resolvedInputValue)
                throws CommandResolutionException {

            log.debug("Resolving command-line value.");
            if (StringUtils.isBlank(resolvedInputValue)) {
                log.debug("Input value is blank. Using value \"\" on the command line.");
                return "";
            }
            List<String> valueList = null;
            if (input.isMultiSelect()) {
                try {
                    valueList = mapper.readValue(resolvedInputValue, new TypeReference<List<String>>() {});
                } catch (IOException e) {
                    // Not a list, treat as string
                }
            }

            if (valueList != null) {
                // handle multiples
                return getMultipleValuesForCommandLine(input, valueList);
            } else {
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
            log.info("Attempting to pull value from parent using matcher \"{}\".", jsonPathSearch);

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

        @Nonnull
        private String getMatcherFromValue(final String valueMatchProperty, final String value, final boolean multiple) {
            if (StringUtils.isBlank(value)) return "";

            // Parse runtime value / default value as array if multiple = true
            if (multiple) {
                try {
                    // Test that "value" is an array
                    mapper.readValue(value, new TypeReference<List<String>>() {});
                    return String.format("@.%s in %s", valueMatchProperty, value);
                } catch (IOException e) {
                    // Ignore
                }
            }
            return String.format("@.%s == '%s'", valueMatchProperty, value);
        }

        @Nullable
        private <T extends XnatModelObject> List<T> matchChildFromParent(final String parentJson,
                                                                         final String value,
                                                                         final String childKey,
                                                                         final String valueMatchProperty,
                                                                         final String matcherFromInput,
                                                                         final TypeRef<List<T>> typeRef,
                                                                         final boolean multiple) {

            final String matcherFromValue = getMatcherFromValue(valueMatchProperty, value, multiple);
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
        private <T extends XnatModelObject> T resolveXnatObject(final @Nullable String value,
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

                if (!(uri instanceof ArchiveItemURI)) {
                    log.debug("Cannot interpret \"{}\" as a URI.", value);
                } else {
                    try {
                        newModelObject = uriToModelObject.apply((ArchiveItemURI) uri);
                    } catch (Throwable e) {
                    	log.error("",e);
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
                log.debug("All attempts have failed. The {} object is null.", modelName);
                return null;
            }
            log.debug("Successfully instantiated a {}.", modelName);

            T aMatch = null;
            if (StringUtils.isNotBlank(matcher)) {
                // To apply the JSONPath matcher, we have to serialize our object to JSON.
                log.debug("Serializing {} to JSON to apply matcher.", modelName);
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
                        log.debug("{} did not match matcher \"{}\".", modelName, matcher);
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

            final Map<String, List<CommandWrapperOutput>> wrapperOutputsByHandledCommandOutputName = new HashMap<>();
            final Map<String, CommandWrapperOutput> wrapperOutputsByName = new HashMap<>();
            if (commandWrapper.outputHandlers() != null) {
                for (final CommandWrapperOutput commandWrapperOutput : commandWrapper.outputHandlers()) {
                    if (wrapperOutputsByHandledCommandOutputName.containsKey(commandWrapperOutput.commandOutputName())) {
                        wrapperOutputsByHandledCommandOutputName.get(commandWrapperOutput.commandOutputName()).add(commandWrapperOutput);
                    } else {
                        final List<CommandWrapperOutput> outputs = new ArrayList<>();
                        outputs.add(commandWrapperOutput);
                        wrapperOutputsByHandledCommandOutputName.put(commandWrapperOutput.commandOutputName(), outputs);
                    }

                    wrapperOutputsByName.put(commandWrapperOutput.name(), commandWrapperOutput);
                }
            }

            final Map<String, ResolvedCommandOutput> resolvedCommandOutputsByOutputHandlerName = new HashMap<>();
            for (final CommandOutput commandOutput : command.outputs()) {
                final List<ResolvedCommandOutput> resolvedOutputList = resolveCommandOutput(commandOutput, resolvedInputTrees, resolvedInputValuesByReplacementKey,
                        wrapperOutputsByHandledCommandOutputName, wrapperOutputsByName);
                if (resolvedOutputList == null || resolvedOutputList.size() == 0) {
                    continue;
                }

                for (final ResolvedCommandOutput resolvedCommandOutput : resolvedOutputList) {
                    log.debug("Finished with resolved output \"{}\".", resolvedCommandOutput.name());
                    resolvedCommandOutputsByOutputHandlerName.put(resolvedCommandOutput.fromOutputHandler(), resolvedCommandOutput);
                }
            }

            // Add resolved outputs in the order of the output handlers
            for (final CommandWrapperOutput commandWrapperOutput : commandWrapper.outputHandlers()) {
                final ResolvedCommandOutput resolvedCommandOutput = resolvedCommandOutputsByOutputHandlerName.get(commandWrapperOutput.name());
                if (resolvedCommandOutput == null) {
                    log.debug("Command wrapper output handler {} has no resolved output. Is... is this an error?", commandWrapperOutput.name());
                    continue;
                }

                log.debug("Adding resolved output \"{}\" to resolved command.", resolvedCommandOutput.name());
                resolvedOutputs.add(resolvedCommandOutput);
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

        private List<ResolvedCommandOutput> resolveCommandOutput(final CommandOutput commandOutput,
                                                                 final List<ResolvedInputTreeNode<? extends Input>> resolvedInputTrees,
                                                                 final Map<String, String> resolvedInputValuesByReplacementKey,
                                                                 final Map<String, List<CommandWrapperOutput>> wrapperOutputsByHandledCommandOutputName,
                                                                 final Map<String, CommandWrapperOutput> wrapperOutputsByName)
                throws CommandResolutionException {
            log.info("Resolving command output \"{}\".", commandOutput.name());
            log.debug("{}", commandOutput);

            final List<ResolvedCommandOutput> resolvedCommandOutputs = new ArrayList<>();

            final List<CommandWrapperOutput> commandOutputHandlers = wrapperOutputsByHandledCommandOutputName.get(commandOutput.name());
            if (commandOutputHandlers == null || commandOutputHandlers.size() == 0) {
                throw new CommandResolutionException(String.format("No wrapper output handler was configured to handle command output \"%s\".", commandOutput.name()));
            }
            log.debug("Found {} Output Handlers for Command output \"{}\".", commandOutputHandlers.size(), commandOutput.name());
            boolean outputHasAtLeastOneLegitHandler = false;

            for (final CommandWrapperOutput commandOutputHandler : commandOutputHandlers) {
                log.debug("Found Output Handler \"{}\" for Command output \"{}\". Checking if its target \"{}\" is an input.",
                        commandOutputHandler.name(), commandOutput.name(), commandOutputHandler.targetName());

                // Here's how these outputs can be structured
                // 1. They will upload back to some input object. This is like they have a session come in as
                //      input, and they want to create a new resource back on that session.
                // 2. They will upload to some object that is also created by an output. For instance, one
                //      output is used to create an assessor, then other outputs are used to create resources
                //      on that assessor.

                // First check if
                //   A. The output is supposed to upload back to an input object
                //   B. That input object is upload-to-able
                final ResolvedInputValue parentInputResolvedValue = getInputValueByName(commandOutputHandler.targetName(), resolvedInputTrees);
                if (parentInputResolvedValue != null) {
                    // If we are here, we know the target is an input and we have its value.
                    log.debug("Handler \"{}\"'s target is input \"{}\". Checking if the input's value makes a legit target.", commandOutputHandler.name(), commandOutputHandler.targetName());

                    // Next check that the handler target input's value is an XNAT object
                    final String parentValue;
                    final XnatModelObject parentXnatObject = parentInputResolvedValue.xnatModelObject();
                    if (parentXnatObject != null) {
                        // If we have an XNAT object, use the URI
                        parentValue = parentXnatObject.getUri();
                    } else {
                        // If not, the input's value needs to be a URI
                        final String parentValueMayBeNull = parentInputResolvedValue.value();
                        parentValue = parentValueMayBeNull != null ? parentValueMayBeNull : "";
                    }

                    URIManager.DataURIA uri = null;
                    try {
                        uri = UriParserUtils.parseURI(parentValue.startsWith("/archive") ? parentValue : "/archive" + parentValue);
                    } catch (MalformedURLException ignored) {
                        // ignored
                    }

                    if (!(uri instanceof ArchiveItemURI)) {
                        final String message = String.format("Cannot resolve output \"%s\". " +
                                        "Input \"%s\" is supposed to handle the output, but it does not have an XNAT object value.",
                                commandOutput.name(), commandOutputHandler.targetName());
                        if (Boolean.TRUE.equals(commandOutput.required()) && !outputHasAtLeastOneLegitHandler) {
                            throw new CommandResolutionException(message);
                        } else {
                            log.error("Skipping handler \"{}\".", commandOutputHandler.name());
                            log.error(message);
                            continue;
                        }
                    }

                    // Next check that the user has edit permissions on the handler target input's XNAT object
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
                                commandOutput.name(), commandOutputHandler.targetName(),
                                userI.getLogin(), parentValue);
                        if (Boolean.TRUE.equals(commandOutput.required()) && !outputHasAtLeastOneLegitHandler) {
                            throw new CommandResolutionException(message);
                        } else {
                            log.error("Skipping handler \"{}\".", commandOutputHandler.name());
                            log.error(message);
                            continue;
                        }
                    }
                } else {
                    // If we are here, either the output handler is uploading to another output,
                    // or its target is just wrong and we can't find anything

                    log.debug("Handler \"{}\"'s target \"{}\" is not an input with a unique value. Is it another output handler?", commandOutputHandler.name(), commandOutputHandler.targetName());

                    final CommandWrapperOutput otherOutputHandler = wrapperOutputsByName.get(commandOutputHandler.targetName());
                    if (otherOutputHandler == null) {
                        // Looks like we can't find an input or an output to which this handler intends to upload its output
                        final String message = String.format("Cannot resolve output \"%s\". " +
                                        "The handler says the output is supposed to be handled by \"%s\", " +
                                        "but either that isn't an input, or an output, or maybe the input does not have a uniquely resolved value.",
                                commandOutput.name(), commandOutputHandler.targetName());
                        if (Boolean.TRUE.equals(commandOutput.required()) && !outputHasAtLeastOneLegitHandler) {
                            throw new CommandResolutionException(message);
                        } else {
                            log.error("Skipping handler \"{}\".", commandOutputHandler.name());
                            log.error(message);
                            continue;
                        }
                    }

                    log.debug("Handler \"{}\"'s target \"{}\" is another output handler. Checking if the two handlers' types are compatible.", commandOutputHandler.name(), commandOutputHandler.targetName());

                    // Ok, we have found an output. Make sure it can handle another output.
                    // Basically, *this* output handler needs to make a resource, and the
                    // *target* output handler needs to make an assessor or a scan.
                    final boolean thisHandlerIsAResource = commandOutputHandler.type().equals(CommandWrapperOutputEntity.Type.RESOURCE.getName());
                    final boolean targetHandlerIsSupported = CommandWrapperOutputEntity.Type.supportedParentOutputTypeNames()
                            .contains(otherOutputHandler.type());
                    if (!(thisHandlerIsAResource && targetHandlerIsSupported)) {
                        // This output is supposed to be uploaded to an object that is created by another output,
                        // but that can only happen when the first (parent) output is an assessor or a scan
                        // and any subsequent (child) outputs are resources
                        final String message = String.format("Cannot resolve handler \"%1$s\". " +
                                        "Handler \"%1$s\" has type \"%2$s\"; target handler \"%3$s\" has type \"%4$s\". " +
                                        "Handler \"%1$s\" must be type Resource, target handler \"%3$s\" needs to be type %5$s.",
                                commandOutputHandler.name(), commandOutputHandler.type(),
                                commandOutputHandler.targetName(), otherOutputHandler.type(),
                                String.join(" OR ", CommandWrapperOutputEntity.Type.supportedParentOutputTypeNames()));
                        if (Boolean.TRUE.equals(commandOutput.required()) && !outputHasAtLeastOneLegitHandler) {
                            throw new CommandResolutionException(message);
                        } else {
                            log.error("Skipping handler \"{}\".", commandOutputHandler.name());
                            log.error(message);
                            continue;
                        }
                    }
                }

                log.debug("Handler \"{}\" for command output \"{}\" looks legit.", commandOutputHandler.name(), commandOutput.name());
                resolvedCommandOutputs.add(ResolvedCommandOutput.builder()
                        .name(commandOutput.name()+":"+commandOutputHandler.name())
                        .fromCommandOutput(commandOutput.name())
                        .fromOutputHandler(commandOutputHandler.name())
                        .required(commandOutput.required())
                        .mount(commandOutput.mount())
                        .glob(commandOutput.glob())
                        .type(commandOutputHandler.type())
                        .handledBy(commandOutputHandler.targetName())
                        .viaWrapupCommand(commandOutputHandler.viaWrapupCommand())
                        .path(resolveTemplate(commandOutput.path(), resolvedInputValuesByReplacementKey))
                        .label(resolveTemplate(commandOutputHandler.label(), resolvedInputValuesByReplacementKey))
                        .format(resolveTemplate(commandOutputHandler.format(), resolvedInputValuesByReplacementKey))
                        .build());
                outputHasAtLeastOneLegitHandler = true;
            }

            return resolvedCommandOutputs;
        }

        @Nonnull
        private String resolveCommandLine(final @Nonnull Map<String, String> resolvedInputCommandLineValuesByReplacementKey)
                throws CommandResolutionException {
            return resolveCommandLine(resolvedInputCommandLineValuesByReplacementKey, command.commandLine());
        }

        @Nonnull
        private String resolveCommandLine(final @Nonnull Map<String, String> resolvedInputCommandLineValuesByReplacementKey,
                                          String commandLine)
                throws CommandResolutionException {
            log.info("Resolving command-line string: {}", commandLine);

            // Resolve the command-line string using the resolved command-line values
            log.debug("Using resolved command-line values to resolve command-line template string.");
            final String resolvedCommandLine = resolveTemplate(commandLine, resolvedInputCommandLineValuesByReplacementKey);

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
            Map<String, ResolvedInputTreeNode<? extends Input>> mountSourceInputs = Maps.newHashMap();
            for (final ResolvedInputTreeNode<? extends Input> rootNode : resolvedInputTrees) {
                mountSourceInputs.putAll(findMountSourceInputs(rootNode));
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
        private List<ResolvedCommand> resolveWrapupCommands(final List<ResolvedCommandOutput> resolvedCommandOutputs,
                                                            final List<ResolvedCommandMount> resolvedCommandMounts)
                throws CommandResolutionException {
            final List<ResolvedCommand> resolvedWrapupCommands = new ArrayList<>();
            Map<String, ResolvedCommandMount> resolvedCommandMountMap = null;
            for (final ResolvedCommandOutput resolvedCommandOutput : resolvedCommandOutputs) {
                if (resolvedCommandOutput.viaWrapupCommand() != null) {
                    log.debug("Found wrapup command \"{}\" for output handler \"{}\".", resolvedCommandOutput.viaWrapupCommand(), resolvedCommandOutput.name());
                    final String outputMountName = resolvedCommandOutput.mount();
                    if (resolvedCommandMountMap == null) {
                        resolvedCommandMountMap = new HashMap<>();
                        for (final ResolvedCommandMount resolvedCommandMount : resolvedCommandMounts) {
                            resolvedCommandMountMap.put(resolvedCommandMount.name(), resolvedCommandMount);
                        }
                    }
                    final ResolvedCommandMount resolvedCommandMount = resolvedCommandMountMap.get(outputMountName);
                    assert resolvedCommandMount != null; // Command output must refer to a mount that exists, otherwise command would have failed validation.

                    final String writableMountPath;
                    try {
                        writableMountPath = getBuildDirectory();
                    } catch (IOException e) {
                        throw new CommandResolutionException("Could not create build directory.", e);
                    }

                    resolvedWrapupCommands.add(resolveSpecialCommandType(CommandType.DOCKER_WRAPUP, resolvedCommandOutput.viaWrapupCommand(), resolvedCommandMount.xnatHostPath(), writableMountPath, resolvedCommandOutput.name()));
                }
            }

            return resolvedWrapupCommands;
        }

        @Nonnull
        private Map<String, ResolvedInputTreeNode<? extends Input>> findMountSourceInputs(final ResolvedInputTreeNode<? extends Input> node) {
            Map<String, ResolvedInputTreeNode<? extends Input>> mountSourceInputs = Maps.newHashMap();

            final Input input = node.input();
            log.debug("Checking if input \"{}\" provides files to a mount.", input.name());
            if (input instanceof CommandWrapperInput) {
                final CommandWrapperInput commandWrapperInput = (CommandWrapperInput) input;
                if (StringUtils.isNotBlank(commandWrapperInput.providesFilesForCommandMount())) {
                    log.debug("Input \"{}\" provides files to mount \"{}\".",
                            input.name(), commandWrapperInput.providesFilesForCommandMount());
                    mountSourceInputs.put(commandWrapperInput.providesFilesForCommandMount(), node);
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
                        mountSourceInputs.putAll(findMountSourceInputs(child));
                    }
                }
            }
            log.debug("Done checking input \"{}\".", input.name());
            return mountSourceInputs;
        }

        @Nonnull
        private ResolvedCommandMount resolveCommandMount(final @Nonnull CommandMount commandMount,
                                                         final @Nullable ResolvedInputTreeNode<? extends Input> resolvedSourceInput,
                                                         final @Nonnull Map<String, String> resolvedInputValuesByReplacementKey)
                throws CommandResolutionException {
            log.debug("Resolving command mount \"{}\".", commandMount.name());

            final PartiallyResolvedCommandMount.Builder partiallyResolvedCommandMountBuilder = PartiallyResolvedCommandMount.builder()
                    .name(commandMount.name())
                    .writable(commandMount.writable())
                    .containerPath(resolveTemplate(commandMount.path(), resolvedInputValuesByReplacementKey));

            String exceptionMsg = null;
            if (resolvedSourceInput == null) {
                log.debug("Command mount \"{}\" has no inputs that provide it files. Assuming it is an output mount.", commandMount.name());
                partiallyResolvedCommandMountBuilder.writable(true);
            } else {
                final Input input = resolvedSourceInput.input();
                final String inputName = input.name();
                final String inputType = input.type();
                log.debug("Mount \"{}\" has source input \"{}\" with type \"{}\".",
                        commandMount.name(),
                        inputName,
                        inputType);

                final List<ResolvedInputTreeValueAndChildren> valuesAndChildren = resolvedSourceInput.valuesAndChildren();
                if (valuesAndChildren.size() > 1) {
                    final String message = String.format("Input \"%s\" has multiple resolved values. We can only use inputs with a single resolved value.", inputName);
                    log.error(message);
                    throw new CommandMountResolutionException(message, commandMount);
                }
                final ResolvedInputTreeValueAndChildren resolvedInputTreeValueAndChildren = valuesAndChildren.get(0);
                final ResolvedInputValue resolvedInputValue = resolvedInputTreeValueAndChildren.resolvedValue();

                String filePath = null;
                String rootDirectory = null;
                String uri = null;
                if (inputType.equals(PROJECT.getName()) || inputType.equals(SESSION.getName()) ||
                        inputType.equals(SCAN.getName()) || inputType.equals(ASSESSOR.getName()) ||
                        inputType.equals(RESOURCE.getName()) || inputType.equals(FILE.getName())) {
                    log.debug("Looking for directory on source input.");
                    final XnatModelObject xnatModelObject = resolvedInputValue.xnatModelObject();
                    if (xnatModelObject == null) {
                        final String message = "Cannot resolve mount URI. Resolved XnatModelObject is null.";
                        log.error(message);
                        throw new CommandResolutionException(message);
                    }
                    uri = xnatModelObject.getUri();

                    if (xnatModelObject instanceof XnatFile) {
                        filePath = ((XnatFile) xnatModelObject).getPath();
                    } else {
                        rootDirectory = JsonPath.parse(resolvedInputValue.jsonValue()).read("directory", String.class);
                    }
                } else if (inputType.equals(DIRECTORY.getName()) || inputType.equals(FILES.getName())) {
                    // TODO add support for these
                    exceptionMsg = "Unsupported input mount source type \"" + inputType + "\" (though coming soon).";
                } else {
                    exceptionMsg = String.format("I don't know how to provide files to a mount from an input of type \"%s\".", inputType);
                    log.error(exceptionMsg);
                }

                if (StringUtils.isBlank(rootDirectory) && StringUtils.isBlank(filePath)) {
                    String message = "Source input has no local path.";
                    if (log.isDebugEnabled()) {
                        message += "\ninput: " + resolvedSourceInput;
                    }
                    log.error(message);
                }

                final String viaSetupCommand = (CommandWrapperInput.class.isAssignableFrom(input.getClass())) ?
                        ((CommandWrapperInput) input).viaSetupCommand() : null;

                log.debug("Done resolving mount \"{}\", source input \"{}\".",
                        commandMount.name(),
                        inputName);
                partiallyResolvedCommandMountBuilder
                        .fromWrapperInput(inputName)
                        .viaSetupCommand(viaSetupCommand)
                        .fromUri(uri)
                        .fromRootDirectory(rootDirectory)
                        .fromFilePath(filePath);
            }

            PartiallyResolvedCommandMount partiallyResolvedCommandMount = partiallyResolvedCommandMountBuilder.build();
            if (exceptionMsg != null) {
                throw new ContainerMountResolutionException(exceptionMsg, partiallyResolvedCommandMount);
            }

            final ResolvedCommandMount resolvedCommandMount = transportMount(partiallyResolvedCommandMount);

            log.debug("Done resolving command mount \"{}\".", commandMount.name());
            return resolvedCommandMount;
        }

        private ResolvedCommandMount transportMount(final PartiallyResolvedCommandMount partiallyResolvedCommandMount)
                throws CommandResolutionException {

            final String resolvedCommandMountName = partiallyResolvedCommandMount.name();
            final ResolvedCommandMount.Builder resolvedCommandMountBuilder = partiallyResolvedCommandMount.toResolvedCommandMountBuilder();

            // First, figure out what we have.
            // Do we have source files? A source directory?
            // Can we mount a directory directly, or should we copy the contents to a build directory?
            // We may need to copy, or may be able to mount directly.
            String localPath;
            if (StringUtils.isNotBlank(partiallyResolvedCommandMount.fromWrapperInput())) {
                String srcPath = partiallyResolvedCommandMount.fromRootDirectory();
                boolean srcIsFile = false;
                if (StringUtils.isBlank(srcPath)) {
                    // maybe it's a file
                    srcPath = partiallyResolvedCommandMount.fromFilePath();
                    srcIsFile = StringUtils.isNotBlank(srcPath);
                }

                if (StringUtils.isBlank(srcPath)) {
                    final String message = String.format("Mount \"%s\" should have a root path but does not.",
                            resolvedCommandMountName);
                    log.error(message);
                    throw new ContainerMountResolutionException(message, partiallyResolvedCommandMount);
                }

                // Determine if this particular URI has remote files
                boolean hasRemoteFiles = false;
                if (srcIsFile) {
                    // If the file isn't local, assume it's remote (attempting to pull with throw an exception if it isn't)
                    hasRemoteFiles = !(new File(srcPath).exists());
                } else {
                    final String uri = partiallyResolvedCommandMount.fromUri();
                    if (StringUtils.isNotBlank(uri)) {
                        try {
                            hasRemoteFiles = catalogService.hasRemoteFiles(userI, uri);
                        } catch (ClientException | ServerException e) {
                            throw new CommandResolutionException(e.getMessage());
                        }
                    }
                }

                final boolean writable = partiallyResolvedCommandMount.writable();
                if (writable || hasRemoteFiles) {
                    // The mount has a source path and is set to "writable" or may have remote files. We must copy files
                    // from the root path into a writable build location.
                    try {
                        localPath = getBuildDirectory();
                    } catch (IOException e) {
                        throw new ContainerMountResolutionException("Could not create build directory.",
                                partiallyResolvedCommandMount, e);
                    }
                    if (srcIsFile) {
                        localPath = Paths.get(localPath).resolve(Paths.get(srcPath).getFileName()).toString();
                    }

                    try {
                        if (hasRemoteFiles) {
                            log.debug("Pulling any remote files into mount \"{}\".", resolvedCommandMountName);
                            catalogService.pullResourceCatalogsToDestination(Users.getAdminUser(),
                                    partiallyResolvedCommandMount.fromUri(), srcPath, localPath);
                        } else {
                            // CS-54 Copy all files out of the root directory to a build directory.
                            log.debug("Mount \"{}\" has a root directory and is set to \"writable\". Copying all files " +
                                    "from the root directory to build directory.", resolvedCommandMountName);
                            if (srcIsFile) {
                                Files.copy(Paths.get(srcPath), Paths.get(localPath), StandardCopyOption.REPLACE_EXISTING);
                            } else {
                                FileUtils.copyDirectory(new File(srcPath), new File(localPath));
                            }
                        }
                    } catch (IOException e) {
                        throw new ContainerMountResolutionException("Could not copy archive path " + srcPath +
                                " into writable build path " + localPath, partiallyResolvedCommandMount, e);
                    } catch (ServerException | ClientException e) {
                        throw new ContainerMountResolutionException("Could not pull remote files into build " +
                                "path " + localPath + ": " + e.getMessage(), partiallyResolvedCommandMount, e);
                    }
                } else {
                    // The source can be directly mounted
                    log.debug("Mount \"{}\" has a root path and is not set to \"writable\". The root path can be " +
                            "mounted directly into the container.", resolvedCommandMountName);
                    localPath = srcPath;
                }
            } else {
                log.debug("Mount \"{}\" has no input files. Ensuring mount is set to \"writable\" and creating new " +
                        "build directory.", resolvedCommandMountName);
                try {
                    localPath = getBuildDirectory();
                } catch (IOException e) {
                    throw new ContainerMountResolutionException("Could not create build directory.", partiallyResolvedCommandMount, e);
                }
                resolvedCommandMountBuilder.writable(true);
            }

            final String pathToMount;

            if (StringUtils.isNotBlank(partiallyResolvedCommandMount.viaSetupCommand())) {
                log.debug("Command mount will be set up with setup command {}.", partiallyResolvedCommandMount.viaSetupCommand());
                // If there is a setup command, we do a switcheroo.
                // Normally, we would mount localDirectory into this mount. Instead, we mount localDirectory
                // into the setup command as its input, along with another writable build directory as its output.
                // Then we mount the output build directory into this mount.
                // In that way, the setup command will write to the mount whatever files we need to find.
                final String writableMountPath;
                try {
                    writableMountPath = getBuildDirectory();
                } catch (IOException e) {
                    throw new ContainerMountResolutionException("Could not create build directory.", partiallyResolvedCommandMount, e);
                }
                resolvedSetupCommands.add(resolveSpecialCommandType(CommandType.DOCKER_SETUP,
                        partiallyResolvedCommandMount.viaSetupCommand(), localPath, writableMountPath,
                        partiallyResolvedCommandMount.name()));
                pathToMount = writableMountPath;
            } else {
                pathToMount = localPath;
            }

            log.debug("Setting mount \"{}\" xnat host path to \"{}\".", resolvedCommandMountName, pathToMount);
            resolvedCommandMountBuilder.xnatHostPath(pathToMount);

            // log.debug("Transporting mount \"{}\".", resolvedCommandMountName);
            // final Path pathOnContainerHost = transportService.transport(containerHost, Paths.get(buildDirectory));
            // TODO transporting is currently a no-op, and the code is simpler if we don't pretend that we are doing something here.

            // Translate paths from XNAT prefix to container host prefix
            final String containerHostPath = getMountContainerHostPath(pathToMount);
            log.debug("Setting mount \"{}\" container host path to \"{}\".", resolvedCommandMountName, containerHostPath);
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

        @Nullable
        private List<String> resolveSwarmConstraints() {
            DockerServerBase server;
            try {
                server = dockerServerService.getServer();
            } catch (NotFoundException e) {
                log.error(e.getMessage(), e);
                return null;
            }
            if (!server.swarmMode()) {
                return null;
            }
            List<DockerServerBase.DockerServerSwarmConstraint> constraints = server.swarmConstraints();
            if (constraints == null || constraints.isEmpty()) {
                return null;
            }

            log.debug("Checking for swarm node constraints");
            List<String> constraintsList = new ArrayList<>();

            // Get user inputs
            Map<String, String> userConstraintsMap = null;
            String constraintsJson = inputValues.get(swarmConstraintsTag);
            if (constraintsJson != null) {
                try {
                    List<LaunchUi.LaunchUiServerConstraintSelected> userConstraints = mapper.readValue(constraintsJson,
                            new TypeReference<List<LaunchUi.LaunchUiServerConstraintSelected>>() {});
                    userConstraintsMap = new HashMap<>();
                    for (LaunchUi.LaunchUiServerConstraintSelected c : userConstraints) {
                        userConstraintsMap.put(c.attribute(), c.value());
                    }

                } catch (IOException e) {
                    log.error(e.getMessage(), e);
                }
            }

            // Populate list from user inputs & server "defaults"
            for (DockerServerBase.DockerServerSwarmConstraint constraint : constraints) {
                if (constraint.userSettable() && userConstraintsMap != null) {
                    // If the constraint is user settable, only add it if we have non-empty values from user input map
                    // don't default to first value or whatever, just let Swarm do its default thing
                    String value = userConstraintsMap.get(constraint.attribute());
                    if (StringUtils.isNotBlank(value)) {
                        constraintsList.add(constraint.asStringConstraint(value));
                    }
                } else {
                    constraintsList.add(constraint.asStringConstraint());
                }
            }

            constraintsList = constraintsList.isEmpty() ? null : constraintsList;
            return constraintsList;
        }
    }

    @Nonnull
    private String getBuildDirectory() throws IOException {
        final String rootBuildPath = siteConfigPreferences.getBuildPath();
        final String uuid = UUID.randomUUID().toString();
        final String buildDir = FilenameUtils.concat(rootBuildPath, uuid);
        final Path created = Files.createDirectory(Paths.get(buildDir));
        created.toFile().setWritable(true);
        return buildDir;
    }
}
