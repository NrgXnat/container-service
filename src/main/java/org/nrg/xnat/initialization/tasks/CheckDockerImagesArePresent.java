package org.nrg.xnat.initialization.tasks;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.api.ContainerControlApi;
import org.nrg.containers.exceptions.DockerServerException;
import org.nrg.containers.exceptions.NoDockerServerException;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.server.docker.DockerServerBase;
import org.nrg.containers.services.CommandService;
import org.nrg.containers.services.DockerService;
import org.nrg.framework.exceptions.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.nrg.xnat.initialization.tasks.InitializingTaskException.Level.SingleNotice;

@Slf4j
@Component
public class CheckDockerImagesArePresent extends AbstractInitializingTask {
    private final DockerService       dockerService;
    private final CommandService      commandService;
    private final ContainerControlApi containerControlApi;

    @Autowired
    public CheckDockerImagesArePresent(final DockerService dockerService,
                                       final CommandService commandService,
                                       final ContainerControlApi containerControlApi) {
        this.dockerService = dockerService;
        this.commandService = commandService;
        this.containerControlApi = containerControlApi;
    }

    @Override
    public String getTaskName() {
        return "Check if all images referenced in commands are present on docker server. If not, pull them.";
    }

    @Override
    protected void callImpl() throws InitializingTaskException {
        final DockerServerBase.DockerServerWithPing dockerServerWithPing;
        try {
            dockerServerWithPing = dockerService.getServer();
        } catch (NotFoundException e) {
            throw new InitializingTaskException(SingleNotice);
        } catch (RuntimeException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof IOException && StringUtils.contains("No such file or directory", cause.getMessage())) {
                log.info("Tried to ping docker server, but got 'No such file or directory' error. This usually indicates that the Docker daemon is not running or the Docker socket is not accessible.");
                throw new InitializingTaskException(SingleNotice);
            }
            throw e;
        }

        final Boolean pullImagesOnXnatInit = dockerServerWithPing.pullImagesOnXnatInit();
        if (!(pullImagesOnXnatInit != null && pullImagesOnXnatInit)) {
            log.info("Docker server is set to not pull images on XNAT initialization. Thus, I'm all done.");
            return;
        }

        final Boolean serverPing = dockerServerWithPing.ping();
        if (!(Boolean.TRUE == serverPing)) {
            log.info("Docker server found in DB, but with no ping. Not attempting to pull images.");
            return;
        }

        final Map<String, String> imageNameToCommandName = new HashMap<>();
        final Map<String, Long> imageNameToCommandId = new HashMap<>();
        for (final Command command : commandService.getAll()) {
            final String imageName = command.image();

            if (imageName == null || imageName.startsWith("sha256")) {
                log.debug("Skipping image \"{}\" for command {} \"{}\".{}", imageName, command.id(), command.name(),
                        imageName == null ? "" : " We can't pull images by hash id.");
                continue;
            }

            imageNameToCommandId.put(imageName, command.id());
            imageNameToCommandName.put(imageName, command.name());
        }

        for (final String imageName : imageNameToCommandId.keySet()) {
            final String commandName = imageNameToCommandName.get(imageName);
            final Long commandId = imageNameToCommandId.get(imageName);
            log.debug("Pulling image \"{}\" for command {} \"{}\".", imageName, commandId, commandName);
            try {
                containerControlApi.pullImage(imageName);
            } catch (NoDockerServerException | DockerServerException | NotFoundException e) {
                log.debug("Encountered a problem pulling image \"{}\": {}", imageName, e.getMessage());
            }
        }
    }
}
