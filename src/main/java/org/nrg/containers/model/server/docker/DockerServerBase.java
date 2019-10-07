package org.nrg.containers.model.server.docker;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.auto.value.AutoValue;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Date;
import java.util.List;
import java.util.Objects;

public abstract class DockerServerBase {
    @JsonProperty("id")
    public abstract long id();

    @JsonProperty("name")
    public abstract String name();

    @JsonProperty("host")
    public abstract String host();

    @Nullable
    @JsonProperty("cert-path")
    public abstract String certPath();

    @JsonProperty("swarm-mode")
    public abstract boolean swarmMode();

    @JsonIgnore
    public abstract Date lastEventCheckTime();

    @Nullable
    @JsonProperty("path-translation-xnat-prefix")
    public abstract String pathTranslationXnatPrefix();

    @Nullable
    @JsonProperty("path-translation-docker-prefix")
    public abstract String pathTranslationDockerPrefix();

    @JsonProperty("pull-images-on-xnat-init")
    public abstract Boolean pullImagesOnXnatInit();

    @Nullable
    @JsonProperty("container-user")
    public abstract String containerUser();

    @Nullable
    @JsonProperty("swarm-constraints")
    public abstract ImmutableList<DockerServerSwarmConstraint> swarmConstraints();

    @AutoValue
    public abstract static class DockerServer extends DockerServerBase {
        public static final DockerServer DEFAULT_SOCKET = DockerServer.create("Local socket", "unix:///var/run/docker.sock");

        @JsonCreator
        public static DockerServer create(@JsonProperty("id") final Long id,
                                          @JsonProperty("name") final String name,
                                          @JsonProperty("host") final String host,
                                          @JsonProperty("cert-path") final String certPath,
                                          @JsonProperty("swarm-mode") final Boolean swarmMode,
                                          @JsonProperty("path-translation-xnat-prefix") final String pathTranslationXnatPrefix,
                                          @JsonProperty("path-translation-docker-prefix") final String pathTranslationDockerPrefix,
                                          @JsonProperty("pull-images-on-xnat-init") final Boolean pullImagesOnXnatInit,
                                          @JsonProperty("container-user") final String containerUser,
                                          @Nullable @JsonProperty("swarm-constraints") final List<DockerServerSwarmConstraint> swarmConstraints) {
            return create(id, name, host, certPath, swarmMode, null, pathTranslationXnatPrefix,
                    pathTranslationDockerPrefix, pullImagesOnXnatInit, containerUser, swarmConstraints);
        }

        public static DockerServer create(final String name,
                                          final String host) {
            return create(0L, name, host, null, false, null, null, null, null, null);
        }

        public static DockerServer create(final Long id,
                                          final String name,
                                          final String host,
                                          final String certPath,
                                          final Boolean swarmMode,
                                          final Date lastEventCheckTime,
                                          final String pathTranslationXnatPrefix,
                                          final String pathTranslationDockerPrefix,
                                          final Boolean pullImagesOnXnatInit,
                                          final String containerUser,
                                          final List<DockerServerSwarmConstraint> swarmConstraints) {
            return builder()
                    .id(id == null ? 0L : id)
                    .name(StringUtils.isBlank(name) ? host : name)
                    .host(host)
                    .certPath(certPath)
                    .swarmMode(swarmMode != null && swarmMode)
                    .lastEventCheckTime(lastEventCheckTime != null ? lastEventCheckTime : new Date())
                    .pathTranslationXnatPrefix(pathTranslationXnatPrefix)
                    .pathTranslationDockerPrefix(pathTranslationDockerPrefix)
                    .pullImagesOnXnatInit(pullImagesOnXnatInit != null && pullImagesOnXnatInit)
                    .containerUser(containerUser)
                    .swarmConstraints(swarmConstraints)
                    .build();
        }

        public static DockerServer create(final DockerServerEntity dockerServerEntity) {
            final Boolean pullImagesOnXnatInit = dockerServerEntity.getPullImagesOnXnatInit();
            List<DockerServerSwarmConstraint> swarmConstraints = dockerServerEntity.getSwarmConstraints() == null ?
                    null :
                    Lists.transform(dockerServerEntity.getSwarmConstraints(), new Function<DockerServerEntitySwarmConstraint, DockerServerSwarmConstraint>() {
                        @Override
                        public DockerServerSwarmConstraint apply(final DockerServerEntitySwarmConstraint input) {
                            return DockerServerSwarmConstraint.create(input);
                        }
                    });
            return create(
                    dockerServerEntity.getId(),
                    dockerServerEntity.getName(),
                    dockerServerEntity.getHost(),
                    dockerServerEntity.getCertPath(),
                    dockerServerEntity.getSwarmMode(),
                    dockerServerEntity.getLastEventCheckTime(),
                    dockerServerEntity.getPathTranslationXnatPrefix(),
                    dockerServerEntity.getPathTranslationDockerPrefix(),
                    pullImagesOnXnatInit == null ? false : pullImagesOnXnatInit,
                    dockerServerEntity.getContainerUser(),
                    swarmConstraints);
        }

        @SuppressWarnings("deprecation")
        public static DockerServer create(final DockerServerPrefsBean dockerServerPrefsBean) {
            return create(
                    0L,
                    dockerServerPrefsBean.getName(),
                    dockerServerPrefsBean.getHost(),
                    dockerServerPrefsBean.getCertPath(),
                    false,
                    dockerServerPrefsBean.getLastEventCheckTime(),
                    null,
                    null,
                    false,
                    dockerServerPrefsBean.getContainerUser(),
                    null);
        }

        public DockerServer updateEventCheckTime(final Date newLastEventCheckTime) {

            return newLastEventCheckTime == null ? this :
                    create(
                            this.id(),
                            this.name(),
                            this.host(),
                            this.certPath(),
                            this.swarmMode(),
                            newLastEventCheckTime,
                            this.pathTranslationXnatPrefix(),
                            this.pathTranslationDockerPrefix(),
                            this.pullImagesOnXnatInit(),
                            this.containerUser(),
                            this.swarmConstraints());
        }

        public static Builder builder() {
            return new AutoValue_DockerServerBase_DockerServer.Builder();
        }

        public abstract Builder toBuilder();

        @AutoValue.Builder
        public static abstract class Builder {
            public abstract Builder id(long id);
            public abstract Builder name(String name);
            public abstract Builder host(String host);
            public abstract Builder certPath(String certPath);
            public abstract Builder swarmMode(boolean swarmMode);
            public abstract Builder lastEventCheckTime(Date lastEventCheckTime);
            public abstract Builder pathTranslationXnatPrefix(String pathTranslationXnatPrefix);
            public abstract Builder pathTranslationDockerPrefix(String pathTranslationDockerPrefix);
            public abstract Builder pullImagesOnXnatInit(Boolean pullImagesOnXnatInit);
            public abstract Builder containerUser(String containerUser);
            public abstract Builder swarmConstraints(List<DockerServerSwarmConstraint> swarmConstraints);

            public abstract DockerServer build();
        }
    }

    @AutoValue
    public static abstract class DockerServerWithPing extends DockerServerBase {
        @Nullable
        @JsonProperty("ping")
        public abstract Boolean ping();

        @JsonCreator
        public static DockerServerWithPing create(@JsonProperty("id") final Long id,
                                                  @JsonProperty("name") final String name,
                                                  @JsonProperty("host") final String host,
                                                  @JsonProperty("cert-path") final String certPath,
                                                  @JsonProperty("swarm-mode") final Boolean swarmMode,
                                                  @JsonProperty("path-translation-xnat-prefix") final String pathTranslationXnatPrefix,
                                                  @JsonProperty("path-translation-docker-prefix") final String pathTranslationDockerPrefix,
                                                  @JsonProperty("pull-images-on-xnat-init") final Boolean pullImagesOnXnatInit,
                                                  @JsonProperty("container-user") final String user,
                                                  @Nullable @JsonProperty("swarm-constraints") final List<DockerServerSwarmConstraint> swarmConstraints,
                                                  @JsonProperty("ping") final Boolean ping) {
            return create(id == null ? 0L : id, name, host, certPath, swarmMode, new Date(0),
                    pathTranslationXnatPrefix, pathTranslationDockerPrefix, pullImagesOnXnatInit, user, swarmConstraints, ping);
        }

        public static DockerServerWithPing create(final Long id,
                                                  final String name,
                                                  final String host,
                                                  final String certPath,
                                                  final Boolean swarmMode,
                                                  final Date lastEventCheckTime,
                                                  final String pathTranslationXnatPrefix,
                                                  final String pathTranslationDockerPrefix,
                                                  final Boolean pullImagesOnXnatInit,
                                                  final String user,
                                                  final List<DockerServerSwarmConstraint> swarmConstraints,
                                                  final Boolean ping) {
            return builder()
                    .id(id == null ? 0L : id)
                    .name(StringUtils.isBlank(name) ? host : name)
                    .host(host)
                    .certPath(certPath)
                    .swarmMode(swarmMode != null && swarmMode)
                    .lastEventCheckTime(lastEventCheckTime != null ? lastEventCheckTime : new Date())
                    .pathTranslationXnatPrefix(pathTranslationXnatPrefix)
                    .pathTranslationDockerPrefix(pathTranslationDockerPrefix)
                    .pullImagesOnXnatInit(pullImagesOnXnatInit != null && pullImagesOnXnatInit)
                    .containerUser(user)
                    .swarmConstraints(swarmConstraints)
                    .ping(ping != null && ping)
                    .build();
        }

        public static DockerServerWithPing create(final DockerServer dockerServer,
                                                  final Boolean ping) {
            return create(
                    dockerServer.id(),
                    dockerServer.name(),
                    dockerServer.host(),
                    dockerServer.certPath(),
                    dockerServer.swarmMode(),
                    dockerServer.lastEventCheckTime(),
                    dockerServer.pathTranslationXnatPrefix(),
                    dockerServer.pathTranslationDockerPrefix(),
                    dockerServer.pullImagesOnXnatInit(),
                    dockerServer.containerUser(),
                    dockerServer.swarmConstraints(),
                    ping
            );
        }

        public static Builder builder() {
            return new AutoValue_DockerServerBase_DockerServerWithPing.Builder();
        }

        public abstract Builder toBuilder();

        @AutoValue.Builder
        public static abstract class Builder {
            public abstract Builder id(long id);
            public abstract Builder name(String name);
            public abstract Builder host(String host);
            public abstract Builder certPath(String certPath);
            public abstract Builder swarmMode(boolean swarmMode);
            public abstract Builder lastEventCheckTime(Date lastEventCheckTime);
            public abstract Builder pathTranslationXnatPrefix(String pathTranslationXnatPrefix);
            public abstract Builder pathTranslationDockerPrefix(String pathTranslationDockerPrefix);
            public abstract Builder pullImagesOnXnatInit(Boolean pullImagesOnXnatInit);
            public abstract Builder containerUser(String containerUser);
            public abstract Builder swarmConstraints(List<DockerServerSwarmConstraint> swarmConstraints);
            public abstract Builder ping(Boolean ping);

            public abstract DockerServerWithPing build();
        }
    }

    @AutoValue
    public static abstract class DockerServerSwarmConstraint {
        @JsonProperty("id") public abstract long id();
        @JsonProperty("user-settable") public abstract boolean userSettable();
        @JsonProperty("attribute") public abstract String attribute();
        @JsonProperty("comparator") public abstract String comparator();
        @JsonProperty("values") public abstract ImmutableList<String> values();

        @JsonCreator
        public static DockerServerSwarmConstraint create(@JsonProperty("id") final long id,
                                                         @JsonProperty("user-settable") final boolean userSettable,
                                                         @JsonProperty("attribute") final String attribute,
                                                         @JsonProperty("comparator") final String comparator,
                                                         @JsonProperty("values") final List<String> values) {
            return builder()
                    .id(id)
                    .userSettable(userSettable)
                    .attribute(attribute)
                    .comparator(comparator)
                    .values(values)
                    .build();
        }

        public static DockerServerSwarmConstraint create(DockerServerEntitySwarmConstraint entity) {
            if (entity == null) return null;
            return builder()
                    .id(entity.getId())
                    .userSettable(entity.getUserSettable())
                    .attribute(entity.getAttribute())
                    .comparator(entity.getComparator())
                    .values(entity.getValues())
                    .build();
        }

        @JsonIgnore
        @Nullable
        public String asStringConstraint() {
            return asStringConstraint(values().get(0));
        }

        @JsonIgnore
        @Nullable
        public String asStringConstraint(@Nonnull String selectedValue) {
            if (!values().contains(selectedValue)) {
                return null;
            }
            return attribute() + comparator() + selectedValue;
        }

        public static Builder builder() {
                return new AutoValue_DockerServerBase_DockerServerSwarmConstraint.Builder();
        }

        public abstract Builder toBuilder();

        @AutoValue.Builder
        public static abstract class Builder {
            public abstract Builder id(long id);
            public abstract Builder userSettable(boolean userSettable);
            public abstract Builder attribute(String attribute);
            public abstract Builder comparator(String comparator);
            public abstract Builder values(List<String> values);

            public abstract DockerServerSwarmConstraint build();
        }
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final DockerServerBase that = (DockerServerBase) o;
        return swarmMode() == that.swarmMode() &&
                Objects.equals(this.name(), that.name()) &&
                Objects.equals(this.host(), that.host()) &&
                Objects.equals(this.certPath(), that.certPath()) &&
                Objects.equals(this.pathTranslationXnatPrefix(), that.pathTranslationXnatPrefix()) &&
                Objects.equals(this.pathTranslationDockerPrefix(), that.pathTranslationDockerPrefix()) &&
                Objects.equals(this.pullImagesOnXnatInit(), that.pullImagesOnXnatInit()) &&
                Objects.equals(this.containerUser(), that.containerUser()) &&
                Objects.equals(this.swarmConstraints(), that.swarmConstraints());
    }

    @Override
    public int hashCode() {
        return Objects.hash(name(), host(), certPath(), swarmMode(),
                pathTranslationXnatPrefix(), pathTranslationDockerPrefix(), pullImagesOnXnatInit(), containerUser(), swarmConstraints());
    }

}