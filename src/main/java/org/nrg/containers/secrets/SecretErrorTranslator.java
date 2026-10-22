package org.nrg.containers.secrets;

import io.kubernetes.client.openapi.ApiException;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.model.server.docker.SecretVolumeType;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns Kubernetes API and kubelet messages about file secrets into one sentence naming the object and the fix,
 * keeping the original text (design doc section 4.2). Unknown messages pass through unchanged.
 */
public final class SecretErrorTranslator {
    private static final Pattern STATUS_MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"");

    private static final Pattern VOLUME_NAME = Pattern.compile("for volume \"([^\"]+)\"");
    private static final Pattern SECRET_NOT_FOUND = Pattern.compile("secret \"([^\"]+)\" not found");
    private static final Pattern SPC_NOT_FOUND = Pattern.compile("failed to get secretproviderclass ([^/\\s]+)/([^,:\\s]+)");
    private static final Pattern PROVIDER_NOT_FOUND = Pattern.compile("provider not found: provider \"([^\"]+)\"|provider not found: (\\w+)");

    private SecretErrorTranslator() {}

    /** What the translator knows about the container the message is about. All parts optional. */
    public static final class Context {
        private final String namespace;
        private final String serviceAccount;
        private final SecretVolumeType volumeType;
        private final Map<String, FileResolvedSecret> secretsByVolumeName;

        private Context(@Nullable final String namespace, @Nullable final String serviceAccount,
                        @Nullable final SecretVolumeType volumeType, final Map<String, FileResolvedSecret> secretsByVolumeName) {
            this.namespace = namespace;
            this.serviceAccount = serviceAccount;
            this.volumeType = volumeType;
            this.secretsByVolumeName = secretsByVolumeName;
        }

        public static Context of(@Nullable final String namespace, @Nullable final String serviceAccount,
                                 @Nullable final SecretVolumeType volumeType, final Collection<FileResolvedSecret> fileSecrets) {
            final Map<String, FileResolvedSecret> byVolume = new HashMap<>();
            for (final FileResolvedSecret secret : fileSecrets) {
                byVolume.put(secret.volumeName(), secret);
            }
            return new Context(namespace, serviceAccount, volumeType, byVolume);
        }

        public static Context empty() {
            return new Context(null, null, null, Collections.emptyMap());
        }

        public boolean ownsVolume(@Nullable final String volumeName) {
            return volumeName != null && secretsByVolumeName.containsKey(volumeName);
        }

        @Nullable
        FileResolvedSecret secretForVolume(@Nullable final String volumeName) {
            return volumeName == null ? null : secretsByVolumeName.get(volumeName);
        }
    }

    /** The volume named in a kubelet mount message, or null. */
    @Nullable
    public static String volumeNameFrom(@Nullable final String raw) {
        if (raw == null) {
            return null;
        }
        final Matcher matcher = VOLUME_NAME.matcher(raw);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** One sentence with object name and suggestion; the raw text when nothing matches. Never contains a value. */
    public static String translate(final String raw, final Context ctx) {
        // Most specific first: the SecretProviderClass text also ends in "... not found".
        Objects.requireNonNull(raw, "raw message");
        final FileResolvedSecret secret = ctx.secretForVolume(volumeNameFrom(raw));
        final String inNamespace = ctx.namespace == null ? "" : " in namespace \"" + ctx.namespace + "\"";

        final Matcher spcMissing = SPC_NOT_FOUND.matcher(raw);
        if (spcMissing.find()) {
            return "SecretProviderClass \"" + spcMissing.group(2) + "\" does not exist in namespace \"" + spcMissing.group(1)
                    + "\": check the secret name template or create the object";
        }
        final Matcher secretMissing = SECRET_NOT_FOUND.matcher(raw);
        if (secretMissing.find()) {
            return "Secret \"" + secretMissing.group(1) + "\" does not exist" + inNamespace;
        }
        final String objectName = secret != null && secret.secretName() != null ? secret.secretName() : "<unknown>";
        if (raw.contains("ResourceNotFoundException")) {
            return "The external secret store has no secret for SecretProviderClass \"" + objectName + "\": check its objectName against the store";
        }
        if (raw.contains("AccessDeniedException")) {
            final String who = ctx.serviceAccount == null ? "The job's ServiceAccount" : "ServiceAccount \"" + ctx.serviceAccount + "\"";
            return who + " is not allowed to read the secret behind SecretProviderClass \"" + objectName
                    + "\": check its IRSA annotation and the IAM policy";
        }
        final Matcher provider = PROVIDER_NOT_FOUND.matcher(raw);
        if (provider.find()) {
            final String name = provider.group(1) != null ? provider.group(1) : provider.group(2);
            return "The Secrets Store CSI provider \"" + name + "\" is not installed in the cluster";
        }
        if (raw.contains("CSI token error") && raw.contains("tokenRequests")) {
            return "The Secrets Store CSI driver was installed without tokenRequests: reinstall or upgrade it with tokenRequests[0].audience=sts.amazonaws.com";
        }
        return raw;
    }

    public static String translateCreateJobFailure(final ApiException e, final String namespace) {
        final String raw = apiMessage(e);
        if (e.getCode() == 404 && raw.contains("namespaces \"" + namespace + "\" not found")) {
            return "Namespace \"" + namespace + "\" does not exist: onboard the project before launching (Kubernetes: " + raw + ")";
        }
        if (e.getCode() == 403 && (raw.contains("is forbidden: User") || raw.contains("cannot create resource \"jobs\""))) {
            return "The Container Service identity cannot create jobs in namespace \"" + namespace
                    + "\": it has no RoleBinding there (Kubernetes: " + raw + ")";
        }
        return "Could not create job in namespace \"" + namespace + "\": " + raw;
    }

    public static SecretProviderClassCheck translateSecretProviderClassLookup(final ApiException e, final String namespace, final String name) {
        final String raw = apiMessage(e);
        if (e.getCode() == 404 && raw.contains("\"" + name + "\" not found")) {
            return SecretProviderClassCheck.missing("SecretProviderClass \"" + name + "\" does not exist in namespace \"" + namespace
                    + "\": check the secret name template or create the object (Kubernetes: " + raw + ")");
        }
        if (e.getCode() == 404) {
            return SecretProviderClassCheck.undetermined("The Secrets Store CSI driver is not installed in the cluster: the secretproviderclasses resource is unknown (Kubernetes: " + raw + ")");
        }
        if (e.getCode() == 403) {
            return SecretProviderClassCheck.undetermined("The Container Service identity lacks get permission on secretproviderclasses in namespace \"" + namespace
                    + "\": grant get on secretproviderclasses to the Role bound to the Container Service's service account (Kubernetes: " + raw + ")");
        }
        return SecretProviderClassCheck.undetermined("Could not check SecretProviderClass \"" + name + "\" in namespace \"" + namespace + "\": " + raw);
    }

    /** The Status object's message when the body is one, else the exception message. */
    static String apiMessage(final ApiException e) {
        final String body = e.getResponseBody();
        if (body != null) {
            final Matcher matcher = STATUS_MESSAGE.matcher(body);
            if (matcher.find()) {
                return matcher.group(1).replace("\\\"", "\"");
            }
        }
        return StringUtils.defaultIfBlank(e.getMessage(), "HTTP " + e.getCode());
    }
}
