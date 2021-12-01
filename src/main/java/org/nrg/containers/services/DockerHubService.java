package org.nrg.containers.services;

import org.nrg.containers.exceptions.NotUniqueException;
import org.nrg.containers.model.dockerhub.DockerHubBase.DockerHub;
import org.nrg.containers.model.dockerhub.DockerHubEntity;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.framework.orm.hibernate.BaseHibernateService;

import java.util.List;

public interface DockerHubService extends BaseHibernateService<DockerHubEntity> {
    DockerHubEntity retrieve(String name) throws NotUniqueException;
    DockerHubEntity get(String name) throws NotUniqueException, NotFoundException;
    DockerHubEntity getByUrl(final String url) throws NotUniqueException, NotFoundException;
    DockerHub retrieveHub(long id);
    DockerHub retrieveHub(String name) throws NotUniqueException;
    DockerHub getHub(long hubId) throws NotFoundException;
    DockerHub getHub(String hubName) throws NotFoundException, NotUniqueException;
    List<DockerHub> getHubs();

    void setDefault(DockerHub dockerHub, String username, String reason);
    DockerHub create(DockerHub dockerHub);
    DockerHubEntity createAndSetDefault(DockerHubEntity dockerHubEntity, String username, String reason);
    DockerHub createAndSetDefault(DockerHub dockerHub, String username, String reason);
    void update(DockerHub dockerHub);
    void updateAndSetDefault(DockerHubEntity dockerHubEntity, String username, String reason);
    void updateAndSetDefault(DockerHub dockerHub, String username, String reason);
    void setDefault(long id, String username, String reason);
    void delete(long id) throws DockerHubDeleteDefaultException;
    void delete(String name) throws DockerHubDeleteDefaultException, NotUniqueException;

    void delete(DockerHubEntity entity) throws DockerHubDeleteDefaultException;

    long getDefaultHubId();
    DockerHub getDefault();

    DockerHub getHubForImage(String imageName);

    class DockerHubDeleteDefaultException extends RuntimeException {
        public DockerHubDeleteDefaultException(final String message) {
            super(message);
        }
    }
}
