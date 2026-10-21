package org.nrg.containers.secrets;

import org.apache.commons.lang3.StringUtils;
import org.junit.Test;
import org.nrg.containers.model.command.auto.Command;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

/** Resolution-time rules from design doc section 2.5: identifier and mount path uniqueness, mount path shape. */
public class FileSecretValidatorTest {

    private static FileResolvedSecret secret(final String identifier, final String secretName, final String mountPath) {
        return new FileResolvedSecret(new Secret(new K8sSecretSource(identifier, secretName), new FileSecretDestination(mountPath)));
    }

    @Test
    public void twoSecretsWithDifferentIdentifiersAndPathsAreFine() {
        FileSecretValidator.validate(Arrays.asList(
                secret("redcap", "xnat-p-redcap", "/run/secrets/redcap"),
                secret("openspecimen", "xnat-p-openspecimen", "/run/secrets/openspecimen")));
    }

    @Test
    public void volumeNamesMustFitADnsLabel() {
        assertRejected(Collections.singletonList(secret(StringUtils.repeat('a', 60), "xnat-p-a", "/run/secrets/a")), "63");
    }

    @Test
    public void identifiersMustBeUniqueAfterSanitising() {
        assertRejected(Arrays.asList(secret("RedCap", "a", "/run/secrets/a"), secret("redcap", "b", "/run/secrets/b")), "identifier");
    }

    @Test
    public void theSameSecretNameMayBeMountedTwice() {
        FileSecretValidator.validate(Arrays.asList(secret("a", "shared", "/run/secrets/a"), secret("b", "shared", "/run/secrets/b")));
    }

    @Test
    public void mountPathsMustBeAbsoluteSubdirectoriesWithoutDotDot() {
        assertRejected(Collections.singletonList(secret("a", "x", "run/secrets/a")), "absolute");
        assertRejected(Collections.singletonList(secret("a", "x", "/")), "must not be /");
        assertRejected(Collections.singletonList(secret("a", "x", "/run/secrets")), "subdirectory");
        assertRejected(Collections.singletonList(secret("a", "x", "/var/run/secrets/")), "subdirectory");
        assertRejected(Collections.singletonList(secret("a", "x", "/run/secrets/../etc")), "..");
    }

    @Test
    public void mountPathsMustBeNormalised() {
        assertRejected(Collections.singletonList(secret("a", "x", "//run/secrets")), "normalised");
        assertRejected(Collections.singletonList(secret("a", "x", "/run/./secrets")), "normalised");
        assertRejected(Arrays.asList(secret("a", "x", "/run/secrets/a"), secret("b", "y", "/run/secrets//a")), "normalised");
        FileSecretValidator.validate(Collections.singletonList(secret("a", "x", "/run/secrets/a/")));
    }

    @Test
    public void identifiersMustBePresentAndUseSafeCharacters() {
        assertRejected(Collections.singletonList(secret(null, "x", "/run/secrets/a")), "identifier is required");
        assertRejected(Collections.singletonList(secret("red cap", "x", "/run/secrets/a")), "may only contain");
    }

    @Test
    public void mountPathsMustBeUniqueAndNotNested() {
        assertRejected(Arrays.asList(secret("a", "x", "/run/secrets/a"), secret("b", "y", "/run/secrets/a/")), "unique");
        assertRejected(Arrays.asList(secret("a", "x", "/run/secrets/a"), secret("b", "y", "/run/secrets/a/b")), "nest");
        FileSecretValidator.validate(Arrays.asList(secret("a", "x", "/run/secrets/a"), secret("ab", "y", "/run/secrets/ab")));
    }

    @Test
    public void setupAndWrapupCommandsMayNotDeclareFileSecrets() {
        final Command setup = Command.builder().name("setup").image("xnat/setup:1").commandLine("true").type("docker-setup")
                .secrets(Collections.singletonList(new Secret(new K8sSecretSource("redcap"), new FileSecretDestination("/run/secrets/redcap"))))
                .build();
        try {
            FileSecretValidator.requireNoFileSecrets(setup, "setup");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), containsString("setup command setup"));
            assertThat(e.getMessage(), containsString("redcap"));
        }

        final Command envOnly = Command.builder().name("wrapup").image("xnat/wrapup:1").commandLine("true").type("docker-wrapup")
                .secrets(Collections.singletonList(new Secret(new SystemPropertySecretSource("p"), new EnvironmentVariableSecretDestination("E"))))
                .build();
        FileSecretValidator.requireNoFileSecrets(envOnly, "wrapup");
    }

    private static void assertRejected(final List<FileResolvedSecret> secrets, final String expectedFragment) {
        try {
            FileSecretValidator.validate(secrets);
            fail("Expected IllegalArgumentException containing: " + expectedFragment);
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), containsString(expectedFragment));
        }
    }
}
