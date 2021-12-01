package org.nrg.containers.daos;

import org.nrg.containers.exceptions.NotUniqueException;
import org.nrg.containers.model.dockerhub.DockerHubEntity;
import org.nrg.framework.orm.hibernate.AbstractHibernateDAO;
import org.springframework.stereotype.Repository;

@Repository
public class DockerHubDao extends AbstractHibernateDAO<DockerHubEntity> {
    public DockerHubEntity findByName(final String name) throws NotUniqueException {
        try {
            return findByUniqueProperty("name", name);
        } catch (RuntimeException e) {
            throw new NotUniqueException("More than one result with name " + name + ".");
        }
    }

    public DockerHubEntity findByUrl(final String url) throws NotUniqueException {
        try {
            return findByUniqueProperty("url", url);
        } catch (RuntimeException e) {
            throw new NotUniqueException("More than one result with url " + url + ".");
        }
    }
}
