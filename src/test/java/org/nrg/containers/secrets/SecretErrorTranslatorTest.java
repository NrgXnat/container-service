package org.nrg.containers.secrets;

import io.kubernetes.client.openapi.ApiException;
import org.junit.Test;

import java.net.SocketTimeoutException;
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

    @Test
    public void secretProviderClassNotFoundIsMissing() {
        final SecretProviderClassCheck check = SecretErrorTranslator.translateSecretProviderClassLookup(
                apiException(404, "secretproviderclasses.secrets-store.csi.x-k8s.io \"xnat-proj01-redcap\" not found"), "xnat-proj01", "xnat-proj01-redcap");
        assertThat(check.result(), is(SecretProviderClassCheck.Result.MISSING));
        assertThat(check.reason(), containsString("SecretProviderClass \"xnat-proj01-redcap\" does not exist in namespace \"xnat-proj01\""));
    }

    @Test
    public void missingCrdIsUndetermined() {
        final SecretProviderClassCheck check = SecretErrorTranslator.translateSecretProviderClassLookup(
                apiException(404, "the server could not find the requested resource"), "xnat-proj01", "xnat-proj01-redcap");
        assertThat(check.result(), is(SecretProviderClassCheck.Result.UNDETERMINED));
        assertThat(check.reason(), containsString("Secrets Store CSI driver is not installed"));
    }

    @Test
    public void forbiddenLookupIsUndetermined() {
        final SecretProviderClassCheck check = SecretErrorTranslator.translateSecretProviderClassLookup(apiException(403,
                "secretproviderclasses.secrets-store.csi.x-k8s.io \"xnat-proj01-redcap\" is forbidden: User \"system:serviceaccount:xnat-proj01:xnat-cs\" cannot get resource \"secretproviderclasses\" in API group \"secrets-store.csi.x-k8s.io\" in the namespace \"xnat-proj01\""),
                "xnat-proj01", "xnat-proj01-redcap");
        assertThat(check.result(), is(SecretProviderClassCheck.Result.UNDETERMINED));
        assertThat(check.reason(), containsString("get permission on secretproviderclasses in namespace \"xnat-proj01\""));
        assertThat(check.reason(), containsString("grant get on secretproviderclasses to the Role bound to the Container Service's service account"));
    }

    @Test
    public void timeoutIsUndeterminedWithTheRawMessage() {
        final ApiException timeout = new ApiException(new SocketTimeoutException("timeout"));
        final SecretProviderClassCheck check = SecretErrorTranslator.translateSecretProviderClassLookup(timeout, "xnat-proj01", "xnat-proj01-redcap");
        assertThat(check.result(), is(SecretProviderClassCheck.Result.UNDETERMINED));
        assertThat(check.reason(), containsString("Could not check SecretProviderClass \"xnat-proj01-redcap\""));
    }
}
