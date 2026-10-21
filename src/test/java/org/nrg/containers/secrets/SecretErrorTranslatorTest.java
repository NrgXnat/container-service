package org.nrg.containers.secrets;

import io.kubernetes.client.openapi.ApiException;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

/** Each row of the translation table in design doc section 4.2, fed with the message the API server / kubelet really sends. */
public class SecretErrorTranslatorTest {

    /**
     * client-java's ApiException(int, String) sets only the message; the Status body must go in the three-argument
     * constructor, otherwise apiMessage() falls back to the message and sees the whole JSON.
     */
    private static ApiException apiException(final int code, final String message) {
        return new ApiException(code, Collections.<String, List<String>>emptyMap(),
                "{\"kind\":\"Status\",\"apiVersion\":\"v1\",\"metadata\":{},\"status\":\"Failure\",\"message\":\""
                        + message.replace("\"", "\\\"") + "\",\"code\":" + code + "}");
    }

    @Test
    public void createJobNotFoundMeansTheNamespaceIsNotOnboarded() {
        final String translated = SecretErrorTranslator.translateCreateJobFailure(
                apiException(404, "namespaces \"xnat-proj02\" not found"), "xnat-proj02");
        assertThat(translated, containsString("Namespace \"xnat-proj02\" does not exist"));
        assertThat(translated, containsString("onboard"));
        assertThat("the raw message is kept", translated, containsString("namespaces \"xnat-proj02\" not found"));
    }

    @Test
    public void createJobForbiddenMeansTheRoleBindingIsMissing() {
        final String translated = SecretErrorTranslator.translateCreateJobFailure(apiException(403,
                "jobs.batch is forbidden: User \"system:serviceaccount:xnat-proj01:xnat-cs\" cannot create resource \"jobs\" in API group \"batch\" in the namespace \"xnat-proj02\""),
                "xnat-proj02");
        assertThat(translated, containsString("cannot create jobs in namespace \"xnat-proj02\""));
        assertThat(translated, containsString("RoleBinding"));
    }

    @Test
    public void otherCreateJobFailuresKeepTheOriginalMessage() {
        final String translated = SecretErrorTranslator.translateCreateJobFailure(apiException(422, "Job.batch \"x\" is invalid"), "ctx");
        assertThat(translated, is("Could not create job in namespace \"ctx\": Job.batch \"x\" is invalid"));
    }
}
