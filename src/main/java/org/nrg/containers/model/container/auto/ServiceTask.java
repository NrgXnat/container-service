package org.nrg.containers.model.container.auto;

import com.github.dockerjava.api.model.Task;
import com.github.dockerjava.api.model.TaskState;
import com.github.dockerjava.api.model.TaskStatus;
import com.github.dockerjava.api.model.TaskStatusContainerStatus;
import com.google.auto.value.AutoValue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@AutoValue
public abstract class ServiceTask implements Serializable {
    private static final long serialVersionUID = 6109868523030890519L;

    private static final Pattern successStatusPattern = Pattern.compile(TaskState.COMPLETE.getValue());
    private static final Pattern exitStatusPattern = Pattern.compile(
            Stream.of(TaskState.FAILED, TaskState.COMPLETE,
                    TaskState.REJECTED, TaskState.SHUTDOWN)
                    .map(TaskState::getValue)
                    .collect(Collectors.joining("|")));

    public static String swarmNodeErrMsg = "Swarm node error";

    public abstract String serviceId();
    @Nullable public abstract String taskId();
    @Nullable public abstract String nodeId();
    public abstract Boolean swarmNodeError();
    public abstract String status();
    @Nullable public abstract String statusTime();
    @Nullable public abstract String containerId();
    @Nullable public abstract String message();
    @Nullable public abstract String err();
    @Nullable public abstract Long exitCode();

    public static ServiceTask create(final @NotNull Task task, final String serviceId) {
        final TaskStatus taskStatus = task.getStatus();
        final TaskStatusContainerStatus containerStatus = taskStatus.getContainerStatus();
        Long exitCode = containerStatus == null ? null : containerStatus.getExitCodeLong();
        // swarmNodeError occurs when node is terminated / spot instance lost while service still trying to run on it
        // Criteria:    current state = [not an exit status] AND either desired state = shutdown OR exit code = -1
        //              OR current state = shutdown
        TaskState curState = taskStatus.getState();
        String msg = taskStatus.getMessage();
        String err = taskStatus.getErr();
        if (curState.equals(TaskState.PENDING)) {
            msg = "";
            err = "";
        }
        boolean swarmNodeError = (!isExitStatus(curState.getValue()) &&
                (task.getDesiredState().equals(TaskState.SHUTDOWN) || (exitCode != null && exitCode < 0))) ||
                curState.equals(TaskState.SHUTDOWN);

        if (swarmNodeError) {
            msg = swarmNodeErrMsg;
        }
        return ServiceTask.builder()
                .serviceId(serviceId)
                .taskId(task.getId())
                .nodeId(task.getNodeId())
                .status(curState.getValue())
                .swarmNodeError(swarmNodeError)
                .statusTime(taskStatus.getTimestamp())
                .message(msg)
                .err(err)
                .exitCode(exitCode)
                .containerId(containerStatus == null ? null : containerStatus.getContainerID())
                .build();
    }

    public static ServiceTask createFromHistoryAndService(final @Nonnull Container.ContainerHistory history,
                                                          final @Nonnull Container service) {
        String exitCode = history.exitCode();
        String message = history.message();
        return ServiceTask.builder()
                .serviceId(service.serviceId())
                .taskId(service.taskId())
                .status(history.status())
                .swarmNodeError(message != null && message.contains(swarmNodeErrMsg)) // Hack
                .exitCode(exitCode == null ? null : Long.parseLong(exitCode))
                .statusTime(history.externalTimestamp())
                .build();
    }

    public boolean isExitStatus() {
        final String status = status();
        return isExitStatus(status);
    }

    public static boolean isExitStatus(String status){
        return status != null && exitStatusPattern.matcher(status).matches();
    }
    
    public static boolean isSuccessfulStatus(String status){
        return status != null && successStatusPattern.matcher(status).matches();
    }
    
    public boolean isSuccessfulStatus(){
        final String status = status();
        return isSuccessfulStatus(status);
    }

    public static Builder builder() {
        return new AutoValue_ServiceTask.Builder();
    }

    public abstract Builder toBuilder();

    @AutoValue.Builder
    public abstract static class Builder {
        public abstract Builder serviceId(final String serviceId);
        public abstract Builder taskId(final String taskId);
        public abstract Builder containerId(final String containerId);
        public abstract Builder nodeId(final String nodeId);
        public abstract Builder status(final String status);
        public abstract Builder swarmNodeError(final Boolean swarmNodeError);
        public abstract Builder statusTime(final String statusTime);
        public abstract Builder message(final String message);
        public abstract Builder err(final String err);
        public abstract Builder exitCode(final Long exitCode);

        public abstract ServiceTask build();
    }
}
