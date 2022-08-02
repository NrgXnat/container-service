package org.nrg.containers.model.kubernetes;

import org.apache.commons.lang.WordUtils;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * See kubernetes docs
 * <a href="https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/#pod-phase">Pod Lifecycle: Pod Phase</a>
 */
public enum KubernetesPodPhase {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    UNKNOWN;

    private static final Map<String, KubernetesPodPhase> ENUM_MAP;
    static {
        Map<String, KubernetesPodPhase> map = Stream.of(KubernetesPodPhase.values())
                .collect(Collectors.toMap(KubernetesPodPhase::toString, Function.identity()));
        ENUM_MAP = Collections.unmodifiableMap(map);
    }

    @Nullable
    public static KubernetesPodPhase fromString(String phase) {
        return ENUM_MAP.get(phase);
    }

    @Override
    public String toString() {
        return WordUtils.capitalizeFully(this.name());
    }

    public static boolean isSuccessful(String phase) {
        final KubernetesPodPhase enumPhase = KubernetesPodPhase.fromString(phase);
        return enumPhase != null && enumPhase.isSuccessful();
    }

    public boolean isSuccessful() {
        return SUCCEEDED == this;
    }

    public boolean isTerminal() {
        return SUCCEEDED == this || FAILED == this;
    }
}
