package org.nrg.containers.secrets;

import io.kubernetes.client.openapi.ApiException;
import org.apache.commons.lang3.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns Kubernetes API and kubelet messages about file secrets into one sentence naming the object and the fix,
 * keeping the original text (design doc section 4.2). Unknown messages pass through unchanged.
 */
public final class SecretErrorTranslator {
    private static final Pattern STATUS_MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private SecretErrorTranslator() {}

    public static String translateCreateJobFailure(final ApiException e, final String namespace) {
        final String raw = apiMessage(e);
        switch (e.getCode()) {
            case 404:
                return "Namespace \"" + namespace + "\" does not exist: onboard the project before launching (Kubernetes: " + raw + ")";
            case 403:
                return "The Container Service identity cannot create jobs in namespace \"" + namespace
                        + "\": it has no RoleBinding there (Kubernetes: " + raw + ")";
            default:
                return "Could not create job in namespace \"" + namespace + "\": " + raw;
        }
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
                    + "\": add it to the xnat-cs Role (Kubernetes: " + raw + ")");
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
