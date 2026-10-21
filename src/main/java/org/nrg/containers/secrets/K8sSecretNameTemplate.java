package org.nrg.containers.secrets;

import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders the template that maps a secret's identifier plus launch context to the name of the
 * Kubernetes Secret (or SecretProviderClass) to mount, and sanitises strings into Kubernetes names.
 * <p>
 * Placeholders: {@code #PROJECT#} (the XNAT project the container runs in), {@code {identifier}}
 * (the identifier declared in the command) and {@code #<input>#} (a wrapper/command input replacement key). Names are RFC 1123 subdomains (lower case, {@code [a-z0-9.-]},
 * at most 253 characters); namespaces are RFC 1123 labels (no dots, at most 63 characters).
 */
public final class K8sSecretNameTemplate {
    public static final String DEFAULT_TEMPLATE       = "xnat-#PROJECT#-{identifier}";
    public static final String PROJECT_PLACEHOLDER    = "#PROJECT#";
    public static final String IDENTIFIER_PLACEHOLDER = "{identifier}";
    public static final int    MAX_NAME_LENGTH        = 253;
    public static final int    MAX_LABEL_LENGTH       = 63;

    /** Any {@code #...#} token left after the project substitution must be a wrapper input replacement key. */
    private static final Pattern INPUT_PLACEHOLDER = Pattern.compile("#[^#\\s]+#");
    private static final Pattern RFC1123_SUBDOMAIN = Pattern.compile("[a-z0-9]([-a-z0-9.]*[a-z0-9])?");

    private K8sSecretNameTemplate() {}

    /**
     * Render the site template (or the default) into a sanitised, length-checked Kubernetes name.
     *
     * @param resolvedInputValuesByReplacementKey wrapper/command input values keyed by their replacement key, e.g. {@code #session#}
     * @throws IllegalArgumentException when the template lacks {@code {identifier}}, a placeholder has no value,
     *                                  or the result is empty or longer than 253 characters
     */
    public static String render(@Nullable final String template, @Nullable final String project, final String identifier,
                                final Map<String, String> resolvedInputValuesByReplacementKey) {
        final String effectiveTemplate = StringUtils.isBlank(template) ? DEFAULT_TEMPLATE : template;
        if (!effectiveTemplate.contains(IDENTIFIER_PLACEHOLDER)) {
            throw new IllegalArgumentException("Secret name template \"" + effectiveTemplate + "\" must contain " + IDENTIFIER_PLACEHOLDER);
        }
        return renderExplicitName(effectiveTemplate, project, identifier, resolvedInputValuesByReplacementKey);
    }

    /**
     * Render a command-level name: literal when it has no placeholders, otherwise the same placeholders as the template.
     * A rendered name is sanitised and length-checked like a template rendering. A literal name (no placeholders)
     * is only validated, never sanitised: rewriting "a--b" to "a-b" would silently point at a different object.
     */
    public static String renderExplicitName(final String name, @Nullable final String project, final String identifier,
                                            final Map<String, String> resolvedInputValuesByReplacementKey) {
        if (!hasPlaceholders(name)) {
            return requireValidName(requireRfc1123Subdomain(name), "Secret name");
        }
        final String substituted = substitute(name.replace(IDENTIFIER_PLACEHOLDER, identifier), project, resolvedInputValuesByReplacementKey);
        return requireValidName(sanitize(substituted), "Secret name");
    }

    static boolean hasPlaceholders(final String name) {
        return name.contains(PROJECT_PLACEHOLDER) || name.contains(IDENTIFIER_PLACEHOLDER) || INPUT_PLACEHOLDER.matcher(name).find();
    }

    private static String requireRfc1123Subdomain(final String name) {
        if (!RFC1123_SUBDOMAIN.matcher(name).matches()) {
            throw new IllegalArgumentException("Secret name \"" + name + "\" is not a valid Kubernetes object name (lower case letters, digits, '-' and '.')");
        }
        return name;
    }

    /** Project first (so the launch project wins over an input that happens to be called PROJECT), then input keys verbatim. */
    static String substitute(final String text, @Nullable final String project, final Map<String, String> inputs) {
        String out = text;
        if (out.contains(PROJECT_PLACEHOLDER)) {
            if (StringUtils.isBlank(project)) {
                throw new IllegalArgumentException("Placeholder " + PROJECT_PLACEHOLDER
                        + " has no value in this launch: it was started without a project");
            }
            out = out.replace(PROJECT_PLACEHOLDER, project);
        }
        final Matcher matcher = INPUT_PLACEHOLDER.matcher(out);
        final StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            final String value = inputs == null ? null : inputs.get(matcher.group());
            if (value == null) {
                throw new IllegalArgumentException("Placeholder " + matcher.group() + " has no value in this launch");
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
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
