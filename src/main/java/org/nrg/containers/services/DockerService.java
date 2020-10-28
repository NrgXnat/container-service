package org.nrg.containers.services;

import org.nrg.containers.exceptions.DockerServerException;
import org.nrg.containers.exceptions.NoDockerServerException;
import org.nrg.containers.exceptions.NotUniqueException;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.dockerhub.DockerHubBase.DockerHub;
import org.nrg.containers.model.dockerhub.DockerHubBase.DockerHubWithPing;
import org.nrg.containers.model.image.docker.DockerImage;
import org.nrg.containers.model.image.docker.DockerImageAndCommandSummary;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServerWithPing;
import org.nrg.containers.services.DockerHubService.DockerHubDeleteDefaultException;
import org.nrg.framework.exceptions.NotFoundException;

import java.util.List;

public interface DockerService {
    List<DockerHubWithPing> getHubs();
    DockerHubWithPing getHub(long id) throws NotFoundException;
    DockerHubWithPing getHub(String name) throws NotFoundException, NotUniqueException;
    DockerHubWithPing createHub(DockerHub hub);
    DockerHubWithPing createHubAndSetDefault(DockerHub hub, String username, String reason);
    void updateHub(DockerHub hub);
    void updateHubAndSetDefault(DockerHub hub, String username, String reason);
    void setDefaultHub(long id, String username, String reason);
    void deleteHub(long id) throws DockerHubDeleteDefaultException;
    void deleteHub(String name) throws DockerHubDeleteDefaultException, NotUniqueException;
    String pingHub(long hubId) throws DockerServerException, NoDockerServerException, NotFoundException;
    String pingHub(long hubId, String username, String password, String token, String email) throws DockerServerException, NoDockerServerException, NotFoundException;
    String pingHub(String hubName) throws DockerServerException, NoDockerServerException, NotUniqueException, NotFoundException;
    String pingHub(String hubName, String username, String password, String token, String email)
            throws DockerServerException, NoDockerServerException, NotUniqueException, NotFoundException;
    DockerImage pullFromHub(long hubId, String imageName, boolean saveCommands)
            throws DockerServerException, NoDockerServerException, NotFoundException;
    DockerImage pullFromHub(long hubId, String imageName, boolean saveCommands, String username, String password, String token, String email)
            throws DockerServerException, NoDockerServerException, NotFoundException;
    DockerImage pullFromHub(String hubName, String imageName, boolean saveCommands)
            throws DockerServerException, NoDockerServerException, NotFoundException, NotUniqueException;
    DockerImage pullFromHub(String hubName, String imageName, boolean saveCommands, String username, String password, String token, String email)
            throws DockerServerException, NoDockerServerException, NotFoundException, NotUniqueException;
    DockerImage pullFromHub(String imageName, boolean saveCommands)
            throws DockerServerException, NoDockerServerException, NotFoundException;

    DockerServerWithPing getServer() throws NotFoundException;
    DockerServerWithPing setServer(DockerServer server);
    String ping() throws NoDockerServerException, DockerServerException;

    List<DockerImage> getImages() throws NoDockerServerException, DockerServerException;
    List<DockerImageAndCommandSummary> getImageSummaries() throws NoDockerServerException, DockerServerException;
    DockerImage getImage(String imageId) throws NoDockerServerException, NotFoundException;
    void removeImageById(String imageId, Boolean force) throws NotFoundException, NoDockerServerException, DockerServerException;
    List<Command> saveFromImageLabels(String imageName) throws DockerServerException, NotFoundException, NoDockerServerException;

    Command getCommandByImage(String image) throws NotFoundException;
}
