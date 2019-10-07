package org.nrg.containers.services;

import org.nrg.containers.exceptions.CommandResolutionException;
import org.nrg.containers.exceptions.UnauthorizedException;
import org.nrg.containers.model.command.auto.Command.ConfiguredCommand;
import org.nrg.containers.model.command.auto.ResolvedCommand;
import org.nrg.containers.model.command.auto.ResolvedCommand.PartiallyResolvedCommand;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xft.security.UserI;

import java.util.Map;

public interface CommandResolutionService {
    String[] ILLEGAL_INPUT_STRINGS = {";", "&&", "||", "`", "("};

    PartiallyResolvedCommand preResolve(long wrapperId,
                                        Map<String, String> inputValues,
                                        final UserI userI)
            throws NotFoundException, CommandResolutionException, UnauthorizedException;

    PartiallyResolvedCommand preResolve(long commandId,
                                        String wrapperName,
                                        Map<String, String> inputValues,
                                        final UserI userI)
            throws NotFoundException, CommandResolutionException, UnauthorizedException;

    PartiallyResolvedCommand preResolve(String project,
                                        long wrapperId,
                                        Map<String, String> inputValues,
                                        final UserI userI)
            throws NotFoundException, CommandResolutionException, UnauthorizedException;

    PartiallyResolvedCommand preResolve(String project,
                                        long commandId,
                                        String wrapperName,
                                        Map<String, String> inputValues,
                                        final UserI userI)
            throws NotFoundException, CommandResolutionException, UnauthorizedException;

    PartiallyResolvedCommand preResolve(ConfiguredCommand configuredCommand,
                                        Map<String, String> inputValues,
                                        UserI userI)
            throws CommandResolutionException, UnauthorizedException;

    ResolvedCommand resolve(ConfiguredCommand configuredCommand,
                            Map<String, String> inputValues,
                            UserI userI)
            throws NotFoundException, CommandResolutionException, UnauthorizedException;
}