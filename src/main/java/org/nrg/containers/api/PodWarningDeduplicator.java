package org.nrg.containers.api;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The last normalised warning message per pod and reason, shared by the pod and event handlers of one namespace group.
 * The kubelet's event recorder creates a new Event object whenever the message changes (for example a fresh AWS
 * request id), so repeats cannot be recognised by comparing an Event with its previous version alone.
 */
class PodWarningDeduplicator {
    private final Map<String, String> lastMessageByPodAndReason = new ConcurrentHashMap<>();

    /** Records the message and reports whether it equals the last one recorded for this pod and reason. */
    boolean isRepeat(final String namespace, final String podName, final String reason, final String normalizedMessage) {
        final String message = normalizedMessage == null ? "" : normalizedMessage;
        return message.equals(lastMessageByPodAndReason.put(podKey(namespace, podName) + reason, message));
    }

    void forgetPod(final String namespace, final String podName) {
        final String prefix = podKey(namespace, podName);
        lastMessageByPodAndReason.keySet().removeIf(key -> key.startsWith(prefix));
    }

    void clear() {
        lastMessageByPodAndReason.clear();
    }

    private static String podKey(final String namespace, final String podName) {
        return namespace + "/" + podName + "|";
    }
}
