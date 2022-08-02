package org.nrg.containers.services;

import lombok.extern.slf4j.Slf4j;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.auto.CommandSummaryForContext;
import org.nrg.containers.model.command.entity.CommandEntity;
import org.nrg.containers.services.impl.CommandServiceImpl;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xdat.security.helpers.Permissions;
import org.nrg.xft.schema.Wrappers.GenericWrapper.GenericWrapperElement;
import org.nrg.xft.security.UserI;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.nrg.containers.utils.ContainerServicePermissionUtils.PROJECT_EDIT_XML_PATH;
import static org.nrg.containers.utils.ContainerServicePermissionUtils.PROJECT_READ_XML_PATH;
import static org.nrg.xdat.security.SecurityManager.EDIT;
import static org.nrg.xdat.security.SecurityManager.READ;
import static org.powermock.api.mockito.PowerMockito.mockStatic;

@Slf4j
@RunWith(PowerMockRunner.class)
@PrepareForTest({Permissions.class, GenericWrapperElement.class})
public class CommandsAvailableTest {

    @Rule
    public TestRule watcher = new TestWatcher() {
        protected void starting(Description description) {
            log.info("BEGINNING TEST " + description.getMethodName());
        }

        protected void finished(Description description) {
            log.info("ENDING TEST " + description.getMethodName());
        }
    };

    @Mock private CommandEntityService commandEntityService;
    @Mock private ContainerConfigService containerConfigService;

    @Mock private UserI admin;
    @Mock private UserI collaborator;
    private static final String project = "project";

    private CommandService commandService;

    @Before
    public void setup() {
        Mockito.when(admin.getLogin()).thenReturn("admin");
        Mockito.when(admin.getUsername()).thenReturn("admin");
        Mockito.when(collaborator.getLogin()).thenReturn("collab");
        Mockito.when(collaborator.getUsername()).thenReturn("collab");

        commandService = new CommandServiceImpl(commandEntityService, containerConfigService);
    }

    @Test
    public void testNonProjectInput() throws Exception {
        // This UUID will stand in for the "context" and a data type
        final String xsiType = UUID.randomUUID().toString();

        // Command setup
        // One wrapper needs only read access to the external input
        // The other has a resource output handler so needs read + edit on the input's type
        // We leave the type as "string" even though that shouldn't be a checked type;
        //  the API uses the input "context" value as the type
        final String externalInputName = "input";
        final String outputName = "output";
        final Command.CommandWrapper wrapperRequiresRead = Command.CommandWrapper.builder()
                .name("requires read")
                .contexts(Collections.singleton(xsiType))
                .addExternalInput(Command.CommandWrapperExternalInput.builder().name(externalInputName).type("string").build())
                .build();
        final Command.CommandWrapper wrapperRequiresEdit = Command.CommandWrapper.builder()
                .name("requires edit")
                .contexts(Collections.singleton(xsiType))
                .addExternalInput(Command.CommandWrapperExternalInput.builder().name(externalInputName).type("string").build())
                .addOutputHandler(
                        Command.CommandWrapperOutput.builder()
                                .name("output-handler")
                                .targetName(externalInputName)
                                .commandOutputName(outputName)
                                .type("Resource")
                                .build()
                )
                .build();
        final Command command = Command.builder()
                .name("the-command")
                .type("docker")
                .image("whatever")
                .addMount(Command.CommandMount.create("name", true, "path"))
                .addOutput(Command.CommandOutput.builder().name(outputName).mount("name").build())
                .addCommandWrapper(wrapperRequiresRead)
                .addCommandWrapper(wrapperRequiresEdit)
                .build();

        // Mock the call to get the commands
        Mockito.when(commandEntityService.getAll()).thenReturn(Collections.singletonList(CommandEntity.fromPojo(command)));

        // Mock the call to get the enabled/disabled status
        Mockito.when(containerConfigService.isEnabled(eq(project), any(Long.class))).thenReturn(true);

        // Mock resolving xsi type
        mockStatic(GenericWrapperElement.class);
        PowerMockito.when(GenericWrapperElement.GetElement(any(String.class)))
                .thenAnswer(invocation -> {
                    GenericWrapperElement gwe = Mockito.mock(GenericWrapperElement.class);
                    // Just return whatever xsi type was passed in
                    Mockito.when(gwe.getXSIType()).thenReturn(invocation.getArgumentAt(0, String.class));
                    return gwe;
                });

        // Mock permissions checks
        mockStatic(Permissions.class);

        // Admin can read and edit
        PowerMockito.when(Permissions.can(admin, PROJECT_READ_XML_PATH, project, READ)).thenReturn(true);
        PowerMockito.when(Permissions.can(admin, xsiType + "/project", project, READ)).thenReturn(true);
        PowerMockito.when(Permissions.can(admin, xsiType + "/project", project, EDIT)).thenReturn(true);

        // Collaborator can only read
        PowerMockito.when(Permissions.can(collaborator, PROJECT_READ_XML_PATH, project, READ)).thenReturn(true);
        PowerMockito.when(Permissions.can(collaborator, xsiType + "/project", project, READ)).thenReturn(true);
        PowerMockito.when(Permissions.can(collaborator, xsiType + "/project", project, EDIT)).thenReturn(false);

        final CommandSummaryForContext readWrapperSummary = CommandSummaryForContext.create(command, wrapperRequiresRead, true, externalInputName);
        final CommandSummaryForContext editWrapperSummary = CommandSummaryForContext.create(command, wrapperRequiresEdit, true, externalInputName);

        // Admin can read and edit
        final List<CommandSummaryForContext> actualForAdmin = commandService.available(project, xsiType, admin);
        assertThat(actualForAdmin, containsInAnyOrder(readWrapperSummary, editWrapperSummary));

        // Collaborator can only read
        final List<CommandSummaryForContext> actualForCollaborator = commandService.available(project, xsiType, collaborator);
        assertThat(actualForCollaborator, equalTo(Collections.singletonList(readWrapperSummary)));

    }

    @Test
    public void testProjectInput() throws Exception {
        // This UUID will stand in for the "context" and a data type
        final String xsiType = XnatProjectdata.SCHEMA_ELEMENT_NAME;

        // Command setup
        // One wrapper needs only read access to the external input
        // The other has a resource output handler so needs read + edit on the input's type
        // We leave the type as "string" even though that shouldn't be a checked type;
        //  the API uses the input "context" value as the type
        final String externalInputName = "input";
        final String outputName = "output";
        final Command.CommandWrapper wrapperRequiresRead = Command.CommandWrapper.builder()
                .name("requires read")
                .contexts(Collections.singleton(xsiType))
                .addExternalInput(Command.CommandWrapperExternalInput.builder().name(externalInputName).type("Project").build())
                .build();
        final Command.CommandWrapper wrapperRequiresEdit = Command.CommandWrapper.builder()
                .name("requires edit")
                .contexts(Collections.singleton(xsiType))
                .addExternalInput(Command.CommandWrapperExternalInput.builder().name(externalInputName).type("Project").build())
                .addOutputHandler(
                        Command.CommandWrapperOutput.builder()
                                .name("output-handler")
                                .targetName(externalInputName)
                                .commandOutputName(outputName)
                                .type("Resource")
                                .build()
                )
                .build();
        final Command command = Command.builder()
                .name("the-command")
                .type("docker")
                .image("whatever")
                .addMount(Command.CommandMount.create("name", true, "path"))
                .addOutput(Command.CommandOutput.builder().name(outputName).mount("name").build())
                .addCommandWrapper(wrapperRequiresRead)
                .addCommandWrapper(wrapperRequiresEdit)
                .build();

        // Mock the call to get the commands
        Mockito.when(commandEntityService.getAll()).thenReturn(Collections.singletonList(CommandEntity.fromPojo(command)));

        // Mock the call to get the enabled/disabled status
        Mockito.when(containerConfigService.isEnabled(eq(project), any(Long.class))).thenReturn(true);

        // Mock resolving xsi type
        mockStatic(GenericWrapperElement.class);
        PowerMockito.when(GenericWrapperElement.GetElement(any(String.class)))
                .thenAnswer(invocation -> {
                    GenericWrapperElement gwe = Mockito.mock(GenericWrapperElement.class);
                    // Just return whatever xsi type was passed in
                    Mockito.when(gwe.getXSIType()).thenReturn(invocation.getArgumentAt(0, String.class));
                    return gwe;
                });

        // Mock permissions checks
        mockStatic(Permissions.class);

        // Admin can read and edit
        PowerMockito.when(Permissions.can(admin, PROJECT_READ_XML_PATH, project, READ)).thenReturn(true);
        PowerMockito.when(Permissions.can(admin, PROJECT_EDIT_XML_PATH, project, EDIT)).thenReturn(true);

        // Collaborator can only read
        PowerMockito.when(Permissions.can(collaborator, PROJECT_READ_XML_PATH, project, READ)).thenReturn(true);
        PowerMockito.when(Permissions.can(collaborator, PROJECT_EDIT_XML_PATH, project, EDIT)).thenReturn(false);

        final CommandSummaryForContext readWrapperSummary = CommandSummaryForContext.create(command, wrapperRequiresRead, true, externalInputName);
        final CommandSummaryForContext editWrapperSummary = CommandSummaryForContext.create(command, wrapperRequiresEdit, true, externalInputName);

        // Admin can read and edit
        final List<CommandSummaryForContext> actualForAdmin = commandService.available(project, xsiType, admin);
        assertThat(actualForAdmin, containsInAnyOrder(readWrapperSummary, editWrapperSummary));

        // Collaborator can only read
        final List<CommandSummaryForContext> actualForCollaborator = commandService.available(project, xsiType, collaborator);
        assertThat(actualForCollaborator, equalTo(Collections.singletonList(readWrapperSummary)));

    }
}
