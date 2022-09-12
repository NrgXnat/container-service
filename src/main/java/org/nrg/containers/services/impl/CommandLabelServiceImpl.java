package org.nrg.containers.services.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.image.docker.DockerImage;
import org.nrg.containers.services.CommandLabelService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class CommandLabelServiceImpl implements CommandLabelService {

    private final ObjectMapper objectMapper;

    @Autowired
    public CommandLabelServiceImpl(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    @Nonnull
    public List<Command> parseLabels(final @Nonnull DockerImage dockerImage) {
        return parseLabels(null, dockerImage);
    }

    @Override
    @Nonnull
    public List<Command> parseLabels(final @Nullable String imageName, final @Nonnull DockerImage dockerImage) {
        final List<Command> commandsToReturn = Lists.newArrayList();
        final Map<String, String> labels = dockerImage.labels();
        if (labels == null || labels.isEmpty() || !labels.containsKey(LABEL_KEY)) {
            return commandsToReturn;
        }

        final String labelValue = labels.get(LABEL_KEY);
        if (StringUtils.isNotBlank(labelValue)) {
            try {
                final List<Command.CommandCreation> commandCreationsFromLabels =
                        objectMapper.readValue(labelValue, new TypeReference<List<Command.CommandCreation>>() {});

                if (commandCreationsFromLabels != null && !commandCreationsFromLabels.isEmpty()) {
                    for (final Command.CommandCreation commandCreation : commandCreationsFromLabels) {
                        // The command as read from the image may not contain all the values we want to store
                        // So we add them now.
                        commandsToReturn.add(
                                Command.builderFromCreation(commandCreation)
                                        .image(imageName)
                                        .hash(dockerImage.imageId())
                                        .build()
                        );
                    }
                }
            } catch (IOException e) {
                // TODO throw exception?
                log.error("Could not parse Commands from label: " + labelValue, e);
            }
        }
        return commandsToReturn;
    }
}
