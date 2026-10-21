package org.nrg.containers.model.server.docker;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.apache.commons.lang3.StringUtils;

/** How a file secret is mounted: a Kubernetes Secret object by name, or a Secrets Store CSI volume naming a SecretProviderClass. */
public enum SecretVolumeType {
    SECRET,
    CSI;

    public static final SecretVolumeType DEFAULT = SECRET;

    @JsonValue
    @Override
    public String toString() {
        return name().toLowerCase();
    }

    /** Blank means "use the default" (the settings form submits "" for an unselected option); anything else must match. */
    @JsonCreator
    public static SecretVolumeType fromJson(final String value) {
        if (StringUtils.isBlank(value)) {
            return DEFAULT;
        }
        for (final SecretVolumeType type : values()) {
            if (type.name().equalsIgnoreCase(value.trim())) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown secret volume type \"" + value + "\"; expected secret or csi");
    }
}
