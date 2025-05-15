package org.nrg.containers;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.nrg.containers.config.OrchestrationTestConfig;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.auto.Command.*;
import org.nrg.containers.model.command.entity.CommandWrapperInputType;
import org.nrg.containers.model.orchestration.auto.Orchestration;
import org.nrg.containers.model.orchestration.auto.Orchestration.OrchestrationIdentifier;
import org.nrg.containers.model.orchestration.auto.OrchestrationProject;
import org.nrg.containers.services.CommandService;
import org.nrg.containers.services.ContainerConfigService;
import org.nrg.containers.services.OrchestrationService;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xft.security.UserI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import javax.validation.ConstraintViolationException;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.nrg.containers.utils.TestingUtils.BUSYBOX;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = OrchestrationTestConfig.class)
public class OrchestrationTest {

    @Autowired private ObjectMapper mapper;
    @Autowired private CommandService commandService;
    @Autowired private OrchestrationService orchestrationService;

    @Rule public ExpectedException expectedException = ExpectedException.none();

    private static final String PROJECT = "project";
    private static final String NAME = "test";
    private static List<Long> WRAPPER_LIST;
    private static List<Long> WRAPPER_LIST_BAD;
    private static Orchestration ORCHESTRATION;
    private UserI user;

    @Before
    public void setup() throws Exception {
        user = Mockito.mock(UserI.class);
        Mockito.when(user.getUsername()).thenReturn("username");

        final Command sesCmd1 = commandService.create(Command.builder()
                .name("will-succeed")
                .image(BUSYBOX)
                .version("0")
                .commandLine("/bin/sh -c \"echo hi; exit 0\"")
                .addCommandWrapper(CommandWrapper.builder()
                        .name("placeholder")
                        .contexts("xnat:imageSessionData")
                        .addExternalInput(Command.CommandWrapperExternalInput.builder()
                                .name("session")
                                .type(CommandWrapperInputType.SESSION.getName())
                                .build())
                        .build())
                .build());

        final Command sesCmd2 = commandService.create(Command.builder()
                .name("will-succeed-2")
                .image(BUSYBOX)
                .version("0")
                .commandLine("/bin/sh -c \"echo hi 2; exit 0\"")
                .addCommandWrapper(CommandWrapper.builder()
                        .name("placeholder2")
                        .contexts("xnat:imageSessionData")
                        .addExternalInput(Command.CommandWrapperExternalInput.builder()
                                .name("session")
                                .type(CommandWrapperInputType.SESSION.getName())
                                .build())
                        .build())
                .build());

        final Command projCmd = commandService.create(Command.builder()
                .name("project")
                .image(BUSYBOX)
                .version("0")
                .commandLine("/bin/sh -c \"echo hi 3; exit 0\"")
                .addCommandWrapper(CommandWrapper.builder()
                        .name("placeholder3")
                        .contexts("xnat:projectData")
                        .addExternalInput(Command.CommandWrapperExternalInput.builder()
                                .name("project")
                                .type(CommandWrapperInputType.PROJECT.getName())
                                .build())
                        .build())
                .build());


        final CommandWrapper wrapperSecond  = sesCmd1.xnatCommandWrappers().get(0);
        final CommandWrapper wrapperFirst   = sesCmd2.xnatCommandWrappers().get(0);
        final CommandWrapper wrapperProject = projCmd.xnatCommandWrappers().get(0);

        commandService.enableForSite(wrapperFirst.id(), user.getUsername(), "test");
        commandService.enableForSite(wrapperSecond.id(), user.getUsername(), "test");
        commandService.enableForSite(wrapperProject.id(), user.getUsername(), "test");

        WRAPPER_LIST = Arrays.asList(wrapperFirst.id(), wrapperSecond.id());
        WRAPPER_LIST_BAD = Arrays.asList(wrapperFirst.id(), wrapperProject.id());
        ORCHESTRATION = new Orchestration(0L, NAME, WRAPPER_LIST);
    }

    @After
    public void cleanup() {
        for (Command cmd : commandService.getAll()) {
            commandService.delete(cmd);
        }
    }

    @Test
    public void testSpringConfiguration() {
        assertThat(orchestrationService, not(nullValue()));
    }

    @Test
    public void testSerializeDeserialize() throws Exception {
        assertThat(mapper.readValue(mapper.writeValueAsString(ORCHESTRATION), Orchestration.class), is(ORCHESTRATION));
    }

    @Test
    @DirtiesContext
    public void testSave() throws Exception {
        Orchestration o = orchestrationService.createOrUpdate(ORCHESTRATION);
        assertThat(o.getId(), not(0L));
        ORCHESTRATION.setId(o.getId());
        assertEquals(ORCHESTRATION, o);
    }


    @Test
    @DirtiesContext
    public void testSaveHaltOnCommandFailure() throws Exception {
        Orchestration o = orchestrationService.createOrUpdate(ORCHESTRATION);
        assertEquals(o.isHaltOnCommandFailure(), true);
        assertThat(o.getId(), not(0L));
        ORCHESTRATION.setId(o.getId());
        assertEquals(ORCHESTRATION, o);
        o.setHaltOnCommandFailure(false);
        assertEquals(o.isHaltOnCommandFailure(), false);
    }


    @Test
    @DirtiesContext
    public void testGetAll() throws Exception {
        Orchestration o = orchestrationService.createOrUpdate(ORCHESTRATION);
        assertThat(o.getId(), not(0L));
        List<Orchestration> o2 = orchestrationService.getAllPojos();
        assertThat(o2, hasSize(1));
        assertThat(o2.get(0), is(o));
    }

    @Test
    @DirtiesContext
    public void testUpdate() throws Exception {
        Orchestration o = orchestrationService.createOrUpdate(ORCHESTRATION);
        assertEquals(o.getName(), NAME);
        assertEquals(o.getWrapperIds(), WRAPPER_LIST);
        List<Long> newWrappers = new ArrayList<>(WRAPPER_LIST);
        Collections.reverse(newWrappers);
        String newName = "new name";
        o.setName(newName);
        o.setWrapperIds(newWrappers);
        o.setHaltOnCommandFailure(false);
        Orchestration retrieved = orchestrationService.createOrUpdate(o);
        assertThat(retrieved.getName(), is(newName));
        assertThat(retrieved.getWrapperIds(), is(newWrappers));
        assertEquals(o.isHaltOnCommandFailure(), false);
    }

    @Test
    @DirtiesContext
    public void testUpdateWithoutId() throws Exception {
        Orchestration o = orchestrationService.createOrUpdate(ORCHESTRATION);
        List<Long> newWrappers = new ArrayList<>(WRAPPER_LIST);
        Collections.reverse(newWrappers);
        o.setWrapperIds(newWrappers);
        o.setId(0L);

        expectedException.expect(org.hibernate.exception.ConstraintViolationException.class);
        expectedException.expectMessage("could not execute statement");
        orchestrationService.createOrUpdate(o);
    }

    @Test
    @DirtiesContext 
    public void testDisableEnable() throws Exception {
        Orchestration o = orchestrationService.createOrUpdate(ORCHESTRATION);
        assertTrue(o.isEnabled());
        orchestrationService.setEnabled(o.getId(), false, user);
        List<Orchestration> retrieved = orchestrationService.getAllPojos();
        assertThat(retrieved, hasSize(1));
        Orchestration o2 = retrieved.get(0);
        assertFalse(o2.isEnabled());
        orchestrationService.createOrUpdate(o2);
        retrieved = orchestrationService.getAllPojos();
        assertThat(retrieved, hasSize(1));
        o2 = retrieved.get(0);
        assertTrue(o2.isEnabled());
    }

    @Test
    @DirtiesContext
    public void testInvalidNoWrappers() throws Exception {
        expectedException.expect(InvalidParameterException.class);
        expectedException.expectMessage("Orchestration of fewer than two wrappers is not allowed");
        orchestrationService.createOrUpdate(new Orchestration(0L, NAME, 
                new ArrayList<>()));
    }

    @Test
    @DirtiesContext
    public void testInvalidOneWrapper() throws Exception {
        expectedException.expect(InvalidParameterException.class);
        expectedException.expectMessage("Orchestration of fewer than two wrappers is not allowed");
        orchestrationService.createOrUpdate(new Orchestration(0L, NAME, 
                Collections.singletonList(1L)));
    }

    @Test
    @DirtiesContext
    public void testInvalidWrapperContext() throws Exception {
        expectedException.expect(InvalidParameterException.class);
        expectedException.expectMessage("Wrappers must all have a common context");
        orchestrationService.createOrUpdate(new Orchestration(0L, NAME, 
                WRAPPER_LIST_BAD));
    }

    @Test
    @DirtiesContext
    public void testInvalidNonexistentWrapper() throws Exception {
        long badWrapperId = 500L;
        expectedException.expect(InvalidParameterException.class);
        expectedException.expectMessage("Wrapper " + badWrapperId + " not enabled for site");
        orchestrationService.createOrUpdate(new Orchestration(0L, NAME, 
                Arrays.asList(badWrapperId, WRAPPER_LIST.get(0))));
    }

    @Test
    @DirtiesContext
    public void testInvalidNoName() throws Exception {
        expectedException.expect(ConstraintViolationException.class);
        expectedException.expectMessage("Validation failed");
        orchestrationService.createOrUpdate(new Orchestration(0L, null, WRAPPER_LIST));
    }

    @Test
    @DirtiesContext
    public void testInvalidDisabledWrapper() throws Exception {
        long wrapperId = ORCHESTRATION.getWrapperIds().get(0);
        commandService.disableForSite(wrapperId, user.getUsername(), "test");
        expectedException.expect(InvalidParameterException.class);
        expectedException.expectMessage("Wrapper " + wrapperId + " not enabled for site");
        orchestrationService.createOrUpdate(ORCHESTRATION);
    }

    @Test
    @DirtiesContext
    public void testFindNextWrapper() throws Exception {
        orchestrationService.createOrUpdate(ORCHESTRATION);
        Command.CommandWrapper wrapper = orchestrationService.findNextWrapper(1L, 0,
                WRAPPER_LIST.get(0));
        assertNotNull(wrapper);
        assertThat(wrapper.id(), is(WRAPPER_LIST.get(1)));

        Command.CommandWrapper wrapper2 = orchestrationService.findNextWrapper(1L, 1,
                WRAPPER_LIST.get(1));
        assertNull(wrapper2);
    }

    @Test
    @DirtiesContext
    public void testFindNextWrapperWrongIdx() throws Exception {
        orchestrationService.createOrUpdate(ORCHESTRATION);
        Command.CommandWrapper wrapper = orchestrationService.findNextWrapper(1L, 1,
                WRAPPER_LIST.get(0));
        assertNull(wrapper);
    }

    @Test
    @DirtiesContext
    public void testAddToProject() throws Exception {
        Orchestration o = orchestrationService.createOrUpdate(ORCHESTRATION);

        Orchestration o2 = orchestrationService.findWhereWrapperIsFirst(new OrchestrationIdentifier(PROJECT, WRAPPER_LIST.get(0)));
        assertNull(o2);

        OrchestrationProject op = orchestrationService.getAvailableForProject(PROJECT);
        assertNull(op.getSelectedOrchestrationId());

        orchestrationService.setProjectOrchestration(PROJECT, o.getId(), user);

        o2 = orchestrationService.findWhereWrapperIsFirst(new OrchestrationIdentifier(PROJECT, WRAPPER_LIST.get(0)));
        assertNotNull(o2);
        assertThat(o, is(o2));

        op = orchestrationService.getAvailableForProject(PROJECT);
        assertThat(op.getSelectedOrchestrationId(), is(o.getId()));
    }

    @Test
    @DirtiesContext
    public void testAddDisabledToProject() throws Exception {
        Orchestration o = orchestrationService.createOrUpdate(ORCHESTRATION);
        orchestrationService.setEnabled(o.getId(), false, user);

        expectedException.expect(InvalidParameterException.class);
        expectedException.expectMessage("Orchestration " + o.getName() + " (" +
                o.getId()+ ") cannot be added to a project because it is not enabled.");
        orchestrationService.setProjectOrchestration(PROJECT, o.getId(), user);
    }

    @Test
    @DirtiesContext
    public void testAddNonexistentToProject() throws Exception {
        Orchestration o = orchestrationService.createOrUpdate(ORCHESTRATION);
        expectedException.expect(NotFoundException.class);
        expectedException.expectMessage("Could not find entity with ID");
        orchestrationService.setProjectOrchestration(PROJECT, o.getId()+500, user);
    }

    @Test
    @DirtiesContext
    public void testFindWhereWrapperIsFirst() throws Exception {
        Orchestration o = orchestrationService.createOrUpdate(ORCHESTRATION);
        orchestrationService.setProjectOrchestration(PROJECT, o.getId(), user);
        Orchestration o2 = orchestrationService.findWhereWrapperIsFirst(new OrchestrationIdentifier(PROJECT, WRAPPER_LIST.get(0)));
        assertNotNull(o2);
        assertThat(o, is(o2));
    }

    @Test
    @DirtiesContext
    public void testFindWhereWrapperIsFirstDisabled() throws Exception {
        Orchestration o = orchestrationService.createOrUpdate(ORCHESTRATION);
        orchestrationService.setProjectOrchestration(PROJECT, o.getId(), user);
        orchestrationService.setEnabled(o.getId(), false, user);
        Orchestration o2 = orchestrationService.findWhereWrapperIsFirst(new OrchestrationIdentifier(PROJECT, WRAPPER_LIST.get(0)));
        assertNull(o2);
    }

    @Test
    @DirtiesContext
    public void testFindWhereWrapperIsFirstRemoved() throws Exception {
        Orchestration o = orchestrationService.createOrUpdate(ORCHESTRATION);
        orchestrationService.setProjectOrchestration(PROJECT, o.getId(), user);
        Orchestration o2 = orchestrationService.findWhereWrapperIsFirst(new OrchestrationIdentifier(PROJECT, WRAPPER_LIST.get(0)));
        assertThat(o, is(o2));
        orchestrationService.removeProjectOrchestration(PROJECT);
        o2 = orchestrationService.findWhereWrapperIsFirst(new OrchestrationIdentifier(PROJECT, WRAPPER_LIST.get(0)));
        assertNull(o2);
    }

    @Test
    @DirtiesContext
    public void testFindWhereWrapperIsFirstNotMatched() throws Exception {
        Orchestration orig = orchestrationService.createOrUpdate(ORCHESTRATION);
        orchestrationService.setProjectOrchestration(PROJECT, orig.getId(), user);

        Orchestration o = orchestrationService.findWhereWrapperIsFirst(new OrchestrationIdentifier(PROJECT, WRAPPER_LIST.get(1)));
        assertNull(o);

        o = orchestrationService.findWhereWrapperIsFirst(new OrchestrationIdentifier(null, WRAPPER_LIST.get(0)));
        assertNull(o);

        o = orchestrationService.findWhereWrapperIsFirst(new OrchestrationIdentifier("alt", WRAPPER_LIST.get(0)));
        assertNull(o);
    }

    @Test
    @DirtiesContext
    public void testGetAvailableForProject() throws Exception {
        Orchestration o = orchestrationService.createOrUpdate(ORCHESTRATION);

        OrchestrationProject op = orchestrationService.getAvailableForProject(PROJECT);
        assertNull(op.getSelectedOrchestrationId());
        assertThat(op.getAvailableOrchestrations(), hasSize(1));
        assertThat(op.getAvailableOrchestrations().get(0).isEnabled(), is(true));

        orchestrationService.setEnabled(o.getId(), false, user);
        op = orchestrationService.getAvailableForProject(PROJECT);
        assertNull(op.getSelectedOrchestrationId());
        assertThat(op.getAvailableOrchestrations(), hasSize(1));
        assertThat(op.getAvailableOrchestrations().get(0).isEnabled(), is(false));

        orchestrationService.setEnabled(o.getId(), true, user);
        orchestrationService.setProjectOrchestration(PROJECT, o.getId(), user);
        op = orchestrationService.getAvailableForProject(PROJECT);
        assertThat(op.getSelectedOrchestrationId(), is(o.getId()));
        assertThat(op.getAvailableOrchestrations(), hasSize(1));
        assertThat(op.getAvailableOrchestrations().get(0).isEnabled(), is(true));

        String name = "alt";
        orchestrationService.createOrUpdate(new Orchestration(0L, name, WRAPPER_LIST));
        op = orchestrationService.getAvailableForProject(PROJECT);
        assertThat(op.getSelectedOrchestrationId(), is(o.getId()));
        assertThat(op.getAvailableOrchestrations(), hasSize(2));
        assertThat(op.getAvailableOrchestrations().get(0).isEnabled(), is(true));
        assertThat(op.getAvailableOrchestrations().get(1).isEnabled(), is(true));

        orchestrationService.removeProjectOrchestration(PROJECT);
        op = orchestrationService.getAvailableForProject(PROJECT);
        assertNull(op.getSelectedOrchestrationId());
        assertThat(op.getAvailableOrchestrations(), hasSize(2));
    }

    @Test
    @DirtiesContext
    public void testFindForProject() throws Exception {
        Orchestration o = orchestrationService.createOrUpdate(ORCHESTRATION);

        Orchestration o2 = orchestrationService.findForProject(PROJECT);
        assertNull(o2);

        orchestrationService.setProjectOrchestration(PROJECT, o.getId(), user);
        o2 = orchestrationService.findForProject(PROJECT);
        assertThat(o2, is(o));

        String name = "alt";
        orchestrationService.createOrUpdate(new Orchestration(0L, name, WRAPPER_LIST));
        o2 = orchestrationService.findForProject(PROJECT);
        assertThat(o2, is(o));

        orchestrationService.setEnabled(o.getId(), false, user);
        o2 = orchestrationService.findForProject(PROJECT);
        assertNull(o2);

        orchestrationService.setEnabled(o.getId(), true, user);
        orchestrationService.setProjectOrchestration(PROJECT, o.getId(), user);
        o2 = orchestrationService.findForProject(PROJECT);
        assertThat(o2, is(o));

        orchestrationService.removeProjectOrchestration(PROJECT);
        o2 = orchestrationService.findForProject(PROJECT);
        assertNull(o2);
    }

    @Test
    @DirtiesContext
    public void testDisableFromCommand() throws Exception {
        Orchestration o = orchestrationService.createOrUpdate(ORCHESTRATION);
        orchestrationService.setProjectOrchestration(PROJECT, o.getId(), user);
        commandService.disableForSite(o.getWrapperIds().get(0), user.getUsername(), "test");
        Orchestration o2 = orchestrationService.findForProject(PROJECT);
        assertNull(o2);

        List<Orchestration> orchestrations = orchestrationService.getAllPojos();
        assertThat(orchestrations, hasSize(1));
        assertThat(orchestrations.get(0).isEnabled(), is(false));

        orchestrationService.setEnabled(o.getId(), true, user);
        orchestrations = orchestrationService.getAllPojos();
        assertThat(orchestrations, hasSize(1));
        assertThat(orchestrations.get(0).isEnabled(), is(true));
        assertThat(commandService.isEnabledForSite(o.getWrapperIds().get(0)), is(true));
    }

    @Test
    @DirtiesContext
    public void testDisableFromCommandMultithreaded() throws Exception {
        Orchestration o = orchestrationService.createOrUpdate(ORCHESTRATION);
        orchestrationService.createOrUpdate(new Orchestration(0L, "alt", WRAPPER_LIST));
        orchestrationService.setProjectOrchestration(PROJECT, o.getId(), user);
        ExecutorService executor = Executors.newFixedThreadPool(10);
        List<Callable<Void>> callableTasks = new ArrayList<>();
        callableTasks.add(() -> {
            commandService.disableForSite(WRAPPER_LIST.get(0), user.getUsername(), "test");
            return null;
        });
        callableTasks.add(() -> {
            commandService.disableForSite(WRAPPER_LIST.get(1), user.getUsername(), "test");
            return null;
        });
        List<Future<Void>> futures = executor.invokeAll(callableTasks);
        for (Future<Void> f : futures) {
            f.get(); // will throw exception as ExecutionException
        }

        Orchestration o2 = orchestrationService.findForProject(PROJECT);
        assertNull(o2);
    }
}
