package org.nrg.containers.events.model;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;

import javax.annotation.Nullable;
import java.util.Date;
import java.util.Map;
import java.util.regex.Pattern;

@AutoValue
public abstract class DockerContainerEvent implements ContainerEvent {
    private static final Pattern ignoreStatusPattern = Pattern.compile("kill|destroy");
    private static final Pattern exitStatusPattern = Pattern.compile("die");

    public abstract String status();
    public abstract String containerId();
    public abstract Date time();
    @Nullable public abstract Long timeNano();
    public abstract ImmutableMap<String, String> attributes();

    public boolean isIgnoreStatus() {
        // These statuses come after "die" and thus we want to ignore them
        final String status = status();
        return status != null && ignoreStatusPattern.matcher(status).matches();
    }

    public boolean isExitStatus() {
        final String status = status();
        return status != null && exitStatusPattern.matcher(status).matches();
    }

    public static boolean isSuccessfulStatus(String status){
        // No way to determine - always exits with "die", have to use exit code
        return true;
    }

    public String exitCode() {
        return isExitStatus() ?
                (attributes().containsKey("exitCode") ?
                        attributes().get("exitCode") :
                        "") :
                null;
    }

    public static DockerContainerEvent create(final String status,
                                              final String containerId,
                                              final Date time,
                                              final Long timeNano,
                                              final Map<String, String> attributes) {
        final ImmutableMap<String, String> attributesCopy = attributes == null ?
                ImmutableMap.<String, String>of() :
                ImmutableMap.copyOf(attributes);
        return new AutoValue_DockerContainerEvent(status, containerId, time, timeNano, attributesCopy);
    }
}
