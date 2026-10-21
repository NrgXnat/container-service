package org.nrg.containers.secrets;

import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.model.command.auto.Command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Resolution-time checks on a command's file secrets (design doc section 2.5). Pure functions: the resolution
 * service wraps IllegalArgumentException into CommandResolutionException.
 */
public final class FileSecretValidator {
    /** Mounting here would hide the kubelet-projected service account token. */
    static final List<String> RESERVED_MOUNT_ROOTS = Arrays.asList("/run/secrets", "/var/run/secrets");

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_.-]+");

    private FileSecretValidator() {}

    public static void validate(final List<FileResolvedSecret> secrets) {
        final Set<String> volumeNames = new HashSet<>();
        final List<String> mountPaths = new ArrayList<>();
        for (final FileResolvedSecret secret : secrets) {
            if (StringUtils.isBlank(secret.identifier())) {
                throw new IllegalArgumentException("Secret source identifier is required for file secrets");
            }
            if (!IDENTIFIER.matcher(secret.identifier()).matches()) {
                throw new IllegalArgumentException("Secret identifier \"" + secret.identifier()
                        + "\" may only contain letters, digits, '_', '.' and '-'");
            }
            // Pod volume names are DNS labels: "secret-" + identifier must stay within 63 characters
            K8sSecretNameTemplate.requireValidLabel(secret.volumeName(),
                    "Secret volume name for identifier \"" + secret.identifier() + "\"");
            if (!volumeNames.add(secret.volumeName())) {
                throw new IllegalArgumentException("Secret identifier \"" + secret.identifier()
                        + "\" is not unique after sanitising (volume " + secret.volumeName()
                        + " would be declared twice)");
            }
            final String raw = secret.mountPath();
            if (raw == null || !raw.startsWith("/")) {
                throw new IllegalArgumentException("Secret mount path \"" + raw + "\" must be absolute");
            }
            final String path = raw.length() > 1 && raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
            if ("/".equals(path)) {
                throw new IllegalArgumentException("Secret mount path must not be /");
            }
            final String[] segments = path.substring(1).split("/", -1);
            for (final String segment : segments) {
                if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                    throw new IllegalArgumentException("Secret mount path \"" + raw
                            + "\" must be a normalised absolute path (no empty, \".\" or \"..\" segments)");
                }
            }
            if (RESERVED_MOUNT_ROOTS.contains(path)) {
                throw new IllegalArgumentException("Secret mount path \"" + raw + "\" must be a subdirectory such as "
                        + path + "/" + secret.identifier() + ", not " + path + " itself");
            }
            for (final String other : mountPaths) {
                if (other.equals(path)) {
                    throw new IllegalArgumentException("Secret mount paths must be unique: \"" + path
                            + "\" is used twice");
                }
                if (path.startsWith(other + "/") || other.startsWith(path + "/")) {
                    throw new IllegalArgumentException("Secret mount paths must not nest: \"" + path
                            + "\" and \"" + other + "\"");
                }
            }
            mountPaths.add(path);
        }
    }

    /**
     * Setup and wrapup commands are resolved without secrets, so a file secret there would be silently dropped.
     * Refuse instead.
     */
    public static void requireNoFileSecrets(final Command command, final String typeStringForLog) {
        for (final Secret secret : command.secrets()) {
            if (secret.destination() instanceof FileSecretDestination) {
                throw new IllegalArgumentException("File secrets are not supported in setup or wrapup commands, but "
                        + typeStringForLog + " command " + command.name() + " (image " + command.image()
                        + ") declares secret \"" + secret.source().identifier() + "\" mounted at "
                        + secret.destination().identifier());
            }
        }
    }
}
