package org.nrg.containers.services.impl;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import org.nrg.containers.exceptions.DockerServerDeleteDefaultException;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.model.server.docker.DockerServerEntity;
import org.nrg.containers.services.DockerServerEntityService;
import org.nrg.containers.services.DockerServerService;
import org.nrg.framework.exceptions.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

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
        return toPojo(dockerServerEntityService.getAllWithDisabled());
    }

    @Nullable
    private DockerServer retrieveDefaultServer() {
        DockerServerEntity defaultServer = dockerServerEntityService.getDefaultServer();

        // If no servers are marked as null, return the first server where defaultServer == null
        // This corresponds to servers that are in the DB before multi-server update
        return toPojo(defaultServer != null ?
                defaultServer :
                dockerServerEntityService.getAll().stream()
                    .filter(dse -> dse.isDefaultServer() ==null)
                    .findFirst()
                        .orElse(null));
    }

    @Override
    @Nonnull
    public DockerServer getDefaultServer() throws NotFoundException {
        final DockerServer server = retrieveDefaultServer();
        if (server == null) {
            throw new NotFoundException("No container server defined.");
        }
        return server;
    }

    // Odd conditional return logic added to maintain compatibility with former logic that allowed only a single server
    // Use update() and addServer() with 'defaultServer = true' set in DockerServer pojo.
    @Deprecated
    @Override
    public DockerServer setDefaultServer(final DockerServer dockerServer) {
        DockerServer defaultDockerServer = dockerServer.toBuilder().defaultServer(true).build();
        if (defaultDockerServer.id() == 0L) {
            return toPojo(dockerServerEntityService.create(fromPojo(defaultDockerServer)));
        }
        else {
            dockerServerEntityService.update(fromPojo(defaultDockerServer));
            return defaultDockerServer;
        }
    }

    @Override
    public void deleteServer(Long id) throws NotFoundException, DockerServerDeleteDefaultException {
        DockerServerEntity server = dockerServerEntityService.getServer(id);
        if (server == null){
            throw new NotFoundException("Docker Server with matching ID not found.");
        } else if (server.isDefaultServer()) {
            throw new DockerServerDeleteDefaultException("Cannot delete default default Docker server.");
        }
        dockerServerEntityService.delete(id);
    }

    @Override
    public void update(final DockerServer dockerServer) {
        dockerServerEntityService.update(fromPojo(dockerServer));
    }

    @Override
    public DockerServer getServer(Long id) {
        return toPojo( dockerServerEntityService.getServer(id));
    }

    @Override
    public DockerServer addServer(DockerServer dockerServer) {
        return toPojo(dockerServerEntityService
                .create(fromPojo(dockerServer)));    }

    @Nullable
    public DockerServer toPojo(final DockerServerEntity dockerServerEntity) {
        return dockerServerEntity == null ? null : DockerServer.create(dockerServerEntity);
    }

    @Nonnull
    public List<DockerServer> toPojo(final List<DockerServerEntity> dockerServerEntities) {
        final List<DockerServer> returnList = Lists.newArrayList();
        if (dockerServerEntities != null) {
            returnList.addAll(Lists.transform(dockerServerEntities, new Function<DockerServerEntity, DockerServer>() {
                @Override
                public DockerServer apply(final DockerServerEntity input) {
                    return toPojo(input);
                }
            }));
        }
        return returnList;
    }

    @Nonnull
    public DockerServerEntity fromPojo(final DockerServer dockerServer) {
        final DockerServerEntity template = dockerServerEntityService.retrieve(dockerServer.id());
        return template == null ? DockerServerEntity.create(dockerServer) : template.update(dockerServer);
    }
}
