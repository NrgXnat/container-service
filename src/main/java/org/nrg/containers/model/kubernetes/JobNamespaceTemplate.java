package org.nrg.containers.model.kubernetes;

import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nullable;

/**
 * The per-project job namespace template, e.g. {@code xnat-#PROJECT#}. Only {@code #PROJECT#} is substituted:
 * namespaces are a project-level boundary, so input placeholders are rejected. The same rules apply when the
 * backend is saved ({@link #validate}) and when a launch renders the namespace ({@link #render}).
 */
public final class JobNamespaceTemplate {
    public static final String PROJECT_PLACEHOLDER = "#PROJECT#";

    private JobNamespaceTemplate() {}

    /**
     * The rules that need no launch context: present, contains {@code #PROJECT#}, no other placeholder, and short
     * enough to render into a namespace with a one-character project (a longer project only makes it longer).
     *
     * @return the error message, or null when the template is acceptable
     */
    @Nullable
    public static String validate(@Nullable final String template) {
        if (StringUtils.isBlank(template)) {
            return "A job namespace template is required in per-project mode";
        }
        if (!template.contains(PROJECT_PLACEHOLDER)) {
            return "Job namespace template \"" + template + "\" must contain " + PROJECT_PLACEHOLDER;
        }
        if (template.replace(PROJECT_PLACEHOLDER, "").contains("#")) {
            return "Job namespace template \"" + template + "\" may only use the " + PROJECT_PLACEHOLDER
                    + " placeholder; input placeholders are not substituted in namespaces";
        }
        try {
            KubernetesNames.requireValidLabel(KubernetesNames.sanitizeLabel(template.replace(PROJECT_PLACEHOLDER, "x")),
                    "Job namespace template (rendered with a one-character project)");
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        return null;
    }

    /**
     * Render the template for a launch into a sanitised, length-checked namespace name.
     *
     * @throws IllegalArgumentException when the template is invalid, the launch has no project, or the result is
     *                                  not a valid namespace name
     */
    public static String render(@Nullable final String template, @Nullable final String project) {
        final String templateError = validate(template);
        if (templateError != null) {
            throw new IllegalArgumentException(templateError);
        }
        if (StringUtils.isBlank(project)) {
            throw new IllegalArgumentException("Placeholder " + PROJECT_PLACEHOLDER
                    + " has no value in this launch: per-project namespaces need a project");
        }
        return KubernetesNames.requireValidLabel(KubernetesNames.sanitizeLabel(template.replace(PROJECT_PLACEHOLDER, project)),
                "Job namespace");
    }
}
