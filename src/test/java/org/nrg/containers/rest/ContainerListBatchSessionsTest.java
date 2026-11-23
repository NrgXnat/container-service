package org.nrg.containers.rest;

import com.google.common.collect.ImmutableMap;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nrg.containers.config.ContainerRestApiTestConfig;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.entity.ContainerEntity;
import org.nrg.containers.model.server.docker.Backend;
import org.nrg.containers.services.ContainerEntityService;
import org.nrg.containers.services.ContainerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.web.WebAppConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;

/**
 * A list read in batches holds one batch in memory only if each batch is read in its own session, so the containers
 * of earlier batches can be collected. This test is not {@code @Transactional}, unlike the others here: a test
 * transaction would keep one session open across every batch, as a transactional caller or an open-session-in-view
 * filter would, and the containers have to be built from entities whose session has closed.
 * <p>
 * The context is closed afterwards rather than cached: the permission checks in the list endpoints reach Spring
 * through XNAT's static {@code ContextService}, which the contexts of the test classes that run in between repoint.
 */
@RunWith(SpringJUnit4ClassRunner.class)
@WebAppConfiguration
@DirtiesContext
@ContextConfiguration(classes = ContainerRestApiTestConfig.class)
public class ContainerListBatchSessionsTest {
    private static final String PROJECT = "batch-sessions";
    private static final int CONTAINERS = 2 * ContainerEntity.BATCH_SIZE + 17;

    @Autowired private ContainerService containerService;
    @Autowired private ContainerEntityService containerEntityService;
    @Autowired private SessionFactory sessionFactory;

    private final List<Long> created = new ArrayList<>();

    @Before
    public void setup() {
        for (int i = 0; i < CONTAINERS; i++) {
            created.add(containerEntityService.create(ContainerEntity.fromPojo(container(i))).getId());
        }
    }

    @After
    public void cleanup() {
        // These rows are committed, and other tests share this context's database.
        created.forEach(containerEntityService::delete);
    }

    @Test
    public void testEachBatchIsReadInItsOwnSession() {
        final Statistics statistics = sessionFactory.getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            final List<Container> listed = new ArrayList<>();
            containerService.getAllInBatches(PROJECT).forEach(listed::add);

            assertThat(statistics.getSessionOpenCount(),
                       greaterThanOrEqualTo((long) (CONTAINERS + ContainerEntity.BATCH_SIZE - 1) / ContainerEntity.BATCH_SIZE));
            final List<Long> ids = new ArrayList<>();
            listed.forEach(container -> ids.add(container.databaseId()));
            assertThat(ids, is(created));
            // Built after its session closed, each container still has its collections.
            assertThat(listed.get(CONTAINERS - 1).history(), hasSize(1));
            assertThat(listed.get(CONTAINERS - 1).environmentVariables(), hasEntry("RUN", String.valueOf(CONTAINERS - 1)));
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    private static Container container(final int index) {
        return Container.builder()
                        .commandId(1)
                        .wrapperId(1)
                        .userId("admin")
                        .project(PROJECT)
                        .backend(Backend.DOCKER)
                        .containerId("batch-session-" + index)
                        .dockerImage("busybox:latest")
                        .commandLine("true")
                        .status("Complete")
                        .environmentVariables(ImmutableMap.of("RUN", String.valueOf(index)))
                        .history(Collections.singletonList(Container.ContainerHistory.builder()
                                                                                     .status("Complete")
                                                                                     .entityType("event")
                                                                                     .timeRecorded(new Date())
                                                                                     .build()))
                        .build();
    }
}
