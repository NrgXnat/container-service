package org.nrg.containers.model.kubernetes;

import org.apache.commons.lang3.StringUtils;
import org.junit.Test;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.nrg.containers.model.kubernetes.KubernetesNamesTest.assertRejected;

public class JobNamespaceTemplateTest {

    @Test
    public void renderProducesADnsLabel() {
        assertThat(JobNamespaceTemplate.render("xnat-#PROJECT#", "PROJ01"), is("xnat-proj01"));
        assertThat(JobNamespaceTemplate.render("xnat-#PROJECT#", "My_Proj.1"), is("xnat-my-proj-1"));
        assertRejected(() -> JobNamespaceTemplate.render("xnat-#PROJECT#", StringUtils.repeat('p', 60)), "63");
        assertRejected(() -> JobNamespaceTemplate.render("xnat-#PROJECT#", null), "#PROJECT# has no value");
        assertRejected(() -> JobNamespaceTemplate.render("xnat-#PROJECT#-#session#", "PROJ01"), "only use");
        assertRejected(() -> JobNamespaceTemplate.render("xnat-jobs", "PROJ01"), "#PROJECT#");
        assertRejected(() -> JobNamespaceTemplate.render(null, "PROJ01"), "required");
    }

    /** Save-time and launch-time share one rule set: validate() reports what render() would throw for the template. */
    @Test
    public void validateReportsTheSameTemplateErrorsAsRender() {
        assertThat(JobNamespaceTemplate.validate("xnat-#PROJECT#"), is(nullValue()));
        assertThat(JobNamespaceTemplate.validate(null), containsString("required"));
        assertThat(JobNamespaceTemplate.validate("xnat-jobs"), containsString("#PROJECT#"));
        assertThat(JobNamespaceTemplate.validate("xnat-#PROJECT#-#session#"), containsString("only use"));
        assertThat(JobNamespaceTemplate.validate(StringUtils.repeat('n', 70) + "-#PROJECT#"), containsString("63"));
    }
}
