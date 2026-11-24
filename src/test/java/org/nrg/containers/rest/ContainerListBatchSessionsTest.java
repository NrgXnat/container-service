package org.nrg.containers.rest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.nrg.xdat.security.services.RoleServiceI;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xft.security.UserI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A list read in batches holds one batch in memory only if each batch is read in its own session, so the containers
 * of earlier batches can be collected. This test is not {@code @Transactional}, unlike the others here: a test
 * transaction would keep one session open across every batch, as a transactional caller or an open-session-in-view
 * filter would, and the containers have to be built from entities whose session has closed. The endpoints are
 * checked through MockMvc as well as the service, so a list endpoint pointed back at {@code getAll} fails here.
 * <p>
 * The context is closed afterwards rather than cached: the permission checks in the list endpoints reach Spring
 * through XNAT's static {@code ContextService}, which the contexts of the test classes that run in between repoint.
 */
@RunWith(SpringJUnit4ClassRunner.class)
@WebAppConfiguration
@DirtiesContext
@ContextConfiguration(classes = ContainerRestApiTestConfig.class)
public class ContainerListBatchSessionsTest {
    private static final String ADMIN = "admin";
    private static final String PROJECT = "batch-sessions";
    private static final int CONTAINERS = 2 * ContainerEntity.BATCH_SIZE + 17;

    @Autowired private WebApplicationContext wac;
    @Autowired private ObjectMapper mapper;
    @Autowired private ContainerService containerService;
    @Autowired private ContainerEntityService containerEntityService;
    @Autowired private SessionFactory sessionFactory;
    @Autowired private RoleServiceI mockRoleService;
    @Autowired private UserManagementServiceI mockUserManagementServiceI;

    private final List<Long> created = new ArrayList<>();
    private Authentication adminAuth;
    private MockMvc mockMvc;

    @Before
    public void setup() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();

        final UserI admin = mock(UserI.class);
        when(admin.getLogin()).thenReturn(ADMIN);
        when(admin.getPassword()).thenReturn("admin-pass");
        when(mockRoleService.isSiteAdmin(admin)).thenReturn(true);
        when(mockUserManagementServiceI.getUser(ADMIN)).thenReturn(admin);
        adminAuth = new TestingAuthenticationToken(admin, "admin-pass");

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
    public void testEachBatchIsReadInItsOwnSession() throws Exception {
        final List<Container> listed = new ArrayList<>();
        final long sessions = sessionsOpenedBy(() -> containerService.getAllInBatches(PROJECT).forEach(listed::add));

        assertThat(sessions, greaterThanOrEqualTo(batches(CONTAINERS)));
        assertThat(idsOf(listed), is(created));
        // Built after its session closed, each container still has its collections.
        assertThat(listed.get(CONTAINERS - 1).history(), hasSize(1));
        assertThat(listed.get(CONTAINERS - 1).environmentVariables(), hasEntry("RUN", String.valueOf(CONTAINERS - 1)));
    }

    @Test
    public void testProjectListEndpointReadsEachBatchInItsOwnSession() throws Exception {
        final List<Container> listed = new ArrayList<>();
        final long sessions = sessionsOpenedBy(() -> listed.addAll(list("/projects/" + PROJECT + "/containers")));

        assertThat(sessions, greaterThanOrEqualTo(batches(CONTAINERS)));
        assertThat(idsOf(listed), is(created));
    }

    @Test
    public void testSiteListEndpointReadsEachBatchInItsOwnSession() throws Exception {
        final List<Container> listed = new ArrayList<>();
        final long sessions = sessionsOpenedBy(() -> listed.addAll(list("/containers")));

        // The site list also holds any containers the other tests in this context's database committed.
        assertThat(sessions, greaterThanOrEqualTo(batches(listed.size())));
        assertThat(idsOf(listed), hasItems(created.toArray(new Long[0])));
    }

    /**
     * The number of Hibernate sessions opened while running the given work.
     */
    private long sessionsOpenedBy(final Work work) throws Exception {
        final Statistics statistics = sessionFactory.getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            work.run();
            return statistics.getSessionOpenCount();
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    private interface Work {
        void run() throws Exception;
    }

    private List<Container> list(final String path) throws Exception {
        final String response = mockMvc.perform(get(path)
                                                        .with(authentication(adminAuth))
                                                        .with(csrf())
                                                        .with(testSecurityContext()))
                                       .andExpect(status().isOk())
                                       .andReturn()
                                       .getResponse()
                                       .getContentAsString();
        return mapper.readValue(response, new TypeReference<List<Container>>() {});
    }

    private static long batches(final int containers) {
        return (containers + ContainerEntity.BATCH_SIZE - 1) / ContainerEntity.BATCH_SIZE;
    }

    private static List<Long> idsOf(final List<Container> containers) {
        return containers.stream().map(Container::databaseId).collect(Collectors.toList());
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
