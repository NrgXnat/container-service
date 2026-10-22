package org.nrg.containers.secrets;

import javax.annotation.Nullable;

/** Outcome of asking the API server whether a SecretProviderClass exists: a tri-state, never an exception. */
public final class SecretProviderClassCheck {
    public enum Result { EXISTS, MISSING, UNDETERMINED }

    private final Result result;
    private final String reason;

    private SecretProviderClassCheck(final Result result, @Nullable final String reason) {
        this.result = result;
        this.reason = reason;
    }

    public static SecretProviderClassCheck exists() {
        return new SecretProviderClassCheck(Result.EXISTS, null);
    }

    public static SecretProviderClassCheck missing(final String reason) {
        return new SecretProviderClassCheck(Result.MISSING, reason);
    }

    public static SecretProviderClassCheck undetermined(final String reason) {
        return new SecretProviderClassCheck(Result.UNDETERMINED, reason);
    }

    public Result result() {
        return result;
    }

    /** Translated, user-facing sentence; null when the object exists. */
    @Nullable
    public String reason() {
        return reason;
    }

    @Override
    public String toString() {
        return result + (reason == null ? "" : ": " + reason);
    }
}
