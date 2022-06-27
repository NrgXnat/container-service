package org.nrg.containers.events.model;

import org.nrg.containers.model.container.KubernetesJobInfo;

import java.util.Collections;
import java.util.Map;

public class KubernetesStatusChangeEvent implements ContainerEvent {
    private final KubernetesJobInfo kubernetesJobInfo;
    private final String status;
    private final String details;
    private final Integer exitCode;

    public KubernetesStatusChangeEvent(KubernetesJobInfo kubernetesJobInfo, String status, String details, Integer exitCode) {
        this.kubernetesJobInfo = kubernetesJobInfo;
        this.status = status;
        this.details = details;
        this.exitCode = exitCode;
    }

    @Override
    public String containerId() {
        return kubernetesJobInfo.jobName();
    }

    public KubernetesJobInfo kubernetesJobInfo() {
        return kubernetesJobInfo;
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public String externalTimestamp() {
        // We don't get a status change time from kubernetes
        return null;
    }

    @Override
    public Map<String, String> attributes() {
        // We don't get any attributes from kubernetes
        return Collections.emptyMap();
    }

    @Override
    public boolean isExitStatus() {
        return exitCode != null;
    }

    @Override
    public String exitCode() {
        return exitCode == null ? null : String.valueOf(exitCode);
    }

    @Override
    public String details() {
        return details;
    }

    @Override
    public String toString() {
        return "KubernetesStatusChangeEvent{" +
                "kubernetesJobInfo=" + kubernetesJobInfo +
                ", status=\"" + status + "\"" +
                ", details=\"" + details + "\"" +
                ", exitCode=\"" + exitCode + "\"" +
                "}";
    }
}
