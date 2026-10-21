package org.nrg.containers.secrets;

import org.apache.commons.lang3.StringUtils;
import org.junit.Test;

import java.util.Collections;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

public class K8sSecretNameTemplateTest {

    private static final Map<String, String> NO_INPUTS = Collections.emptyMap();

    @Test
    public void renderUsesDefaultTemplateAndSanitisesProject() {
        assertThat(K8sSecretNameTemplate.render(null, "Proj_01", "redcap", NO_INPUTS), is("xnat-proj-01-redcap"));
        assertThat(K8sSecretNameTemplate.render("site-{identifier}-#PROJECT#", "PROJ01", "redcap", NO_INPUTS), is("site-redcap-proj01"));
    }

    @Test
    public void renderSubstitutesWrapperInputReplacementKeys() {
        final Map<String, String> inputs = Collections.singletonMap("#session#", "SESS_01");
        assertThat(K8sSecretNameTemplate.render("xnat-#PROJECT#-#session#-{identifier}", "P1", "redcap", inputs), is("xnat-p1-sess-01-redcap"));
    }

    @Test
    public void renderPrefersTheLaunchProjectOverAnInputNamedProject() {
        final Map<String, String> inputs = Collections.singletonMap("#PROJECT#", "from-input");
        assertThat(K8sSecretNameTemplate.render(null, "PROJ01", "redcap", inputs), is("xnat-proj01-redcap"));
    }

    @Test
    public void renderFailsLoudlyOnUnresolvedPlaceholders() {
        assertRejected(() -> K8sSecretNameTemplate.render(null, null, "redcap", NO_INPUTS), "#PROJECT# has no value");
        assertRejected(() -> K8sSecretNameTemplate.render("x-#nope#-{identifier}", "P1", "redcap", NO_INPUTS), "#nope# has no value");
        assertRejected(() -> K8sSecretNameTemplate.render("x-#PROJECT#", "P1", "redcap", NO_INPUTS), "{identifier}");
        assertRejected(() -> K8sSecretNameTemplate.render(null, StringUtils.repeat('p', 250), "redcap", NO_INPUTS), "253");
    }

    @Test
    public void renderExplicitNameIsLiteralUnlessItHasPlaceholders() {
        assertThat(K8sSecretNameTemplate.renderExplicitName("my-custom-secret", null, "redcap", NO_INPUTS), is("my-custom-secret"));
        assertThat(K8sSecretNameTemplate.renderExplicitName("redcap-#PROJECT#", "PROJ01", "redcap", NO_INPUTS), is("redcap-proj01"));
        assertThat(K8sSecretNameTemplate.renderExplicitName("{identifier}-Fixed", null, "redcap", NO_INPUTS), is("redcap-fixed"));
        assertThat("a literal name is never rewritten", K8sSecretNameTemplate.renderExplicitName("a--b.c", null, "redcap", NO_INPUTS), is("a--b.c"));
        assertRejected(() -> K8sSecretNameTemplate.renderExplicitName("My_Secret", null, "redcap", NO_INPUTS), "not a valid Kubernetes object name");
        assertRejected(() -> K8sSecretNameTemplate.renderExplicitName("redcap-#PROJECT#", null, "redcap", NO_INPUTS), "#PROJECT# has no value");
    }

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
