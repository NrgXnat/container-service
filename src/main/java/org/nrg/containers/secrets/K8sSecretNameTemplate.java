package org.nrg.containers.secrets;

import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.model.kubernetes.JobNamespaceTemplate;
import org.nrg.containers.model.kubernetes.KubernetesNames;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders the template that maps a secret's identifier plus launch context to the name of the
 * Kubernetes Secret (or SecretProviderClass) to mount.
 * <p>
 * Placeholders: {@code #PROJECT#} (the XNAT project the container runs in), {@code {identifier}}
 * (the identifier declared in the command; mandatory in every template) and {@code #<input>#}
 * (a wrapper/command input replacement key). The command's {@code name-template}, the site's
 * {@code secret-name-template} and the built-in default all follow the same rule.
 * Rendered names are RFC 1123 subdomains; see {@link KubernetesNames}.
 */
public final class K8sSecretNameTemplate {
    /** With no site template the identifier alone is the object name, so a fresh install needs no backend configuration. */
    public static final String DEFAULT_TEMPLATE       = "{identifier}";
    public static final String PROJECT_PLACEHOLDER    = JobNamespaceTemplate.PROJECT_PLACEHOLDER;
    public static final String IDENTIFIER_PLACEHOLDER = "{identifier}";

    /** A {@code #...#} token is {@code #PROJECT#} or a wrapper input replacement key. */
    private static final Pattern INPUT_PLACEHOLDER = Pattern.compile("#[^#\\s]+#");
    /** Exact Kubernetes rule: dot-separated RFC 1123 labels, so "a..b" and "a-.b" are rejected. */
    private static final Pattern RFC1123_SUBDOMAIN =
            Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*");

    private K8sSecretNameTemplate() {}

    /**
     * Render a secret name template (the command's, the site's, or the built-in default when blank) into a sanitised,
     * length-checked Kubernetes name. Every template must contain {@code {identifier}}, so two secrets of one
     * command can never render to the same object.
     *
     * @param resolvedInputValuesByReplacementKey wrapper/command input values keyed by their replacement key,
     *                                            e.g. {@code #session#}
     * @throws IllegalArgumentException when the template lacks {@code {identifier}}, a placeholder is malformed or
     *                                  has no value, or the result is not a valid Kubernetes object name
     */
    public static String render(@Nullable final String template, @Nullable final String project, final String identifier,
                                final Map<String, String> resolvedInputValuesByReplacementKey) {
        final String effectiveTemplate = StringUtils.isBlank(template) ? DEFAULT_TEMPLATE : template;
        final String templateError = validateTemplate(effectiveTemplate, "Secret name template");
        if (templateError != null) {
            throw new IllegalArgumentException(templateError);
        }
        if (StringUtils.isBlank(identifier)) {
            throw new IllegalArgumentException("Secret identifier is required");
        }
        final String substituted = substitute(effectiveTemplate.replace(IDENTIFIER_PLACEHOLDER, identifier), project,
                resolvedInputValuesByReplacementKey);
        return KubernetesNames.requireValidName(requireRfc1123Subdomain(KubernetesNames.sanitize(substituted)), "Secret name");
    }

    /**
     * The static part of the template rule, shared by {@code Command.validate()} and {@code DockerServer.validate()}:
     * present, contains {@code {identifier}}, and every {@code #}, <code>{</code> or <code>}</code> belongs to a
     * well-formed placeholder. Needs no launch context.
     *
     * @param what how to name the template in the message, e.g. {@code Secret name template}
     * @return the error message, or null when the template is acceptable
     */
    @Nullable
    public static String validateTemplate(@Nullable final String template, final String what) {
        if (StringUtils.isBlank(template)) {
            return what + " is required";
        }
        if (!template.contains(IDENTIFIER_PLACEHOLDER)) {
            return what + " \"" + template + "\" must contain " + IDENTIFIER_PLACEHOLDER;
        }
        final String withoutPlaceholders = INPUT_PLACEHOLDER.matcher(template.replace(IDENTIFIER_PLACEHOLDER, "")).replaceAll("");
        if (StringUtils.containsAny(withoutPlaceholders, '#', '{', '}')) {
            return what + " \"" + template + "\" has a malformed placeholder; use " + PROJECT_PLACEHOLDER
                    + ", #<input>#, " + IDENTIFIER_PLACEHOLDER;
        }
        return null;
    }


    private static String requireRfc1123Subdomain(final String name) {
        if (!RFC1123_SUBDOMAIN.matcher(name).matches()) {
            throw new IllegalArgumentException("Secret name \"" + name + "\" is not a valid Kubernetes object name"
                    + " (RFC 1123 subdomain: dot-separated labels of lower case letters, digits and '-',"
                    + " each starting and ending with a letter or digit)");
        }
        return name;
    }

    /**
     * Project first (so the launch project wins over an input that happens to be called PROJECT),
     * then input keys verbatim.
     */
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
            final String value = inputs.get(matcher.group());
            if (value == null) {
                throw new IllegalArgumentException("Placeholder " + matcher.group() + " has no value in this launch");
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

}
