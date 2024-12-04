package org.nrg.containers.model.server.docker;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.exceptions.InvalidDefinitionException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

public abstract class DockerServerBase implements Serializable {
    private static final long serialVersionUID = 4458269433790705942L;

    @JsonProperty("id")
    public abstract long id();

    @Nullable @JsonProperty("name")
    public abstract String name();

    @Nullable @JsonProperty("host")
    public abstract String host();

    @Nullable
    @JsonProperty("cert-path")
    public abstract String certPath();

    /**
     * @deprecated Use {@link DockerServerBase#backend()} instead.
     * swarmMode is true when backend is {@link Backend#SWARM} and
     * false otherwise.
     */
    @Deprecated
    @JsonProperty("swarm-mode")
    public boolean swarmMode() {
        return backend() == Backend.SWARM;
    }

    @JsonProperty("backend")
    public abstract Backend backend();

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

    @JsonProperty("auto-cleanup")
    public abstract boolean autoCleanup();

    @Nullable
    @JsonProperty("swarm-constraints")
    public abstract ImmutableList<DockerServerSwarmConstraint> swarmConstraints();

    @Nullable
    @JsonProperty("max-concurrent-finalizing-jobs")
    public abstract Integer maxConcurrentFinalizingJobs();

    @JsonProperty("status-email-enabled")
    public abstract boolean statusEmailEnabled();

    @JsonProperty("gpu-vendor")
    @Nullable
    public abstract String gpuVendor();

    @JsonProperty("archive-pvc-name")
    @Nullable
    public abstract String archivePvcName();

    @JsonProperty("build-pvc-name")
    @Nullable
    public abstract String buildPvcName();

    @JsonProperty("combined-pvc-name")
    @Nullable
    public abstract String combinedPvcName();

    @JsonProperty("archive-path-translation")
    @Nullable
    public abstract String archivePathTranslation();

    @JsonProperty("build-path-translation")
    @Nullable
    public abstract String buildPathTranslation();

    @JsonProperty("combined-path-translation")
    @Nullable
    public abstract String combinedPathTranslation();

    @AutoValue
    public abstract static class DockerServer extends DockerServerBase {
        private static final long serialVersionUID = 3879071283219400186L;

        public static final DockerServer DEFAULT_SOCKET = DockerServer.create("Local socket", "unix:///var/run/docker.sock");

        @JsonCreator
        public static DockerServer create(@JsonProperty("id") final Long id,
                                          @JsonProperty("name") final String name,
                                          @JsonProperty("host") final String host,
                                          @JsonProperty("cert-path") final String certPath,
                                          @JsonProperty("swarm-mode") final Boolean swarmMode,
                                          @JsonProperty("backend") Backend backend,
                                          @JsonProperty("path-translation-xnat-prefix") final String pathTranslationXnatPrefix,
                                          @JsonProperty("path-translation-docker-prefix") final String pathTranslationDockerPrefix,
                                          @JsonProperty("pull-images-on-xnat-init") final Boolean pullImagesOnXnatInit,
                                          @JsonProperty("container-user") final String containerUser,
                                          @JsonProperty("auto-cleanup") final boolean autoCleanup,
                                          @Nullable @JsonProperty("swarm-constraints") final List<DockerServerSwarmConstraint> swarmConstraints,
                                          @JsonProperty("max-concurrent-finalizing-jobs") final Integer maxConcurrentFinalizingJobs,
                                          @JsonProperty("status-email-enabled") final boolean statusEmailEnabled,
                                          @JsonProperty("gpu-vendor") final String gpuVendor,
                                          @JsonProperty("archive-pvc-name") final String archivePvcName,
                                          @JsonProperty("build-pvc-name") final String buildPvcName,
                                          @JsonProperty("combined-pvc-name") final String combinedPvcName,
                                          @JsonProperty("archive-path-translation") final String archivePathTranslation,
                                          @JsonProperty("build-path-translation") final String buildPathTranslation,
                                          @JsonProperty("combined-path-translation") final String combinedPathTranslation) {
            if (backend == null) {
                backend = swarmMode != null && swarmMode ? Backend.SWARM : Backend.DOCKER;
            }
            return create(id, name, host, certPath, backend, null, pathTranslationXnatPrefix,
                    pathTranslationDockerPrefix, pullImagesOnXnatInit, containerUser, autoCleanup, swarmConstraints,
                    maxConcurrentFinalizingJobs, statusEmailEnabled, gpuVendor, archivePvcName, buildPvcName, combinedPvcName,
                    archivePathTranslation, buildPathTranslation, combinedPathTranslation);
        }

        public static DockerServer create(final String name,
                                          final String host) {
            return builder()
                    .name(StringUtils.isBlank(name) ? host : name)
                    .host(host)
                    .build();
        }

        public static DockerServer create(final Long id,
                                          final String name,
                                          final String host,
                                          final String certPath,
                                          final Backend backend,
                                          final Date lastEventCheckTime,
                                          final String pathTranslationXnatPrefix,
                                          final String pathTranslationDockerPrefix,
                                          final Boolean pullImagesOnXnatInit,
                                          final String containerUser,
                                          final Boolean autoCleanup,
                                          final List<DockerServerSwarmConstraint> swarmConstraints,
                                          final Integer maxConcurrentFinalizingJobs,
                                          final Boolean statusEmailEnabled,
                                          final String gpuVendor,
                                          final String archivePvcName,
                                          final String buildPvcName,
                                          final String combinedPvcName,
                                          final String archivePathTranslation,
                                          final String buildPathTranslation,
                                          final String combinedPathTranslation) {
            return builder()
                    .id(id == null ? 0L : id)
                    .name(StringUtils.isBlank(name) ? host : name)
                    .host(host)
                    .certPath(certPath)
                    .backend(backend)
                    .lastEventCheckTime(lastEventCheckTime != null ? lastEventCheckTime : new Date())
                    .pathTranslationXnatPrefix(pathTranslationXnatPrefix)
                    .pathTranslationDockerPrefix(pathTranslationDockerPrefix)
                    .pullImagesOnXnatInit(pullImagesOnXnatInit != null && pullImagesOnXnatInit)
                    .containerUser(containerUser)
                    .autoCleanup(autoCleanup == null || autoCleanup)
                    .swarmConstraints(swarmConstraints)
                    .maxConcurrentFinalizingJobs(maxConcurrentFinalizingJobs)
                    .statusEmailEnabled(statusEmailEnabled == null || statusEmailEnabled)
                    .gpuVendor(gpuVendor)
                    .archivePvcName(archivePvcName)
                    .buildPvcName(buildPvcName)
                    .combinedPvcName(combinedPvcName)
                    .archivePathTranslation(archivePathTranslation)
                    .buildPathTranslation(buildPathTranslation)
                    .combinedPathTranslation(combinedPathTranslation)
                    .build();
        }

        public static DockerServer create(final DockerServerEntity dockerServerEntity) {
            final Boolean pullImagesOnXnatInit = dockerServerEntity.getPullImagesOnXnatInit();
            List<DockerServerSwarmConstraint> swarmConstraints = dockerServerEntity.getSwarmConstraints() == null ?
                    null :
                    dockerServerEntity.getSwarmConstraints().stream().map(DockerServerSwarmConstraint::create).collect(Collectors.toList());
            return create(
                    dockerServerEntity.getId(),
                    dockerServerEntity.getName(),
                    dockerServerEntity.getHost(),
                    dockerServerEntity.getCertPath(),
                    dockerServerEntity.getBackend(),
                    dockerServerEntity.getLastEventCheckTime(),
                    dockerServerEntity.getPathTranslationXnatPrefix(),
                    dockerServerEntity.getPathTranslationDockerPrefix(),
                    pullImagesOnXnatInit != null && pullImagesOnXnatInit,
                    dockerServerEntity.getContainerUser(),
                    dockerServerEntity.isAutoCleanup(),
                    swarmConstraints,
                    dockerServerEntity.getMaxConcurrentFinalizingJobs(),
                    dockerServerEntity.isStatusEmailEnabled(),
                    dockerServerEntity.getGpuVendor(),
                    dockerServerEntity.getArchivePvcName(),
                    dockerServerEntity.getBuildPvcName(),
                    dockerServerEntity.getCombinedPvcName(),
                    dockerServerEntity.getArchivePathTranslation(),
                    dockerServerEntity.getBuildPathTranslation(),
                    dockerServerEntity.getCombinedPathTranslation());
        }

        public static DockerServer create(final DockerServerPrefsBean dockerServerPrefsBean) {
            return create(
                    0L,
                    dockerServerPrefsBean.getName(),
                    dockerServerPrefsBean.getHost(),
                    dockerServerPrefsBean.getCertPath(),
                    Backend.DOCKER,
                    dockerServerPrefsBean.getLastEventCheckTime(),
                    null,
                    null,
                    false,
                    dockerServerPrefsBean.getContainerUser(),
                    true,
                    null,
                    null,
                    true,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        public DockerServer updateEventCheckTime(final Date newLastEventCheckTime) {

            return newLastEventCheckTime == null ? this :
                    create(
                            this.id(),
                            this.name(),
                            this.host(),
                            this.certPath(),
                            this.backend(),
                            newLastEventCheckTime,
                            this.pathTranslationXnatPrefix(),
                            this.pathTranslationDockerPrefix(),
                            this.pullImagesOnXnatInit(),
                            this.containerUser(),
                            this.autoCleanup(),
                            this.swarmConstraints(),
                            this.maxConcurrentFinalizingJobs(),
                            this.statusEmailEnabled(),
                            this.gpuVendor(),
                            this.archivePvcName(),
                            this.buildPvcName(),
                            this.combinedPvcName(),
                            this.archivePathTranslation(),
                            this.buildPathTranslation(),
                            this.combinedPathTranslation()
                    );
        }

        public static Builder builder() {
            return new AutoValue_DockerServerBase_DockerServer.Builder()
                    .id(0L)
                    .name(null)
                    .host(null)
                    .certPath(null)
                    .backend(Backend.DOCKER)
                    .lastEventCheckTime(new Date(0))
                    .pathTranslationXnatPrefix(null)
                    .pathTranslationDockerPrefix(null)
                    .pullImagesOnXnatInit(false)
                    .containerUser(null)
                    .autoCleanup(true)
                    .swarmConstraints(Collections.emptyList())
                    .maxConcurrentFinalizingJobs(null)
                    .statusEmailEnabled(false)
                    .gpuVendor(null)
                    .archivePvcName(null)
                    .buildPvcName(null)
                    .combinedPvcName(null)
                    .archivePathTranslation(null)
                    .buildPathTranslation(null)
                    .combinedPathTranslation(null);
        }

        public abstract Builder toBuilder();

        public void validate() throws InvalidDefinitionException {
            // It is tempting to put validation in the AutoValue.Builder, but then the exception is thrown during
            // serialization (as HttpMessageNotReadableException) rather than as a validation (a.k.a., "I understood
            // your request, but I won't accept it")
            List<String> errors = new ArrayList<>();
            if (backend() == Backend.KUBERNETES) {
                // Check that container user is parsable as a long
                final String containerUser = containerUser();
                if (StringUtils.isNotBlank(containerUser)) {
                    try {
                        Long.parseLong(containerUser);
                    } catch (NumberFormatException e) {
                        errors.add("If set, container user must be a string with integer value with " +
                                "kubernetes backend");
                    }
                }
                final String gpuVendor = gpuVendor();
                if (StringUtils.isNotEmpty(gpuVendor) && !(StringUtils.equalsIgnoreCase("nvidia", gpuVendor) ||
                        StringUtils.equalsIgnoreCase("amd", gpuVendor))) {
                    errors.add("The value of the GPU Vendor can only be nvidia or amd");
                }
                if (StringUtils.isNotEmpty(combinedPvcName()) && (StringUtils.isNotEmpty(archivePvcName())
                        || StringUtils.isNotEmpty(buildPvcName()))) {
                    errors.add("You cannot have a Kubernetes server setup which uses mounts from a PVC with separate and combined locations for build and archive. Please choose one or the other.");
                }
                if (StringUtils.isNotEmpty(combinedPathTranslation()) && (StringUtils.isNotEmpty(archivePathTranslation())
                        || StringUtils.isNotEmpty(buildPathTranslation()))) {
                    errors.add("You cannot have a Kubernetes server setup which has path translations for both separate and combined locations for build and archive directories. Please choose one or the other.");
                }
            } else {
                // Docker + Swarm must have a host configured
                if (StringUtils.isBlank(host())) {
                    errors.add("Host cannot be empty for " + backend() + " server setting");
                }
            }

            List<DockerServerSwarmConstraint> constraints = swarmConstraints();
            if (constraints != null) {
                if (constraints.stream().anyMatch(constraint -> StringUtils.isBlank(constraint.attribute()))) {
                    errors.add("Constraint node attribute cannot be blank");
                }
                if (constraints.stream().anyMatch(constraint -> constraint.values().isEmpty() ||
                            constraint.values().stream().anyMatch(StringUtils::isBlank))) {
                    errors.add("Constraint values cannot be blank");
                }
            }

            if (!errors.isEmpty()) {
                throw new InvalidDefinitionException(String.join("\n", errors));
            }
        }

        @AutoValue.Builder
        public static abstract class Builder {
            public abstract Builder id(long id);
            public abstract Builder name(String name);
            public abstract Builder host(String host);
            public abstract Builder certPath(String certPath);
            public abstract Builder backend(Backend backend);
            public abstract Builder lastEventCheckTime(Date lastEventCheckTime);
            public abstract Builder pathTranslationXnatPrefix(String pathTranslationXnatPrefix);
            public abstract Builder pathTranslationDockerPrefix(String pathTranslationDockerPrefix);
            public abstract Builder pullImagesOnXnatInit(Boolean pullImagesOnXnatInit);
            public abstract Builder containerUser(String containerUser);
            public abstract Builder autoCleanup(boolean autoCleanup);
            public abstract Builder swarmConstraints(List<DockerServerSwarmConstraint> swarmConstraints);
            public abstract Builder maxConcurrentFinalizingJobs(Integer maxConcurrentFinalizingJobs);
            public abstract Builder statusEmailEnabled(boolean statusEmailEnabled);
            public abstract Builder gpuVendor(String gpuVendor);
            public abstract Builder archivePvcName(String archivePvcName);
            public abstract Builder buildPvcName(String buildPvcName);
            public abstract Builder combinedPvcName(String combinedPvcName);
            public abstract Builder archivePathTranslation(String archivePathTranslation);
            public abstract Builder buildPathTranslation(String buildPathTranslation);
            public abstract Builder combinedPathTranslation(String combinedPathTranslation);
            public abstract DockerServer build();
        }
    }

    @AutoValue
    public static abstract class DockerServerWithPing extends DockerServerBase {
        private static final long serialVersionUID = 4329295970646182399L;

        @Nullable
        @JsonProperty("ping")
        public abstract Boolean ping();

        @JsonCreator
        public static DockerServerWithPing create(@JsonProperty("id") final Long id,
                                                  @JsonProperty("name") final String name,
                                                  @JsonProperty("host") final String host,
                                                  @JsonProperty("cert-path") final String certPath,
                                                  @JsonProperty("swarm-mode") final Boolean swarmMode,
                                                  @JsonProperty("backend") Backend backend,
                                                  @JsonProperty("path-translation-xnat-prefix") final String pathTranslationXnatPrefix,
                                                  @JsonProperty("path-translation-docker-prefix") final String pathTranslationDockerPrefix,
                                                  @JsonProperty("pull-images-on-xnat-init") final Boolean pullImagesOnXnatInit,
                                                  @JsonProperty("container-user") final String user,
                                                  @JsonProperty("auto-cleanup") final boolean autoCleanup,
                                                  @Nullable @JsonProperty("swarm-constraints") final List<DockerServerSwarmConstraint> swarmConstraints,
                                                  @JsonProperty("max-concurrent-finalizing-jobs")
                                                          final Integer maxConcurrentFinalizingJobs,
                                                  @JsonProperty("status-email-enabled") final boolean statusEmailEnabled,
                                                  @JsonProperty("gpu-vendor") final String gpuVendor,
                                                  @JsonProperty("archive-pvc-name") final String archivePvcName,
                                                  @JsonProperty("build-pvc-name") final String buildPvcName,
                                                  @JsonProperty("combined-pvc-name") final String combinedPvcName,
                                                  @JsonProperty("archive-path-translation") final String archivePathTranslation,
                                                  @JsonProperty("build-path-translation") final String buildPathTranslation,
                                                  @JsonProperty("combined-path-translation") final String combinedPathTranslation,
                                                  @JsonProperty("ping") final Boolean ping) {
            if (backend == null) {
                backend = swarmMode != null && swarmMode ? Backend.SWARM : Backend.DOCKER;
            }

            return create(id, name, host, certPath, backend, new Date(0),
                    pathTranslationXnatPrefix, pathTranslationDockerPrefix, pullImagesOnXnatInit,
                    user, autoCleanup, swarmConstraints, maxConcurrentFinalizingJobs, statusEmailEnabled,
                    gpuVendor, archivePvcName, buildPvcName, combinedPvcName, archivePathTranslation, buildPathTranslation,
                    combinedPathTranslation, ping);
        }

        public static DockerServerWithPing create(final Long id,
                                                  final String name,
                                                  final String host,
                                                  final String certPath,
                                                  final Backend backend,
                                                  final Date lastEventCheckTime,
                                                  final String pathTranslationXnatPrefix,
                                                  final String pathTranslationDockerPrefix,
                                                  final Boolean pullImagesOnXnatInit,
                                                  final String user,
                                                  final Boolean autoCleanup,
                                                  final List<DockerServerSwarmConstraint> swarmConstraints,
                                                  final Integer maxConcurrentFinalizingJobs,
                                                  final Boolean statusEmailEnabled,
                                                  final String gpuVendor,
                                                  final String archivePvcName,
                                                  final String buildPvcName,
                                                  final String combinedPvcName,
                                                  final String archivePathTranslation,
                                                  final String buildPathTranslation,
                                                  final String combinedPathTranslation,
                                                  final Boolean ping) {
            return builder()
                    .id(id == null ? 0L : id)
                    .name(StringUtils.isBlank(name) ? host : name)
                    .host(host)
                    .certPath(certPath)
                    .backend(backend)
                    .lastEventCheckTime(lastEventCheckTime != null ? lastEventCheckTime : new Date())
                    .pathTranslationXnatPrefix(pathTranslationXnatPrefix)
                    .pathTranslationDockerPrefix(pathTranslationDockerPrefix)
                    .pullImagesOnXnatInit(pullImagesOnXnatInit != null && pullImagesOnXnatInit)
                    .containerUser(user)
                    .autoCleanup(autoCleanup == null || autoCleanup)
                    .swarmConstraints(swarmConstraints)
                    .maxConcurrentFinalizingJobs(maxConcurrentFinalizingJobs)
                    .statusEmailEnabled(statusEmailEnabled == null || statusEmailEnabled)
                    .gpuVendor(gpuVendor)
                    .archivePvcName(archivePvcName)
                    .buildPvcName(buildPvcName)
                    .combinedPvcName(combinedPvcName)
                    .archivePathTranslation(archivePathTranslation)
                    .buildPathTranslation(buildPathTranslation)
                    .combinedPathTranslation(combinedPathTranslation)
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
                    dockerServer.backend(),
                    dockerServer.lastEventCheckTime(),
                    dockerServer.pathTranslationXnatPrefix(),
                    dockerServer.pathTranslationDockerPrefix(),
                    dockerServer.pullImagesOnXnatInit(),
                    dockerServer.containerUser(),
                    dockerServer.autoCleanup(),
                    dockerServer.swarmConstraints(),
                    dockerServer.maxConcurrentFinalizingJobs(),
                    dockerServer.statusEmailEnabled(),
                    dockerServer.gpuVendor(),
                    dockerServer.archivePvcName(),
                    dockerServer.buildPvcName(),
                    dockerServer.combinedPvcName(),
                    dockerServer.archivePathTranslation(),
                    dockerServer.buildPathTranslation(),
                    dockerServer.combinedPathTranslation(),
                    ping
            );
        }

        public static Builder builder() {
            return new AutoValue_DockerServerBase_DockerServerWithPing.Builder()
                    .id(0L)
                    .ping(false)
                    .name(null)
                    .host(null)
                    .certPath(null)
                    .backend(Backend.DOCKER)
                    .lastEventCheckTime(new Date(0))
                    .pathTranslationXnatPrefix(null)
                    .pathTranslationDockerPrefix(null)
                    .pullImagesOnXnatInit(false)
                    .containerUser(null)
                    .autoCleanup(true)
                    .swarmConstraints(Collections.emptyList())
                    .maxConcurrentFinalizingJobs(null)
                    .statusEmailEnabled(false)
                    .gpuVendor(null)
                    .archivePvcName(null)
                    .buildPvcName(null)
                    .combinedPvcName(null)
                    .archivePathTranslation(null)
                    .buildPathTranslation(null)
                    .combinedPathTranslation(null);
        }

        public abstract Builder toBuilder();

        @AutoValue.Builder
        public static abstract class Builder {
            public abstract Builder id(long id);
            public abstract Builder name(String name);
            public abstract Builder host(String host);
            public abstract Builder certPath(String certPath);
            public abstract Builder backend(Backend backend);
            public abstract Builder lastEventCheckTime(Date lastEventCheckTime);
            public abstract Builder pathTranslationXnatPrefix(String pathTranslationXnatPrefix);
            public abstract Builder pathTranslationDockerPrefix(String pathTranslationDockerPrefix);
            public abstract Builder pullImagesOnXnatInit(Boolean pullImagesOnXnatInit);
            public abstract Builder containerUser(String containerUser);
            public abstract Builder autoCleanup(boolean autoCleanup);
            public abstract Builder swarmConstraints(List<DockerServerSwarmConstraint> swarmConstraints);
            public abstract Builder maxConcurrentFinalizingJobs(Integer maxConcurrentFinalizingJobs);
            public abstract Builder statusEmailEnabled(boolean statusEmailEnabled);
            public abstract Builder gpuVendor(String gpuVendor);
            public abstract Builder archivePvcName(String archivePvcName);
            public abstract Builder buildPvcName(String buildPvcName);
            public abstract Builder combinedPvcName(String combinedPvcName);
            public abstract Builder archivePathTranslation(String archivePathTranslation);
            public abstract Builder buildPathTranslation(String buildPathTranslation);
            public abstract Builder combinedPathTranslation(String combinedPathTranslation);
            public abstract Builder ping(Boolean ping);

            public abstract DockerServerWithPing build();
        }
    }

    @AutoValue
    public static abstract class DockerServerSwarmConstraint implements Serializable {
        private static final long serialVersionUID = 7702940141914762990L;

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
        return backend() == that.backend() &&
                Objects.equals(this.name(), that.name()) &&
                Objects.equals(this.host(), that.host()) &&
                Objects.equals(this.certPath(), that.certPath()) &&
                Objects.equals(this.pathTranslationXnatPrefix(), that.pathTranslationXnatPrefix()) &&
                Objects.equals(this.pathTranslationDockerPrefix(), that.pathTranslationDockerPrefix()) &&
                Objects.equals(this.pullImagesOnXnatInit(), that.pullImagesOnXnatInit()) &&
                Objects.equals(this.containerUser(), that.containerUser()) &&
                Objects.equals(this.autoCleanup(), that.autoCleanup()) &&
                Objects.equals(this.swarmConstraints(), that.swarmConstraints()) &&
                Objects.equals(this.maxConcurrentFinalizingJobs(), that.maxConcurrentFinalizingJobs()) &&
                Objects.equals(this.statusEmailEnabled(), that.statusEmailEnabled()) &&
                Objects.equals(this.gpuVendor(), that.gpuVendor()) &&
                Objects.equals(this.archivePvcName(), that.archivePvcName()) &&
                Objects.equals(this.buildPvcName(), that.buildPvcName()) &&
                Objects.equals(this.combinedPvcName(), that.combinedPvcName()) &&
                Objects.equals(this.archivePathTranslation(), that.archivePathTranslation()) &&
                Objects.equals(this.buildPathTranslation(), that.buildPathTranslation()) &&
                Objects.equals(this.combinedPathTranslation(), that.combinedPathTranslation());
    }

    @Override
    public int hashCode() {
        return Objects.hash(name(), host(), certPath(), backend(),
                pathTranslationXnatPrefix(), pathTranslationDockerPrefix(), pullImagesOnXnatInit(),
                containerUser(), autoCleanup(), swarmConstraints(), maxConcurrentFinalizingJobs(),
                statusEmailEnabled(), gpuVendor(), archivePvcName(), buildPvcName(), combinedPvcName(),
                archivePathTranslation(), buildPathTranslation(), combinedPathTranslation());
    }

}