package org.nrg.containers.model.container;

public class KubernetesJobInfo {
    private final String jobName;
    private final String podName;
    private final String containerId;

    public KubernetesJobInfo(String jobName, String podName, String containerId) {
        this.jobName = jobName;
        this.podName = podName;
        this.containerId = containerId;
    }

    public String jobName() {
        return jobName;
    }

    public String podName() {
        return podName;
    }

    public String containerId() {
        return containerId;
    }

    @Override
    public String toString() {
        return "KubernetesJobInfo{" +
                "jobName='" + jobName + '\'' +
                ", podName='" + podName + '\'' +
                ", containerId='" + containerId + '\'' +
                '}';
    }
}
