package org.nrg.containers.model.command.auto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.nrg.containers.model.container.auto.Container;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

public abstract class LaunchReport {
    @JsonProperty("status") public abstract String status();
    @JsonProperty("params") public abstract ImmutableMap<String, String> launchParams();
    @Nullable @JsonProperty("id") public abstract Long databaseId();
    @Nullable @JsonProperty("command-id") public abstract Long commandId();
    @Nullable @JsonProperty("wrapper-id") public abstract Long wrapperId();

    @AutoValue
    public static abstract class Success extends LaunchReport {
        protected final static String STATUS = "success";
        @Nonnull @JsonProperty("workflow-id") public abstract String workflowId();

        @JsonCreator
        @SuppressWarnings("unused")
        static Success create(@JsonProperty("workflow-id") final @Nonnull String workflowId,
                              @JsonProperty("status") final String ignoredStatus,
                              @JsonProperty("params") final Map<String, String> launchParams,
                              @JsonProperty("id") final Long databaseId,
                              @JsonProperty("command-id") final Long commandId,
                              @JsonProperty("wrapper-id") final Long wrapperId) {
            return create(workflowId, launchParams, databaseId, commandId, wrapperId);
        }

        public static Success create(final @Nonnull String workflowId,
                                     final Map<String, String> launchParams,
                                     final Long databaseId,
                                     final Long commandId,
                                     final Long wrapperId) {
            final ImmutableMap<String, String> launchParamsCopy =
                    launchParams == null ?
                            ImmutableMap.<String, String>of() :
                            ImmutableMap.copyOf(launchParams);
            return new AutoValue_LaunchReport_Success(STATUS, launchParamsCopy, databaseId, commandId, wrapperId, workflowId);
        }
    }

    @AutoValue
    public static abstract class Failure extends LaunchReport {
        private final static String STATUS = "failure";
        @JsonProperty("message") public abstract String message();

        @JsonCreator
        @SuppressWarnings("unused")
        static Failure create(@JsonProperty("message") final @Nonnull String message,
                              @JsonProperty("status") final String ignoredStatus,
                              @JsonProperty("params") final Map<String, String> launchParams,
                              @JsonProperty("id") final Long databaseId,
                              @JsonProperty("command-id") final Long commandId,
                              @JsonProperty("wrapper-id") final Long wrapperId) {
            return create(message, launchParams, databaseId, commandId, wrapperId);
        }

        public static Failure create(final @Nonnull String message,
                                     final Map<String, String> launchParams,
                                     final long commandId,
                                     final long wrapperId) {
            final Long commandIdCopy = commandId == 0L ? null : commandId;
            final Long wrapperIdCopy = wrapperId == 0L ? null : wrapperId;
            return create(message, launchParams, 0L, commandIdCopy, wrapperIdCopy);
        }

        public static Failure create(final @Nonnull String message,
                                     final Map<String, String> launchParams,
                                     final Long databaseId,
                                     final Long commandId,
                                     final Long wrapperId) {
            final ImmutableMap<String, String> launchParamsCopy =
                    launchParams == null ?
                            ImmutableMap.<String, String>of() :
                            ImmutableMap.copyOf(launchParams);
            return new AutoValue_LaunchReport_Failure(STATUS, launchParamsCopy, databaseId, commandId, wrapperId, message);
        }
    }

    @AutoValue
    public abstract static class BulkLaunchReport {
        @JsonProperty("bulk-launch-id") public abstract String bulkLaunchId();
        @JsonProperty("pipeline-name") public abstract String pipelineName();
        @JsonProperty("successes") public abstract ImmutableList<Success> successes();
        @JsonProperty("failures") public abstract ImmutableList<Failure> failures();

        public static Builder builder() {
            return new AutoValue_LaunchReport_BulkLaunchReport.Builder();
        }

        @JsonCreator
        public static BulkLaunchReport create(@JsonProperty("bulk-launch-id") final String id,
                                              @JsonProperty("pipeline-name") final String pipelineName,
                                              @JsonProperty("successes") final List<Success> successes,
                                              @JsonProperty("failures") final List<Failure> failures) {
            return builder()
                    .bulkLaunchId(id)
                    .pipelineName(pipelineName)
                    .successes(successes)
                    .failures(failures)
                    .build();
        }

        @AutoValue.Builder
        public static abstract class Builder {
            public abstract Builder bulkLaunchId(String id);
            public abstract Builder pipelineName(String pipelineName);
            public abstract Builder successes(List<Success> successes);
            abstract ImmutableList.Builder<Success> successesBuilder();
            public Builder addSuccess(final @Nonnull Success success) {
                successesBuilder().add(success);
                return this;
            }
            public abstract Builder failures(List<Failure> failures);
            abstract ImmutableList.Builder<Failure> failuresBuilder();
            public Builder addFailure(final @Nonnull Failure failure) {
                failuresBuilder().add(failure);
                return this;
            }

            public Builder addReport(final @Nonnull LaunchReport report) {
                if (Success.class.isAssignableFrom(report.getClass())) {
                    return addSuccess((Success)report);
                } else {
                    return addFailure((Failure)report);
                }
            }

            public abstract BulkLaunchReport build();
        }
    }
}
