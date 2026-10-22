package org.nrg.containers.api;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The last normalized warning message per pod and reason, shared by the pod and event handlers of one namespace group.
 * The kubelet's event recorder creates a new Event object whenever the message changes (for example a fresh AWS
 * request id), so repeats cannot be recognized by comparing an Event with its previous version alone.
 * Dedupe state lives as long as the pod (cleared on Running, on delete and on group stop).
 */
class PodWarningDeduplicator {
    private final Map<String, String> lastMessageByPodAndReason = new ConcurrentHashMap<>();

    /** Records the message and reports whether it equals the last one recorded for this pod and reason. */
    boolean isRepeat(final String namespace, final String podName, final String reason, final String normalizedMessage) {
        final String message = normalizedMessage == null ? "" : normalizedMessage;
        return message.equals(lastMessageByPodAndReason.put(key(namespace, podName, reason), message));
    }

    /** Forgets the message recorded for this pod and reason, so the next one is sent even if identical (e.g. after a failed send). */
    void forget(final String namespace, final String podName, final String reason) {
        lastMessageByPodAndReason.remove(key(namespace, podName, reason));
    }

    /** Forgets every reason recorded for this pod only; pods whose name merely starts with podName are kept. */
    void forgetPod(final String namespace, final String podName) {
        final String prefix = podKey(namespace, podName);
        lastMessageByPodAndReason.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /** Forgets every pod; called when the namespace group stops. */
    void clear() {
        lastMessageByPodAndReason.clear();
    }

    private static String key(final String namespace, final String podName, final String reason) {
        return podKey(namespace, podName) + reason;
    }

    private static String podKey(final String namespace, final String podName) {
        return namespace + "/" + podName + "|";
    }
}
