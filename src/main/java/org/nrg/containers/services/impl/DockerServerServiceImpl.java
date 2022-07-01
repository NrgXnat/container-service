package org.nrg.containers.services.impl;

import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.model.server.docker.DockerServerEntity;
import org.nrg.containers.services.DockerServerEntityService;
import org.nrg.containers.services.DockerServerService;
import org.nrg.framework.exceptions.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class DockerServerServiceImpl implements DockerServerService {
    private final DockerServerEntityService dockerServerEntityService;

    @Autowired
    public DockerServerServiceImpl(final DockerServerEntityService dockerServerEntityService) {
        this.dockerServerEntityService = dockerServerEntityService;
    }

    @Override
    @Nonnull
    public List<DockerServer> getServers() {
        final List<DockerServerEntity> dockerServerEntities = dockerServerEntityService.getAllWithDisabled();
        return dockerServerEntities == null ? Collections.emptyList() :
                dockerServerEntities.stream().map(this::toPojo).collect(Collectors.toList());
    }

    @Override
    @Nullable
    public DockerServer retrieveServer() {
        return toPojo(dockerServerEntityService.getServer());
    }

    @Override
    @Nonnull
    public DockerServer getServer() throws NotFoundException {
        final DockerServer server = retrieveServer();
        if (server == null) {
            throw new NotFoundException("No container server defined.");
        }
        return server;
    }

    @Override
    public DockerServer setServer(final DockerServer dockerServer) {
        return toPojo(dockerServerEntityService.create(fromPojo(dockerServer)));
    }

    @Override
    public void update(final DockerServer dockerServer) {
        dockerServerEntityService.update(fromPojo(dockerServer));
    }

    @Nullable
    public DockerServer toPojo(final DockerServerEntity dockerServerEntity) {
        return dockerServerEntity == null ? null : DockerServer.create(dockerServerEntity);
    }

    @Nonnull
    public DockerServerEntity fromPojo(final DockerServer dockerServer) {
        final DockerServerEntity template = dockerServerEntityService.retrieve(dockerServer.id());
        return template == null ? DockerServerEntity.create(dockerServer) : template.update(dockerServer);
    }
}
