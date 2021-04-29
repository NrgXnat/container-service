package org.nrg.containers.services;

import org.nrg.containers.exceptions.DockerServerDeleteDefaultException;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.framework.exceptions.NotFoundException;

import java.util.List;

public interface DockerServerService {
    List<DockerServer> getServers();
    DockerServer getServer(Long id);
    DockerServer getDefaultServer() throws NotFoundException;
    DockerServer addServer(DockerServer dockerServer);
    DockerServer setDefaultServer(DockerServer dockerServer);
    void deleteServer(Long id) throws NotFoundException, DockerServerDeleteDefaultException;
    void update(DockerServer dockerServer);

}
