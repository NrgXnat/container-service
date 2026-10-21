package org.nrg.containers.secrets;

import org.apache.commons.lang3.StringUtils;
import org.junit.Test;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

public class K8sSecretNameTemplateTest {

    @Test
    public void sanitizeKeepsDotsButLowercasesAndReplacesOtherCharacters() {
        assertThat(K8sSecretNameTemplate.sanitize("Xnat_Proj.01--redcap"), is("xnat-proj.01-redcap"));
        assertThat(K8sSecretNameTemplate.sanitize("--a--"), is("a"));
    }

    @Test
    public void sanitizeLabelAlsoReplacesDots() {
        assertThat(K8sSecretNameTemplate.sanitizeLabel("xnat-My_Proj.1"), is("xnat-my-proj-1"));
        assertThat(K8sSecretNameTemplate.sanitizeLabel("..x.."), is("x"));
    }

    @Test
    public void requireValidNameRejectsBlankAndOverlong() {
        assertThat(K8sSecretNameTemplate.requireValidName("ok", "Secret name"), is("ok"));
        assertRejected(() -> K8sSecretNameTemplate.requireValidName("", "Secret name"), "Secret name");
        assertRejected(() -> K8sSecretNameTemplate.requireValidName(StringUtils.repeat('a', 254), "Secret name"), "253");
    }

    @Test
    public void requireValidLabelRejectsOverlong() {
        assertThat(K8sSecretNameTemplate.requireValidLabel(StringUtils.repeat('a', 63), "Namespace"), is(StringUtils.repeat('a', 63)));
        assertRejected(() -> K8sSecretNameTemplate.requireValidLabel(StringUtils.repeat('a', 64), "Namespace"), "63");
    }

    private static void assertRejected(final Runnable call, final String expectedFragment) {
        try {
            call.run();
            fail("Expected IllegalArgumentException containing: " + expectedFragment);
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), containsString(expectedFragment));
        }
    }
}
