package org.nrg.containers.secrets;

import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * Renders the template that maps a secret's identifier plus launch context to the name of the
 * Kubernetes Secret (or SecretProviderClass) to mount, and sanitises strings into Kubernetes names.
 * <p>
 * Placeholders: {@code #PROJECT#} (the XNAT project the container runs in) and {@code {identifier}}
 * (the identifier declared in the command). Names are RFC 1123 subdomains (lower case, {@code [a-z0-9.-]},
 * at most 253 characters); namespaces are RFC 1123 labels (no dots, at most 63 characters).
 */
public final class K8sSecretNameTemplate {
    public static final String DEFAULT_TEMPLATE       = "xnat-#PROJECT#-{identifier}";
    public static final String PROJECT_PLACEHOLDER    = "#PROJECT#";
    public static final String IDENTIFIER_PLACEHOLDER = "{identifier}";
    public static final int    MAX_NAME_LENGTH        = 253;
    public static final int    MAX_LABEL_LENGTH       = 63;

    private K8sSecretNameTemplate() {}

    public static String render(@Nullable final String template, @Nullable final String project, final String identifier) {
        final String effectiveTemplate = StringUtils.isBlank(template) ? DEFAULT_TEMPLATE : template;
        final String rendered = effectiveTemplate
                .replace(PROJECT_PLACEHOLDER, StringUtils.defaultString(project))
                .replace(IDENTIFIER_PLACEHOLDER, StringUtils.defaultString(identifier));
        return sanitize(rendered);
    }

    /** Make a string usable as a Kubernetes object name (RFC 1123 subdomain subset). Length is not checked here. */
    public static String sanitize(final String raw) {
        final String lowered = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.-]", "-").replaceAll("-{2,}", "-");
        return StringUtils.strip(lowered, "-.");
    }

    /** Make a string usable as a Kubernetes namespace (RFC 1123 label: like {@link #sanitize} but dots become dashes). */
    public static String sanitizeLabel(final String raw) {
        final String lowered = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-").replaceAll("-{2,}", "-");
        return StringUtils.strip(lowered, "-");
    }

    /** @throws IllegalArgumentException when the sanitised name is empty or longer than 253 characters. Never truncates. */
    public static String requireValidName(final String sanitized, final String what) {
        return requireLength(sanitized, what, MAX_NAME_LENGTH);
    }

    /** @throws IllegalArgumentException when the sanitised label is empty or longer than 63 characters. Never truncates. */
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
