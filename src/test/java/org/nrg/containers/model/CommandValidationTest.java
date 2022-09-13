package org.nrg.containers.model;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.Test;
import org.nrg.containers.model.command.auto.Command;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

public class CommandValidationTest {
    private static final int NAME_LENGTH = 5;

    /**
     * Test that we can make and validate a wrapper output handler
     */
    @Test
    public void testValidateCommandWrapperOutput() {
        final String outputName = "output-" + RandomStringUtils.randomAlphabetic(NAME_LENGTH);

        final String mountName = RandomStringUtils.randomAlphabetic(NAME_LENGTH);
        final Command.CommandOutput output = Command.CommandOutput.builder()
                .name(outputName)
                .mount(mountName)
                .build();
        final Command.CommandMount mount = Command.CommandMount.create(
                mountName, true, RandomStringUtils.randomAlphabetic(NAME_LENGTH)
        );
        final Command command = Command.builder()
                .name(RandomStringUtils.randomAlphabetic(NAME_LENGTH))
                .image(RandomStringUtils.randomAlphabetic(NAME_LENGTH) + ":latest")
                .addOutput(output)
                .addMount(mount)
                .addCommandWrapper(makeWrapperThatHandlesOutput(outputName))
                .build();

        final List<String> errors = command.validate();
        assertThat(errors, is(empty()));
    }

    /**
     * Test that we can make and validate a command with two wrappers, each with an output handler
     */
    @Test
    public void testValidateMultipleOutputHandlers() {
        final String outputName = "output-" + RandomStringUtils.randomAlphabetic(NAME_LENGTH);

        final String mountName = RandomStringUtils.randomAlphabetic(NAME_LENGTH);
        final Command.CommandOutput output = Command.CommandOutput.builder()
                .name(outputName)
                .mount(mountName)
                .build();
        final Command.CommandMount mount = Command.CommandMount.create(
                mountName, true, RandomStringUtils.randomAlphabetic(NAME_LENGTH)
        );
        final Command command = Command.builder()
                .name(RandomStringUtils.randomAlphabetic(NAME_LENGTH))
                .image(RandomStringUtils.randomAlphabetic(NAME_LENGTH) + ":latest")
                .addOutput(output)
                .addMount(mount)
                .addCommandWrapper(makeWrapperThatHandlesOutput(outputName))
                .addCommandWrapper(makeWrapperThatHandlesOutput(outputName))
                .build();

        final List<String> errors = command.validate();
        assertThat(errors, is(empty()));
    }

    private Command.CommandWrapper makeWrapperThatHandlesOutput(final String outputName) {
        final String externalInputName = "externalinput-" + RandomStringUtils.randomAlphabetic(NAME_LENGTH);
        final String outputHandlerName = "outputhandler-" + RandomStringUtils.randomAlphabetic(NAME_LENGTH);
        final String outputHandlerLabel = RandomStringUtils.randomAlphabetic(NAME_LENGTH);
        final Command.CommandWrapperOutput outputHandler = Command.CommandWrapperOutput.builder()
                .name(outputHandlerName)
                .commandOutputName(outputName)
                .targetName(externalInputName)
                .type("Resource")
                .label(outputHandlerLabel)
                .build();
        final Command.CommandWrapperExternalInput externalInput = Command.CommandWrapperExternalInput.builder()
                .name(externalInputName)
                .build();
        return Command.CommandWrapper.builder()
                .name(RandomStringUtils.randomAlphabetic(NAME_LENGTH))
                .addExternalInput(externalInput)
                .addOutputHandler(outputHandler)
                .build();
    }
}
