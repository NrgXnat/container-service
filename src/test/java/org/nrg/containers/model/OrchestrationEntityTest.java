package org.nrg.containers.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.nrg.containers.config.OrchestrationEntityTestConfig;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.auto.Command.*;
import org.nrg.containers.model.command.entity.CommandWrapperInputType;
import org.nrg.containers.model.orchestration.auto.Orchestration;
import org.nrg.containers.model.orchestration.entity.OrchestrationEntity;
import org.nrg.containers.services.CommandService;
import org.nrg.containers.services.OrchestrationEntityService;
import org.nrg.framework.constants.Scope;
import org.nrg.framework.exceptions.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.transaction.annotation.Transactional;

import javax.validation.ConstraintViolationException;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.nrg.containers.utils.TestingUtils.BUSYBOX;

@RunWith(SpringJUnit4ClassRunner.class)
@Transactional
@ContextConfiguration(classes = OrchestrationEntityTestConfig.class)
public class OrchestrationEntityTest {

    @Autowired private ObjectMapper mapper;
    @Autowired private CommandService commandService;
    @Autowired private OrchestrationEntityService orchestrationEntityService;

    @Rule public ExpectedException expectedException = ExpectedException.none();

    private static final String PROJECT = "project";
    private static final String NAME = "test";
    private static List<Long> WRAPPER_LIST;
    private static List<Long> WRAPPER_LIST_BAD;
    private static Orchestration ORCHESTRATION;

    @Before
    public void setup() throws Exception {
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

        WRAPPER_LIST = Arrays.asList(wrapperFirst.id(), wrapperSecond.id());
        WRAPPER_LIST_BAD = Arrays.asList(wrapperFirst.id(), wrapperProject.id());
        ORCHESTRATION = new Orchestration(0L, NAME, PROJECT, Scope.Project.name(), WRAPPER_LIST);
    }

    @Test
    public void testSpringConfiguration() {
        assertThat(orchestrationEntityService, not(nullValue()));
    }

    @Test
    public void testSerializeDeserialize() throws Exception {
        assertThat(mapper.readValue(mapper.writeValueAsString(ORCHESTRATION), Orchestration.class), is(ORCHESTRATION));
    }

    @Test
    @DirtiesContext
    public void testSave() throws Exception {
        Orchestration o = orchestrationEntityService.createOrUpdate(ORCHESTRATION);
        assertThat(o.getId(), not(0L));
        ORCHESTRATION.setId(o.getId());
        assertEquals(ORCHESTRATION, o);
    }

    @Test
    @DirtiesContext
    public void testFind() throws Exception {
        Orchestration o = orchestrationEntityService.createOrUpdate(ORCHESTRATION);
        Orchestration o2 = orchestrationEntityService.find(Scope.Project, PROJECT);
        assertThat(o, is(o2));
    }

    @Test
    @DirtiesContext
    public void testUpdate() throws Exception {
        Orchestration o = orchestrationEntityService.createOrUpdate(ORCHESTRATION);
        assertEquals(o.getName(), NAME);
        assertEquals(o.getWrapperIds(), WRAPPER_LIST);
        List<Long> newWrappers = new ArrayList<>(WRAPPER_LIST);
        Collections.reverse(newWrappers);
        String newName = "new name";
        o.setName(newName);
        o.setWrapperIds(newWrappers);
        Orchestration retrieved = orchestrationEntityService.createOrUpdate(o);
        assertThat(retrieved.getName(), is(newName));
        assertThat(retrieved.getWrapperIds(), is(newWrappers));
    }

    @Test
    @DirtiesContext
    public void testUpdateWithoutId() throws Exception {
        Orchestration o = orchestrationEntityService.createOrUpdate(ORCHESTRATION);
        List<Long> newWrappers = new ArrayList<>(WRAPPER_LIST);
        Collections.reverse(newWrappers);
        String newName = "new name";
        o.setName(newName);
        o.setWrapperIds(newWrappers);
        o.setId(0L);

        expectedException.expect(InvalidParameterException.class);
        expectedException.expectMessage(Scope.Project + " " + PROJECT +
                " already has orchestration configured: update using id or delete and recreate.");
        orchestrationEntityService.createOrUpdate(o);
    }

    @Test
    @DirtiesContext
    public void testDisableEnable() throws Exception {
        Orchestration o = orchestrationEntityService.createOrUpdate(ORCHESTRATION);
        orchestrationEntityService.disable(o.getId());
        Orchestration retrieved = orchestrationEntityService.find(Scope.Project, PROJECT);
        assertFalse(retrieved.isEnabled());
        orchestrationEntityService.createOrUpdate(retrieved);
        retrieved = orchestrationEntityService.find(Scope.Project, PROJECT);
        assertTrue(retrieved.isEnabled());
    }

    @Test
    @DirtiesContext
    public void testInvalidNoWrappers() throws Exception {
        expectedException.expect(InvalidParameterException.class);
        expectedException.expectMessage("Orchestration of fewer than two wrappers is not allowed");
        orchestrationEntityService.createOrUpdate(new Orchestration(0L, NAME, PROJECT, Scope.Project.name(),
                new ArrayList<>()));
    }

    @Test
    @DirtiesContext
    public void testInvalidOneWrapper() throws Exception {
        expectedException.expect(InvalidParameterException.class);
        expectedException.expectMessage("Orchestration of fewer than two wrappers is not allowed");
        orchestrationEntityService.createOrUpdate(new Orchestration(0L, NAME, PROJECT, Scope.Project.name(),
                Collections.singletonList(1L)));
    }

    @Test
    @DirtiesContext
    public void testInvalidWrapperContext() throws Exception {
        expectedException.expect(InvalidParameterException.class);
        expectedException.expectMessage("Wrappers must all have a common context");
        orchestrationEntityService.createOrUpdate(new Orchestration(0L, NAME, PROJECT, Scope.Project.name(),
                WRAPPER_LIST_BAD));
    }

    @Test
    @DirtiesContext
    public void testInvalidNonexistantWrapper() throws Exception {
        expectedException.expect(NotFoundException.class);
        expectedException.expectMessage("No command wrapper for id 500");
        orchestrationEntityService.createOrUpdate(new Orchestration(0L, NAME, PROJECT, Scope.Project.name(),
                Arrays.asList(500L, WRAPPER_LIST.get(0))));
    }

    @Test
    @DirtiesContext
    public void testInvalidNoName() throws Exception {
        expectedException.expect(ConstraintViolationException.class);
        expectedException.expectMessage("Validation failed");
        orchestrationEntityService.createOrUpdate(new Orchestration(0L, null, PROJECT,
                Scope.Project.name(), WRAPPER_LIST));
    }

    @Test
    @DirtiesContext
    public void testInvalidProject() throws Exception {
        expectedException.expect(InvalidParameterException.class);
        expectedException.expectMessage("Scoped item ID required for scope Project");
        orchestrationEntityService.createOrUpdate(new Orchestration(0L, NAME, null,
                Scope.Project.name(), WRAPPER_LIST));
    }

    @Test
    @DirtiesContext
    public void testInvalidScope() throws Exception {
        expectedException.expect(InvalidParameterException.class);
        expectedException.expectMessage("Scope not specified");
        orchestrationEntityService.createOrUpdate(new Orchestration(0L, NAME, PROJECT, null, WRAPPER_LIST));
    }

    @Test
    @DirtiesContext
    public void testLazyLoad() throws Exception {
        orchestrationEntityService.createOrUpdate(ORCHESTRATION);
        OrchestrationEntity oe = orchestrationEntityService.get(1L);
        List<Long> wrapperIds = oe.getWrapperList().stream().map(owe ->
                owe.getCommandWrapperEntity().getId()).collect(Collectors.toList());
        assertEquals(wrapperIds, WRAPPER_LIST);
    }

    @Test
    @DirtiesContext
    public void testFindNextWrapper() throws Exception {
        orchestrationEntityService.createOrUpdate(ORCHESTRATION);
        Command.CommandWrapper wrapper = orchestrationEntityService.findNextWrapper(1L, 0,
                WRAPPER_LIST.get(0));
        assertNotNull(wrapper);
        assertThat(wrapper.id(), is(WRAPPER_LIST.get(1)));

        Command.CommandWrapper wrapper2 = orchestrationEntityService.findNextWrapper(1L, 1,
                WRAPPER_LIST.get(1));
        assertNull(wrapper2);
    }

    @Test
    @DirtiesContext
    public void testFindNextWrapperWrongIdx() throws Exception {
        orchestrationEntityService.createOrUpdate(ORCHESTRATION);
        Command.CommandWrapper wrapper = orchestrationEntityService.findNextWrapper(1L, 1,
                WRAPPER_LIST.get(0));
        assertNull(wrapper);
    }

    @Test
    @DirtiesContext
    public void testFindWhereWrapperIsFirst() throws Exception {
        orchestrationEntityService.createOrUpdate(ORCHESTRATION);
        Orchestration o = orchestrationEntityService.findWhereWrapperIsFirst(
                new Orchestration.OrchestrationIdentifier(Scope.Project, PROJECT, WRAPPER_LIST.get(0)));
        assertNotNull(o);
        ORCHESTRATION.setId(o.getId());
        assertThat(o, is(ORCHESTRATION));
    }

    @Test
    @DirtiesContext
    public void testFindWhereWrapperIsFirstNotMatched() throws Exception {
        orchestrationEntityService.createOrUpdate(ORCHESTRATION);
        Orchestration o = orchestrationEntityService.findWhereWrapperIsFirst(
                new Orchestration.OrchestrationIdentifier(Scope.Project, PROJECT, WRAPPER_LIST.get(1)));
        assertNull(o);

        o = orchestrationEntityService.findWhereWrapperIsFirst(
                new Orchestration.OrchestrationIdentifier(Scope.Site, null, WRAPPER_LIST.get(0)));
        assertNull(o);
    }
}
