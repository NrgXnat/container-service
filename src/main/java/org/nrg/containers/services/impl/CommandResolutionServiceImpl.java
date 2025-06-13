package org.nrg.containers.services.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.InvalidJsonException;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.ParseContext;
import com.jayway.jsonpath.TypeRef;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import com.jayway.jsonpath.spi.mapper.MappingException;
import lombok.extern.slf4j.Slf4j;
import org.ahocorasick.trie.Emit;
import org.ahocorasick.trie.Trie;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Triple;
import org.nrg.action.ClientException;
import org.nrg.action.ServerException;
import org.nrg.containers.exceptions.*;
import org.nrg.containers.model.command.auto.Command;
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
import org.nrg.containers.model.command.auto.LaunchUi;
import org.nrg.containers.model.command.auto.PreresolvedInputTreeNode;
import org.nrg.containers.model.command.auto.ResolvedCommand;
import org.nrg.containers.model.command.auto.ResolvedCommand.PartiallyResolvedCommand;
import org.nrg.containers.model.command.auto.ResolvedCommand.ResolvedCommandOutput;
import org.nrg.containers.model.command.auto.ResolvedCommandMount;
import org.nrg.containers.model.command.auto.ResolvedInputTreeNode;
import org.nrg.containers.model.command.auto.ResolvedInputTreeNode.ResolvedInputTreeValueAndChildren;
import org.nrg.containers.model.command.auto.ResolvedInputValue;
import org.nrg.containers.model.command.entity.CommandInputEntity;
import org.nrg.containers.model.command.entity.CommandType;
import org.nrg.containers.model.command.entity.CommandWrapperInputType;
import org.nrg.containers.model.command.entity.CommandWrapperOutputEntity;
import org.nrg.containers.model.server.docker.Backend;
import org.nrg.containers.model.server.docker.DockerServerBase;
import org.nrg.containers.model.xnat.Assessor;
import org.nrg.containers.model.xnat.Project;
import org.nrg.containers.model.xnat.ProjectAsset;
import org.nrg.containers.model.xnat.Resource;
import org.nrg.containers.model.xnat.Scan;
import org.nrg.containers.model.xnat.Session;
import org.nrg.containers.model.xnat.Subject;
import org.nrg.containers.model.xnat.SubjectAssessor;
import org.nrg.containers.model.xnat.XnatFile;
import org.nrg.containers.model.xnat.XnatModelObject;
import org.nrg.containers.secrets.ResolvedSecret;
import org.nrg.containers.secrets.Secret;
import org.nrg.containers.secrets.SecretDestination;
import org.nrg.containers.services.CommandResolutionService;
import org.nrg.containers.services.CommandService;
import org.nrg.containers.services.ContainerSecretService;
import org.nrg.containers.services.DockerServerService;
import org.nrg.containers.services.DockerService;
import org.nrg.containers.utils.ContainerServicePermissionUtils;
import org.nrg.containers.utils.ContainerUtils;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.security.helpers.Permissions;
import org.nrg.xdat.security.helpers.Users;
import org.nrg.xdat.services.cache.UserDataCache;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.archive.ResourceData;
import org.nrg.xnat.helpers.uri.URIManager;
import org.nrg.xnat.helpers.uri.URIManager.ArchiveItemURI;
import org.nrg.xnat.helpers.uri.UriParserUtils;
import org.nrg.xnat.helpers.uri.archive.ScanURII;
import org.nrg.xnat.services.archive.CatalogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.xml.sax.InputSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
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
import static org.nrg.containers.model.command.entity.CommandWrapperInputType.FILE_INPUT;
import static org.nrg.containers.model.command.entity.CommandWrapperInputType.NUMBER;
import static org.nrg.containers.model.command.entity.CommandWrapperInputType.PROJECT;
import static org.nrg.containers.model.command.entity.CommandWrapperInputType.PROJECT_ASSET;
import static org.nrg.containers.model.command.entity.CommandWrapperInputType.RESOURCE;
import static org.nrg.containers.model.command.entity.CommandWrapperInputType.SCAN;
import static org.nrg.containers.model.command.entity.CommandWrapperInputType.SESSION;
import static org.nrg.containers.model.command.entity.CommandWrapperInputType.STRING;
import static org.nrg.containers.model.command.entity.CommandWrapperInputType.SUBJECT;
import static org.nrg.containers.model.command.entity.CommandWrapperInputType.SUBJECT_ASSESSOR;

@Slf4j
@Service
public class CommandResolutionServiceImpl implements CommandResolutionService {
    private final static String JSONPATH_SUBSTRING_REGEX = "\\^(wrapper:)?(.+)\\^";
    private final static Pattern JSONPATH_SUBSTRING = Pattern.compile(JSONPATH_SUBSTRING_REGEX);

    private final CommandService commandService;

    private final DockerServerService dockerServerService;
    private final SiteConfigPreferences siteConfigPreferences;
    private final DockerService dockerService;
    private final CatalogService catalogService;
    private final UserDataCache userDataCache;
    private final ContainerSecretService secretService;

    private final ObjectMapper mapper;
    private final ParseContext jsonpathContext;
    private final ParseContext alwaysListParseContext;

    public static final String swarmConstraintsTag = "swarm-constraints";

    @Autowired
    public CommandResolutionServiceImpl(final CommandService commandService,
                                        final DockerServerService dockerServerService,
                                        final SiteConfigPreferences siteConfigPreferences,
                                        final ObjectMapper mapper,
                                        final DockerService dockerService,
                                        final CatalogService catalogService,
                                        final UserDataCache userDataCache,
                                        final ContainerSecretService secretService) {
        this.commandService = commandService;
        this.dockerServerService = dockerServerService;
        this.siteConfigPreferences = siteConfigPreferences;
        this.dockerService = dockerService;
        this.catalogService = catalogService;
        this.userDataCache = userDataCache;
        this.secretService = secretService;

        this.mapper = mapper;
        final Configuration jsonpathJackson = Configuration.builder()
                .mappingProvider(new JacksonMappingProvider(mapper))
                .jsonProvider(new JacksonJsonProvider(mapper))
                .build();
        jsonpathContext = JsonPath.using(jsonpathJackson);
        final Configuration alwaysListConfiguration = jsonpathJackson.addOptions(Option.ALWAYS_RETURN_LIST);
        alwaysListParseContext = JsonPath.using(alwaysListConfiguration);
    }

    @Override
    public PartiallyResolvedCommand preResolve(final long wrapperId,
                                               final Map<String, String> inputValues,
                                               final UserI userI)
            throws NotFoundException, CommandResolutionException, UnauthorizedException {
        return preResolve(null, 0, null, wrapperId, inputValues, userI);
    }

    @Override
    public PartiallyResolvedCommand preResolve(final long commandId,
                                               final String wrapperName,
                                               final Map<String, String> inputValues,
                                               final UserI userI)
            throws NotFoundException, CommandResolutionException, UnauthorizedException {
        return preResolve(null, commandId, wrapperName, 0, inputValues, userI);
    }

    @Override
    public PartiallyResolvedCommand preResolve(final String project,
                                               final long wrapperId,
                                               final Map<String, String> inputValues,
                                               final UserI userI)
            throws NotFoundException, CommandResolutionException, UnauthorizedException {
        return preResolve(project, 0, null, wrapperId, inputValues, userI);
    }

    @Override
    public PartiallyResolvedCommand preResolve(final String project,
                                               final long commandId,
                                               final String wrapperName,
                                               final Map<String, String> inputValues,
                                               final UserI userI)
            throws NotFoundException, CommandResolutionException, UnauthorizedException {
        return preResolve(project, commandId, wrapperName, 0, inputValues, userI);
    }

    @Override
    public PartiallyResolvedCommand preResolve(final String project,
                                               final long commandId,
                                               final String wrapperName,
                                               final long wrapperId,
                                               final Map<String, String> inputValues,
                                               final UserI userI)
            throws NotFoundException, CommandResolutionException, UnauthorizedException {
        return preResolve(commandService.getAndConfigure(project, commandId, wrapperName, wrapperId), inputValues, project, userI);
    }

    @Override
    public PartiallyResolvedCommand preResolve(final ConfiguredCommand configuredCommand,
                                               final Map<String, String> inputValues,
                                               final String project,
                                               final UserI userI)
            throws CommandResolutionException, UnauthorizedException {
        try {
            final CommandResolutionHelper helper = new CommandResolutionHelper(configuredCommand, inputValues, project, userI, null);
            return helper.preResolve();
        } catch (CommandResolutionException | UnauthorizedException e) {
            log.error("Could not preresolve command", e);
            throw e;
        }
    }

    @Override
    @Nonnull
    public ResolvedCommand resolve(final ConfiguredCommand configuredCommand,
                                   final Map<String, String> inputValues,
                                   final UserI userI)
            throws NotFoundException, CommandResolutionException, UnauthorizedException {
        return resolve(configuredCommand, inputValues, null, userI, null);
    }

    @Override
    @Nonnull
    public ResolvedCommand resolve(final ConfiguredCommand configuredCommand,
                                   final Map<String, String> inputValues,
                                   final String project,
                                   final UserI userI,
                                   final String workflowId)
            throws NotFoundException, CommandResolutionException, UnauthorizedException {
        try {
            final CommandResolutionHelper helper = new CommandResolutionHelper(configuredCommand, inputValues, project, userI, workflowId);
            return helper.resolve();
        } catch (CommandResolutionException | UnauthorizedException e) {
            log.error("Could not resolve command", e);
            throw e;
        }
    }

    @SuppressWarnings("LoggingSimilarMessage")
    private class CommandResolutionHelper {

        private final CommandWrapper commandWrapper;
        private final ConfiguredCommand command;
        private final String project;
        private final UserI userI;
        private final String workflowId;

        private final Map<String, Set<String>> loadTypesMap;

        private final DocumentContext commandJsonpathContext;
        private final DocumentContext commandWrapperJsonpathContext;

        private String pathTranslationXnatPrefix = null;
        private String pathTranslationContainerHostPrefix = null;

        private String archivePvcName = null;
        private String buildPvcName = null;

        private String archivePathTranslation;

        private String buildPathTranslation;

        private final List<ResolvedCommand> resolvedSetupCommands;

        // Caches
        private final Map<String, String> inputValues;

        private CommandResolutionHelper(final ConfiguredCommand configuredCommand,
                                        final Map<String, String> inputValues,
                                        final String project,
                                        final UserI userI,
                                        final String workflowId) throws CommandResolutionException {
            this.commandWrapper = configuredCommand.wrapper();
            this.command = configuredCommand;
            this.project = project;
            this.userI = userI;
            this.workflowId = workflowId;

            try {
                log.debug("Getting docker server to read path prefixes.");
                final DockerServerBase.DockerServer dockerServer = dockerServerService.getServer();
                pathTranslationXnatPrefix = dockerServer.pathTranslationXnatPrefix();
                pathTranslationContainerHostPrefix = dockerServer.pathTranslationDockerPrefix();
                if (StringUtils.isNotBlank(dockerServer.combinedPvcName())) {
                    archivePvcName = dockerServer.combinedPvcName();
                    buildPvcName = dockerServer.combinedPvcName();
                    retrievePvcPathTranslations(dockerServer);
                } else if (StringUtils.isNotBlank(dockerServer.buildPvcName()) && StringUtils.isNotBlank(dockerServer.archivePvcName())) {
                    archivePvcName = dockerServer.archivePvcName();
                    buildPvcName = dockerServer.buildPvcName();
                    retrievePvcPathTranslations(dockerServer);
                }
            } catch (NotFoundException e) {
                log.debug("Could not get docker server. I'll keep going, but this is likely to cause other problems down the line.");
            }

            try {
                commandJsonpathContext = alwaysListParseContext.parse(mapper.writeValueAsString(command));
                commandWrapperJsonpathContext = alwaysListParseContext.parse(mapper.writeValueAsString(commandWrapper));
            } catch (JsonProcessingException e) {
                throw new CommandResolutionException("Could not serialize command to JSON.", e);
            }

            this.inputValues = inputValues == null ?
                    Collections.emptyMap() :
                    inputValues;

            this.resolvedSetupCommands = new ArrayList<>();

            // During preresolution, we want to work as quickly as possible (user is waiting for UI form). As such,
            // we determine how deeply we need to resolve the XNAT objects for JSON serialization.
            this.loadTypesMap = getTypeLoadMapForWrapper();
        }

        private void retrievePvcPathTranslations(DockerServerBase.DockerServer dockerServer) {
            if (StringUtils.isNotBlank(dockerServer.combinedPathTranslation())) {
                archivePathTranslation = dockerServer.combinedPathTranslation();
                buildPathTranslation = dockerServer.combinedPathTranslation();
                return;
            }

            if (StringUtils.isNotBlank(dockerServer.archivePathTranslation())) {
                archivePathTranslation = dockerServer.archivePathTranslation();
            } else {
                archivePathTranslation = siteConfigPreferences.getArchivePath();
                if (!archivePathTranslation.endsWith("/")) {
                    archivePathTranslation += "/";
                }
            }
             if (StringUtils.isNotBlank(dockerServer.buildPathTranslation())) {
                buildPathTranslation = dockerServer.buildPathTranslation();
            } else {
                buildPathTranslation = siteConfigPreferences.getBuildPath();

                if (!buildPathTranslation.endsWith("/")) {
                    buildPathTranslation += "/";
                }
            }
        }

        @Nonnull
        private List<ResolvedInputTreeNode<? extends Input>> preResolveInputTrees()
                throws CommandResolutionException, UnauthorizedException {
            return resolveInputTrees(new HashMap<>(), null, false);
        }

        @Nonnull
        private List<ResolvedInputTreeNode<? extends Input>> resolveInputTrees(final Map<String, String> resolvedInputValuesByReplacementKey,
                                                                               @Nullable final Map<String, String> resolvedCommandLineValuesByReplacementKey,
                                                                               boolean resolveFully)
                throws CommandResolutionException, UnauthorizedException {
            final List<PreresolvedInputTreeNode<? extends Input>> rootNodes = initializePreresolvedInputTree(resolvedCommandLineValuesByReplacementKey);

            final List<ResolvedInputTreeNode<? extends Input>> resolvedInputTrees = new ArrayList<>();
            for (final PreresolvedInputTreeNode<? extends Input> rootNode : rootNodes) {
                log.debug("Resolving input tree with root input \"{}\".", rootNode.input().name());
                final ResolvedInputTreeNode<? extends Input> resolvedRootNode =
                        resolveNode(rootNode, null, resolvedInputValuesByReplacementKey, resolveFully);
                if (resolveFully && resolvedRootNode.input().type().equals("file") && StringUtils.isBlank(resolvedRootNode.valuesAndChildren().get(0).resolvedValue().value())) {
                    log.debug("Root input \"{}\" is a file without a specified value. Skipping.", resolvedRootNode.input().name());
                    continue;
                }
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
                    .project(project)
                    .wrapperId(commandWrapper.id())
                    .wrapperName(commandWrapper.name())
                    .wrapperLabel(commandWrapper.label())
                    .wrapperDescription(commandWrapper.description())
                    .commandId(command.id())
                    .commandName(command.name())
                    .commandLabel(command.label())
                    .commandDescription(command.description())
                    .commandMetadata(command.commandMetadata())
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

            final Map<String, String> resolvedInputValuesByReplacementKey = new HashMap<>();
            final Map<String, String> resolvedCommandLineValuesByReplacementKey = new HashMap<>();
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

            // Resolve secrets
            final List<ResolvedSecret> resolvedSecrets = resolveSecrets(command.secrets());
            ensureInputsDoNotUseSecrets(resolvedInputTrees, resolvedSecrets);

            final List<ResolvedCommandOutput> resolvedCommandOutputs = resolveOutputs(resolvedInputTrees, resolvedInputValuesByReplacementKey);
            final String resolvedContainerName = resolveContainerName(resolvedInputValuesByReplacementKey, command.containerName());
            final Map<String, String> resolvedEnvironmentVariables = resolveEnvironmentVariables(resolvedInputValuesByReplacementKey);
            final String resolvedWorkingDirectory = resolveWorkingDirectory(resolvedInputValuesByReplacementKey);
            final Map<String, String> resolvedPorts = resolvePorts(resolvedInputValuesByReplacementKey);
            final List<ResolvedCommandMount> resolvedCommandMounts = resolveCommandMounts(resolvedInputTrees, resolvedInputValuesByReplacementKey);
            copyInputFilesToBuildDir(resolvedInputTrees, resolvedCommandMounts, resolvedCommandLineValuesByReplacementKey);
            final String resolvedCommandLine = resolveCommandLine(resolvedCommandLineValuesByReplacementKey, command.commandLine());
            final List<ResolvedCommand> resolvedWrapupCommands = resolveWrapupCommands(resolvedCommandOutputs, resolvedCommandMounts);
            final Map<String, String> resolvedContainerLabels =
                    resolveContainerLabels(resolvedInputValuesByReplacementKey, command.containerLabels());

            // Populate setup & wrap-up commands with environment variables from parent command
            List<ResolvedCommand> populatedSetupCommands = new ArrayList<>(resolvedSetupCommands.size());
            for(ResolvedCommand setup : resolvedSetupCommands){
                populatedSetupCommands.add(
                        setup.toBuilder()
                             .addEnvironmentVariables(resolvedEnvironmentVariables)
                             .commandLine(resolveCommandLine(resolvedCommandLineValuesByReplacementKey, setup.commandLine()))
                             .containerLabels(resolveContainerLabels(resolvedInputValuesByReplacementKey, setup.containerLabels()))
                             .containerName(resolveContainerName(resolvedInputValuesByReplacementKey, setup.containerName()))
                             .build());
            }
            List<ResolvedCommand> populatedWrapupCommands = new ArrayList<>(resolvedWrapupCommands.size());
            for(ResolvedCommand wrapup : resolvedWrapupCommands){
                populatedWrapupCommands.add(
                        wrapup.toBuilder()
                              .addEnvironmentVariables(resolvedEnvironmentVariables)
                              .commandLine(resolveCommandLine(resolvedCommandLineValuesByReplacementKey, wrapup.commandLine()))
                              .containerLabels(resolveContainerLabels(resolvedInputValuesByReplacementKey, wrapup.containerLabels()))
                              .containerName(resolveContainerName(resolvedInputValuesByReplacementKey, wrapup.containerName()))
                              .build());
            }

            log.debug("Checking for node constraints");
            final List<String> swarmConstraints = resolveSwarmConstraints();

            final ResolvedCommand resolvedCommand = ResolvedCommand.builder()
                    .project(project)
                    .wrapperId(commandWrapper.id())
                    .wrapperName(commandWrapper.name())
                    .wrapperDescription(commandWrapper.description())
                    .commandId(command.id())
                    .commandName(command.name())
                    .commandDescription(command.description())
                    .image(command.image())
                    .containerName(resolvedContainerName)
                    .type(command.type())
                    .overrideEntrypoint(command.overrideEntrypoint() != null && command.overrideEntrypoint())
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
                    .runtime(command.runtime())
                    .ipcMode(command.ipcMode())
                    .autoRemove(command.autoRemove() != null && command.autoRemove())
                    .shmSize(command.shmSize())
                    .network(command.network())
                    .containerLabels(resolvedContainerLabels)
                    .gpus(command.gpus())
                    .genericResources(command.genericResources())
                    .ulimits(command.ulimits())
                    .secrets(resolvedSecrets)
                    .build();

            log.info("Done resolving command.");
            log.debug("Resolved command: \n{}", resolvedCommand);
            return resolvedCommand;
        }

        private void copyInputFilesToBuildDir(List<ResolvedInputTreeNode<? extends Input>> resolvedInputTrees,
                                              List<ResolvedCommandMount> resolvedCommandMounts,
                                              Map<String, String> resolvedCommandLineValuesByReplacementKey)
                throws CommandResolutionException {
            ResolvedCommandMount mount = null;
            for (ResolvedCommandMount cmdMount : resolvedCommandMounts) {
                if (cmdMount.writable() && cmdMount.viaSetupCommand() == null) {
                    mount = cmdMount;
                    break;
                }
            }

            if (mount == null) {
                // just pass the user cache path, can be retrieved by REST API
                return;
            }

            for (ResolvedInputTreeNode<? extends Input> inputTree : resolvedInputTrees) {
                Input input = inputTree.input();
                if (input instanceof CommandInput && (FILE_INPUT.getName().equals(input.type()) ||
                        FILE.getName().equals(input.type()))) {
                    String usrCachePath = inputTree.valuesAndChildren().get(0).resolvedValue().value();
                    if (usrCachePath == null) {
                        continue;
                    }
                    String resource = usrCachePath.replace("/user/cache/resources/","")
                            .replaceAll("/files/.*","");
                    String filename = usrCachePath.replaceAll(".*/files/","");
                    Path relativePath = Paths.get(resource, filename);

                    // download file to mount loc
                    File xnatLoc = Paths.get(mount.xnatHostPath()).resolve(relativePath).toFile();
                    File f = userDataCache.getUserDataCacheFile(userI, relativePath);
                    try {
                        FileUtils.copyFile(f, xnatLoc);
                    } catch (IOException e) {
                        log.error("Unable to copy file {} to build dir {}", f, xnatLoc, e);
                        continue;
                    }

                    // update CLI with this path
                    String containerPath = Paths.get(mount.containerPath()).resolve(relativePath).toString();
                    resolvedCommandLineValuesByReplacementKey.put(input.replacementKey(),
                            getValueForCommandLine((CommandInput) input, containerPath));
                }
            }

        }

        private ResolvedCommand resolveSpecialCommandType(final CommandType type,
                                                          final String image,
                                                          final String inputMountXnatHostPath,
                                                          final String outputMountXnatHostPath,
                                                          final String parentSourceObjectName,
                                                          final String inputMountPvcName,
                                                          final String outputMountPvcName)
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

            String inputMountContainerHostPath;
            String outputMountContainerHostPath;

            if (StringUtils.isNotBlank(archivePathTranslation) && type.equals(CommandType.DOCKER_SETUP)) {
                inputMountContainerHostPath = getMountContainerHostPath(inputMountXnatHostPath, archivePathTranslation, "");
            }
            else if (StringUtils.isNotBlank(buildPathTranslation) && type.equals(CommandType.DOCKER_WRAPUP)) {
                inputMountContainerHostPath = getMountContainerHostPath(inputMountXnatHostPath, buildPathTranslation, "");
            } else {
                inputMountContainerHostPath = getMountContainerHostPath(inputMountXnatHostPath, pathTranslationXnatPrefix, pathTranslationContainerHostPrefix);
            }

            if (StringUtils.isNotBlank(buildPathTranslation)) {
                outputMountContainerHostPath = getMountContainerHostPath(outputMountXnatHostPath, buildPathTranslation, "");
            } else {
                outputMountContainerHostPath = getMountContainerHostPath(outputMountXnatHostPath, pathTranslationXnatPrefix, pathTranslationContainerHostPrefix);
            }

            return ResolvedCommand.fromSpecialCommandType(command, inputMountXnatHostPath, inputMountContainerHostPath,
                    outputMountXnatHostPath, outputMountContainerHostPath, parentSourceObjectName, inputMountPvcName, outputMountPvcName);
        }

        private String getMountContainerHostPath(final String mountXnatHostPath, String xnatPathPrefix, String containerHostPathPrefix) {
            return (xnatPathPrefix != null && containerHostPathPrefix != null) ? mountXnatHostPath.replace(xnatPathPrefix,
                    containerHostPathPrefix) : mountXnatHostPath;
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
         * @param childToParentsMap child input name -> list of parents (parent, grandparent, etc.)
         */
        private void addTypeDependencies(Map<String, Set<String>> typesMap,
                                         CommandWrapperInput input,
                                         @Nonnull Map<String, Set<String>> childToParentsMap) {

            String name = input.name();
            Set<String> typesUsedByInput = findTypesUsed(input);

            typesMap.computeIfAbsent(name, k -> new HashSet<>()).addAll(typesUsedByInput);

            if (input instanceof CommandWrapperDerivedInput) {
                // add child types to parents, too
                for (String parentName : childToParentsMap.get(name)) {
                    typesMap.computeIfAbsent(parentName, k -> new HashSet<>()).addAll(typesUsedByInput);
                }
            }
        }

        /**
         * Get types used by input
         * @param input     the input
         */
        private Set<String> findTypesUsed(CommandWrapperInput input) {
            Set<String> typesUsed = new HashSet<>();
            typesUsed.add(input.type());

            // Very hacky way to determine if the JSON matcher needs any deeper object types
            String matcher = input.matcher();
            if (matcher != null) {
                matcher = matcher.toLowerCase();
                for (CommandWrapperInputType type : CommandWrapperInputType.values()) {
                    String typeName = type.name().toLowerCase();
                    if (matcher.contains(typeName)) {
                        typesUsed.add(typeName);
                    }
                }
            }

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
         * Get all parents (parent, grandparent, etc.) for input
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
                if (input.parentIsAboveInHierarchy(parent)) {
                    // We only need to add load type dependencies from parents who are above us in the tree
                    // (project > subject > session > [assessor/scan] > resource > file); we don't need to pass
                    // load types back down the tree
                    if (parent instanceof CommandWrapperDerivedInput) {
                        getParents(childToParentsMap, (CommandWrapperDerivedInput) parent, inputsByName);
                        parents.addAll(childToParentsMap.get(parent.name()));
                    }
                    parents.add(parent.name());
                }
            }
            childToParentsMap.put(input.name(), parents);
        }

        @Nonnull
        private ResolvedInputValue resolveExternalWrapperInput(final CommandWrapperExternalInput input,
                                                               final Map<String, String> resolvedInputValuesByReplacementKey,
                                                               final boolean loadFiles)
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
            final String runtimeValue = inputValues.get(input.name());
            log.debug("Runtime value: \"{}\"", runtimeValue);
            if (runtimeValue != null) {
                resolvedValue = runtimeValue;
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
            final String resolvedMatcher = resolveTemplate(input.matcher(), resolvedInputValuesByReplacementKey);
            log.debug("Resolved matcher: \"{}\".", resolvedMatcher);

            if (StringUtils.isNotBlank(resolvedValue)) {
                // Process the input based on its type
                log.debug("Processing input value as a {}.", input.type());

                final CommandWrapperInputType type = CommandWrapperInputType.fromName(input.type());
                final CommandWrapperInputType[] resolvableTypes = {PROJECT, PROJECT_ASSET, SUBJECT, SUBJECT_ASSESSOR, SESSION, SCAN, ASSESSOR, RESOURCE};
                if (type != null && Arrays.asList(resolvableTypes).contains(type)) {

                    Set<String> typesNeeded = loadTypesMap.get(input.name());

                    final XnatModelObject xnatModelObject;
                    try {
                        xnatModelObject = resolveXnatObject(type, resolvedValue, resolvedMatcher, loadFiles, typesNeeded, input.loadChildren());
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
                throws CommandResolutionException {
            log.info("Resolving input \"{}\".", input.name());

            // Resolve the matcher, if one was provided
            log.debug("Matcher: \"{}\".", input.matcher());
            final String resolvedMatcher = resolveTemplate(input.matcher(), resolvedInputValuesByReplacementKey);

            // Resolve the parser, if one was provided
            log.debug("Parser: \"{}\".", input.parser());
            final String resolvedParser = resolveTemplate(input.parser(), resolvedInputValuesByReplacementKey);

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
                } else if (parentType.equals(PROJECT.getName()) || parentType.equals(PROJECT_ASSET.getName()) || parentType.equals(SUBJECT.getName()) ||
                        parentType.equals(SUBJECT_ASSESSOR.getName()) || parentType.equals(SESSION.getName()) || parentType.equals(SCAN.getName()) ||
                        parentType.equals(ASSESSOR.getName()) || parentType.equals(FILE.getName()) || parentType.equals(RESOURCE.getName())) {
                    final String parentValue = pullStringFromParentJson("$." + propertyToGet, resolvedMatcher, parentJson, resolvedParser);
                    resolvedXnatObjects = null;
                    resolvedValues = parentValue != null ? Collections.singletonList(parentValue) : Collections.emptyList();
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
                    final String parentValue = pullStringFromParentJson("$.directory", resolvedMatcher, parentJson, resolvedParser);
                    resolvedXnatObjects = null;
                    resolvedValues = parentValue != null ? Collections.singletonList(parentValue) : Collections.emptyList();
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
                        resolvedXnatObjects = new ArrayList<>(files);
                        resolvedValues = files.stream().map(XnatModelObject::getUri).collect(Collectors.toList());
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
                } else if (!(parentType.equals(PROJECT_ASSET.getName()) || parentType.equals(SESSION.getName()) || parentType.equals(SUBJECT.getName()) ||
                        parentType.equals(SCAN.getName()) || parentType.equals(ASSESSOR.getName()) || parentType.equals(SUBJECT_ASSESSOR.getName()))) {
                    logIncompatibleTypes(input.type(), parentType);
                    resolvedXnatObjects = Collections.emptyList();
                    resolvedValues = Collections.emptyList();
                } else {
                    final Project project;
                    if (parentType.equals(PROJECT_ASSET.getName())) {
                        project = ((ProjectAsset)parentXnatObject).getProject(userI,false, typesNeeded);
                    } else if (parentType.equals(SUBJECT.getName())) {
                        project = ((Subject)parentXnatObject).getProject(userI, false, typesNeeded);
                    } else if (parentType.equals(SESSION.getName())) {
                        project = ((Session)parentXnatObject).getProject(userI, false, typesNeeded);
                    } else if (parentType.equals(SUBJECT_ASSESSOR.getName())) {
                        project = ((SubjectAssessor)parentXnatObject).getProject(userI, false, typesNeeded);
                    } else if (parentType.equals(SCAN.getName())) {
                        project = ((Scan)parentXnatObject).getProject(userI, false, typesNeeded);
                    } else {
                        project = ((Assessor)parentXnatObject).getProject(userI, false, typesNeeded);
                    }
                    resolvedXnatObjects = Collections.singletonList(project);
                    resolvedValues = Collections.singletonList(project.getUri());
                }
            } else if (type.equals(PROJECT_ASSET.getName())) {
                if (parentXnatObject == null) {
                    log.error("Cannot derive input \"{}\". Parent input's XNAT object is null.", input.name());
                    resolvedXnatObjects = Collections.emptyList();
                    resolvedValues = Collections.emptyList();
                } else if (!parentType.equals(PROJECT.getName())) {
                    logIncompatibleTypes(input.type(), parentType);
                    resolvedXnatObjects = Collections.emptyList();
                    resolvedValues = Collections.emptyList();
                } else {  // parent is a project
                    List<ProjectAsset> childList = matchChildFromParent(
                            parentJson,
                            valueCouldContainId,
                            "project-assets",
                            "uri",
                            resolvedMatcher,
                            new TypeRef<List<ProjectAsset>>() {},
                            multiple);
                    if (childList == null || childList.isEmpty()) {
                        // It is also possible that the value they gave us contains an id
                        childList = matchChildFromParent(
                                parentJson,
                                valueCouldContainId,
                                "project-assets",
                                "id",
                                resolvedMatcher,
                                new TypeRef<List<ProjectAsset>>() {},
                                multiple);
                    }
                    if (childList == null || childList.isEmpty()) {
                        // It is also possible that the value they gave us contains a label
                        childList = matchChildFromParent(
                                parentJson,
                                valueCouldContainId,
                                "project-assets",
                                "label",
                                resolvedMatcher,
                                new TypeRef<List<ProjectAsset>>() {},
                                multiple);
                    }
                    if (childList == null || childList.isEmpty()) {
                        // It is also possible that the value they gave us contains a directory
                        childList = matchChildFromParent(
                                parentJson,
                                valueCouldContainId,
                                "project-assets",
                                "directory",
                                resolvedMatcher,
                                new TypeRef<List<ProjectAsset>>() {},
                                multiple);
                    }
                    if (childList == null) {
                        resolvedXnatObjects = Collections.emptyList();
                        resolvedValues = Collections.emptyList();
                    } else {
                        resolvedXnatObjects = new ArrayList<>(childList);
                        resolvedValues = childList.stream().map(XnatModelObject::getUri).collect(Collectors.toList());
                    }
                }

            } else if (type.equals(SUBJECT.getName())) {
                if (parentXnatObject == null) {
                    log.error("Cannot derive input \"{}\". Parent input's XNAT object is null.", input.name());
                    resolvedXnatObjects = Collections.emptyList();
                    resolvedValues = Collections.emptyList();
                } else if (!(parentType.equals(PROJECT.getName()) || parentType.equals(SESSION.getName()) || parentType.equals(SUBJECT_ASSESSOR.getName()))) {
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
                        if (childList == null || childList.isEmpty()) {
                            // It is also possible that the value they gave us contains a directory
                            childList = matchChildFromParent(
                                    parentJson,
                                    valueCouldContainId,
                                    "subjects",
                                    "directory",
                                    resolvedMatcher,
                                    new TypeRef<List<Subject>>() {},
                                    multiple);
                        }
                        if (childList == null) {
                            resolvedXnatObjects = Collections.emptyList();
                            resolvedValues = Collections.emptyList();
                        } else {
                            resolvedXnatObjects = new ArrayList<>(childList);
                            resolvedValues = childList.stream().map(XnatModelObject::getUri).collect(Collectors.toList());
                        }
                    } else if (parentType.equals(SUBJECT_ASSESSOR.getName())) {
                        final Subject subject = ((SubjectAssessor)parentXnatObject).getSubject(userI, false, typesNeeded);
                        resolvedXnatObjects = Collections.singletonList(subject);
                        resolvedValues = Collections.singletonList(subject.getUri());
                    } else {
                        final Subject subject = ((Session)parentXnatObject).getSubject(userI, false, typesNeeded);
                        resolvedXnatObjects = Collections.singletonList(subject);
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
                        if (childList == null || childList.isEmpty()) {
                            // It is also possible that the value they gave us contains a directory
                            childList = matchChildFromParent(
                                    parentJson,
                                    valueCouldContainId,
                                    "sessions",
                                    "directory",
                                    resolvedMatcher,
                                    new TypeRef<List<Session>>() {},
                                    multiple);
                        }
                        if (childList == null) {
                            resolvedXnatObjects = Collections.emptyList();
                            resolvedValues = Collections.emptyList();
                        } else {
                            resolvedXnatObjects = new ArrayList<>(childList);
                            resolvedValues = childList.stream().map(XnatModelObject::getUri).collect(Collectors.toList());
                        }
                    } else if (parentType.equals(ASSESSOR.getName())) {
                        final Session session = ((Assessor)parentXnatObject).getSession(userI, false, typesNeeded);
                        resolvedXnatObjects = Collections.singletonList(session);
                        resolvedValues = Collections.singletonList(session.getUri());
                    } else {
                        // Parent is scan
                        final Session session = ((Scan)parentXnatObject).getSession(userI, false, typesNeeded);
                        resolvedXnatObjects = Collections.singletonList(session);
                        resolvedValues = Collections.singletonList(session.getUri());
                    }
                }
            } else if (type.equals(SUBJECT_ASSESSOR.getName())) {
                if (parentXnatObject == null) {
                    log.error("Cannot derive input \"{}\". Parent input's XNAT object is null.", input.name());
                    resolvedXnatObjects = Collections.emptyList();
                    resolvedValues = Collections.emptyList();
                } else {
                    if (parentType.equals(SUBJECT.getName())) {
                        List<SubjectAssessor> childList = matchChildFromParent(
                                parentJson,
                                valueCouldContainId,
                                "subject-assessors",
                                "uri",
                                resolvedMatcher,
                                new TypeRef<List<SubjectAssessor>>() {},
                                multiple);
                        if (childList == null || childList.isEmpty()) {
                            // It is also possible that the value they gave us contains an id
                            childList = matchChildFromParent(
                                    parentJson,
                                    valueCouldContainId,
                                    "subject-assessors",
                                    "id",
                                    resolvedMatcher,
                                    new TypeRef<List<SubjectAssessor>>() {},
                                    multiple);
                        }
                        if (childList == null || childList.isEmpty()) {
                            // It is also possible that the value they gave us contains a label
                            childList = matchChildFromParent(
                                    parentJson,
                                    valueCouldContainId,
                                    "subject-assessors",
                                    "label",
                                    resolvedMatcher,
                                    new TypeRef<List<SubjectAssessor>>() {},
                                    multiple);
                        }
                        if (childList == null || childList.isEmpty()) {
                            // It is also possible that the value they gave us contains a directory
                            childList = matchChildFromParent(
                                    parentJson,
                                    valueCouldContainId,
                                    "subject-assessors",
                                    "directory",
                                    resolvedMatcher,
                                    new TypeRef<List<SubjectAssessor>>() {},
                                    multiple);
                        }
                        if (childList == null) {
                            resolvedXnatObjects = Collections.emptyList();
                            resolvedValues = Collections.emptyList();
                        } else {
                            resolvedXnatObjects = new ArrayList<>(childList);
                            resolvedValues = childList.stream().map(XnatModelObject::getUri).collect(Collectors.toList());
                        }
                    } else {
                        logIncompatibleTypes(input.type(), parentType);
                        resolvedXnatObjects = Collections.emptyList();
                        resolvedValues = Collections.emptyList();
                    }
                }
            } else if (type.equals(SCAN.getName())) {
                if (parentXnatObject == null) {
                    log.error("Cannot derive input \"{}\". Parent input's XNAT object is null.", input.name());
                    resolvedXnatObjects = Collections.emptyList();
                    resolvedValues = Collections.emptyList();
                } else if (parentType.equals(SESSION.getName())){
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
                    if (childList == null || childList.isEmpty()) {
                        // It is also possible that the value they gave us contains a directory
                        childList = matchChildFromParent(
                                parentJson,
                                valueCouldContainId,
                                "scans",
                                "directory",
                                resolvedMatcher,
                                new TypeRef<List<Scan>>() {},
                                multiple);
                    }
                    if (childList == null) {
                        resolvedXnatObjects = Collections.emptyList();
                        resolvedValues = Collections.emptyList();
                    } else {
                        resolvedXnatObjects = new ArrayList<>(childList);
                        resolvedValues = childList.stream().map(XnatModelObject::getUri).collect(Collectors.toList());
                    }
                } else if (parentType.equals(RESOURCE.getName())) {
                    Scan scan = null;
                    try {
                        final URIManager.DataURIA parentURI = UriParserUtils.parseURI(((Resource) parentXnatObject).getParentUri());
                        scan = new Scan((ScanURII) parentURI, true, Collections.emptySet());
                    } catch (MalformedURLException e) {
                        log.error("Could not derive Scan from Resource", e);
                    }
                    if (scan != null) {
                        resolvedXnatObjects = Collections.singletonList(scan);
                        resolvedValues = Collections.singletonList(scan.getUri());
                    } else {
                        resolvedXnatObjects = Collections.emptyList();
                        resolvedValues = Collections.emptyList();
                    }
                } else {
                    logIncompatibleTypes(input.type(), parentType);
                    resolvedXnatObjects = Collections.emptyList();
                    resolvedValues = Collections.emptyList();
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
                        resolvedXnatObjects = new ArrayList<>(childList);
                        resolvedValues = childList.stream().map(XnatModelObject::getUri).collect(Collectors.toList());
                    }
                }
            } else if (type.equals(RESOURCE.getName())) {
                if (parentXnatObject == null) {
                    log.error("Cannot derive input \"{}\". Parent input's XNAT object is null.", input.name());
                    resolvedXnatObjects = Collections.emptyList();
                    resolvedValues = Collections.emptyList();
                } else if (!(parentType.equals(PROJECT.getName()) || parentType.equals(PROJECT_ASSET.getName()) || parentType.equals(SUBJECT.getName()) ||
                                parentType.equals(SESSION.getName()) || parentType.equals(SCAN.getName()) ||
                                parentType.equals(ASSESSOR.getName()) || parentType.equals(SUBJECT_ASSESSOR.getName()))) {
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
                        resolvedXnatObjects = new ArrayList<>(childList);
                        resolvedValues = childList.stream().map(XnatModelObject::getUri).collect(Collectors.toList());
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
            final List<ResolvedInputValue> resolvedInputs = new ArrayList<>();
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
                                    null, jsonValue, resolvedParser);
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
                                                       final String providedValue)
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
            }

            log.info("Done resolving input \"{}\". Value: \"{}\".", input.name(), resolvedValue);

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
            final Map<String, PreresolvedInputTreeNode<? extends Input>> nodesThatProvideValueForCommandInputs = new HashMap<>();
            final Map<String, PreresolvedInputTreeNode<? extends Input>> nodesByName = new HashMap<>();
            final List<PreresolvedInputTreeNode<? extends Input>> rootNodes = new ArrayList<>();
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
                    throw new CommandResolutionException("Derived input \"" + input + "\" needs a parent.");
                }

                // Make sure that we have already made a node for the parent.
                final PreresolvedInputTreeNode<? extends Input> parent = nodesByName.get(parentName);
                if (parent == null) {
                    // This is unlikely to happen. This should be caught by command validation.
                    throw new CommandResolutionException(
                            String.format( "Derived input \"%1$s\" claims parent \"%2$s\", but I couldn't find \"%2$s\". " +
                                            "Are the inputs out of order?",
                                    input, parentName)
                    );
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
                log.debug("Resolving input \"{}\"{}.", preresolvedInputNode.input().name(), parentValue == null ? "" : " for parent value \"" + parentValue.value() + "\"");
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
                                parentValue != null ? parentValue.value() : null)
                );
            }


            // Recursively resolve values for child nodes, using each of this node's resolved values
            final List<ResolvedInputTreeValueAndChildren> resolvedValuesAndChildren = new ArrayList<>();
            for (final ResolvedInputValue resolvedInputValue : resolvedInputValues) {
                if (preresolvedInputNode.children() != null && !preresolvedInputNode.children().isEmpty()) {
                    final List<ResolvedInputTreeNode<? extends Input>> resolvedChildNodes = new ArrayList<>();

                    for (final PreresolvedInputTreeNode<? extends Input> child : preresolvedInputNode.children()) {
                        log.debug("Resolving input \"{}\" child \"{}\" using value \"{}\".",
                                thisNode.input().name(),
                                child.input().name(),
                                resolvedInputValue.value());

                        resolvedInputValuesByReplacementKey.put(thisNode.input().replacementKey(), resolvedInputValue.value());
                        resolvedChildNodes.add(resolveNode(child, resolvedInputValue, resolvedInputValuesByReplacementKey, loadFiles));
                    }
                    resolvedValuesAndChildren.add(ResolvedInputTreeValueAndChildren.create(resolvedInputValue, resolvedChildNodes));
                } else {
                    log.debug("Input \"{}\" (no children) has resolved value \"{}\".",
                            thisNode.input().name(),
                            resolvedInputValue.value());
                    resolvedValuesAndChildren.add(ResolvedInputTreeValueAndChildren.create(resolvedInputValue));
                    resolvedInputValuesByReplacementKey.put(thisNode.input().replacementKey(), resolvedInputValue.value());
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
                if (resolvedValueAndChildren.isEmpty()) {
                    // This node has no values, so we can't add any uniquely resolved values to the map
                    message += "could not be resolved for this item. You might check the JSONPath matchers in " +
                            "command.json and confirm that this item has " + node.input().type() + " data.";
                } else {
                    // This node has multiple or no values, so we can't add any uniquely resolved values to the map
                    message += " does not have a unique, resolved value and multiple = true is not set in command.json.";
                }

                if (resolveFully && node.input().required()) {
                    // we're resolving for real, throw exception
                    throw new CommandResolutionException(message);
                } else if (node.input() instanceof CommandWrapperDerivedInput && node.input().required() && resolvedValueAndChildren.isEmpty()) {
                    //we're in pre-resolve, but we will never find matching data for this input
                    throw new CommandPreResolutionException("The following required fields cannot be resolved using the provided data: " + node.input().name());
                }else {
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
            final List<String> missingRequiredInputNames = new ArrayList<>();
            for (final ResolvedInputTreeNode<? extends Input> resolvedRootNode : resolvedInputTrees) {
                log.debug("Checking for missing required inputs in input tree starting with input \"{}\".", resolvedRootNode.input().name());
                missingRequiredInputNames.addAll(findMissingRequiredInputs(resolvedRootNode));
            }
            return missingRequiredInputNames;
        }

        private List<String> findMissingRequiredInputs(final ResolvedInputTreeNode<? extends Input> resolvedInputTreeNode) {
            final List<String> missingRequiredInputNames = new ArrayList<>();

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
                                                final String parentJson,
                                                final String parser) {
            final String jsonPathSearch = rootJsonPathSearch +
                    (StringUtils.isNotBlank(resolvedMatcher) ? "[?(" + resolvedMatcher + ")]" : "");
            log.info("Attempting to pull value from parent using matcher \"{}\".", jsonPathSearch);

            String jsonPathSearchResult = jsonPathSearch(parentJson, jsonPathSearch, new TypeRef<String>() {});
            if(StringUtils.isBlank(jsonPathSearchResult) || StringUtils.isBlank(parser)){
                return jsonPathSearchResult;
            } else {
                // parse resultant string with parser
                String result = null;
                try {
                    //Check for XPath & XML
                    InputSource xml = new InputSource(new StringReader(jsonPathSearchResult));
                    XPath xPath = XPathFactory.newInstance().newXPath();
                    result = (String) xPath.evaluate(parser, xml, XPathConstants.STRING);
                } catch (XPathExpressionException e) {
                    log.debug("Failed attempt to parse wrapper input: {} with XPath {}. Trying RegEx next.", rootJsonPathSearch, parser);
                } catch (Throwable e) {
                    log.error("Error while attempting to parse:\n{} \nwith XMLPath\n {}", jsonPathSearchResult, parser, e);
                }
                // Xpath failed for some reason, maybe this is a regular expression.
                if (result == null){
                    try {
                        Matcher matcher = Pattern.compile(parser).matcher(jsonPathSearchResult);
                        if (matcher.matches()) {
                            result = matcher.group();
                        }
                    } catch (Exception e) {
                        log.debug("Failed attempt to parse wrapper input: {} with RegEx {}. Returning null.", rootJsonPathSearch, parser);
                    }
                }
                return result;
            }

        }

        @Nullable
        private <T> T jsonPathSearch(final String parentJson,
                                     final String jsonPathSearch,
                                     final TypeRef<T> typeRef) {
            try {
                return jsonpathContext.parse(parentJson).read(jsonPathSearch, typeRef);
            } catch (InvalidPathException | InvalidJsonException | MappingException e) {
                log.error("Error searching through json with search string \"{}\"", jsonPathSearch, e);
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
        private XnatModelObject resolveXnatObject(final CommandWrapperInputType type,
                                                  final @Nullable String resolvedValue,
                                                  final @Nullable String resolvedMatcher,
                                                  final boolean loadFiles,
                                                  final @Nonnull Set<String> typesNeeded,
                                                  final boolean preload)
                throws CommandInputResolutionException, UnauthorizedException {
            if (type == null) {
                return null;
            }
            final XnatModelObject xnatModelObject;
            switch (type) {
                case PROJECT:
                    xnatModelObject = resolveXnatObject(resolvedValue, resolvedMatcher,
                            Project.class, Project.uriToModelObject(loadFiles, typesNeeded, preload),
                            Project.idToModelObject(userI, loadFiles, typesNeeded, preload));
                    break;
                case PROJECT_ASSET:
                    xnatModelObject = resolveXnatObject(resolvedValue, resolvedMatcher,
                            ProjectAsset.class, ProjectAsset.uriToModelObject(loadFiles, typesNeeded),
                            ProjectAsset.idToModelObject(userI, loadFiles, typesNeeded));
                    break;
                case SUBJECT:
                    xnatModelObject = resolveXnatObject(resolvedValue, resolvedMatcher,
                            Subject.class, Subject.uriToModelObject(loadFiles, typesNeeded),
                            Subject.idToModelObject(userI, loadFiles, typesNeeded));
                    break;
                case SUBJECT_ASSESSOR:
                    xnatModelObject = resolveXnatObject(resolvedValue, resolvedMatcher,
                            SubjectAssessor.class, SubjectAssessor.uriToModelObject(loadFiles, typesNeeded),
                            SubjectAssessor.idToModelObject(userI, loadFiles, typesNeeded));
                    break;
                case SESSION:
                    xnatModelObject = resolveXnatObject(resolvedValue, resolvedMatcher,
                            Session.class, Session.uriToModelObject(project, loadFiles, typesNeeded),
                            Session.idToModelObject(userI, project, loadFiles, typesNeeded));
                    break;
                case SCAN:
                    xnatModelObject = resolveXnatObject(resolvedValue, resolvedMatcher,
                            Scan.class, Scan.uriToModelObject(loadFiles, typesNeeded),
                            Scan.idToModelObject(userI, loadFiles, typesNeeded));
                    break;
                case ASSESSOR:
                    xnatModelObject = resolveXnatObject(resolvedValue, resolvedMatcher,
                            Assessor.class, Assessor.uriToModelObject(userI, loadFiles, typesNeeded),
                            Assessor.idToModelObject(userI, loadFiles, typesNeeded));
                    break;
                case RESOURCE:
                    xnatModelObject = resolveXnatObject(resolvedValue, resolvedMatcher,
                            Resource.class, Resource.uriToModelObject(loadFiles, typesNeeded),
                            Resource.idToModelObject(userI, loadFiles, typesNeeded));
                    break;
                default:
                    xnatModelObject = null;
            }
            return xnatModelObject;
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

                final ResourceData resourceData;
                try {
                    resourceData = catalogService.getResourceDataFromUri(value.startsWith("/archive") ? value : "/archive" + value);
                } catch (ClientException e) {
                    throw new CommandInputResolutionException("Invalid " + modelName + " uri " + value, value, e);
                }

                final ArchiveItemURI uri = resourceData.getXnatUri();
                try {
                    newModelObject = uriToModelObject.apply(uri);
                } catch (Throwable e) { // We have to throw non-checked exceptions from a Function
                    throw new CommandInputResolutionException("Invalid " + modelName + " uri " + value, value, e);
                }

                // TODO This is a workaround for CS-263 and XXX-55. Once XXX-55 is fixed, this can (hopefully) be removed.
                try {
                    if (!Permissions.canRead(userI, uri.getSecurityItem())) {
                        throw new UnauthorizedException("User does not have permission to read " + modelName +" with URI " + value + ".");
                    }
                } catch (UnauthorizedException e) {
                    throw e;
                } catch (Exception e) {  // Need to catch this here because Permissions.canRead() can throw whatever
                    throw new CommandInputResolutionException("Could not verify read permissions for user " + userI.getUsername() + " with URI " + value + ".", value);
                }

            } else if (value.startsWith("{")) {
                try {
                    log.debug("Attempting to deserialize {} from value as JSON.", modelName);
                    newModelObject = mapper.readValue(value, model);
                } catch (IOException e) {
                    log.debug("Could not deserialize {} from value as JSON.", modelName);
                }
            } else if (idToModelObject != null) {
                log.debug("Attempting to initialize a {} using value as ID string.", modelName);
                try {
                    newModelObject = idToModelObject.apply(value);
                } catch (Throwable e) {
                    throw new CommandInputResolutionException("Could not instantiate " + modelName + " by ID " + value, value, e);
                }
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
                    doMatch = jsonpathContext.parse(newModelObjectJson).read(jsonPathSearch, new TypeRef<List<T>>() {});

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
            } else {
                log.info("Successfully instantiated matching {}.", modelName);
                log.trace("Match: {}", aMatch);
            }
            return aMatch;
        }

        @Nonnull
        private List<ResolvedCommandOutput> resolveOutputs(final List<ResolvedInputTreeNode<? extends Input>> resolvedInputTrees,
                                                           final Map<String, String> resolvedInputValuesByReplacementKey)
                throws CommandResolutionException {
            log.info("Resolving command outputs.");
            if (command.outputs() == null) {
                return Collections.emptyList();
            }

            final List<CommandWrapperOutput> outputHandlers = commandWrapper.outputHandlers();
            final Map<String, CommandWrapperOutput> wrapperOutputsByName;
            final Map<String, List<CommandWrapperOutput>> wrapperOutputsByHandledCommandOutputName;
            if (outputHandlers != null) {
                wrapperOutputsByName = new HashMap<>();
                wrapperOutputsByHandledCommandOutputName = new HashMap<>();
                for (final CommandWrapperOutput commandWrapperOutput : outputHandlers) {
                    wrapperOutputsByHandledCommandOutputName
                            .computeIfAbsent(commandWrapperOutput.commandOutputName(), k -> new ArrayList<>())
                            .add(commandWrapperOutput);

                    wrapperOutputsByName.put(commandWrapperOutput.name(), commandWrapperOutput);
                }
            } else {
                wrapperOutputsByName = Collections.emptyMap();
                wrapperOutputsByHandledCommandOutputName = Collections.emptyMap();
            }

            final Map<String, ResolvedCommandOutput> resolvedCommandOutputsByOutputHandlerName = new HashMap<>();
            for (final CommandOutput commandOutput : command.outputs()) {
                final List<ResolvedCommandOutput> resolvedOutputList = resolveCommandOutput(commandOutput, resolvedInputTrees, resolvedInputValuesByReplacementKey,
                        wrapperOutputsByHandledCommandOutputName, wrapperOutputsByName);

                for (final ResolvedCommandOutput resolvedCommandOutput : resolvedOutputList) {
                    log.debug("Finished with resolved output \"{}\".", resolvedCommandOutput.name());
                    resolvedCommandOutputsByOutputHandlerName.put(resolvedCommandOutput.fromOutputHandler(), resolvedCommandOutput);
                }
            }

            // Add resolved outputs in the order of the output handlers
            final List<ResolvedCommandOutput> resolvedOutputs = new ArrayList<>(commandWrapper.outputHandlers().size());
            for (final CommandWrapperOutput commandWrapperOutput : commandWrapper.outputHandlers()) {
                final ResolvedCommandOutput resolvedCommandOutput = resolvedCommandOutputsByOutputHandlerName.get(commandWrapperOutput.name());
                if (resolvedCommandOutput == null) {
                    throw new CommandResolutionException(
                            "Command wrapper output handler \"" + commandWrapperOutput.name() + "\" has no resolved output to handle."
                    );
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

            final List<CommandWrapperOutput> commandOutputHandlers = wrapperOutputsByHandledCommandOutputName.get(commandOutput.name());
            if (CollectionUtils.isEmpty(commandOutputHandlers)) {
                throw new CommandResolutionException(String.format("No wrapper output handler was configured to handle command output \"%s\".", commandOutput.name()));
            }
            log.debug("Found {} Output Handlers for Command output \"{}\".", commandOutputHandlers.size(), commandOutput.name());

            final List<ResolvedCommandOutput> resolvedCommandOutputs = new ArrayList<>();
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
                    // If we are here, we know the target is an input, and we have its value.
                    log.debug("Handler \"{}\"'s target is input \"{}\". Checking if the input's value makes a legit target.", commandOutputHandler.name(), commandOutputHandler.targetName());

                    // Next check that the handler target input's value is an XNAT object
                    XnatModelObject parentXnatObject = parentInputResolvedValue.xnatModelObject();
                    if (parentXnatObject == null) {
                        CommandWrapperInputType parentType = CommandWrapperInputType.fromName(parentInputResolvedValue.type());
                        String parentValue = parentInputResolvedValue.value();
                        log.debug("Attempting to load input \"{}\"=\"{}\" as a {}.", commandOutputHandler.targetName(), parentValue, parentType);
                        try {
                            parentXnatObject = resolveXnatObject(parentType, parentValue,
                                    null, false, Collections.emptySet(), false);
                        } catch (UnauthorizedException ignored) {
                            // ignored
                        }
                    }

                    if (parentXnatObject == null) {
                        final String message = String.format("Cannot resolve output \"%s\". " +
                                        "Input \"%s\" is supposed to handle the output, but it does not have an XNAT object value.",
                                commandOutput.name(), commandOutputHandler.targetName());
                        if (Boolean.TRUE.equals(commandOutput.required())) {
                            throw new CommandResolutionException(message);
                        } else {
                            log.error("Skipping handler \"{}\". {}", commandOutputHandler.name(), message);
                            continue;
                        }
                    }

                    // Next check that the user has edit permissions on the handler target input's XNAT object
                    if (!ContainerServicePermissionUtils.canCreateOutputObject(userI, project, parentXnatObject, commandOutputHandler)) {
                        final String message = "User \"" + userI.getUsername() + "\" " +
                                "does not have sufficient permissions to create the output " +
                                commandOutputHandler.type() +
                                ".";
                        if (Boolean.TRUE.equals(commandOutput.required())) {
                            throw new CommandResolutionException(message);
                        } else {
                            log.error("Skipping handler \"{}\". {}", commandOutputHandler.name(), message);
                            continue;
                        }
                    }
                    log.debug("User has permission to create an output {} in project {}", commandOutputHandler.type(), project);
                } else {
                    // If we are here, either the output handler is uploading to another output,
                    // or its target is just wrong, and we can't find anything

                    log.debug("Handler \"{}\"'s target \"{}\" is not an input with a unique value. Is it another output handler?", commandOutputHandler.name(), commandOutputHandler.targetName());

                    final CommandWrapperOutput otherOutputHandler = wrapperOutputsByName.get(commandOutputHandler.targetName());
                    if (otherOutputHandler == null) {
                        // Looks like we can't find an input or an output to which this handler intends to upload its output
                        final String message = String.format("Cannot resolve output \"%s\". " +
                                        "The handler says the output is supposed to be handled by \"%s\", " +
                                        "but either that isn't an input, or an output, or maybe the input does not have a uniquely resolved value.",
                                commandOutput.name(), commandOutputHandler.targetName());
                        if (Boolean.TRUE.equals(commandOutput.required())) {
                            throw new CommandResolutionException(message);
                        } else {
                            log.error("Skipping handler \"{}\". {}", commandOutputHandler.name(), message);
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
                        if (Boolean.TRUE.equals(commandOutput.required())) {
                            throw new CommandResolutionException(message);
                        } else {
                            log.error("Skipping handler \"{}\". {}", commandOutputHandler.name(), message);
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
                        .xsiType(commandOutputHandler.xsiType())
                        .handledBy(commandOutputHandler.targetName())
                        .viaWrapupCommand(commandOutputHandler.viaWrapupCommand())
                        .path(resolveTemplate(commandOutput.path(), resolvedInputValuesByReplacementKey))
                        .label(resolveTemplate(commandOutputHandler.label(), resolvedInputValuesByReplacementKey))
                        .format(resolveTemplate(commandOutputHandler.format(), resolvedInputValuesByReplacementKey))
                        .description(resolveTemplate(commandOutputHandler.description(), resolvedInputValuesByReplacementKey))
                        .content(resolveTemplate(commandOutputHandler.content(), resolvedInputValuesByReplacementKey))
                        .tags(resolveTemplates(commandOutputHandler.tags(), resolvedInputValuesByReplacementKey))
                        .build());
            }

            return resolvedCommandOutputs;
        }

        private String resolveContainerName(final Map<String, String> resolvedInputValuesByReplacementKey,
                                            final String containerName)
                throws CommandResolutionException {
            if(StringUtils.isBlank(containerName)){
                log.info("No container name given to resolve.");
                return null;
            }

            log.debug("Using resolved container-name values to resolve container-name template string.");
            final String resolvedContainerName = resolveTemplate(containerName, resolvedInputValuesByReplacementKey);

            log.info("Done resolving container-name string.");
            if (log.isDebugEnabled()) {
                log.debug("Container-name string: {}", resolvedContainerName);
            }
            return resolvedContainerName;
        }

        @Nonnull
        private Map<String, String> resolveContainerLabels(final Map<String, String> resolvedInputValuesByReplacementKey,
                                                           final Map<String, String> containerLabels)
                throws CommandResolutionException {
            log.info("Resolving container labels: {}", containerLabels);

            if (containerLabels == null || containerLabels.isEmpty()) {
                log.info("No container labels to resolve.");
                return Collections.emptyMap();
            }

            Map<String, String> resolvedContainerLabels = resolveTemplateMap(containerLabels, resolvedInputValuesByReplacementKey);
            log.info("Done resolving container values.");
            if (log.isDebugEnabled()) {
                log.debug("Resolved Container Labels: {}", resolvedContainerLabels);
            }
            return resolvedContainerLabels;
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

            final Map<String, String> envTemplates = command.environmentVariables();
            if (envTemplates == null || envTemplates.isEmpty()) {
                log.info("No environment variables to resolve.");
                return Collections.emptyMap();
            }

            final Map<String, String> resolvedMap = resolveTemplateMap(envTemplates, resolvedInputValuesByReplacementKey);

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

            final Map<String, String> portTemplates = command.ports();
            if (portTemplates == null || portTemplates.isEmpty()) {
                log.info("No ports to resolve.");
                return Collections.emptyMap();
            }

            final Map<String, String> resolvedMap = resolveTemplateMap(portTemplates, resolvedInputValuesByReplacementKey);

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
            if (templateMap == null || templateMap.isEmpty()) {
                return Collections.emptyMap();
            }
            final Map<String, String> resolvedMap = new HashMap<>(templateMap.size());
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
                        resolvedCommandMountMap = resolvedCommandMounts.stream()
                                .collect(Collectors.toMap(ResolvedCommandMount::name, Function.identity()));
                    }
                    final ResolvedCommandMount resolvedCommandMount = resolvedCommandMountMap.get(outputMountName);
                    assert resolvedCommandMount != null; // Command output must refer to a mount that exists, otherwise command would have failed validation.

                    final String writableMountPath = getBuildDirectory();

                    resolvedWrapupCommands.add(resolveSpecialCommandType(CommandType.DOCKER_WRAPUP, resolvedCommandOutput.viaWrapupCommand(),
                            resolvedCommandMount.xnatHostPath(), writableMountPath, resolvedCommandOutput.name(), buildPvcName, buildPvcName));
                }
            }

            return resolvedWrapupCommands;
        }

        @Nonnull
        private Map<String, ResolvedInputTreeNode<? extends Input>> findMountSourceInputs(final ResolvedInputTreeNode<? extends Input> node) {
            Map<String, ResolvedInputTreeNode<? extends Input>> mountSourceInputs = new HashMap<>();

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
        private List<ResolvedCommandMount> resolveCommandMounts(final @Nonnull List<ResolvedInputTreeNode<? extends Input>> resolvedInputTrees,
                                                                final @Nonnull Map<String, String> resolvedInputValuesByReplacementKey)
                throws CommandResolutionException {
            log.info("Resolving mounts.");
            final List<CommandMount> commandMounts = command.mounts();
            if (commandMounts == null || commandMounts.isEmpty()) {
                log.info("No mounts.");
                return Collections.emptyList();
            }

            log.debug("Search input trees to find inputs that provide files to mounts.");
            Map<String, ResolvedInputTreeNode<? extends Input>> mountSourceInputs = new HashMap<>(commandMounts.size());
            for (final ResolvedInputTreeNode<? extends Input> rootNode : resolvedInputTrees) {
                mountSourceInputs.putAll(findMountSourceInputs(rootNode));
            }

            final List<ResolvedCommandMount> resolvedMounts = new ArrayList<>(commandMounts.size());
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
        private ResolvedCommandMount resolveCommandMount(final @Nonnull CommandMount commandMount,
                                                         final @Nullable ResolvedInputTreeNode<? extends Input> resolvedSourceInput,
                                                         final @Nonnull Map<String, String> resolvedInputValuesByReplacementKey)
                throws CommandResolutionException {
            final String mountName = commandMount.name();
            log.debug("Resolving command mount \"{}\".", mountName);

            final String resolvedContainerPath = resolveTemplate(commandMount.path(), resolvedInputValuesByReplacementKey);

            if (resolvedSourceInput == null) {
                return resolveOutputMount(commandMount, resolvedContainerPath);
            }

            // Get xnat model object from input
            final XnatModelObject xnatModelObject = getXnatModelObjectForMounting(resolvedSourceInput);

            StringBuilder mountPathBuilder = new StringBuilder();

            // Get a path to whatever we need to mount
            boolean isBuildMount = resolveMountPathForModelObject(commandMount, xnatModelObject, mountPathBuilder);

            String mountPath = String.valueOf(mountPathBuilder);

            // Determine if we need to insert a setup command
            final Input input = resolvedSourceInput.input();
            final String viaSetupCommand = (CommandWrapperInput.class.isAssignableFrom(input.getClass())) ?
                    ((CommandWrapperInput) input).viaSetupCommand() : null;
            if (StringUtils.isNotBlank(viaSetupCommand)) {
                log.debug("Command mount will be set up with setup command {}.", viaSetupCommand);
                // If there is a setup command, we do a switcheroo.
                // Normally, we would mount mountPath into this mount. Instead, we mount mountPath
                // into the setup command as its input, along with another writable build directory as its output.
                // Then we mount the output build directory into this mount.
                // In that way, the setup command will write to the mount whatever files we need to find.
                final String writableMountPath = getBuildDirectory();
                isBuildMount = true;
                resolvedSetupCommands.add(
                        resolveSpecialCommandType(CommandType.DOCKER_SETUP, viaSetupCommand, mountPath, writableMountPath, mountName, archivePvcName, buildPvcName)
                );
                mountPath = writableMountPath;
            }

            log.debug("Setting mount \"{}\" xnat host path to \"{}\".", mountName, mountPath);

            // Translate paths from XNAT prefix to container host prefix
            final String mountPathOnContainerHost;
            if (isBuildMount && StringUtils.isNotBlank(buildPvcName)) {
                mountPathOnContainerHost = getMountContainerHostPath(mountPath, buildPathTranslation, "");
            } else if (!isBuildMount && StringUtils.isNotBlank(archivePvcName)){
                mountPathOnContainerHost = getMountContainerHostPath(mountPath, archivePathTranslation, "");
            }else {
                mountPathOnContainerHost = getMountContainerHostPath(mountPath, pathTranslationXnatPrefix, pathTranslationContainerHostPrefix);
            }

            log.debug("Setting mount \"{}\" container host path to \"{}\".", mountName, mountPathOnContainerHost);

            final ResolvedCommandMount resolvedCommandMount = ResolvedCommandMount.builder()
                    .name(mountName)
                    .writable(commandMount.writable())
                    .containerPath(resolvedContainerPath)
                    .viaSetupCommand(viaSetupCommand)
                    .xnatHostPath(mountPath)
                    .containerHostPath(mountPathOnContainerHost)
                    .mountPvcName(isBuildMount ? buildPvcName : archivePvcName)
                    .build();

            log.debug("Done resolving mount \"{}\", source input \"{}\".", mountName, input.name());
            return resolvedCommandMount;
        }

        @Nonnull
        private ResolvedCommandMount resolveOutputMount(final @Nonnull CommandMount commandMount,
                                                        final @Nonnull String resolvedContainerPath)
                throws CommandResolutionException {

            final String name = commandMount.name();
            log.debug("Command mount \"{}\" has no inputs that provide it files. Assuming it is an output mount.", name);

            final String xnatHostPath = getBuildDirectory();


            if (StringUtils.isNotBlank(buildPvcName)) {
                final String containerHostPath = getMountContainerHostPath(xnatHostPath, buildPathTranslation, "");
                return ResolvedCommandMount.output(name, xnatHostPath, containerHostPath, resolvedContainerPath, buildPvcName);
            } else {
                final String containerHostPath = getMountContainerHostPath(xnatHostPath, pathTranslationXnatPrefix, pathTranslationContainerHostPrefix);
                return ResolvedCommandMount.output(name, xnatHostPath, containerHostPath, resolvedContainerPath, null);
            }
        }

        @Nonnull
        private XnatModelObject getXnatModelObjectForMounting(@Nonnull ResolvedInputTreeNode<? extends Input> resolvedSourceInput) throws CommandMountResolutionException {
            final Input input = resolvedSourceInput.input();

            final CommandWrapperInputType inputType = CommandWrapperInputType.fromName(input.type());
            if (!CommandWrapperInputType.MOUNTABLE_TYPES.contains(inputType)) {
                throw new CommandMountResolutionException("I don't know how to provide files to a mount from an input of type \"" + inputType + "\".");
            }

            final List<ResolvedInputTreeValueAndChildren> valuesAndChildren = resolvedSourceInput.valuesAndChildren();
            if (valuesAndChildren.size() > 1) {
                throw new CommandMountResolutionException("Input \"" + input.name() + "\" has multiple resolved values. We can only use inputs with a single resolved value.");
            }
            final ResolvedInputTreeValueAndChildren resolvedInputTreeValueAndChildren = valuesAndChildren.get(0);
            final ResolvedInputValue resolvedInputValue = resolvedInputTreeValueAndChildren.resolvedValue();

            final XnatModelObject xnatModelObject = resolvedInputValue.xnatModelObject();

            if (xnatModelObject == null) {
                throw new CommandMountResolutionException("Input \"" + input.name() + "\"'s XnatModelObject value is null");
            }
            return xnatModelObject;
        }

        private boolean resolveMountPathForModelObject(@Nonnull CommandMount commandMount, @Nonnull XnatModelObject xnatModelObject, StringBuilder mountPath) throws CommandResolutionException {
            boolean isBuildPath = false;
            String currentMountPath;
            final String srcPath;
            if (xnatModelObject instanceof Project) {
                try {
                    Map<Path, Path> allPaths = org.nrg.xnat.utils.FileUtils.getAllSharedPaths(xnatModelObject.getId(), userI, false, false, true, false);
                    if (allPaths.isEmpty()) {
                        srcPath = xnatModelObject.getRootPath();
                    } else {
                        Path hardLinkPath = org.nrg.xnat.utils.FileUtils.createDirectoryForSharedData(allPaths, Paths.get(ContainerUtils.CS_SHARED_PROJECT_STRING, workflowId + xnatModelObject.getId()));
                        srcPath = hardLinkPath.toString();
                    }
                }catch (Exception e) {
                    throw new CommandResolutionException(e);
                }
            } else {
                srcPath = xnatModelObject.getRootPath();
            }

            if (StringUtils.isBlank(srcPath)) {
                throw new CommandMountResolutionException("Mount \"" + commandMount.name() + "\" should have a root path but does not.", commandMount);
            }
            final Path srcPathObj = Paths.get(srcPath);
            final File srcPathFile = srcPathObj.toFile();
            final boolean srcIsFile = srcPathFile.isFile();

            // Determine if this particular URI has remote files
            final String uri = xnatModelObject.getUri();
            boolean hasRemoteFiles;
            if (srcIsFile) {
                // If the file isn't local, assume it's remote (attempting to pull with throw an exception if it isn't)
                hasRemoteFiles = !srcPathFile.exists();
            } else {
                hasRemoteFiles = hasRemoteFiles(uri);
            }

            // Determine if we can mount the archive path directly or if we need to create a build directory
            final boolean writable = commandMount.writable();
            if (!(writable || hasRemoteFiles)) {
                // The source can be directly mounted
                log.debug("Mount \"{}\" has a root path and is not set to \"writable\". The root path can be " +
                        "mounted directly into the container.", commandMount.name());
                currentMountPath = srcPath;
            } else {
                // The mount has a source path and is set to "writable" or may have remote files. We must copy files
                // from the root path into a writable build location.
                isBuildPath = true;
                currentMountPath = getBuildDirectory();

                if (srcIsFile) {
                    currentMountPath = Paths.get(currentMountPath).resolve(srcPathObj.getFileName()).toString();
                }

                if (hasRemoteFiles) {
                    log.debug("Pulling any remote files into mount \"{}\".", commandMount.name());
                    pullRemoteFiles(uri, srcPath, currentMountPath);
                } else {
                    // CS-54 Copy all files out of the root directory to a build directory.
                    log.debug("Mount \"{}\" has a root directory and is set to \"writable\". Copying all files " +
                            "from the root directory to build directory.", commandMount.name());
                    copyLocalFiles(srcPath, currentMountPath, srcIsFile);
                }
            }
            //Adding the mount path to the input string builder to be able to return both the mount path itself
            //and whether we're working with a build directory or an archive directory. Definitely a hack, but I don't
            //think it should introduce any issues.
            mountPath.delete(0, mountPath.length()).append(currentMountPath);
            return isBuildPath;
        }

        private boolean hasRemoteFiles(final String uri) throws CommandMountResolutionException {
            if (StringUtils.isNotBlank(uri)) {
                try {
                    return catalogService.hasRemoteFiles(userI, uri);
                } catch (ClientException | ServerException e) {
                    throw new CommandMountResolutionException("Error getting mount files from uri " + uri, e);
                }
            }
            return false;
        }

        private void pullRemoteFiles(final String uri, final String archiveSrc, final String localPath) throws CommandMountResolutionException {
            try {
                catalogService.pullResourceCatalogsToDestination(Users.getAdminUser(), uri, archiveSrc, localPath);
            } catch (ServerException | ClientException e) {
                throw new CommandMountResolutionException("Could not pull remote files into build " +
                        "path " + localPath, e);
            }
        }

        private void copyLocalFiles(final String src, final String dest, final boolean srcIsFile) throws CommandMountResolutionException {
            try {
                if (srcIsFile) {
                    Files.copy(Paths.get(src), Paths.get(dest), StandardCopyOption.REPLACE_EXISTING);
                } else {
                    FileUtils.copyDirectory(new File(src), new File(dest));
                }
            } catch (IOException e) {
                throw new CommandMountResolutionException("Could not copy archive path " + src +
                        " into writable build path " + dest, e);
            }
        }

        /**
         * Resolves a templated string by replacing its template substrings.
         * <p>
         * Many fields in the command definition may contain templated strings. These
         * strings are allowed to contain placeholder values, which are intended to be replaced
         * by real values at resolution time.
         * <p>
         * A templatized string may draw its value from anywhere in the command or wrapper by encoding the
         * value that it needs as a JSONPath expression. This JSONPath expression will be extracted from
         * the templatized string, used to search through the command or wrapper, and the result replaced into
         * the templatized string. See {@link #resolveJsonpathSubstring(String)}.
         * <p>
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
        private String resolveTemplate(final String template, Map<String, String> valuesMap)
                throws CommandResolutionException {
            log.debug("Resolving template: \"{}\".", template);

            if (StringUtils.isBlank(template)) {
                log.trace("Template is blank.");
                return template;
            }
            if (MapUtils.isEmpty(valuesMap)) {
                log.trace("No template replacement values found.");
                return template;
            }

            // First find any JSONPath strings in the template
            String toResolve = resolveJsonpathSubstring(template);

            // Look through the provided map of cached replacement values, and replace any that are found.
            for (final String replacementKey : valuesMap.keySet()) {
                final String replacementValue = valuesMap.get(replacementKey);
                final String copyForLogging = toResolve;

                toResolve = toResolve.replace(replacementKey, replacementValue == null ? "" : replacementValue);
                if (log.isTraceEnabled() && !toResolve.equals(copyForLogging)) {
                    // If the replacement operation changed the template, log the replacement
                    log.trace("{} -> {}", replacementKey, replacementValue);
                }
            }

            log.debug("Resolved template: \"{}\".", toResolve);
            return toResolve;
        }

        @Nonnull
        private List<String> resolveTemplates(final List<String> templates, Map<String, String> valuesMap)
                throws CommandResolutionException {
            if(templates == null || templates.isEmpty()){
                log.trace("No template replacement values found.");
                return Collections.emptyList();
            }
            final List<String> resolvedTemplates = new ArrayList<>(templates.size());
            for(String toResolve : templates){
                resolvedTemplates.add(resolveTemplate(toResolve, valuesMap));
            }
            return resolvedTemplates;
        }

        /**
         * Checks an input string for a JSONPath substring, extracts it,
         * and uses it to search the command or wrapper for the value.
         * <p>
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
                log.trace("Checking for JSONPath substring in \"{}\".", stringThatMayContainJsonpathSubstring);

                final Matcher jsonpathSubstringMatcher = JSONPATH_SUBSTRING.matcher(stringThatMayContainJsonpathSubstring);

                // TODO - Consider this: should I be looking for multiple JSONPath substrings and replacing them all?
                if (jsonpathSubstringMatcher.find()) {

                    final String jsonpathSearchWithMarkers = jsonpathSubstringMatcher.group(0);
                    final String useWrapper = jsonpathSubstringMatcher.group(1);
                    final String jsonpathSearchWithoutMarkers = jsonpathSubstringMatcher.group(2);

                    log.trace("Found possible JSONPath substring \"{}\".", jsonpathSearchWithMarkers);

                    if (StringUtils.isNotBlank(jsonpathSearchWithoutMarkers)) {

                        final List<String> searchResult;
                        if (StringUtils.isNotBlank(useWrapper)) {
                            log.trace("Performing JSONPath search through command wrapper with search string \"{}\".", jsonpathSearchWithoutMarkers);
                            searchResult = commandWrapperJsonpathContext.read(jsonpathSearchWithoutMarkers);
                        } else {
                            log.trace("Performing JSONPath search through command with search string \"{}\".", jsonpathSearchWithoutMarkers);
                            searchResult = commandJsonpathContext.read(jsonpathSearchWithoutMarkers);
                        }

                        if (searchResult != null && !searchResult.isEmpty() && searchResult.get(0) != null) {
                            log.trace("JSONPath search result: {}", searchResult);
                            if (searchResult.size() == 1) {
                                final String result = searchResult.get(0);
                                final String replacement = stringThatMayContainJsonpathSubstring.replace(jsonpathSearchWithMarkers, result);
                                log.trace("Replacing \"{}\" with \"{}\" in \"{}\".", jsonpathSearchWithMarkers, result, stringThatMayContainJsonpathSubstring);
                                log.trace("Result: \"{}\".", replacement);
                                return replacement;
                            } else {
                                throw new CommandResolutionException("JSONPath search " +
                                        "\"" + jsonpathSearchWithoutMarkers + "\" " +
                                        "returned multiple results: " +
                                        searchResult +
                                        ". Cannot determine value to replace.");
                            }
                        } else {
                            log.trace("No result");
                        }
                    }
                }

                log.trace("No jsonpath substring found.");
            }
            return stringThatMayContainJsonpathSubstring;
        }

        @Nullable
        private ResolvedInputValue getInputValueByName(final String name, final List<ResolvedInputTreeNode<? extends Input>> resolvedInputTrees) {
            for (final ResolvedInputTreeNode<? extends Input> root : resolvedInputTrees) {
                log.trace("Looking for input {} on input tree rooted on input {}.", name, root.input().name());
                final ResolvedInputValue resolvedInputValue = getInputValueByName(name, root);

                if (resolvedInputValue != null) {
                    return resolvedInputValue;
                }
            }

            log.trace("Did not find unique value for input {}.", name);
            return null;
        }

        @Nullable
        private ResolvedInputValue getInputValueByName(final String name, final ResolvedInputTreeNode<? extends Input> resolvedInputTreeNode) {
            log.trace("Checking input node with input \"{}\".", resolvedInputTreeNode.input().name());
            final List<ResolvedInputTreeValueAndChildren> valuesAndChildren = resolvedInputTreeNode.valuesAndChildren();
            if (valuesAndChildren.size() != 1) {
                log.trace("Input \"{}\" does not have a uniquely resolved value. There is no hope of its children having unique values. Returning null.", resolvedInputTreeNode.input().name());
                return null;
            }

            log.trace("Input \"{}\" has a uniquely resolved value.", resolvedInputTreeNode.input().name());
            final ResolvedInputTreeValueAndChildren valueAndChildren = valuesAndChildren.get(0);
            if (resolvedInputTreeNode.input().name() != null && resolvedInputTreeNode.input().name().equals(name)) {
                log.debug("Found target input \"{}\".", name);
                return valueAndChildren.resolvedValue();
            } else {
                log.trace("Input \"{}\" not found. Checking children.", name);
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
                log.error("No docker server", e);
                return null;
            }
            if (!Backend.SUPPORTS_CONSTRAINTS.contains(server.backend())) {
                log.debug("Skipping constraints for {} backend", server.backend().toString());
                return null;
            }
            List<DockerServerBase.DockerServerSwarmConstraint> serverConstraints = server.swarmConstraints();
            if (serverConstraints == null || serverConstraints.isEmpty()) {
                log.debug("No server constraints defined");
                return null;
            }

            List<String> resolvedConstraints = new ArrayList<>(serverConstraints.size());

            // Get user inputs
            Map<String, String> userConstraints = new HashMap<>(serverConstraints.size());
            String userConstraintsInputJson = inputValues.get(swarmConstraintsTag);
            if (userConstraintsInputJson != null) {
                try {
                    List<LaunchUi.LaunchUiServerConstraintSelected> userConstraintsInput = mapper.readValue(userConstraintsInputJson,
                            new TypeReference<List<LaunchUi.LaunchUiServerConstraintSelected>>() {});
                    for (LaunchUi.LaunchUiServerConstraintSelected c : userConstraintsInput) {
                        userConstraints.put(c.attribute(), c.value());
                    }

                } catch (IOException e) {
                    log.error("Problem reading constraint json \"{}\"", userConstraintsInputJson, e);
                }
            }

            // Populate list from user inputs & server "defaults"
            for (DockerServerBase.DockerServerSwarmConstraint constraint : serverConstraints) {
                if (constraint.userSettable()) {
                    // If the constraint is user settable, only add it if we have non-empty values from user input map
                    // Don't default to first value or whatever, just let Swarm do its default thing.
                    // If user did not select a value we do not include the constraint
                    String value = userConstraints.get(constraint.attribute());
                    if (StringUtils.isNotBlank(value)) {
                        String resolvedConstraint = constraint.asStringConstraint(value);
                        if (resolvedConstraint != null) {
                            resolvedConstraints.add(resolvedConstraint);
                            log.debug("Resolved user-specified constraint \"{}\"", resolvedConstraint);
                        } else {
                            log.debug("Skipping constraint {}. User-supplied value {} was not one of the constraint's allowed values {}",
                                    constraint.attribute(), value, constraint.values());
                        }
                    } else {
                        final List<String> values = constraint.values();
                        if (CollectionUtils.isNotEmpty(values)) {
                            String resolvedConstraint = constraint.asStringConstraint(values.get(0));
                            if (resolvedConstraint != null) {
                                resolvedConstraints.add(resolvedConstraint);
                                log.debug("Resolved user-settable constraint \"{}\" from default constraint values", resolvedConstraint);
                            } else {
                                log.error("Skipping constraint {}. Value {} from the constraint's allowed values {} was invalid, which shouldn't happen",
                                          constraint.attribute(), value, constraint.values());
                            }
                        } else {
                            log.debug("Skipping constraint {}. No user-selected or default value found.", constraint.attribute());
                        }
                    }
                } else {
                    // Always add non-user-settable constraints (with default value)
                    final String resolvedConstraint = constraint.asStringConstraint();
                    resolvedConstraints.add(resolvedConstraint);
                    log.debug("Resolved non-user-settable constraint using default value \"{}\"", resolvedConstraint);
                }
            }

            return resolvedConstraints.isEmpty() ? null : resolvedConstraints;
        }

        private List<ResolvedSecret> resolveSecrets(final List<Secret> secrets) throws CommandResolutionException {
            if (secrets.isEmpty()) {
                return Collections.emptyList();
            }

            final List<ResolvedSecret> resolvedSecrets = new ArrayList<>();
            for (Secret secret : secrets) {
                try {
                    resolvedSecrets.add(secretService.resolve(secret));
                } catch (ContainerServiceSecretException e) {
                    throw new CommandResolutionException("Could not resolve secret " + secret, e);
                }
            }
            return resolvedSecrets;
        }

        private void ensureInputsDoNotUseSecrets(
                final List<ResolvedInputTreeNode<? extends Input>> resolvedInputTrees,
                final List<ResolvedSecret> resolvedSecrets
        ) throws CommandResolutionException {
            // Find all the destination identifiers
            final List<String> secretDestinationIdentifiers = resolvedSecrets.stream()
                    .map(Secret::destination)
                    .map(SecretDestination::identifier)
                    .collect(Collectors.toList());

            // Will search resolved values for the secret identifiers using Aho-Corasick algorithm
            final Trie trie = Trie.builder()
                    .ignoreOverlaps()
                    .stopOnHit()
                    .addKeywords(secretDestinationIdentifiers)
                    .build();

            final List<Triple<String, String, String>> detectedSecrets = resolvedInputTrees.stream()
                    .flatMap(resolvedInputTree -> findInputsWithResolvedValuesThatContainSecretDestinationIdentifiers(resolvedInputTree, trie))
                    .collect(Collectors.toList());

            if (!detectedSecrets.isEmpty()) {
                final String message = detectedSecrets.stream()
                        .map(triple -> "Input \"" + triple.getLeft() +
                                "\" resolved value \"" + triple.getMiddle() +
                                "\" refers to secret destination \"" + triple.getRight() + "\"")
                        .collect(Collectors.joining(" | "));
                throw new CommandResolutionException(message);
            }
        }

        private Stream<Triple<String, String, String>> findInputsWithResolvedValuesThatContainSecretDestinationIdentifiers(
                final ResolvedInputTreeNode<? extends Input> resolvedInputTree, final Trie trie) {
            return resolvedInputTree.valuesAndChildren().stream()
                    .flatMap(valueAndChildren -> {
                        final Stream<Triple<String, String, String>> childResult = valueAndChildren.children().stream().flatMap(child -> findInputsWithResolvedValuesThatContainSecretDestinationIdentifiers(child, trie));

                        final String resolvedValue = valueAndChildren.resolvedValue().value();
                        final Emit valueMatch = resolvedValue == null ? null : trie.firstMatch(resolvedValue);
                        return (valueMatch != null)
                                ? Stream.concat(Stream.of(Triple.of(resolvedInputTree.input().name(), resolvedValue, valueMatch.getKeyword())), childResult)
                                : childResult;
                    });
        }

    }

    @Nonnull
    private String getBuildDirectory() throws CommandResolutionException {
        final String rootBuildPath = siteConfigPreferences.getBuildPath();
        final String uuid = UUID.randomUUID().toString();
        final String buildDir = FilenameUtils.concat(rootBuildPath, uuid);
        final Path created;
        try {
            created = Files.createDirectory(Paths.get(buildDir));
        } catch (IOException e) {
            throw new CommandResolutionException("Could not create build directory", e);
        }
        //noinspection ResultOfMethodCallIgnored
        created.toFile().setWritable(true);
        return buildDir;
    }
}
