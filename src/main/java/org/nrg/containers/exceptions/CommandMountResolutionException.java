package org.nrg.containers.exceptions;

import org.nrg.containers.model.command.auto.Command.CommandMount;

public class CommandMountResolutionException extends CommandResolutionException {
    final CommandMount mount;

    public CommandMountResolutionException(final String message) {
        this(message, null, null);
    }

    public CommandMountResolutionException(final String message, final CommandMount mount) {
        this(message, mount, null);
    }

    public CommandMountResolutionException(final String message, final Throwable cause) {
        this(message, null, cause);
    }

    public CommandMountResolutionException(final String message, final CommandMount mount, final Throwable cause) {
        super(message, cause);
        this.mount = mount;
    }

    public CommandMount getMount() {
        return mount;
    }
}
