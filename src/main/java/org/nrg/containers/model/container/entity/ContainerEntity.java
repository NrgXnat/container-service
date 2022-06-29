package org.nrg.containers.model.container.entity;

import com.google.common.base.MoreObjects;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.services.impl.ContainerServiceImpl;
import org.nrg.containers.utils.ContainerUtils;
import org.nrg.framework.orm.hibernate.AbstractHibernateEntity;
import org.nrg.xft.event.persist.PersistentWorkflowUtils;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.Transient;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Entity
@Slf4j
public class ContainerEntity extends AbstractHibernateEntity {
    public static final String KILL_STATUS = "kill";

    private long commandId;
    private long wrapperId;
    private String status;
    private Date statusTime;
    private String dockerImage;
    private String containerName;
    private String commandLine;
    private Boolean overrideEntrypoint;
    private String workingDirectory;
    private Map<String, String> environmentVariables = new HashMap<>();
    private Map<String, String> ports = new HashMap<>();
    private List<ContainerEntityMount> mounts = new ArrayList<>();
    private String containerId;
    private String workflowId;
    private String userId;
    private Boolean swarm;
    private String serviceId;
    private String taskId;
    private String nodeId;
    private String subtype;
    private ContainerEntity parentContainerEntity;
    private String parentSourceObjectName;
    private List<ContainerEntityInput> inputs;
    private List<ContainerEntityOutput> outputs;
    private List<ContainerEntityHistory> history = new ArrayList<>();
    private List<String> logPaths;
    private Long reserveMemory;
    private Long limitMemory;
    private Double limitCpu;
    private List<String> swarmConstraints;
    private String project;
    private String runtime;
    private String ipcMode;
    private Boolean autoRemove;
    private Long shmSize;
    private String network;
    private Map<String, String> containerLabels = new HashMap<>();
    private String gpus;
    private Map<String, String> genericResources = new HashMap<>();
    private Map<String, String> ulimits;


    public ContainerEntity() {}

    public static ContainerEntity fromPojo(final Container containerPojo) {
        if (containerPojo == null) {
            return null; // This is for the setup container parent, which may be null
        }
        final ContainerEntity containerEntity = new ContainerEntity();
        containerEntity.update(containerPojo);
        return containerEntity;
    }

    public ContainerEntity update(final Container containerPojo) {
        this.setId(containerPojo.databaseId());
        this.setStatus(containerPojo.status());
        this.setStatusTime(containerPojo.statusTime());
        this.setCommandId(containerPojo.commandId());
        this.setWrapperId(containerPojo.wrapperId());
        this.setContainerId(containerPojo.containerId());
        this.setWorkflowId(containerPojo.workflowId());
        this.setUserId(containerPojo.userId());
        this.setProject(containerPojo.project());
        this.setServiceId(containerPojo.serviceId());
        this.setTaskId(containerPojo.taskId());
        this.setNodeId(containerPojo.nodeId());
        this.setSwarm(containerPojo.swarm());
        this.setDockerImage(containerPojo.dockerImage());
        this.setContainerName(containerPojo.containerName());
        this.setCommandLine(containerPojo.commandLine());
        this.setOverrideEntrypoint(containerPojo.overrideEntrypoint());
        this.setWorkingDirectory(containerPojo.workingDirectory());
        this.setSubtype(containerPojo.subtype());
        this.setParentContainerEntity(fromPojo(containerPojo.parent()));
        this.setParentSourceObjectName(containerPojo.parentSourceObjectName());
        this.setEnvironmentVariables(containerPojo.environmentVariables());
        this.setPorts(containerPojo.ports());
        this.setLogPaths(containerPojo.logPaths());
        this.setMounts(containerPojo.mounts().stream().map(ContainerEntityMount::fromPojo).collect(Collectors.toList()));
        this.setInputs(containerPojo.inputs().stream().map(ContainerEntityInput::fromPojo).collect(Collectors.toList()));
        this.setOutputs(containerPojo.outputs().stream().map(ContainerEntityOutput::fromPojo).collect(Collectors.toList()));
        this.setHistory(containerPojo.history().stream().map(ContainerEntityHistory::fromPojo).collect(Collectors.toList()));
        this.setReserveMemory(containerPojo.reserveMemory());
        this.setLimitMemory(containerPojo.limitMemory());
        this.setLimitCpu(containerPojo.limitCpu());
        this.setSwarmConstraints(containerPojo.swarmConstraints());
        this.setRuntime(containerPojo.runtime());
        this.setIpcMode(containerPojo.ipcMode());
        this.setAutoRemove(containerPojo.autoRemove());
        this.setShmSize(containerPojo.shmSize());
        this.setNetwork(containerPojo.network());
        this.setContainerLabels(containerPojo.containerLabels());
        this.setGpus(containerPojo.gpus());
        this.setGenericResources(containerPojo.genericResources());
        this.setUlimits(containerPojo.ulimits());

        return this;
    }

    public long getCommandId() {
        return commandId;
    }

    public void setCommandId(final long commandId) {
        this.commandId = commandId;
    }

    public long getWrapperId() {
        return wrapperId;
    }

    public void setWrapperId(final long wrapperId) {
        this.wrapperId = wrapperId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(final String status) {
        this.status = mapStatus(status);
    }

    @Transient
    public String mapStatus(String inStatus) {
        if (inStatus == null) {
            return null;
        }
        switch (inStatus) {
            case "complete":
                return ContainerServiceImpl.WAITING;
            case "created":
                return StringUtils.capitalize(inStatus);
            case "create":
            case "start":
            case "started":
            case "starting":
            case "running":
                return PersistentWorkflowUtils.RUNNING;
            case "remove":
            case "orphaned":
            case "oom":
            case "rejected":
            case "shutdown":
                return prefixForService(PersistentWorkflowUtils.FAILED + " (" +
                        StringUtils.capitalize(inStatus) + ")");
            case KILL_STATUS:
                return prefixForService(PersistentWorkflowUtils.FAILED + " (Killed)");
            case "failed":
                return PersistentWorkflowUtils.FAILED;
            default:
                return inStatus;
        }
    }

    private String prefixForService(String inStatus) {
        return swarm ? ContainerServiceImpl.WAITING + " (" +inStatus+ ")" : inStatus;
    }

    /**
     * @deprecated Pass {@link ContainerEntity#getStatus()} to {@link ContainerUtils#statusIsTerminal(String)}
     */
    @Transient
    @Deprecated
    public boolean statusIsTerminal() {
        return ContainerUtils.statusIsTerminal(this.status);
    }

    public Date getStatusTime() {
        return statusTime;
    }

    public void setStatusTime(final Date statusTime) {
        this.statusTime = statusTime == null ? null : new Date(statusTime.getTime());
    }

    public String getDockerImage() {
        return dockerImage;
    }

    public void setDockerImage(final String dockerImage) {
        this.dockerImage = dockerImage;
    }

    public String getContainerName() { return containerName; }

    public void setContainerName(String containerName) { this.containerName = containerName; }

    @Column(columnDefinition = "TEXT")
    public String getCommandLine() {
        return commandLine;
    }

    public void setCommandLine(final String commandLine) {
        this.commandLine = commandLine;
    }

    public Boolean getOverrideEntrypoint() {
        return overrideEntrypoint;
    }

    public void setOverrideEntrypoint(final Boolean overrideEntrypoint) {
        this.overrideEntrypoint = overrideEntrypoint;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public void setWorkingDirectory(final String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    @ElementCollection
    public Map<String, String> getEnvironmentVariables() {
        return environmentVariables;
    }

    public void setEnvironmentVariables(final Map<String, String> environmentVariables) {
        this.environmentVariables = environmentVariables == null ?
                new HashMap<>() :
                environmentVariables;
    }

    @ElementCollection
    public Map<String, String> getPorts() {
        return ports;
    }

    public void setPorts(final Map<String, String> ports) {
        this.ports = ports == null ?
                new HashMap<>() :
                ports;
    }

    public String getSubtype() {
        return subtype;
    }

    public void setSubtype(final String subtype) {
        this.subtype = subtype;
    }

    @OneToMany(mappedBy = "containerEntity", cascade = CascadeType.ALL, orphanRemoval = true)
    public List<ContainerEntityMount> getMounts() {
        return mounts;
    }

    public void setMounts(final List<ContainerEntityMount> mounts) {
        this.mounts = mounts == null ?
                new ArrayList<>() :
                mounts;
        for (final ContainerEntityMount mount : this.mounts) {
            mount.setContainerEntity(this);
        }
    }

    public String getContainerId() {
        return containerId;
    }

    public void setContainerId(final String containerId) {
        this.containerId = containerId;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(final String workflowId) {
        this.workflowId = workflowId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(final String user) {
        this.userId = user;
    }

    public Boolean getSwarm() {
        return swarm;
    }

    public void setSwarm(final Boolean swarm) {
        this.swarm = swarm != null && swarm;
    }

    public String getServiceId() {
        return serviceId;
    }

    public void setServiceId(final String serviceId) {
        this.serviceId = serviceId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(final String taskId) {
        this.taskId = taskId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(final String nodeId) {
        this.nodeId = nodeId;
    }

    public Long getReserveMemory() {
        return reserveMemory;
    }

    public void setReserveMemory(final Long reserveMemory) {
        this.reserveMemory = reserveMemory;
    }

    public Long getLimitMemory() {
        return limitMemory;
    }

    public void setLimitMemory(final Long limitMemory) {
        this.limitMemory = limitMemory;
    }

    public Double getLimitCpu() {
        return limitCpu;
    }

    public void setLimitCpu(final Double limitCpu) {
        this.limitCpu = limitCpu;
    }

    public String getRuntime() { return runtime; }

    public void setRuntime(final String runtime) { this.runtime = runtime; }

    public String getIpcMode() { return ipcMode; }

    public void setIpcMode(String ipcMode) { this.ipcMode = ipcMode; }

    public Boolean getAutoRemove() { return autoRemove; }

    public void setAutoRemove(Boolean autoRemove) { this.autoRemove = autoRemove; }

    public Long getShmSize() { return shmSize; }

    public void setShmSize(Long shmSize) { this.shmSize = shmSize; }

    public String getNetwork() { return network; }

    public void setNetwork(String network) { this.network = network; }

    @ElementCollection
    public Map<String, String> getContainerLabels() { return containerLabels; }

    public void setContainerLabels(Map<String, String> containerLabels) {
        this.containerLabels = containerLabels == null ? new HashMap<>() : containerLabels;
    }

    public String getGpus() { return gpus; }

    public void setGpus(String gpus) { this.gpus = gpus; }

    @ElementCollection
    public Map<String, String> getGenericResources() { return genericResources; }

    public void setGenericResources(Map<String, String> genericResources) {
        this.genericResources = genericResources  == null ? new HashMap<>() : genericResources;
    }

    @ElementCollection
    public Map<String, String> getUlimits() { return ulimits; }

    public void setUlimits(Map<String, String> ulimits) {
        this.ulimits = ulimits == null ?
        new HashMap<>() : ulimits;
    }


    @ManyToOne
    public ContainerEntity getParentContainerEntity() {
        return parentContainerEntity;
    }

    public void setParentContainerEntity(final ContainerEntity parentContainerEntity) {
        this.parentContainerEntity = parentContainerEntity;
    }

    public String getParentSourceObjectName() {
        return parentSourceObjectName;
    }

    public void setParentSourceObjectName(final String parentSourceObjectName) {
        this.parentSourceObjectName = parentSourceObjectName;
    }

    @OneToMany(mappedBy = "containerEntity", cascade = CascadeType.ALL, orphanRemoval = true)
    public List<ContainerEntityInput> getInputs() {
        return inputs;
    }

    public void setInputs(final List<ContainerEntityInput> inputs) {
        this.inputs = inputs == null ?
                new ArrayList<>() :
                inputs;
        for (final ContainerEntityInput input : this.inputs) {
            input.setContainerEntity(this);
        }
    }

    public void addInput(final ContainerEntityInput input) {
        if (input == null) {
            return;
        }
        input.setContainerEntity(this);

        if (this.inputs == null) {
            this.inputs = new ArrayList<>();
        }
        this.inputs.add(input);
    }

    @OneToMany(mappedBy = "containerEntity", cascade = CascadeType.ALL, orphanRemoval = true)
    public List<ContainerEntityOutput> getOutputs() {
        return outputs;
    }

    public void setOutputs(final List<ContainerEntityOutput> outputs) {
        this.outputs = outputs == null ?
                new ArrayList<>() :
                outputs;
        for (final ContainerEntityOutput output : this.outputs) {
            output.setContainerEntity(this);
        }
    }

    @OneToMany(mappedBy = "containerEntity", cascade = CascadeType.ALL, orphanRemoval = true)
    public List<ContainerEntityHistory> getHistory() {
        return history;
    }

    public void setHistory(final List<ContainerEntityHistory> history) {
        this.history = history == null ?
                new ArrayList<>() :
                history;
        for (final ContainerEntityHistory historyItem : this.history) {
            historyItem.setContainerEntity(this);
        }
    }

    @Transient
    public void addToHistory(final ContainerEntityHistory historyItem) {
        if (historyItem == null) {
            return;
        }
        historyItem.setContainerEntity(this);
        if (this.history == null) {
            this.history = new ArrayList<>();
        }
        this.history.add(historyItem);
    }

    /**
     * Does this item have a different status (and externalTimestamp) than any previously recorded history item?
     * @param historyItem the candidate history item
     * @return T/F
     */
    @Transient
    public synchronized boolean isItemInHistory(final ContainerEntityHistory historyItem) {
    	if (this.history == null){
    		return false;
    	}
    	historyItem.setContainerEntity(this);
    	return this.history.contains(historyItem);
    }

    @ElementCollection
    public List<String> getLogPaths() {
        return logPaths;
    }

    public void setLogPaths(final List<String> logPaths) {
        this.logPaths = logPaths;
    }

    public String getProject() {
        return project;
    }

    public void setProject(final String project) {
        this.project = project;
    }


    @ElementCollection
    public List<String> getSwarmConstraints() {
        return swarmConstraints;
    }

    public void setSwarmConstraints(List<String> swarmConstraints) {
        this.swarmConstraints = swarmConstraints;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final ContainerEntity that = (ContainerEntity) o;
        return Objects.equals(this.containerId, that.containerId) &&
                Objects.equals(this.serviceId, that.serviceId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(containerId, serviceId);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("swarm", swarm)
                .add("containerId", containerId)
                .add("serviceId", serviceId)
                .add("taskId", taskId)
                .add("nodeId", nodeId)
                .add("userId", userId)
                .add("subtype", subtype)
                .add("project", project)
                .add("parentContainerEntityId", parentContainerEntity == null ? null : parentContainerEntity.getId())
                .add("parentContainerEntityContainerId", parentContainerEntity == null ? null : parentContainerEntity.getContainerId())
                .add("parentSourceObjectName", parentSourceObjectName)
                .add("workflowId", workflowId)
                .add("commandId", commandId)
                .add("wrapperId", wrapperId)
                .add("status", status)
                .add("statusTime", statusTime)
                .add("dockerImage", dockerImage)
                .add("containerName", containerName)
                .add("commandLine", commandLine)
                .add("overrideEntrypoint", overrideEntrypoint)
                .add("workingDirectory", workingDirectory)
                .add("environmentVariables", environmentVariables)
                .add("ports", ports)
                .add("mounts", mounts)
                .add("inputs", inputs)
                .add("outputs", outputs)
                .add("history", history)
                .add("logPaths", logPaths)
                .add("reserveMemory", reserveMemory)
                .add("limitMemory", limitMemory)
                .add("limitCpu", limitCpu)
                .add("swarmConstraints", swarmConstraints)
                .add("runtime", runtime)
                .add("ipcMode", ipcMode)
                .add("autoRemove", autoRemove)
                .add("shmSize", shmSize)
                .add("network", network)
                .add("containerLabels", containerLabels)
                .add("gpus", gpus)
                .add("genericResources", genericResources)
                .add("ulimits", ulimits)
                .toString();
    }
}
