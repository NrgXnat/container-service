package org.nrg.containers.model.kubernetes;

import org.apache.commons.lang3.StringUtils;

import java.util.Locale;

/**
 * Turns arbitrary strings into Kubernetes names and checks their length. Object names are RFC 1123 subdomains
 * (lower case, {@code [a-z0-9.-]}, at most 253 characters); namespaces and volume names are RFC 1123 labels
 * (no dots, at most 63 characters). Nothing is ever truncated: an overlong name is an error.
 */
public final class KubernetesNames {
    public static final int MAX_NAME_LENGTH  = 253;
    public static final int MAX_LABEL_LENGTH = 63;

    private KubernetesNames() {}

    /** Make a string usable as a Kubernetes object name (RFC 1123 subdomain subset). Length is not checked here. */
    public static String sanitize(final String raw) {
        final String lowered = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.-]", "-").replaceAll("-{2,}", "-");
        return StringUtils.strip(lowered, "-.");
    }

    /** Make a string usable as a Kubernetes label-style name (RFC 1123 label: like {@link #sanitize} but dots become dashes). */
    public static String sanitizeLabel(final String raw) {
        final String lowered = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-").replaceAll("-{2,}", "-");
        return StringUtils.strip(lowered, "-");
    }

    /** @throws IllegalArgumentException when the sanitised name is empty or longer than 253 characters. */
    public static String requireValidName(final String sanitized, final String what) {
        return requireLength(sanitized, what, MAX_NAME_LENGTH);
    }

    /** @throws IllegalArgumentException when the sanitised label is empty or longer than 63 characters. */
    public static String requireValidLabel(final String sanitized, final String what) {
        return requireLength(sanitized, what, MAX_LABEL_LENGTH);
    }

    private static String requireLength(final String sanitized, final String what, final int max) {
        if (StringUtils.isBlank(sanitized)) {
            throw new IllegalArgumentException(what + " is empty after sanitising");
        }
        if (sanitized.length() > max) {
            throw new IllegalArgumentException(what + " \"" + sanitized + "\" is " + sanitized.length()
                    + " characters long; Kubernetes allows at most " + max);
        }
        return sanitized;
    }
}
