package org.nrg.containers.secrets;

import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * Renders the site-level template that maps a secret's logical name plus launch context
 * to the name of the Kubernetes Secret (or SecretProviderClass) to mount.
 * <p>
 * Supported placeholders: {@code #PROJECT#} (the XNAT project the container runs in) and
 * {@code {identifier}} (the logical name declared in the command). The result is sanitised
 * into a valid Kubernetes object name: lower case, only {@code [a-z0-9.-]}, no leading or
 * trailing dash.
 */
public final class K8sSecretNameTemplate {
    public static final String DEFAULT_TEMPLATE = "xnat-#PROJECT#-{identifier}";

    private static final String PROJECT_PLACEHOLDER    = "#PROJECT#";
    private static final String IDENTIFIER_PLACEHOLDER = "{identifier}";

    private K8sSecretNameTemplate() {}

    public static String render(@Nullable final String template, @Nullable final String project, final String identifier) {
        final String effectiveTemplate = StringUtils.isBlank(template) ? DEFAULT_TEMPLATE : template;
        final String rendered = effectiveTemplate
                .replace(PROJECT_PLACEHOLDER, StringUtils.defaultString(project))
                .replace(IDENTIFIER_PLACEHOLDER, StringUtils.defaultString(identifier));
        return sanitize(rendered);
    }

    /**
     * Make a string usable as a Kubernetes object name (RFC 1123 subdomain subset).
     */
    public static String sanitize(final String raw) {
        final String lowered = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.-]", "-").replaceAll("-{2,}", "-");
        return StringUtils.strip(lowered, "-.");
    }
}
