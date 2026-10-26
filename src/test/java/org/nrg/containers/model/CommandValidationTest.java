package org.nrg.containers.model;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.Test;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.secrets.FileSecretDestination;
import org.nrg.containers.secrets.K8sSecretSource;
import org.nrg.containers.secrets.Secret;

import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
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

    /** File secret declarations are checked when the command is saved, not only when it is launched (XNAT-8703). */
    @Test
    public void testFileSecretDeclarationsAreValidatedOnSave() {
        assertThat(commandWithSecret(new K8sSecretSource("redcap"), "/run/secrets/redcap").validate(), is(empty()));
        assertThat(commandWithSecret(new K8sSecretSource("redcap", "xnat-#PROJECT#-{identifier}"), "/run/secrets/redcap").validate(), is(empty()));

        assertThat(commandWithSecret(new K8sSecretSource("redcap", "redcap"), "/run/secrets/redcap").validate(),
                contains(containsString("must contain {identifier}")));
        assertThat(commandWithSecret(new K8sSecretSource("redcap", null, "xnat-proj01-redcap", null), "/run/secrets/redcap").validate(),
                contains(containsString("name-template")));
        assertThat(commandWithSecret(new K8sSecretSource("red cap"), "/run/secrets/redcap").validate(),
                contains(containsString("may only contain")));
        assertThat(commandWithSecret(new K8sSecretSource("redcap"), "/run/secrets").validate(),
                contains(containsString("subdirectory")));
    }

    private static Command commandWithSecret(final K8sSecretSource source, final String mountPath) {
        return Command.builder()
                .name("cmd").image("busybox:latest").commandLine("true")
                .secrets(Collections.singletonList(new Secret(source, new FileSecretDestination(mountPath))))
                .build();
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
