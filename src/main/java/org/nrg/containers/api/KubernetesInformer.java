package org.nrg.containers.api;

public interface KubernetesInformer {
    /** Start the context namespace group and every group registered so far. */
    void start();
    /** Stop every group. */
    void stop();
    /** Register (and, once started, start) a group for this namespace; a no-op when it already exists. */
    void ensureWatching(String namespace);
    /** The kubeconfig context namespace. */
    String getNamespace();
}
