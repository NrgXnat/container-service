package org.nrg.containers.model.kubernetes;

import org.apache.commons.lang3.StringUtils;
import org.junit.Test;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

public class KubernetesNamesTest {

    @Test
    public void sanitizeKeepsDotsButLowercasesAndReplacesOtherCharacters() {
        assertThat(KubernetesNames.sanitize("Xnat_Proj.01--redcap"), is("xnat-proj.01-redcap"));
        assertThat(KubernetesNames.sanitize("--a--"), is("a"));
    }

    @Test
    public void sanitizeLabelAlsoReplacesDots() {
        assertThat(KubernetesNames.sanitizeLabel("xnat-My_Proj.1"), is("xnat-my-proj-1"));
        assertThat(KubernetesNames.sanitizeLabel("..x.."), is("x"));
    }

    @Test
    public void requireValidNameRejectsBlankAndOverlong() {
        assertThat(KubernetesNames.requireValidName("ok", "Secret name"), is("ok"));
        assertRejected(() -> KubernetesNames.requireValidName("", "Secret name"), "Secret name");
        assertRejected(() -> KubernetesNames.requireValidName(StringUtils.repeat('a', 254), "Secret name"), "253");
    }

    @Test
    public void requireValidLabelRejectsOverlong() {
        assertThat(KubernetesNames.requireValidLabel(StringUtils.repeat('a', 63), "Namespace"), is(StringUtils.repeat('a', 63)));
        assertRejected(() -> KubernetesNames.requireValidLabel(StringUtils.repeat('a', 64), "Namespace"), "63");
    }

    static void assertRejected(final Runnable call, final String expectedFragment) {
        try {
            call.run();
            fail("Expected IllegalArgumentException containing: " + expectedFragment);
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), containsString(expectedFragment));
        }
    }
}
