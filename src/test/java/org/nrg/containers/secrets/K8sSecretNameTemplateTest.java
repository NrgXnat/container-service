package org.nrg.containers.secrets;

import org.apache.commons.lang3.StringUtils;
import org.junit.Test;

import java.util.Collections;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

public class K8sSecretNameTemplateTest {

    private static final Map<String, String> NO_INPUTS = Collections.emptyMap();

    @Test
    public void renderUsesTheIdentifierAloneWhenNoTemplateIsConfigured() {
        assertThat(K8sSecretNameTemplate.DEFAULT_TEMPLATE, is("{identifier}"));
        assertThat(K8sSecretNameTemplate.render(null, "Proj_01", "redcap", NO_INPUTS), is("redcap"));
        assertThat("a project-less launch works with the default", K8sSecretNameTemplate.render("", null, "redcap", NO_INPUTS), is("redcap"));
        assertThat("the identifier is sanitised like any rendering", K8sSecretNameTemplate.render(null, "P1", "Red_Cap", NO_INPUTS), is("red-cap"));
        assertThat(K8sSecretNameTemplate.render("xnat-#PROJECT#-{identifier}", "Proj_01", "redcap", NO_INPUTS), is("xnat-proj-01-redcap"));
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
        assertThat(K8sSecretNameTemplate.render("xnat-#PROJECT#-{identifier}", "PROJ01", "redcap", inputs), is("xnat-proj01-redcap"));
    }

    @Test
    public void renderFailsLoudlyOnUnresolvedPlaceholders() {
        assertRejected(() -> K8sSecretNameTemplate.render("xnat-#PROJECT#-{identifier}", null, "redcap", NO_INPUTS), "#PROJECT# has no value");
        assertRejected(() -> K8sSecretNameTemplate.render("x-#nope#-{identifier}", "P1", "redcap", NO_INPUTS), "#nope# has no value");
        assertRejected(() -> K8sSecretNameTemplate.render("x-#PROJECT#", "P1", "redcap", NO_INPUTS), "{identifier}");
        assertRejected(() -> K8sSecretNameTemplate.render("xnat-#PROJECT#-{identifier}", StringUtils.repeat('p', 250), "redcap", NO_INPUTS), "253");
    }

    /** Command-level templates follow the same rule as the site template: {identifier} is mandatory, so two secrets of one command can never render to the same object. */
    @Test
    public void everyTemplateMustContainTheIdentifierPlaceholder() {
        assertThat(K8sSecretNameTemplate.render("{identifier}", null, "redcap", NO_INPUTS), is("redcap"));
        assertThat(K8sSecretNameTemplate.render("redcap-#PROJECT#-{identifier}", "PROJ01", "redcap", NO_INPUTS), is("redcap-proj01-redcap"));
        assertThat(K8sSecretNameTemplate.render("{identifier}-Fixed", null, "redcap", NO_INPUTS), is("redcap-fixed"));
        assertRejected(() -> K8sSecretNameTemplate.render("my-custom-secret", null, "redcap", NO_INPUTS), "{identifier}");
        assertRejected(() -> K8sSecretNameTemplate.render("redcap-#PROJECT#", "PROJ01", "redcap", NO_INPUTS), "{identifier}");
        assertRejected(() -> K8sSecretNameTemplate.render("redcap-#PROJECT#-{identifier}", null, "redcap", NO_INPUTS), "#PROJECT# has no value");
    }

    /** The static part of the rule, shared by Command.validate and DockerServer.validate: no launch context needed. */
    @Test
    public void validateTemplateReportsMissingIdentifierAndMalformedPlaceholders() {
        assertThat(K8sSecretNameTemplate.validateTemplate("{identifier}", "Secret name template"), is(nullValue()));
        assertThat(K8sSecretNameTemplate.validateTemplate("xnat-#PROJECT#-#session#-{identifier}", "Secret name template"), is(nullValue()));
        assertThat(K8sSecretNameTemplate.validateTemplate("xnat-#PROJECT#", "Secret name template"), containsString("must contain {identifier}"));
        assertThat(K8sSecretNameTemplate.validateTemplate("xnat-#PROJECT-{identifier}", "Secret name template"), containsString("malformed placeholder"));
        assertThat(K8sSecretNameTemplate.validateTemplate("{identifier}-{foo}", "Secret name template"), containsString("malformed placeholder"));
        assertThat(K8sSecretNameTemplate.validateTemplate("#a b#-{identifier}", "Secret name template"), containsString("malformed placeholder"));
        assertThat(K8sSecretNameTemplate.validateTemplate("", "Secret name template"), containsString("required"));
        assertThat(K8sSecretNameTemplate.validateTemplate("x", "Secret \"redcap\" name-template"), containsString("Secret \"redcap\" name-template"));
    }

    @Test
    public void renderRejectsMalformedPlaceholders() {
        assertRejected(() -> K8sSecretNameTemplate.render("xnat-#PROJECT-{identifier}", "P1", "redcap", NO_INPUTS), "malformed");
        assertRejected(() -> K8sSecretNameTemplate.render("#a b#-{identifier}", "P1", "redcap", NO_INPUTS), "malformed");
    }

    @Test
    public void renderRejectsMissingIdentifierWithoutNullPointer() {
        assertRejected(() -> K8sSecretNameTemplate.render(null, "P1", null, NO_INPUTS), "required");
        assertRejected(() -> K8sSecretNameTemplate.render("{identifier}", "P1", null, NO_INPUTS), "required");
    }

    @Test
    public void renderTreatsRegexReplacementCharactersInValuesLiterally() {
        final Map<String, String> inputs = Collections.singletonMap("#v#", "a$b\\c");
        assertThat(K8sSecretNameTemplate.render("x-#v#-{identifier}", "P1", "redcap", inputs), is("x-a-b-c-redcap"));
    }

    @Test
    public void namesMustHaveWellFormedDotSegments() {
        assertRejected(() -> K8sSecretNameTemplate.render("a..b-{identifier}", null, "redcap", NO_INPUTS), "not a valid Kubernetes object name");
        assertRejected(() -> K8sSecretNameTemplate.render("a-.b-{identifier}", null, "redcap", NO_INPUTS), "not a valid Kubernetes object name");
        assertRejected(() -> K8sSecretNameTemplate.render("xnat-#PROJECT#-{identifier}", "P.", "redcap", NO_INPUTS), "not a valid Kubernetes object name");
        assertRejected(() -> K8sSecretNameTemplate.render("xnat-#PROJECT#.{identifier}", "P.", "redcap", NO_INPUTS),
                "not a valid Kubernetes object name");
        assertThat(K8sSecretNameTemplate.render("a.b-c-{identifier}", null, "redcap", NO_INPUTS), is("a.b-c-redcap"));
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

    @Test
    public void renderNamespaceProducesADnsLabel() {
        assertThat(K8sSecretNameTemplate.renderNamespace("xnat-#PROJECT#", "PROJ01"), is("xnat-proj01"));
        assertThat(K8sSecretNameTemplate.renderNamespace("xnat-#PROJECT#", "My_Proj.1"), is("xnat-my-proj-1"));
        assertRejected(() -> K8sSecretNameTemplate.renderNamespace("xnat-#PROJECT#", StringUtils.repeat('p', 60)), "63");
        assertRejected(() -> K8sSecretNameTemplate.renderNamespace("xnat-#PROJECT#", null), "#PROJECT# has no value");
        assertRejected(() -> K8sSecretNameTemplate.renderNamespace("xnat-#PROJECT#-#session#", "PROJ01"), "only use");
        assertRejected(() -> K8sSecretNameTemplate.renderNamespace("xnat-jobs", "PROJ01"), "#PROJECT#");
        assertRejected(() -> K8sSecretNameTemplate.renderNamespace(null, "PROJ01"), "required");
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
