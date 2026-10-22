package org.nrg.containers.secrets;

import org.nrg.containers.events.model.KubernetesStatusChangeEvent;

import javax.annotation.Nullable;

/** A FailedMount event that concerns one of the container's own secret volumes, with its translation. */
public final class SecretMountFailure {
    public static final String FAILED_MOUNT = "FailedMount";

    private final KubernetesStatusChangeEvent event;
    private final String translated;

    private SecretMountFailure(final KubernetesStatusChangeEvent event, final String translated) {
        this.event = event;
        this.translated = translated;
    }

    /** Null unless the event is a FailedMount naming a volume that {@code ctx} owns. PVC and hostPath mounts are not ours to time. */
    @Nullable
    public static SecretMountFailure from(final KubernetesStatusChangeEvent event, final SecretErrorTranslator.Context ctx) {
        if (!FAILED_MOUNT.equals(event.getWarningReason()) || event.getPodPhaseReason() == null) {
            return null;
        }
        if (!ctx.ownsVolume(SecretErrorTranslator.volumeNameFrom(event.getPodPhaseReason()))) {
            return null;
        }
        return new SecretMountFailure(event, SecretErrorTranslator.translate(event.getPodPhaseReason(), ctx));
    }

    public String translated() {
        return translated;
    }

    /** The same event with the translation in front of the raw message, so history shows both in one record. */
    public KubernetesStatusChangeEvent translatedEvent() {
        final String raw = event.getPodPhaseReason();
        return translated.equals(raw) ? event : event.toBuilder().podPhaseReason(translated + " [" + raw + "]").build();
    }
}
