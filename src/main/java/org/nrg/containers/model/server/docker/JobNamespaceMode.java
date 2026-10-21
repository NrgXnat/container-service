package org.nrg.containers.model.server.docker;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.apache.commons.lang3.StringUtils;

/** Where Kubernetes jobs are created: the kubeconfig context namespace (release behaviour) or a namespace rendered per project. */
public enum JobNamespaceMode {
    SINGLE("single"),
    PER_PROJECT("per-project");

    public static final JobNamespaceMode DEFAULT = SINGLE;

    private final String json;

    JobNamespaceMode(final String json) {
        this.json = json;
    }

    @JsonValue
    @Override
    public String toString() {
        return json;
    }

    @JsonCreator
    public static JobNamespaceMode fromJson(final String value) {
        if (StringUtils.isBlank(value)) {
            return DEFAULT;
        }
        for (final JobNamespaceMode mode : values()) {
            if (mode.json.equalsIgnoreCase(value.trim()) || mode.name().equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown job namespace mode \"" + value + "\"; expected single or per-project");
    }
}
