package org.nrg.containers.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.CustomTypeSafeMatcher;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nrg.containers.config.DockerServerEntityTestConfig;
import org.nrg.containers.model.server.docker.Backend;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServerSwarmConstraint;
import org.nrg.containers.model.server.docker.DockerServerEntity;
import org.nrg.containers.model.server.docker.DockerServerEntitySwarmConstraint;
import org.nrg.containers.services.DockerServerEntityService;
import org.nrg.containers.services.DockerServerService;
import org.nrg.containers.utils.TestingUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

@RunWith(SpringJUnit4ClassRunner.class)
@Transactional
@ContextConfiguration(classes = DockerServerEntityTestConfig.class)
public class DockerServerEntityTest {

    @Autowired private ObjectMapper mapper;
    @Autowired private DockerServerService dockerServerService;
    @Autowired private DockerServerEntityService dockerServerEntityService;

    private static final String CONTAINER_HOST = "a host";

    private final static DockerServer STANDALONE = DockerServer.builder()
            .backend(Backend.DOCKER)
            .host(CONTAINER_HOST)
            .name("TestStandalone")
            .build();
    private final static DockerServerEntity STANDALONE_ENTITY = DockerServerEntity.create(STANDALONE);

    private final static DockerServer K8S = DockerServer.builder()
            .backend(Backend.KUBERNETES)
            .host(CONTAINER_HOST)
            .name("TestKubernetes")
            .build();
    private final static DockerServerEntity K8S_ENTITY = DockerServerEntity.create(K8S);

    private final static DockerServer SWARM_NO_CONSTRAINTS = DockerServer.builder()
            .backend(Backend.SWARM)
            .host(CONTAINER_HOST)
            .name("TestSwarmNoConstraints")
            .build();
    private final static DockerServerEntity SWARM_NO_CONSTRAINTS_ENTITY = DockerServerEntity.create(SWARM_NO_CONSTRAINTS);

    private final static DockerServer SWARM_EMPTY_CONSTRAINTS = DockerServer.builder()
            .backend(Backend.SWARM)
            .host(CONTAINER_HOST)
            .name("TestSwarmEmptyConstraints")
            .swarmConstraints(Collections.emptyList())
            .build();
    private final static DockerServerEntity SWARM_EMPTY_CONSTRAINTS_ENTITY = DockerServerEntity.create(SWARM_EMPTY_CONSTRAINTS);

    private final static DockerServerSwarmConstraint NOT_SETTABLE = DockerServerSwarmConstraint.builder()
            .id(0L)
            .attribute("node.role")
            .comparator("==")
            .values(Collections.singletonList("manager"))
            .userSettable(false)
            .build();
    private final static DockerServerSwarmConstraint SETTABLE = DockerServerSwarmConstraint.builder()
            .id(0L)
            .attribute("engine.labels.instance.spot")
            .comparator("==")
            .values(Arrays.asList("True","False"))
            .userSettable(true)
            .build();
    private final static DockerServer SWARM_WITH_CONSTRAINTS = DockerServer.builder()
            .backend(Backend.SWARM)
            .host(CONTAINER_HOST)
            .name("TestSwarmConstraints")
            .swarmConstraints(Arrays.asList(NOT_SETTABLE, SETTABLE))
            .build();
    private final static DockerServerEntity SWARM_WITH_CONSTRAINTS_ENTITY = DockerServerEntity.create(SWARM_WITH_CONSTRAINTS);

    @Test
    public void testSpringConfiguration() {
        assertThat(dockerServerService, not(nullValue()));
    }

    @Test
    public void testSerializeDeserialize() throws Exception {
        assertThat(mapper.readValue(mapper.writeValueAsString(STANDALONE),
                DockerServer.class), is(STANDALONE));
        assertThat(mapper.readValue(mapper.writeValueAsString(K8S),
                DockerServer.class), is(K8S));
        assertThat(mapper.readValue(mapper.writeValueAsString(SWARM_NO_CONSTRAINTS),
                DockerServer.class), is(SWARM_NO_CONSTRAINTS));
        assertThat(mapper.readValue(mapper.writeValueAsString(SWARM_EMPTY_CONSTRAINTS),
                DockerServer.class), is(SWARM_EMPTY_CONSTRAINTS));
        assertThat(mapper.readValue(mapper.writeValueAsString(SWARM_WITH_CONSTRAINTS),
                DockerServer.class), is(SWARM_WITH_CONSTRAINTS));
        assertThat(mapper.readValue(mapper.writeValueAsString(NOT_SETTABLE),
                DockerServerSwarmConstraint.class), is(NOT_SETTABLE));
        assertThat(mapper.readValue(mapper.writeValueAsString(SETTABLE),
                DockerServerSwarmConstraint.class), is(SETTABLE));
    }

    @Test
    @DirtiesContext
    public void testCreateUpdateHibernate() throws Exception {
        for (DockerServerEntity dockerServerEntity :
                Arrays.asList(STANDALONE_ENTITY,
                        K8S_ENTITY,
                        SWARM_NO_CONSTRAINTS_ENTITY,
                        SWARM_EMPTY_CONSTRAINTS_ENTITY)) {
            DockerServerEntity createdEntity = dockerServerEntityService.create(dockerServerEntity);
            TestingUtils.commitTransaction();
            DockerServerEntity retrievedEntity = dockerServerEntityService.retrieve(createdEntity.getId());
            assertThat(retrievedEntity, is(createdEntity));

            retrievedEntity.update(SWARM_WITH_CONSTRAINTS);
            assertThat(retrievedEntity, is(not(createdEntity)));
            dockerServerEntityService.update(retrievedEntity);
            TestingUtils.commitTransaction();
            DockerServerEntity retrievedUpdatedEntity = dockerServerEntityService.retrieve(retrievedEntity.getId());
            assertThat(retrievedEntity, is(retrievedUpdatedEntity));
        }

        DockerServerEntity createdEntity = dockerServerEntityService.create(SWARM_WITH_CONSTRAINTS_ENTITY);
        TestingUtils.commitTransaction();
        DockerServerEntity retrievedEntity = dockerServerEntityService.retrieve(createdEntity.getId());
        assertThat(retrievedEntity, is(createdEntity));

        List<DockerServerEntitySwarmConstraint> constraintList = retrievedEntity.getSwarmConstraints();
        assertThat(constraintList, Matchers.hasSize(2));
        assertThat(constraintList.get(0).getDockerServerEntity(), is(createdEntity));
        assertThat(constraintList.get(1).getDockerServerEntity(), is(createdEntity));

        retrievedEntity.update(STANDALONE);
        assertThat(retrievedEntity, is(not(createdEntity)));
        dockerServerEntityService.update(retrievedEntity);
        TestingUtils.commitTransaction();
        DockerServerEntity retrievedUpdatedEntity = dockerServerEntityService.retrieve(retrievedEntity.getId());
        assertThat(retrievedEntity, is(retrievedUpdatedEntity));
    }

    @Test
    @DirtiesContext
    public void testCreateUpdateServerService() throws Exception {
        for (DockerServer dockerServer :
                Arrays.asList(STANDALONE,
                        K8S,
                        SWARM_NO_CONSTRAINTS,
                        SWARM_EMPTY_CONSTRAINTS)) {
            DockerServer server = dockerServerService.setServer(dockerServer);
            TestingUtils.commitTransaction();
            assertThat(server, isIgnoreId(dockerServer));
            assertThat(dockerServerService.getServer(), is(server));

            DockerServer updatedServer = SWARM_WITH_CONSTRAINTS.toBuilder().id(server.id()).build();
            dockerServerService.update(updatedServer);
            TestingUtils.commitTransaction();
            server = dockerServerService.getServer();
            assertThat(server, isIgnoreId(updatedServer));
        }

        DockerServer server = dockerServerService.setServer(SWARM_WITH_CONSTRAINTS);
        TestingUtils.commitTransaction();
        assertThat(server, isIgnoreId(SWARM_WITH_CONSTRAINTS));
        assertThat(dockerServerService.getServer(), is(server));

        DockerServer updatedServer = STANDALONE.toBuilder().id(server.id()).build();
        dockerServerService.update(updatedServer);
        TestingUtils.commitTransaction();
        server = dockerServerService.getServer();
        assertThat(server, isIgnoreId(updatedServer));
    }

    private Matcher<DockerServer> isIgnoreId(final DockerServer server) {
        final String description = "a DockerServer equal to (other than the ID) one of " + server;
        return new CustomTypeSafeMatcher<DockerServer>(description) {
            @Override
            protected boolean matchesSafely(final DockerServer actual) {
                // Overwrite server id
                DockerServer.Builder actualWithSameIdBuilder =
                        actual.toBuilder().id(server.id());

                // Overwrite swarm constraint IDs (matched between the two lists based on attributes)
                List<DockerServerSwarmConstraint> serverConstraints = server.swarmConstraints();
                List<DockerServerSwarmConstraint> actualConstraints = actual.swarmConstraints();
                if (actualConstraints != null && serverConstraints != null) {
                    // We will want to look up constraints by their attributes
                    Map<String, DockerServerSwarmConstraint> serverConstraintsByAttr =
                            serverConstraints.stream()
                                    .collect(Collectors.toMap(
                                            DockerServerSwarmConstraint::attribute,
                                            Function.identity()));

                    // For each actual constraint, replace its id by the id of the server constraint with the same attribute
                    actualWithSameIdBuilder.swarmConstraints(actualConstraints.stream()
                            .map(c -> {
                                DockerServerSwarmConstraint serverConstraint = serverConstraintsByAttr.get(c.attribute());
                                return serverConstraint == null ? null : c.toBuilder().id(serverConstraint.id()).build();
                            })
                            .collect(Collectors.toList()));
                }

                return server.equals(actualWithSameIdBuilder.build());
            }
        };
    }
}

