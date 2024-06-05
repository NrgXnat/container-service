package org.nrg.containers.services.impl;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.nrg.containers.daos.DockerHubDao;
import org.nrg.containers.exceptions.NotUniqueException;
import org.nrg.containers.model.dockerhub.DockerHubBase.DockerHub;
import org.nrg.containers.model.dockerhub.DockerHubEntity;
import org.nrg.containers.services.ContainerConfigService;
import org.nrg.containers.services.DockerHubService;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.framework.orm.hibernate.AbstractHibernateEntityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Nullable;
import java.util.List;

@Service
@Transactional
@Slf4j
public class HibernateDockerHubService
        extends AbstractHibernateEntityService<DockerHubEntity, DockerHubDao>
        implements DockerHubService {

    private final ContainerConfigService containerConfigService;

    @Autowired
    public HibernateDockerHubService(final ContainerConfigService containerConfigService) {
        this.containerConfigService = containerConfigService;
    }

    @Override
    public DockerHubEntity retrieve(final String name) throws NotUniqueException {
        final DockerHubEntity dockerHubEntity = getDao().findByName(name);
        if (dockerHubEntity == null) {
            try {
                // We didn't find it by name. If the name is a number, maybe they meant to find it by ID?
                final Long id = Long.valueOf(name);

                // If we got to this line, then the name is a proper ID and we should give them that hub.
                return retrieve(id);
            } catch (NumberFormatException ignored) {
                // Nope, name is not an id
            }
        }

        // If we got here, we should return this thing, null or not
        return dockerHubEntity;
    }

    @Override
    public DockerHubEntity get(final String name) throws NotUniqueException, NotFoundException {
        final DockerHubEntity dockerHubEntity = retrieve(name);
        if (dockerHubEntity == null) {
            throw new NotFoundException("Could not find hub with name " + name);
        }
        return dockerHubEntity;
    }

    @Override
    public DockerHub getByUrl(final String url) {
        try {
            DockerHubEntity entity = getDao().findByUrl(url);
            if (entity == null && url.startsWith("https")) {
                // try with http (org.mandas.docker.client.ImageRef always returns https)
                entity = getDao().findByUrl(url.replace("https", "http"));
            }
            return toPojo(entity, getDefaultHubId());
        } catch (NotUniqueException e) {
            log.error("Found multiple DockerHubs with url {}", url);
            return null;
        }
    }

    @Override
    public DockerHub retrieveHub(final long id) {
        return toPojo(retrieve(id), getDefaultHubId());
    }

    @Override
    public DockerHub retrieveHub(final String name) throws NotUniqueException {
        return toPojo(retrieve(name), getDefaultHubId());
    }

    @Override
    public DockerHub getHub(final long id) throws NotFoundException {
        try {
            return toPojo(get(id), getDefaultHubId());
        } catch (org.nrg.framework.exceptions.NotFoundException e) {
            // TODO remove this when I toPojo to use the framework "get" - XNAT-4682
            throw new NotFoundException(e);
        }
    }

    @Override
    public DockerHub getHub(final String name) throws NotFoundException, NotUniqueException {
        return toPojo(get(name), getDefaultHubId());
    }

    @Override
    public List<DockerHub> getHubs() {
        return toPojo(getAll(), getDefaultHubId());
    }

    @Override
    public DockerHub create(final DockerHub dockerHub) {
        return toPojo(create(fromPojo(dockerHub)), getDefaultHubId());
    }

    @Override
    public DockerHubEntity createAndSetDefault(final DockerHubEntity dockerHubEntity, final String username, final String reason) {
        final DockerHubEntity created = create(dockerHubEntity);
        setDefault(created.getId(), username, reason);
        return created;
    }

    @Override
    public DockerHub createAndSetDefault(final DockerHub dockerHub, final String username, final String reason) {
        final DockerHubEntity created = createAndSetDefault(fromPojo(dockerHub), username, reason);
        return toPojo(created, created.getId());
    }

    @Override
    public void update(final DockerHub dockerHub) {
        update(fromPojoWithTemplate(dockerHub));
    }

    @Override
    public void updateAndSetDefault(final DockerHubEntity dockerHubEntity, final String username, final String reason) {
        update(dockerHubEntity);
        setDefault(dockerHubEntity.getId(), username, reason);
    }

    @Override
    public void updateAndSetDefault(final DockerHub dockerHub, final String username, final String reason) {
        updateAndSetDefault(fromPojoWithTemplate(dockerHub), username, reason);
    }

    @Override
    public long getDefaultHubId() {
        return containerConfigService.getDefaultDockerHubId();
    }

    @Override
    public DockerHub getDefault() {
        return retrieveHub(getDefaultHubId());
    }

    @Override
    public void setDefault(final DockerHub dockerHub, final String username, final String reason) {
        setDefault(dockerHub.id(), username, reason);
    }

    @Override
    public void setDefault(final long id, final String username, final String reason) {
        containerConfigService.setDefaultDockerHubId(id, username, reason);
    }

    @Override
    public void delete(final long id) throws DockerHubDeleteDefaultException {
        delete(retrieve(id));
    }

    @Override
    public void delete(final String name) throws DockerHubDeleteDefaultException, NotUniqueException {
        delete(retrieve(name));
    }

    @Override
    public void delete(final DockerHubEntity entity) throws DockerHubDeleteDefaultException {
        if (isDefault(entity.getId())) {
            throw new DockerHubDeleteDefaultException("Cannot delete default docker hub. Make another hub default first.");
        }
        super.delete(entity);
    }

    private DockerHub toPojo(final DockerHubEntity dockerHubEntity, final long defaultId) {
        return dockerHubEntity == null ? null : dockerHubEntity.toPojo(defaultId);
    }

    private List<DockerHub> toPojo(final List<DockerHubEntity> dockerHubEntityList, final long defaultId) {
        return dockerHubEntityList == null ? null :
                Lists.transform(dockerHubEntityList, new Function<DockerHubEntity, DockerHub>() {
                    @Nullable
                    @Override
                    public DockerHub apply(@Nullable final DockerHubEntity dockerHubEntity) {
                        return toPojo(dockerHubEntity, defaultId);
                    }
                });
    }

    private DockerHubEntity fromPojo(final DockerHub dockerHub) {
        return dockerHub == null ? null : DockerHubEntity.fromPojo(dockerHub);
    }


    private DockerHubEntity fromPojoWithTemplate(final DockerHub dockerHub) {
        if (dockerHub == null) {
            return null;
        }

        return DockerHubEntity.fromPojoWithTemplate(dockerHub, retrieve(dockerHub.id()));
    }

    private boolean isDefault(final long id) {
        return containerConfigService.getDefaultDockerHubId() == id;
    }

}
