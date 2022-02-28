package org.nrg.containers.model;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.nrg.containers.config.DockerHubEntityTestConfig;
import org.nrg.containers.model.dockerhub.DockerHubBase;
import org.nrg.containers.model.dockerhub.DockerHubEntity;
import org.nrg.containers.services.DockerHubService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;

@RunWith(SpringJUnit4ClassRunner.class)
@Transactional
@ContextConfiguration(classes = DockerHubEntityTestConfig.class)
public class DockerHubEntityTest {

    @Autowired private DockerHubService dockerHubService;

    @Test
    public void testSpringConfiguration() {
        assertThat(dockerHubService, not(nullValue()));
    }

    @Test
    @DirtiesContext
    public void testCreateDockerHub() throws Exception {
        final DockerHubBase.DockerHub hubToCreate = DockerHubBase.DockerHub.create(
                0L, "a hub name", "http://localhost", Boolean.FALSE
        );
        final DockerHubBase.DockerHub created = dockerHubService.create(hubToCreate);
        assertThat(created.id(), is(not(0L)));
        assertThat(created.name(), is(hubToCreate.name()));
        assertThat(created.url(), is(hubToCreate.url()));
        assertThat(created.isDefault(), is(hubToCreate.isDefault()));

        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        final DockerHubEntity createdEntity = dockerHubService.retrieve(created.id());
        assertThat(createdEntity.getId(), is(created.id()));
        assertThat(createdEntity.getName(), is(created.name()));
        assertThat(createdEntity.getUrl(), is(created.url()));
    }

    @Test
    @DirtiesContext
    public void testUpdateDockerHubEntity() throws Exception {
        final DockerHubBase.DockerHub hubToCreate = DockerHubBase.DockerHub.create(
                0L, "a hub entity name", "http://localhost", Boolean.FALSE
        );
        final DockerHubEntity hubEntityToCreate = DockerHubEntity.fromPojo(hubToCreate);
        final DockerHubEntity created = dockerHubService.create(hubEntityToCreate);

        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        final DockerHubBase.DockerHub hubToUpdate = DockerHubBase.DockerHub.create(
                created.getId(),
                "some other hub entity name",
                created.getUrl(),
                hubToCreate.isDefault()
        );
        final DockerHubEntity hubEntityToUpdate = DockerHubEntity.fromPojo(hubToUpdate);

        dockerHubService.update(hubEntityToUpdate);

        final DockerHubEntity retrieved = dockerHubService.retrieve(created.getId());
        assertThat(retrieved, is(hubEntityToUpdate));
    }

    @Test
    @DirtiesContext
    public void testUpdateDockerHubPojo() throws Exception {
        final DockerHubBase.DockerHub hubToCreate = DockerHubBase.DockerHub.create(
                0L, "a hub pojo name", "http://localhost", Boolean.FALSE
        );
        final DockerHubBase.DockerHub created = dockerHubService.create(hubToCreate);

        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        final DockerHubBase.DockerHub hubToUpdate = DockerHubBase.DockerHub.create(
                created.id(),
                "some other hub entity name",
                created.url(),
                created.isDefault()
        );
        dockerHubService.update(hubToUpdate);

        final DockerHubBase.DockerHub updated = dockerHubService.retrieveHub(created.id());
        assertThat(updated, is(hubToUpdate));
    }
}
