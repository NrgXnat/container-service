package org.nrg.containers.secrets;

import io.kubernetes.client.openapi.ApiException;
import org.apache.commons.lang3.StringUtils;
import org.junit.Test;
import org.nrg.containers.model.server.docker.SecretVolumeType;

import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
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
    public void createJobNotFoundFromAReverseProxyIsNotAMissingNamespace() {
        final ApiException proxy404 = new ApiException("Not Found", 404, Collections.<String, List<String>>emptyMap(),
                "<html><body><h1>404 Not Found</h1></body></html>");
        assertThat(SecretErrorTranslator.translateCreateJobFailure(proxy404, "xnat-proj02"),
                is("Could not create job in namespace \"xnat-proj02\": Not Found"));
    }

    @Test
    public void createJobForbiddenByAnAdmissionWebhookIsNotAMissingRoleBinding() {
        final String webhook = "admission webhook \"validate.kyverno.svc-fail\" denied the request: policy require-labels/check-team failed";
        assertThat(SecretErrorTranslator.translateCreateJobFailure(apiException(403, webhook), "xnat-proj02"),
                is("Could not create job in namespace \"xnat-proj02\": " + webhook));
    }

    @Test
    public void otherCreateJobFailuresKeepTheOriginalMessage() {
        final String translated = SecretErrorTranslator.translateCreateJobFailure(apiException(422, "Job.batch \"x\" is invalid"), "ctx");
        assertThat(translated, is("Could not create job in namespace \"ctx\": Job.batch \"x\" is invalid"));
    }

    @Test
    public void longStatusMessageDoesNotOverflowTheStack() {
        final String message = StringUtils.repeat("field invalid; ", 600);
        assertThat(SecretErrorTranslator.translateCreateJobFailure(apiException(422, message), "ctx"),
                containsString(message.substring(0, 40)));
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

    private static SecretErrorTranslator.Context ctx() {
        final FileResolvedSecret redcap = new FileResolvedSecret(new Secret(
                new K8sSecretSource("redcap", "xnat-proj01-redcap"), new FileSecretDestination("/run/secrets/redcap")));
        return SecretErrorTranslator.Context.of("xnat-proj01", "xnat-jobs", SecretVolumeType.CSI, Collections.singletonList(redcap));
    }

    /** Partially reconstructed (rpc error wrapper from EKS fragment): the captured part is "failed to get secretproviderclass xnat-proj01/xnat-proj01-redcap". */
    @Test
    public void secretProviderClassMissingAtMountTime() {
        final String raw = "MountVolume.SetUp failed for volume \"secret-redcap\" : rpc error: code = Unknown desc = failed to get secretproviderclass xnat-proj01/xnat-proj01-redcap, error: secretproviderclasses.secrets-store.csi.x-k8s.io \"xnat-proj01-redcap\" not found";
        assertThat(SecretErrorTranslator.translate(raw, ctx()),
                is("SecretProviderClass \"xnat-proj01-redcap\" does not exist in namespace \"xnat-proj01\": check the secret name template or create the object"));
    }

    @Test
    public void kubernetesSecretMissingAtMountTime() {
        final String raw = "MountVolume.SetUp failed for volume \"secret-redcap\" : secret \"xnat-proj01-redcap\" not found";
        assertThat(SecretErrorTranslator.translate(raw, ctx()), is("Secret \"xnat-proj01-redcap\" does not exist in namespace \"xnat-proj01\""));
    }

    /** Reconstructed from the runbook fragment "FailedMount ... ResourceNotFoundException" in the AWS provider's message format; replace with captured text once scenario 1b has run on EKS. */
    @Test
    public void storeHasNoSuchSecret() {
        final String raw = "MountVolume.SetUp failed for volume \"secret-redcap\" : rpc error: code = Unknown desc = failed to mount secrets store objects for pod xnat-proj01/secret-probe-7fd2a-x7k2p, err: rpc error: code = Unknown desc = Failed fetching secret xnat/proj01/redcap: ResourceNotFoundException: Secrets Manager can't find the specified secret.";
        assertThat(SecretErrorTranslator.translate(raw, ctx()),
                is("The external secret store has no secret for SecretProviderClass \"xnat-proj01-redcap\": check its objectName against the store"));
        assertThat(SecretErrorTranslator.translate(raw, SecretErrorTranslator.Context.empty()),
                is("The external secret store has no secret for SecretProviderClass \"<unknown>\": check its objectName against the store"));
    }

    /** Reconstructed from the runbook fragment "FailedMount ... AccessDeniedException"; replace with captured text once scenario 1b has run on EKS. */
    @Test
    public void storeDeniesAccess() {
        final String raw = "MountVolume.SetUp failed for volume \"secret-redcap\" : rpc error: code = Unknown desc = failed to mount secrets store objects for pod xnat-proj01/secret-probe-7fd2a-x7k2p, err: rpc error: code = Unknown desc = Failed fetching secret xnat/proj01/redcap: AccessDeniedException: User: arn:aws:sts::123456789012:assumed-role/xnat-proj01-jobs/secrets-store-csi-driver-provider-aws is not authorized to perform: secretsmanager:GetSecretValue on resource: xnat/proj01/redcap";
        assertThat(SecretErrorTranslator.translate(raw, ctx()),
                is("ServiceAccount \"xnat-jobs\" is not allowed to read the secret behind SecretProviderClass \"xnat-proj01-redcap\": check its IRSA annotation and the IAM policy"));
        assertThat(SecretErrorTranslator.translate(raw, SecretErrorTranslator.Context.empty()),
                is("The job's ServiceAccount is not allowed to read the secret behind SecretProviderClass \"<unknown>\": check its IRSA annotation and the IAM policy"));
    }

    /** Reconstructed from the runbook fragment "provider not found: aws"; replace with captured text when it is next observed. */
    @Test
    public void providerNotInstalled() {
        final String raw = "MountVolume.SetUp failed for volume \"secret-redcap\" : rpc error: code = Unknown desc = failed to mount secrets store objects for pod xnat-proj01/secret-probe-7fd2a-x7k2p, err: error connecting to provider \"aws\": provider not found: provider \"aws\"";
        assertThat(SecretErrorTranslator.translate(raw, ctx()), is("The Secrets Store CSI provider \"aws\" is not installed in the cluster"));
    }

    @Test
    public void csiTokenRequestsNotConfigured() {
        final String raw = "MountVolume.SetUp failed for volume \"secret-redcap\" : rpc error: code = Unknown desc = failed to mount secrets store objects for pod xnat-proj01/secret-probe-7fd2a-x7k2p, err: CSI token error: serviceAccount.tokens not provided - ensure tokenRequests is configured in CSIDriver";
        assertThat(SecretErrorTranslator.translate(raw, ctx()),
                is("The Secrets Store CSI driver was installed without tokenRequests: reinstall or upgrade it with tokenRequests[0].audience=sts.amazonaws.com"));
    }

    @Test
    public void unknownMessagePassesThrough() {
        final String raw = "MountVolume.SetUp failed for volume \"secret-redcap\" : something new";
        assertThat(SecretErrorTranslator.translate(raw, ctx()), is(raw));
    }

    @Test
    public void emptyContextDoesNotFail() {
        assertThat("no context: still a useful sentence from the raw text",
                SecretErrorTranslator.translate("MountVolume.SetUp failed for volume \"secret-redcap\" : secret \"xnat-proj01-redcap\" not found", SecretErrorTranslator.Context.empty()),
                is("Secret \"xnat-proj01-redcap\" does not exist"));
    }

    @Test
    public void volumeNameFromExtractsTheSecretVolume() {
        assertThat(SecretErrorTranslator.volumeNameFrom("MountVolume.SetUp failed for volume \"secret-redcap\" : something new"), is("secret-redcap"));
        assertThat(SecretErrorTranslator.volumeNameFrom("no volume here"), is(nullValue()));
    }
}
