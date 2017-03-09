package org.nrg.containers.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.io.Resources;
import org.hamcrest.Matchers;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nrg.containers.config.CommandTestConfig;
import org.nrg.containers.model.auto.Command;
import org.nrg.containers.services.CommandEntityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

@RunWith(SpringJUnit4ClassRunner.class)
@Transactional
@ContextConfiguration(classes = CommandTestConfig.class)
public class CommandEntityTest {
    private static final String COOL_INPUT_JSON = "{" +
            "\"name\":\"my_cool_input\", " +
            "\"description\":\"A boolean value\", " +
            "\"type\":\"boolean\", " +
            "\"required\":true," +
            "\"true-value\":\"-b\", " +
            "\"false-value\":\"\"" +
            "}";
    private static final String STRING_INPUT_NAME = "foo";
    private static final String STRING_INPUT_JSON = "{" +
            "\"name\":\"" + STRING_INPUT_NAME + "\", " +
            "\"description\":\"A foo that bars\", " +
            "\"required\":false," +
            "\"default-value\":\"bar\"," +
            "\"command-line-flag\":\"--flag\"," +
            "\"command-line-separator\":\"=\"" +
            "}";

    private static final String COMMAND_OUTPUT_NAME = "the_output";
    private static final String COMMAND_OUTPUT = "{" +
            "\"name\":\"" + COMMAND_OUTPUT_NAME + "\"," +
            "\"description\":\"It's the output\"," +
            "\"mount\":\"out\"," +
            "\"path\":\"relative/path/to/dir\"" +
            "}";
    private static final String INPUT_LIST_JSON = "[" + COOL_INPUT_JSON + ", " + STRING_INPUT_JSON + "]";

    private static final String MOUNT_IN = "{\"name\":\"in\", \"writable\": false, \"path\":\"/input\"}";
    private static final String MOUNT_OUT = "{\"name\":\"out\", \"writable\": true, \"path\":\"/output\"}";

    private static final String EXTERNAL_INPUT_NAME = "session";
    private static final String XNAT_COMMAND_WRAPPER_EXTERNAL_INPUT = "{" +
            "\"name\": \"" + EXTERNAL_INPUT_NAME + "\"" +
            ", \"type\": \"Session\"" +
            "}";
    private static final String DERIVED_INPUT_NAME = "label";
    private static final String XNAT_OBJECT_PROPERTY = "label";
    private static final String XNAT_COMMAND_WRAPPER_DERIVED_INPUT = "{" +
            "\"name\": \"" + DERIVED_INPUT_NAME + "\"" +
            ", \"type\": \"string\"" +
            ", \"derived-from-xnat-input\": \"" + EXTERNAL_INPUT_NAME + "\"" +
            ", \"derived-from-xnat-object-property\": \"" + XNAT_OBJECT_PROPERTY + "\"" +
            ", \"provides-value-for-command-input\": \"" + STRING_INPUT_NAME + "\"" +
            "}";

    private static final String OUTPUT_HANDLER_LABEL = "a_label";
    private static final String XNAT_COMMAND_WRAPPER_OUTPUT_HANDLER = "{" +
            "\"type\": \"Resource\"" +
            ", \"accepts-command-output\": \"" + COMMAND_OUTPUT_NAME + "\"" +
            ", \"as-a-child-of-xnat-input\": \"" + EXTERNAL_INPUT_NAME + "\"" +
            ", \"label\": \"" + OUTPUT_HANDLER_LABEL + "\"" +
            "}";

    private static final String XNAT_COMMAND_WRAPPER_NAME = "wrappername";
    private static final String XNAT_COMMAND_WRAPPER_DESC = "the wrapper description";
    private static final String XNAT_COMMAND_WRAPPER = "{" +
            "\"name\": \"" + XNAT_COMMAND_WRAPPER_NAME + "\", " +
            "\"description\": \"" + XNAT_COMMAND_WRAPPER_DESC + "\"," +
            "\"external-inputs\": [" + XNAT_COMMAND_WRAPPER_EXTERNAL_INPUT + "], " +
            "\"derived-inputs\": [" + XNAT_COMMAND_WRAPPER_DERIVED_INPUT + "], " +
            "\"output-handlers\": [" + XNAT_COMMAND_WRAPPER_OUTPUT_HANDLER + "]" +
            "}";

    private static final String DOCKER_IMAGE_COMMAND_JSON = "{" +
            "\"name\":\"docker_image_command\", " +
            "\"description\":\"Docker Image command for the test\", " +
            "\"type\": \"docker\", " +
            "\"info-url\":\"http://abc.xyz\", " +
            "\"environment-variables\":{\"foo\":\"bar\"}, " +
            "\"command-line\":\"cmd #foo# #my_cool_input#\", " +
            "\"mounts\":[" + MOUNT_IN + ", " + MOUNT_OUT + "]," +
            "\"ports\": {\"22\": \"2222\"}, " +
            "\"inputs\":" + INPUT_LIST_JSON + ", " +
            "\"outputs\":[" + COMMAND_OUTPUT + "], " +
            "\"image\":\"abc123\"" +
            ", \"xnat\": [" + XNAT_COMMAND_WRAPPER + "]" +
            "}";


    @Autowired private ObjectMapper mapper;
    @Autowired private CommandEntityService commandEntityService;

    @Test
    public void testSpringConfiguration() {
        assertThat(commandEntityService, not(nullValue()));
    }

    @Test
    public void testDeserializeCommandInput() throws Exception {
        final CommandInputEntity commandInputEntity0 =
                mapper.readValue(COOL_INPUT_JSON, CommandInputEntity.class);
        final Command.CommandInput commandInput0 = Command.CommandInput.create(commandInputEntity0);
        final CommandInputEntity fooInputEntity =
                mapper.readValue(STRING_INPUT_JSON, CommandInputEntity.class);
        final Command.CommandInput fooInput = Command.CommandInput.create(fooInputEntity);

        assertEquals("my_cool_input", commandInput0.name());
        assertEquals("A boolean value", commandInput0.description());
        assertEquals(CommandInputEntity.Type.BOOLEAN.getName(), commandInput0.type());
        assertTrue(commandInput0.required());
        assertEquals("-b", commandInput0.trueValue());
        assertEquals("", commandInput0.falseValue());
        assertEquals("#my_cool_input#", commandInput0.replacementKey());
        assertEquals("", commandInput0.commandLineFlag());
        assertEquals(" ", commandInput0.commandLineSeparator());
        assertNull(commandInput0.defaultValue());

        assertEquals("foo", fooInput.name());
        assertEquals("A foo that bars", fooInput.description());
        assertEquals(CommandInputEntity.Type.STRING.getName(), fooInput.type());
        assertFalse(fooInput.required());
        assertNull(fooInput.trueValue());
        assertNull(fooInput.falseValue());
        assertEquals("#foo#", fooInput.replacementKey());
        assertEquals("--flag", fooInput.commandLineFlag());
        assertEquals("=", fooInput.commandLineSeparator());
        assertEquals("bar", fooInput.defaultValue());
    }

    @Test
    public void testDeserializeDockerImageCommand() throws Exception {

        final List<CommandInputEntity> commandInputEntityList =
                mapper.readValue(INPUT_LIST_JSON, new TypeReference<List<CommandInputEntity>>() {});
        final CommandOutputEntity commandOutputEntity = mapper.readValue(COMMAND_OUTPUT, CommandOutputEntity.class);

        final CommandMountEntity input = mapper.readValue(MOUNT_IN, CommandMountEntity.class);
        final CommandMountEntity output = mapper.readValue(MOUNT_OUT, CommandMountEntity.class);

        final CommandEntity commandEntity = mapper.readValue(DOCKER_IMAGE_COMMAND_JSON, CommandEntity.class);
        for (final CommandInputEntity commandInputEntity : commandInputEntityList) {
            commandInputEntity.setCommandEntity(commandEntity);
        }
        commandOutputEntity.setCommandEntity(commandEntity);
        input.setCommandEntity(commandEntity);
        output.setCommandEntity(commandEntity);

        assertEquals("abc123", commandEntity.getImage());

        assertEquals("docker_image_command", commandEntity.getName());
        assertEquals("Docker Image command for the test", commandEntity.getDescription());
        assertEquals("http://abc.xyz", commandEntity.getInfoUrl());
        assertEquals(commandInputEntityList, commandEntity.getInputs());
        assertEquals(Lists.newArrayList(commandOutputEntity), commandEntity.getOutputs());

        // final CommandRun run = command.getRun();
        assertEquals("cmd #foo# #my_cool_input#", commandEntity.getCommandLine());
        assertEquals(ImmutableMap.of("foo", "bar"), commandEntity.getEnvironmentVariables());
        assertEquals(Lists.newArrayList(input, output), commandEntity.getMounts());

        assertThat(commandEntity, instanceOf(DockerCommandEntity.class));
        assertEquals(ImmutableMap.of("22", "2222"), ((DockerCommandEntity) commandEntity).getPorts());
    }

    @Test
    @DirtiesContext
    public void testPersistDockerImageCommand() throws Exception {

        final CommandEntity commandEntity = mapper.readValue(DOCKER_IMAGE_COMMAND_JSON, CommandEntity.class);

        commandEntityService.create(commandEntity);

        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        final CommandEntity retrievedCommandEntity = commandEntityService.retrieve(commandEntity.getId());

        assertEquals(commandEntity, retrievedCommandEntity);

        assertThat(Command.create(commandEntity).validate(), is(Matchers.<String>emptyIterable()));
    }

    @Test
    public void testDeserializeXnatCommandInputsAndOutputs() throws Exception {
        final CommandWrapperExternalInputEntity externalInput = mapper.readValue(XNAT_COMMAND_WRAPPER_EXTERNAL_INPUT, CommandWrapperExternalInputEntity.class);
        assertEquals(EXTERNAL_INPUT_NAME, externalInput.getName());
        assertEquals(CommandWrapperInputType.SESSION, externalInput.getType());
        assertNull(externalInput.getProvidesValueForCommandInput());
        assertNull(externalInput.getDefaultValue());
        assertNull(externalInput.getMatcher());
        assertFalse(externalInput.getRequired());

        final CommandWrapperDerivedInputEntity derivedInput = mapper.readValue(XNAT_COMMAND_WRAPPER_DERIVED_INPUT, CommandWrapperDerivedInputEntity.class);
        assertEquals(DERIVED_INPUT_NAME, derivedInput.getName());
        assertEquals(CommandWrapperInputType.STRING, derivedInput.getType());
        assertEquals(EXTERNAL_INPUT_NAME, derivedInput.getDerivedFromXnatInput());
        assertEquals(XNAT_OBJECT_PROPERTY, derivedInput.getDerivedFromXnatObjectProperty());
        assertEquals(STRING_INPUT_NAME, derivedInput.getProvidesValueForCommandInput());
        assertNull(derivedInput.getDefaultValue());
        assertNull(derivedInput.getMatcher());
        assertFalse(derivedInput.getRequired());

        final CommandWrapperOutputEntity output = mapper.readValue(XNAT_COMMAND_WRAPPER_OUTPUT_HANDLER, CommandWrapperOutputEntity.class);
        assertEquals(CommandWrapperOutputEntity.Type.RESOURCE, output.getType());
        assertEquals(EXTERNAL_INPUT_NAME, output.getXnatInputName());
        assertEquals(COMMAND_OUTPUT_NAME, output.getCommandOutputName());
        assertEquals(OUTPUT_HANDLER_LABEL, output.getLabel());
    }

    @Test
    public void testDeserializeCommandWithCommandWrapper() throws Exception {

        final CommandWrapperEntity commandWrapperEntity = mapper.readValue(XNAT_COMMAND_WRAPPER, CommandWrapperEntity.class);

        final CommandEntity commandEntity = mapper.readValue(DOCKER_IMAGE_COMMAND_JSON, CommandEntity.class);

        assertThat(commandEntity.getCommandWrapperEntities(), hasSize(1));
        assertTrue(commandEntity.getCommandWrapperEntities().contains(commandWrapperEntity));
    }

    @Test
    @DirtiesContext
    public void testPersistCommandWithWrapper() throws Exception {

        final CommandEntity commandEntity = mapper.readValue(DOCKER_IMAGE_COMMAND_JSON, CommandEntity.class);

        commandEntityService.create(commandEntity);

        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        final CommandEntity retrievedCommandEntity = commandEntityService.retrieve(commandEntity.getId());

        assertEquals(commandEntity, retrievedCommandEntity);

        final List<CommandWrapperEntity> commandWrappers = retrievedCommandEntity.getCommandWrapperEntities();
        assertThat(commandWrappers, hasSize(1));

        final CommandWrapperEntity commandWrapperEntity = commandWrappers.get(0);
        assertThat(commandWrapperEntity.getId(), not(0L));
        assertEquals(commandEntity, commandWrapperEntity.getCommandEntity());

        assertThat(Command.create(commandEntity).validate(), is(Matchers.<String>emptyIterable()));
    }

    @Test
    @DirtiesContext
    public void testDeleteCommandWithWrapper() throws Exception {

        final CommandEntity commandEntity = mapper.readValue(DOCKER_IMAGE_COMMAND_JSON, CommandEntity.class);

        final CommandEntity created = commandEntityService.create(commandEntity);

        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        commandEntityService.delete(created);

        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        assertNull(commandEntityService.retrieve(created.getId()));
    }

    @Test
    @DirtiesContext
    public void testRetrieveCommandWrapper() throws Exception {

        final CommandEntity commandEntity = mapper.readValue(DOCKER_IMAGE_COMMAND_JSON, CommandEntity.class);

        final CommandEntity created = commandEntityService.create(commandEntity);

        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        final CommandWrapperEntity createdWrapper = created.getCommandWrapperEntities().get(0);
        final long wrapperId = createdWrapper.getId();
        assertEquals(createdWrapper, commandEntityService.retrieve(created, wrapperId));

        assertThat(Command.create(created).validate(), is(Matchers.<String>emptyIterable()));
    }

    @Test
    @DirtiesContext
    public void testAddCommandWrapper() throws Exception {

        final CommandEntity commandEntity = mapper.readValue(DOCKER_IMAGE_COMMAND_JSON, CommandEntity.class);
        final CommandWrapperEntity toAdd = commandEntity.getCommandWrapperEntities().get(0);
        commandEntity.setCommandWrapperEntities(null);

        final CommandEntity created = commandEntityService.create(commandEntity);

        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        final CommandWrapperEntity added = commandEntityService.addWrapper(created, toAdd);

        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        final CommandEntity retrieved = commandEntityService.get(commandEntity.getId());
        assertEquals(added, retrieved.getCommandWrapperEntities().get(0));

        assertThat(Command.create(retrieved).validate(), is(Matchers.<String>emptyIterable()));
    }

    @Test
    @DirtiesContext
    public void testUpdateCommandWrapper() throws Exception {

        final CommandEntity commandEntity = mapper.readValue(DOCKER_IMAGE_COMMAND_JSON, CommandEntity.class);

        final CommandEntity created = commandEntityService.create(commandEntity);

        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        final CommandWrapperEntity createdWrapper = created.getCommandWrapperEntities().get(0);

        final String newDescription = "This is probably a new description, right?";
        createdWrapper.setDescription(newDescription);
        final CommandWrapperEntity updated = commandEntityService.update(createdWrapper);
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        assertEquals(newDescription, updated.getDescription());

        assertThat(Command.create(created).validate(), is(Matchers.<String>emptyIterable()));
    }

    @Test
    @DirtiesContext
    public void testDeleteCommandWrapper() throws Exception {

        final CommandEntity commandEntity = mapper.readValue(DOCKER_IMAGE_COMMAND_JSON, CommandEntity.class);

        final CommandEntity created = commandEntityService.create(commandEntity);

        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        final long commandId = created.getId();
        final long wrapperId = created.getCommandWrapperEntities().get(0).getId();
        commandEntityService.delete(commandId, wrapperId);

        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        assertNull(commandEntityService.retrieve(commandId, wrapperId));
    }

    @Test
    public void testCreateEcatHeaderDump() throws Exception {
        // A User was attempting to create the command in this resource.
        // Spring didn't tell us why. See CS-70.
        final String dir = Resources.getResource("ecatHeaderDump").getPath().replace("%20", " ");
        final String commandJsonFile = dir + "/command.json";
        final CommandEntity ecatHeaderDump = mapper.readValue(new File(commandJsonFile), CommandEntity.class);
        commandEntityService.create(ecatHeaderDump);
    }
}