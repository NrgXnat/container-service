package org.nrg.containers.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.hamcrest.CustomTypeSafeMatcher;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nrg.containers.config.DockerServerEntityTestConfig;
import org.nrg.containers.model.server.docker.DockerServerBase;
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

import java.util.*;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;

@RunWith(SpringJUnit4ClassRunner.class)
@Transactional
@ContextConfiguration(classes = DockerServerEntityTestConfig.class)
public class DockerServerEntityTest {

    @Autowired private ObjectMapper mapper;
    @Autowired private DockerServerService dockerServerService;
    @Autowired private DockerServerEntityService dockerServerEntityService;

    private String containerHost;
    private String certPath;

    DockerServerBase.DockerServer dockerServerStandalone;
    DockerServerBase.DockerServer dockerServerSwarmNoConstraints;
    DockerServerBase.DockerServer dockerServerSwarmEmptyConstraints;
    DockerServerBase.DockerServer dockerServerSwarmConstraints;
    DockerServerEntity dockerServerStandaloneEntity;
    DockerServerEntity dockerServerSwarmNoConstraintsEntity;
    DockerServerEntity dockerServerSwarmEmptyConstraintsEntity;
    DockerServerEntity dockerServerSwarmConstraintsEntity;

    DockerServerBase.DockerServerSwarmConstraint constraintNotSettable;
    DockerServerBase.DockerServerSwarmConstraint constraintSettable;

    DockerServerEntitySwarmConstraint constraintNotSettableEntity;
    DockerServerEntitySwarmConstraint constraintSettableEntity;

    @Before
    public void setup() throws Exception {
        // Setup docker server
        final String defaultHost = "unix:///var/run/docker.sock";
        final String hostEnv = System.getenv("DOCKER_HOST");
        final String certPathEnv = System.getenv("DOCKER_CERT_PATH");
        final String tlsVerify = System.getenv("DOCKER_TLS_VERIFY");

        final boolean useTls = tlsVerify != null && tlsVerify.equals("1");
        if (useTls) {
            if (StringUtils.isBlank(certPathEnv)) {
                throw new Exception("Must set DOCKER_CERT_PATH if DOCKER_TLS_VERIFY=1.");
            }
            certPath = certPathEnv;
        } else {
            certPath = null;
        }

        if (StringUtils.isBlank(hostEnv)) {
            containerHost = defaultHost;
        } else {
            final Pattern tcpShouldBeHttpRe = Pattern.compile("tcp://.*");
            final java.util.regex.Matcher tcpShouldBeHttpMatch = tcpShouldBeHttpRe.matcher(hostEnv);
            if (tcpShouldBeHttpMatch.matches()) {
                // Must switch out tcp:// for either http:// or https://
                containerHost = hostEnv.replace("tcp://", "http" + (useTls ? "s" : "") + "://");
            } else {
                containerHost = hostEnv;
            }
        }

        dockerServerStandalone = DockerServerBase.DockerServer.builder()
                .id(0L)
                .lastEventCheckTime(new Date())
                .swarmMode(false)
                .host(containerHost)
                .certPath(certPath)
                .name("TestStandalone")
                .pullImagesOnXnatInit(false)
                .swarmConstraints(Collections.<DockerServerBase.DockerServerSwarmConstraint>emptyList())
                .build();
        dockerServerStandaloneEntity = DockerServerEntity.create(dockerServerStandalone);

        dockerServerSwarmNoConstraints = DockerServerBase.DockerServer.builder()
                .id(0L)
                .lastEventCheckTime(new Date())
                .swarmMode(true)
                .host(containerHost)
                .certPath(certPath)
                .name("TestSwarmNoConstraints")
                .pullImagesOnXnatInit(false)
                .swarmConstraints(Collections.<DockerServerBase.DockerServerSwarmConstraint>emptyList())
                .build();
        dockerServerSwarmNoConstraintsEntity = DockerServerEntity.create(dockerServerSwarmNoConstraints);

        dockerServerSwarmEmptyConstraints = DockerServerBase.DockerServer.builder()
                .id(0L)
                .lastEventCheckTime(new Date())
                .swarmMode(true)
                .host(containerHost)
                .certPath(certPath)
                .name("TestSwarmEmptyConstraints")
                .pullImagesOnXnatInit(false)
                .swarmConstraints(Collections.<DockerServerBase.DockerServerSwarmConstraint>emptyList())
                .build();
        dockerServerSwarmEmptyConstraintsEntity = DockerServerEntity.create(dockerServerSwarmEmptyConstraints);

        constraintNotSettable = DockerServerBase.DockerServerSwarmConstraint.builder()
                .id(0L)
                .attribute("node.role")
                .comparator("==")
                .values(Collections.singletonList("manager"))
                .userSettable(false)
                .build();
        constraintNotSettableEntity = DockerServerEntitySwarmConstraint.fromPojo(constraintNotSettable);

        constraintSettable = DockerServerBase.DockerServerSwarmConstraint.builder()
                .id(0L)
                .attribute("engine.labels.instance.spot")
                .comparator("==")
                .values(Arrays.asList("True","False"))
                .userSettable(true)
                .build();
        constraintSettableEntity = DockerServerEntitySwarmConstraint.fromPojo(constraintSettable);

        dockerServerSwarmConstraints = DockerServerBase.DockerServer.builder()
                .id(0L)
                .lastEventCheckTime(new Date())
                .swarmMode(true)
                .host(containerHost)
                .certPath(certPath)
                .name("TestSwarmConstraints")
                .pullImagesOnXnatInit(false)
                .swarmConstraints(Arrays.asList(constraintNotSettable, constraintSettable))
                .build();
        dockerServerSwarmConstraintsEntity = DockerServerEntity.create(dockerServerSwarmConstraints);

    }

    @Test
    public void testSpringConfiguration() {
        assertThat(dockerServerService, not(nullValue()));
    }

    @Test
    public void testSerializeDeserialize() throws Exception {
        assertThat(mapper.readValue(mapper.writeValueAsString(dockerServerStandalone),
                DockerServerBase.DockerServer.class), is(dockerServerStandalone));
        assertThat(mapper.readValue(mapper.writeValueAsString(dockerServerSwarmNoConstraints),
                DockerServerBase.DockerServer.class), is(dockerServerSwarmNoConstraints));
        assertThat(mapper.readValue(mapper.writeValueAsString(dockerServerSwarmEmptyConstraints),
                DockerServerBase.DockerServer.class), is(dockerServerSwarmEmptyConstraints));
        assertThat(mapper.readValue(mapper.writeValueAsString(dockerServerSwarmConstraints),
                DockerServerBase.DockerServer.class), is(dockerServerSwarmConstraints));
        assertThat(mapper.readValue(mapper.writeValueAsString(constraintNotSettable),
                DockerServerBase.DockerServerSwarmConstraint.class), is(constraintNotSettable));
        assertThat(mapper.readValue(mapper.writeValueAsString(constraintSettable),
                DockerServerBase.DockerServerSwarmConstraint.class), is(constraintSettable));
    }

    @Test
    @DirtiesContext
    public void testCreateUpdateHibernate() throws Exception {
        for (DockerServerEntity dockerServerEntity :
                Arrays.asList(dockerServerStandaloneEntity,
                        dockerServerSwarmNoConstraintsEntity,
                        dockerServerSwarmEmptyConstraintsEntity)) {
            DockerServerEntity createdEntity = dockerServerEntityService.create(dockerServerEntity);
            TestingUtils.commitTransaction();
            DockerServerEntity retrievedEntity = dockerServerEntityService.retrieve(createdEntity.getId());
            assertThat(retrievedEntity, is(createdEntity));

            retrievedEntity.update(dockerServerSwarmConstraints);
            assertThat(retrievedEntity, is(not(createdEntity)));
            dockerServerEntityService.update(retrievedEntity);
            TestingUtils.commitTransaction();
            DockerServerEntity retrievedUpdatedEntity = dockerServerEntityService.retrieve(retrievedEntity.getId());
            assertThat(retrievedEntity, is(retrievedUpdatedEntity));
        }

        DockerServerEntity createdEntity = dockerServerEntityService.create(dockerServerSwarmConstraintsEntity);
        TestingUtils.commitTransaction();
        DockerServerEntity retrievedEntity = dockerServerEntityService.retrieve(createdEntity.getId());
        assertThat(retrievedEntity, is(createdEntity));

        List<DockerServerEntitySwarmConstraint> constraintList = retrievedEntity.getSwarmConstraints();
        assertThat(constraintList, Matchers.<DockerServerEntitySwarmConstraint>hasSize(2));
        assertThat(constraintList.get(0).getDockerServerEntity(), is(createdEntity));
        assertThat(constraintList.get(1).getDockerServerEntity(), is(createdEntity));

        retrievedEntity.update(dockerServerStandalone);
        assertThat(retrievedEntity, is(not(createdEntity)));
        dockerServerEntityService.update(retrievedEntity);
        TestingUtils.commitTransaction();
        DockerServerEntity retrievedUpdatedEntity = dockerServerEntityService.retrieve(retrievedEntity.getId());
        assertThat(retrievedEntity, is(retrievedUpdatedEntity));
    }

    @Test
    @DirtiesContext
    public void testCreateUpdateServerService() throws Exception {
        for (DockerServerBase.DockerServer dockerServer :
                Arrays.asList(dockerServerStandalone,
                        dockerServerSwarmNoConstraints,
                        dockerServerSwarmEmptyConstraints)) {
            DockerServerBase.DockerServer server = dockerServerService.setServer(dockerServer);
            TestingUtils.commitTransaction();
            assertThat(server, isIgnoreId(dockerServer));
            assertThat(dockerServerService.getServer(), is(server));

            DockerServerBase.DockerServer updatedServer = dockerServerSwarmConstraints.toBuilder().id(server.id()).build();
            dockerServerService.update(updatedServer);
            TestingUtils.commitTransaction();
            server = dockerServerService.getServer();
            assertThat(server, isIgnoreId(updatedServer));
        }

        DockerServerBase.DockerServer server = dockerServerService.setServer(dockerServerSwarmConstraints);
        TestingUtils.commitTransaction();
        assertThat(server, isIgnoreId(dockerServerSwarmConstraints));
        assertThat(dockerServerService.getServer(), is(server));

        DockerServerBase.DockerServer updatedServer = dockerServerStandalone.toBuilder().id(server.id()).build();
        dockerServerService.update(updatedServer);
        TestingUtils.commitTransaction();
        server = dockerServerService.getServer();
        assertThat(server, isIgnoreId(updatedServer));
    }

    private Matcher<DockerServerBase.DockerServer> isIgnoreId(final DockerServerBase.DockerServer server) {
        final String description = "a DockerServer equal to (other than the ID) one of " + server;
        return new CustomTypeSafeMatcher<DockerServerBase.DockerServer>(description) {
            @Override
            protected boolean matchesSafely(final DockerServerBase.DockerServer actual) {
                DockerServerBase.DockerServer actualWithSameId =
                        actual.toBuilder().id(server.id()).build();
                if (actualWithSameId.swarmConstraints() != null && server.swarmConstraints() != null) {
                    Map<String, DockerServerBase.DockerServerSwarmConstraint> constrMap = new HashMap<>();
                    for (final DockerServerBase.DockerServerSwarmConstraint constraint : server.swarmConstraints()) {
                        constrMap.put(constraint.attribute(), constraint);
                    }
                    List<DockerServerBase.DockerServerSwarmConstraint> constrSharedId = new ArrayList<>();
                    for (final DockerServerBase.DockerServerSwarmConstraint constraint : actualWithSameId.swarmConstraints()) {
                        String attr = constraint.attribute();
                        if (!constrMap.containsKey(attr)) {
                            return false;
                        }
                        constrSharedId.add(constraint.toBuilder().id(constrMap.get(attr).id()).build());
                    }
                    actualWithSameId = actualWithSameId.toBuilder().swarmConstraints(constrSharedId).build();
                }

                return server.equals(actualWithSameId);
            }
        };
    }
}

