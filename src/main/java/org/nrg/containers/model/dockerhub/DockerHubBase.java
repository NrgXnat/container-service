package org.nrg.containers.model.dockerhub;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.auto.value.AutoValue;

import javax.annotation.Nullable;
import java.io.Serializable;

public abstract class DockerHubBase implements Serializable {
    private static final long serialVersionUID = -7053921281835388132L;

    @JsonProperty("id") public abstract long id();
    @Nullable @JsonProperty("name") public abstract String name();
    @Nullable @JsonProperty("url") public abstract String url();
    @JsonProperty("default") public abstract Boolean isDefault();
    @Nullable @JsonProperty("username") public abstract String username();
    @Nullable @JsonProperty(value = "password", access = JsonProperty.Access.WRITE_ONLY) public abstract String password();
    @Nullable @JsonProperty("email") public abstract String email();
    @Nullable @JsonProperty(value = "token", access = JsonProperty.Access.WRITE_ONLY) public abstract String token();

    @AutoValue
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public abstract static class DockerHub extends DockerHubBase {
        private static final long serialVersionUID = 6104412147824430916L;

        public static final String DEFAULT_NAME = "Docker Hub";
        public static final String DEFAULT_URL = "https://index.docker.io/v1/";
        public static final DockerHub DEFAULT = DockerHub.create(0L, DEFAULT_NAME, DEFAULT_URL, false,
                null, null, null, null);

        public static DockerHub create(final Long id,
                                       final String name,
                                       final String url,
                                       final Boolean isDefault) {
            return create(id == null ? 0L : id, name, url, isDefault,
                    null, null, null, null);
        }

        @JsonCreator
        public static DockerHub create(@JsonProperty("id") final Long id,
                                       @JsonProperty("name") final String name,
                                       @JsonProperty("url") final String url,
                                       @JsonProperty("default") final Boolean isDefault,
                                       @Nullable @JsonProperty("username") final String username,
                                       @Nullable @JsonProperty("password") final String password,
                                       @Nullable @JsonProperty("email") final String email,
                                       @Nullable @JsonProperty("token") final String token) {
            return new AutoValue_DockerHubBase_DockerHub(id == null ? 0L : id, name, url, isDefault != null && isDefault,
                    username, password, email, token);
        }
    }

    @AutoValue
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public abstract static class DockerHubStatus implements Serializable {
        private static final long serialVersionUID = 4252661488644868308L;

        @JsonProperty("ping") public abstract Boolean ping();
        @JsonProperty("response") public abstract String response();
        @JsonProperty("message") public abstract String message();

        @JsonCreator
        public static DockerHubStatus create(@JsonProperty("ping") final Boolean ping,
                                             @Nullable @JsonProperty("response") final String response,
                                             @Nullable @JsonProperty("message") final String message) {
            return builder()
                    .ping(ping)
                    .response(response == null ? "" : response)
                    .message(message == null ? "" : message)
                    .build();
        }

        public static DockerHubStatus create(@JsonProperty("ping") final Boolean ping) {
            return create(ping, null, null);
        }

        public static Builder builder() {return new AutoValue_DockerHubBase_DockerHubStatus.Builder();}

        public abstract Builder toBuilder();

        @AutoValue.Builder
        public static abstract class Builder {
            public abstract Builder ping(Boolean ping);
            public abstract Builder response(String response);
            public abstract Builder message(String message);
            public abstract DockerHubStatus build();
        }
    }

    @AutoValue
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public abstract static class DockerHubWithPing extends DockerHubBase {
        private static final long serialVersionUID = -4277893938218132409L;

        @Nullable @JsonProperty("status") public abstract DockerHubStatus status();

        @JsonCreator
        public static DockerHubWithPing create(@JsonProperty("id") final Long id,
                                               @JsonProperty("name") final String name,
                                               @JsonProperty("url") final String url,
                                               @JsonProperty("default") final Boolean isDefault,
                                               @Nullable @JsonProperty("username") final String username,
                                               @Nullable @JsonProperty("password") final String password,
                                               @Nullable @JsonProperty("email") final String email,
                                               @Nullable @JsonProperty("token") final String token,
                                               @JsonProperty("status") final DockerHubStatus status) {
            return new AutoValue_DockerHubBase_DockerHubWithPing(id == null ? 0L : id, name, url, isDefault == null ? false : isDefault,
                    username, password, email, token, status);
        }

        public static DockerHubWithPing create(final DockerHub dockerHub,
                                               final DockerHubStatus status) {
            return create(
                    dockerHub.id(),
                    dockerHub.name(),
                    dockerHub.url(),
                    dockerHub.isDefault(),
                    dockerHub.username(),
                    dockerHub.password(),
                    dockerHub.email(),
                    dockerHub.token(),
                    status
            );
        }

        public static DockerHubWithPing create(final DockerHub dockerHub,
                                               final Boolean ping,
                                               final String status,
                                               final String message) {
            return create(
                    dockerHub.id(),
                    dockerHub.name(),
                    dockerHub.url(),
                    dockerHub.isDefault(),
                    dockerHub.username(),
                    dockerHub.password(),
                    dockerHub.email(),
                    dockerHub.token(),
                    DockerHubStatus.create(ping, status, message)
            );
        }
    }
}
