package org.nrg.containers.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nrg.containers.config.CommandTestConfig;
import org.nrg.containers.model.xnat.Session;
import org.nrg.containers.services.CommandService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;

@RunWith(SpringJUnit4ClassRunner.class)
@Transactional
@ContextConfiguration(classes = CommandTestConfig.class)
public class CommandTest {
    private static final String COOL_INPUT_JSON = "{" +
            "\"name\":\"my_cool_input\", " +
            "\"description\":\"A boolean value\", " +
            "\"type\":\"boolean\", " +
            "\"required\":true," +
            "\"true-value\":\"-b\", " +
            "\"false-value\":\"\"" +
            "}";
    private static final String FOO_INPUT_JSON = "{" +
            "\"name\":\"foo\", " +
            "\"description\":\"A foo that bars\", " +
            "\"required\":false," +
            "\"default-value\":\"bar\"," +
            "\"command-line-flag\":\"--flag\"," +
            "\"command-line-separator\":\"=\"" +
            "}";
    
    private static final String OUTPUT_JSON = "{" +
            "\"name\":\"the_output\"," +
            "\"description\":\"It's the output\"," +
            "\"mount\":\"out\"," +
            "\"path\":\"relative/path/to/dir\"" +
            "}";
    private static final String INPUT_LIST_JSON = "[" + COOL_INPUT_JSON + ", " + FOO_INPUT_JSON + "]";

    private static final String MOUNT_IN = "{\"name\":\"in\", \"writable\": false, \"path\":\"/input\"}";
    private static final String MOUNT_OUT = "{\"name\":\"out\", \"writable\": true, \"path\":\"/output\"}";
    private static final String RESOLVED_MOUNT_IN = "{" +
            "\"name\":\"in\", " +
            "\"writable\": false, " +
            "\"remote-path\":\"/input\", " +
            "\"host-path\":\"/path/to/files\", " +
            "\"file-input\":\"session\", " +
            "\"resource\":\"a_resource\"" +
            "}";
    private static final String RESOLVED_MOUNT_OUT = "{" +
            "\"name\": \"out\", " +
            "\"writable\": true, " +
            "\"path\":\"/output\", " +
            "\"file-input\":\"session\", " +
            "\"resource\":\"out\"" +
            "}";
    private static final String RESOLVED_OUTPUT_JSON = "{" +
            "\"name\":\"the_output\"," +
            "\"type\":\"Resource\"," +
            "\"label\":\"DATA\"," +
            "\"parent\":\"session\"," +
            "\"mount\":\"out\"," +
            "\"path\":\"relative/path/to/dir\"" +
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
            "\"outputs\":[" + OUTPUT_JSON + "], " +
            "\"image\":\"abc123\"" +
            "}";

    private static final String RESOLVED_DOCKER_IMAGE_COMMAND_JSON_TEMPLATE = "{" +
            "\"command-id\":%d, " +
            "\"docker-image\":\"abc123\", " +
            "\"env\":{\"foo\":\"bar\"}, " +
            "\"command-line\":\"cmd --flag=bar \", " +
            "\"mounts-in\":[" + RESOLVED_MOUNT_IN + "]," +
            "\"mounts-out\":[" + RESOLVED_MOUNT_OUT + "]," +
            "\"input-values\": {" +
                "\"my_cool_input\": \"%s\"," +
                "\"foo\": \"%s\"," +
                "\"session\": \"%s\"" +
            "}," +
            "\"outputs\":[ " + RESOLVED_OUTPUT_JSON + "]," +
            "\"ports\": {\"22\": \"2222\"}" +
            "}";

    @Autowired private ObjectMapper mapper;
    @Autowired private CommandService commandService;

    @Test
    public void testSpringConfiguration() {
        assertThat(commandService, not(nullValue()));
    }


    @Test
    public void testDeserializeCommandInput() throws Exception {
        final CommandInput commandInput0 =
                mapper.readValue(COOL_INPUT_JSON, CommandInput.class);
        final CommandInput fooInput =
                mapper.readValue(FOO_INPUT_JSON, CommandInput.class);

        assertEquals("my_cool_input", commandInput0.getName());
        assertEquals("A boolean value", commandInput0.getDescription());
        assertEquals(CommandInput.Type.BOOLEAN, commandInput0.getType());
        assertEquals(true, commandInput0.isRequired());
        assertEquals("-b", commandInput0.getTrueValue());
        assertEquals("", commandInput0.getFalseValue());
        assertEquals("#my_cool_input#", commandInput0.getReplacementKey());
        assertEquals("", commandInput0.getCommandLineFlag());
        assertEquals(" ", commandInput0.getCommandLineSeparator());
        assertNull(commandInput0.getDefaultValue());

        assertEquals("foo", fooInput.getName());
        assertEquals("A foo that bars", fooInput.getDescription());
        assertEquals(CommandInput.Type.STRING, fooInput.getType());
        assertEquals(false, fooInput.isRequired());
        assertNull(fooInput.getTrueValue());
        assertNull(fooInput.getFalseValue());
        assertEquals("#foo#", fooInput.getReplacementKey());
        assertEquals("--flag", fooInput.getCommandLineFlag());
        assertEquals("=", fooInput.getCommandLineSeparator());
        assertEquals("bar", fooInput.getDefaultValue());
    }

    @Test
    public void testDeserializeDockerImageCommand() throws Exception {

        final Set<CommandInput> commandInputList =
                mapper.readValue(INPUT_LIST_JSON, new TypeReference<Set<CommandInput>>() {});
        final CommandOutput commandOutput = mapper.readValue(OUTPUT_JSON, CommandOutput.class);

        final CommandMount input = mapper.readValue(MOUNT_IN, CommandMount.class);
        final CommandMount output = mapper.readValue(MOUNT_OUT, CommandMount.class);

        final Command command = mapper.readValue(DOCKER_IMAGE_COMMAND_JSON, Command.class);

        assertEquals("abc123", command.getImage());

        assertEquals("docker_image_command", command.getName());
        assertEquals("Docker Image command for the test", command.getDescription());
        assertEquals("http://abc.xyz", command.getInfoUrl());
        assertEquals(commandInputList, command.getInputs());
        assertEquals(Sets.newHashSet(commandOutput), command.getOutputs());

        // final CommandRun run = command.getRun();
        assertEquals("cmd #foo# #my_cool_input#", command.getCommandLine());
        assertEquals(ImmutableMap.of("foo", "bar"), command.getEnvironmentVariables());
        assertEquals(Sets.newHashSet(input, output), command.getMounts());

        assertThat(command, instanceOf(DockerCommand.class));
        assertEquals(ImmutableMap.of("22", "2222"), ((DockerCommand)command).getPorts());
    }

    @Test
    public void testPersistDockerImageCommand() throws Exception {

        final Command command = mapper.readValue(DOCKER_IMAGE_COMMAND_JSON, Command.class);

        commandService.create(command);
        commandService.flush();
        commandService.refresh(command);

        final Command retrievedCommand = commandService.retrieve(command.getId());

        assertEquals(command, retrievedCommand);
    }

    @Test
    public void testResolveCommand() throws Exception {
        final String sessionId = "1";
        final String resourceId = "1";
        final String sessionUri = "/experiments/" + sessionId;
        final String resourceUri = sessionUri + "/resources/" + resourceId;
        final String resourceJson = "{" +
                "\"id\":" + resourceId + ", " +
                "\"type\":\"Resource\", " +
                "\"label\":\"a_resource\", " +
                "\"uri\":\"" + resourceUri + "\", " +
                "\"directory\":\"/path/to/files\"" +
                "}";
        final String sessionJson = "{" +
                "\"id\":\"1\", " +
                "\"type\":\"Session\", " +
                "\"label\":\"a_session\", " +
                "\"uri\": \"" + sessionUri + "\", " +
                "\"xsiType\":\"xnat:fakesessiondata\", " +
                "\"resources\":[" + resourceJson + "]" +
                "}";


        final Command command = mapper.readValue(DOCKER_IMAGE_COMMAND_JSON, Command.class);

        final Map<String, String> runtimeValues = Maps.newHashMap();
        runtimeValues.put("my_cool_input", "false");
        runtimeValues.put("session", sessionJson);
        final ResolvedCommand resolvedCommand = commandService.resolveCommand(command, runtimeValues, null);

//        final String filledOutSessionJson = mapper.writeValueAsString(mapper.readValue(sessionJson, Session.class)).replaceAll("\\\"", "\\\\\\\"");
        final Session session = mapper.readValue(sessionJson, Session.class);
        final String resolvedCommandJson1 =
                String.format(RESOLVED_DOCKER_IMAGE_COMMAND_JSON_TEMPLATE,
                        command.getId(), "", "bar", session.getUri());
        final ResolvedCommand expected1 = mapper.readValue(resolvedCommandJson1, ResolvedCommand.class);
        assertEquals(expected1, resolvedCommand);

        runtimeValues.put("my_cool_input", "true");
        final ResolvedCommand resolvedCommand2 = commandService.resolveCommand(command, runtimeValues, null);

        final String resolvedCommandJson2 =
                String.format(RESOLVED_DOCKER_IMAGE_COMMAND_JSON_TEMPLATE,
                        command.getId(), "true", "bar", session.getUri());
        final ResolvedCommand expected2 = mapper.readValue(resolvedCommandJson2, ResolvedCommand.class);
        assertEquals(expected2.getEnvironmentVariables(), resolvedCommand2.getEnvironmentVariables());
        assertEquals(expected2.getMountsIn(), resolvedCommand2.getMountsIn());
        assertEquals(expected2.getMountsOut(), resolvedCommand2.getMountsOut());

        assertEquals("cmd --flag=bar -b", resolvedCommand2.getCommandLine());
    }
}
