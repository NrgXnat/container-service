package org.nrg.containers.model.container.auto;

import com.google.auto.value.AutoValue;
import com.spotify.docker.client.messages.swarm.ContainerStatus;
import com.spotify.docker.client.messages.swarm.Task;
import com.spotify.docker.client.messages.swarm.TaskStatus;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Date;
import java.util.regex.Pattern;

@AutoValue
public abstract class ServiceTask {
    private static final Pattern successStatusPattern = Pattern.compile(TaskStatus.TASK_STATE_COMPLETE);
    private static final Pattern exitStatusPattern = Pattern.compile(
            StringUtils.join(
                    Arrays.asList(TaskStatus.TASK_STATE_FAILED, TaskStatus.TASK_STATE_COMPLETE,
                            TaskStatus.TASK_STATE_REJECTED, TaskStatus.TASK_STATE_SHUTDOWN), '|'));
    private static final Pattern hasNotStartedPattern = Pattern.compile(
            StringUtils.join(
                    Arrays.asList(TaskStatus.TASK_STATE_NEW, TaskStatus.TASK_STATE_ALLOCATED, TaskStatus.TASK_STATE_PENDING,
                            TaskStatus.TASK_STATE_ASSIGNED, TaskStatus.TASK_STATE_ACCEPTED, TaskStatus.TASK_STATE_PREPARING,
                            TaskStatus.TASK_STATE_READY, TaskStatus.TASK_STATE_STARTING), '|'));

    public static String swarmNodeErrMsg = "Swarm node error";

    public abstract String serviceId();
    @Nullable public abstract String taskId();
    @Nullable public abstract String nodeId();
    public abstract Boolean swarmNodeError();
    public abstract String status();
    @Nullable public abstract Date statusTime();
    @Nullable public abstract String containerId();
    @Nullable public abstract String message();
    @Nullable public abstract String err();
    @Nullable public abstract Long exitCode();

    public static ServiceTask create(final @Nonnull Task task, final String serviceId) {
        final ContainerStatus containerStatus = task.status().containerStatus();
        Long exitCode = containerStatus == null ? null : containerStatus.exitCode();
        // swarmNodeError occurs when node is terminated / spot instance lost while service still trying to run on it
        // Criteria:    current state = [not an exit status] AND either desired state = shutdown OR exit code = -1
        //              OR current state = shutdown
        String curState = task.status().state();
        String msg = task.status().message();
        String err = task.status().err();
        if (curState.equals(TaskStatus.TASK_STATE_PENDING)) {
            msg = "";
            err = "";
        }
        boolean swarmNodeError = (!isExitStatus(curState) &&
                (task.desiredState().equals(TaskStatus.TASK_STATE_SHUTDOWN) || (exitCode != null && exitCode < 0))) ||
                curState.equals(TaskStatus.TASK_STATE_SHUTDOWN);

        if (swarmNodeError) {
            msg = swarmNodeErrMsg;
        }
        return ServiceTask.builder()
                .serviceId(serviceId)
                .taskId(task.id())
                .nodeId(task.nodeId())
                .status(task.status().state())
                .swarmNodeError(swarmNodeError)
                .statusTime(task.status().timestamp())
                .message(msg)
                .err(err)
                .exitCode(exitCode)
                .containerId(containerStatus == null ? null : containerStatus.containerId())
                .build();
    }

    public static ServiceTask createFromHistoryAndService(final @Nonnull Container.ContainerHistory history,
                                                          final @Nonnull Container service) {
        String exitCode = history.exitCode();
        String externalTime = history.externalTimestamp();
        String message = history.message();
        return ServiceTask.builder()
                .serviceId(service.serviceId())
                .taskId(service.taskId())
                .status(history.status())
                .swarmNodeError(message != null && message.contains(swarmNodeErrMsg)) // Hack
                .exitCode(exitCode == null ? null : Long.parseLong(exitCode))
                .statusTime(externalTime == null ? null : new Date(Long.parseLong(externalTime)))
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

    public boolean hasNotStarted() {
        final String status = status();
        return status == null || hasNotStartedPattern.matcher(status).matches();
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
        public abstract Builder statusTime(final Date statusTime);
        public abstract Builder message(final String message);
        public abstract Builder err(final String err);
        public abstract Builder exitCode(final Long exitCode);

        public abstract ServiceTask build();
    }
}
