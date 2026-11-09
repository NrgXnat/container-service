package org.nrg.containers.services.impl;

import lombok.extern.slf4j.Slf4j;
import org.nrg.containers.api.ContainerControlApi;
import org.nrg.containers.events.model.KubernetesStatusChangeEvent;
import org.nrg.containers.exceptions.ContainerBackendException;
import org.nrg.containers.exceptions.NoContainerServerException;
import org.nrg.containers.jms.requests.ContainerRequest;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.auto.Container.ContainerHistory;
import org.nrg.containers.model.container.entity.ContainerEntity;
import org.nrg.containers.model.kubernetes.KubernetesPodPhase;
import org.nrg.containers.model.kubernetes.PodMountState;
import org.nrg.containers.model.kubernetes.PodWarningReason;
import org.nrg.containers.model.server.docker.DockerServerBase;
import org.nrg.containers.secrets.FileResolvedSecret;
import org.nrg.containers.secrets.SecretErrorTranslator;
import org.nrg.containers.secrets.SecretMountFailure;
import org.nrg.containers.services.ContainerService;
import org.nrg.containers.services.DockerServerService;
import org.nrg.containers.tasks.SecretMountWatchdog;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xdat.security.helpers.Users;
import org.nrg.xdat.security.user.exceptions.UserInitException;
import org.nrg.xdat.security.user.exceptions.UserNotFoundException;
import org.nrg.xft.security.UserI;

import javax.annotation.Nullable;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The container side of the secret mount watchdog (design doc section 5.2): turns pod events into watchdog entries,
 * fails a container whose secret volume stays unmountable past the timeout, and recognises the pod events that
 * follow such a timeout so they are not recorded or finalized again.
 *
 * <p>Created and registered by {@link ContainerServiceImpl}; it works through the {@link ContainerService} interface
 * for history, finalize and lookups, so the container service keeps a single place for those operations.
 */
@Slf4j
class SecretMountTimeoutHandler implements SecretMountWatchdog.TimeoutHandler {
    static final String SECRET_MOUNT_TIMEOUT_PREFIX = "Secret mount timed out after ";
    /** Entity type ContainerHistory.fromUserAction records. */
    private static final String USER_ENTITY_TYPE = "user";
    /** Failed attempts (user lookup or Job delete) after which the watchdog gives up on a timed-out container. */
    static final int MAX_SECRET_MOUNT_TIMEOUT_ATTEMPTS = 10;

    private final ContainerService containerService;
    private final ContainerControlApi containerControlApi;
    private final SecretMountWatchdog secretMountWatchdog;
    private final DockerServerService dockerServerService;

    SecretMountTimeoutHandler(final ContainerService containerService,
                              final ContainerControlApi containerControlApi,
                              final SecretMountWatchdog secretMountWatchdog,
                              final DockerServerService dockerServerService) {
        this.containerService = containerService;
        this.containerControlApi = containerControlApi;
        this.secretMountWatchdog = secretMountWatchdog;
        this.dockerServerService = dockerServerService;
    }

    /**
     * A FailedMount on one of this container's secret volumes, translated; null for any other event, including mount
     * failures of other volumes (PVC, hostPath), which stay history only. The reason is checked first: building the
     * translator context reads the server row, not worth it for every event.
     */
    @Nullable
    SecretMountFailure secretMountFailure(final KubernetesStatusChangeEvent kEvent, final Container container) {
        return PodWarningReason.FAILED_MOUNT.equals(kEvent.getWarningReason())
                ? SecretMountFailure.from(kEvent, translatorContext(container)) : null;
    }

    /**
     * Called for every Kubernetes event once it is in history (or found to be there already). A secret mount failure
     * starts the clock whether or not the event is new: an informer restart replays old events and must not lose the
     * start time. Running or exited clears it.
     */
    void onEventRecorded(final KubernetesStatusChangeEvent kEvent, final Container container,
                         @Nullable final SecretMountFailure secretMountFailure) {
        if (secretMountFailure != null) {
            secretMountWatchdog.recordFailedMount(container.databaseId(), kEvent.getFirstTimestamp(), secretMountFailure.translated());
        }
        if (kEvent.getPodPhase() == KubernetesPodPhase.RUNNING || kEvent.isExitStatus()) {
            secretMountWatchdog.clear(container.databaseId());
        }
    }

    /**
     * Once the secret mount watchdog has queued finalize (and then deleted the job), that finalize is the single
     * source of truth for the container. Every later pod event is noise: the synthetic onDelete FAILED event, the
     * genuine FAILED / ContainerStatusUnknown / 137 event the informer delivers after the delete, and repeated
     * FailedMount warnings. Recording any of them would add history and finalize a second time.
     * A user kill still passes: it maps the status to "Failed (Killed)", which is terminal, and is finalized only
     * through the onDelete event, so a container with a user kill entry must not be swallowed.
     */
    boolean isEventAfterTimeout(final Container container) {
        return hasSecretMountTimeoutEntry(container)
                && (isQueuedOrFinalizing(container) || container.statusIsTerminal())
                && !hasUserKillEntry(container);
    }

    /**
     * Watchdog callback. Order matters: history first, then queue finalize, then kill only once the finalize request
     * was accepted. Killing an unqueued job would make the pod's onDelete FAILED event look like a real failure.
     * While finalizing is throttled the entry stays and the next tick retries.
     * The Pending check and queueFinalize are not atomic: a pod that turns Running in that millisecond window is
     * finalized as failed, which is accepted by design.
     */
    @Override
    public void onSecretMountTimeout(final long containerDbId, final int timeoutSeconds, final String translatedReason) {
        final Container container = containerService.retrieve(containerDbId);
        if (container == null || container.statusIsTerminal()) {
            secretMountWatchdog.clear(containerDbId);
            return;
        }
        if (isQueuedOrFinalizing(container)) {
            // Finalize was accepted on an earlier tick but the delete failed: just retry the delete
            deleteJobAndClear(container, containerDbId);
            return;
        }
        if (KubernetesPodPhase.fromString(container.status()) != KubernetesPodPhase.PENDING) {
            secretMountWatchdog.clear(containerDbId);   // past the mount stage (Running etc.): nothing to time out
            return;
        }
        if (containerControlApi.podMountState(container) == PodMountState.PAST_VOLUME_MOUNT) {
            // The FailedMount that started the clock is stale (e.g. replayed by the informer after a reconnect): the
            // pod has mounted its volumes since and is held by something else, which the secret timeout must not report.
            log.info("Container {} is no longer waiting on a secret mount; not timing it out", containerDbId);
            secretMountWatchdog.clear(containerDbId);
            return;
        }
        final UserI userI;
        try {
            userI = Users.getUser(container.userId());
        } catch (UserInitException | UserNotFoundException e) {
            recordFailedAttempt(containerDbId, "get user " + container.userId(), e);
            return;
        }
        if (!hasSecretMountTimeoutEntry(container)) {
            containerService.addContainerHistoryItem(container, ContainerHistory.fromSystem(container.status(),
                    SECRET_MOUNT_TIMEOUT_PREFIX + timeoutSeconds + " s: " + translatedReason), userI);
        }

        containerService.queueFinalize("1", false, container, userI);

        final Container requeried = containerService.retrieve(containerDbId);
        if (requeried == null || !isQueuedOrFinalizing(requeried)) {
            log.debug("Finalize of container {} is throttled; keeping the job until the next tick", containerDbId);
            return;
        }
        deleteJobAndClear(requeried, containerDbId);
    }

    private void deleteJobAndClear(final Container container, final long containerDbId) {
        try {
            containerControlApi.kill(container);
        } catch (NotFoundException e) {
            log.debug("Job of container {} is already gone", containerDbId);
        } catch (NoContainerServerException | ContainerBackendException e) {
            recordFailedAttempt(containerDbId, "delete the job", e);
            return;
        }
        secretMountWatchdog.clear(containerDbId);
    }

    /** Stack trace on the first failure only; after the cap the entry is dropped so a broken Job is not retried every minute forever. */
    private void recordFailedAttempt(final long containerDbId, final String action, final Exception e) {
        final int attempts = secretMountWatchdog.recordFailedAttempt(containerDbId);
        if (attempts >= MAX_SECRET_MOUNT_TIMEOUT_ATTEMPTS) {
            log.error("Could not {} for timed-out container {} after {} attempts; giving up, its Kubernetes Job needs manual cleanup: {}",
                    action, containerDbId, attempts, e.toString());
            secretMountWatchdog.clear(containerDbId);
        } else if (attempts <= 1) {
            log.error("Could not {} for timed-out container {}; will retry", action, containerDbId, e);
        } else {
            log.warn("Could not {} for timed-out container {} (attempt {}); will retry: {}", action, containerDbId, attempts, e.toString());
        }
    }

    private SecretErrorTranslator.Context translatorContext(final Container container) {
        final List<FileResolvedSecret> fileSecrets = container.secrets().stream()
                .filter(secret -> secret instanceof FileResolvedSecret)
                .map(secret -> (FileResolvedSecret) secret)
                .collect(Collectors.toList());
        final DockerServerBase.DockerServer server = dockerServerService.retrieveServer();
        return SecretErrorTranslator.Context.of(container.namespace(),
                server == null ? null : server.jobServiceAccount(),
                fileSecrets);
    }

    private static boolean hasSecretMountTimeoutEntry(final Container container) {
        return container.history().stream()
                .anyMatch(item -> item.message() != null && item.message().startsWith(SECRET_MOUNT_TIMEOUT_PREFIX));
    }

    /** History items keep the raw status, so a user kill is recorded as "kill" even though the container shows "Failed (Killed)". */
    private static boolean hasUserKillEntry(final Container container) {
        return container.history().stream()
                .anyMatch(item -> ContainerEntity.KILL_STATUS.equals(item.status()) && USER_ENTITY_TYPE.equals(item.entityType()));
    }

    /** queueFinalize marks acceptance by prefixing the status with "_" (e.g. "_Pending"); "Finalizing" follows once consumed. */
    private boolean isQueuedOrFinalizing(final Container container) {
        final String status = container.status();
        return containerService.isFinalizing(container) || (status != null && status.startsWith(ContainerRequest.inQueueStatusPrefix));
    }
}
