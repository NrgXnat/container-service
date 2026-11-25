package org.nrg.containers.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nrg.containers.config.ContainerRestApiTestConfig;
import org.nrg.containers.model.container.ContainerInputType;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.entity.ContainerEntity;
import org.nrg.containers.model.container.entity.ContainerEntityMount;
import org.nrg.containers.model.container.entity.ContainerEntityOutput;
import org.nrg.containers.model.server.docker.Backend;
import org.nrg.containers.services.ContainerEntityService;
import org.nrg.containers.services.ContainerService;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xdat.security.services.RoleServiceI;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xft.security.UserI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.nrg.containers.services.ContainerService.XNAT_PASS;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The full container lists ({@code GET /containers} and {@code GET /projects/{project}/containers} without
 * {@code nonfinalized=true}) are read a batch at a time. These tests check that the result is the same as loading each
 * container on its own, the way the lists were read before, across several batches and with every collection a
 * container serializes.
 */
@RunWith(SpringJUnit4ClassRunner.class)
@WebAppConfiguration
@Transactional
@ContextConfiguration(classes = ContainerRestApiTestConfig.class)
public class ContainerListBatchingTest {
    private static final String ADMIN = "admin";
    private static final String PROJECT = "batched";
    private static final String OTHER_PROJECT = "other";

    // Enough containers for two full batches and a partial one site-wide, and a full and a partial one in PROJECT.
    private static final int IN_PROJECT = ContainerEntity.BATCH_SIZE + 13;
    private static final int IN_OTHER_PROJECT = ContainerEntity.BATCH_SIZE + 37;

    private Authentication adminAuth;
    private MockMvc mockMvc;

    @Autowired private WebApplicationContext wac;
    @Autowired private ObjectMapper mapper;
    @Autowired private ContainerService containerService;
    @Autowired private ContainerEntityService containerEntityService;
    @Autowired private SessionFactory sessionFactory;
    @Autowired private RoleServiceI mockRoleService;
    @Autowired private UserManagementServiceI mockUserManagementServiceI;

    @Before
    public void setup() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();

        final UserI admin = mock(UserI.class);
        when(admin.getLogin()).thenReturn(ADMIN);
        when(admin.getPassword()).thenReturn("admin-pass");
        when(mockRoleService.isSiteAdmin(admin)).thenReturn(true);
        when(mockUserManagementServiceI.getUser(ADMIN)).thenReturn(admin);
        adminAuth = new TestingAuthenticationToken(admin, "admin-pass");

        // Interleave the projects so each batch holds containers from both.
        int project = 0;
        int other = 0;
        while (project < IN_PROJECT || other < IN_OTHER_PROJECT) {
            if (project < IN_PROJECT) {
                containerEntityService.create(ContainerEntity.fromPojo(container(PROJECT, project++)));
            }
            if (other < IN_OTHER_PROJECT) {
                containerEntityService.create(ContainerEntity.fromPojo(container(OTHER_PROJECT, other++)));
            }
        }
        // The site-wide list leaves out disabled containers; a project's list does not.
        final ContainerEntity disabled = ContainerEntity.fromPojo(container(PROJECT, IN_PROJECT));
        disabled.setEnabled(false);
        containerEntityService.create(disabled);
        // And one launched outside any project.
        containerEntityService.create(ContainerEntity.fromPojo(container(null, 0)));

        // Make every read below go to the database.
        sessionFactory.getCurrentSession().flush();
        sessionFactory.getCurrentSession().clear();
    }

    @Test
    public void testSiteListMatchesContainersLoadedOneAtATime() throws Exception {
        final List<Long> ids = idsOf(containerService.getAll());
        assertThat(ids.size(), greaterThan(2 * ContainerEntity.BATCH_SIZE));

        final String listed = list("/containers");

        assertThat(listed, is(mapper.writeValueAsString(loadOneAtATime(ids))));
    }

    @Test
    public void testProjectListMatchesContainersLoadedOneAtATime() throws Exception {
        final List<Long> ids = idsOf(containerService.getAll(PROJECT));
        assertThat(ids, hasSize(IN_PROJECT + 1)); // includes the disabled one

        final String listed = list("/projects/" + PROJECT + "/containers");

        assertThat(listed, is(mapper.writeValueAsString(loadOneAtATime(ids))));
    }

    @Test
    public void testListLoadsCollectionsABatchAtATime() throws Exception {
        final int containers = containerService.getAll().size();
        sessionFactory.getCurrentSession().clear();

        final Statistics statistics = sessionFactory.getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            list("/containers");

            // The lists load for a batch of containers at a time: a query per batch, or a few for a partial batch,
            // which Hibernate splits into its fixed batch sizes. Each container has two mounts.
            final long atMost = containers / 10;
            for (final String collection : Arrays.asList("history", "inputs", "outputs", "mounts", "logPaths", "swarmConstraints")) {
                assertThat(collection, fetches(statistics, ContainerEntity.class, collection), lessThanOrEqualTo(atMost));
            }
            assertThat(fetches(statistics, ContainerEntityOutput.class, "tags"), lessThanOrEqualTo(atMost));
            assertThat(fetches(statistics, ContainerEntityMount.class, "inputFiles"), lessThanOrEqualTo(2 * atMost));

            // The maps load one container at a time, so their keys keep the order a single container's query gives.
            for (final String map : Arrays.asList("environmentVariables", "ports", "containerLabels")) {
                assertThat(map, fetches(statistics, ContainerEntity.class, map), is((long) containers));
            }
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    private static long fetches(final Statistics statistics, final Class<?> owner, final String collection) {
        return statistics.getCollectionStatistics(owner.getName() + "." + collection).getFetchCount();
    }

    @Test
    public void testGetByNameFindsAContainerInALaterBatch() {
        final String name = containerName(OTHER_PROJECT, IN_OTHER_PROJECT - 1);

        final Container found = containerService.getByName(name, false);
        assertThat(found, not(nullValue()));
        assertThat(found.containerName(), is(name));
        assertThat(found.project(), is(OTHER_PROJECT));

        final Container inProject = containerService.getByName(OTHER_PROJECT, name, false);
        assertThat(inProject, not(nullValue()));
        assertThat(inProject.databaseId(), is(found.databaseId()));

        assertThat(containerService.getByName(PROJECT, name, false), nullValue());
        assertThat(containerService.getByName("no-such-container", false), nullValue());

        // A null project means containers without one, as getAll(String) has it.
        assertThat(containerService.getByName(null, containerName(null, 0), false), not(nullValue()));
        assertThat(containerService.getByName(null, name, false), nullValue());
    }

    /**
     * The response to a list request, after checking it is a JSON array: a streamed {@code Iterable} that Jackson
     * didn't recognize as one would come out as an object.
     */
    private String list(final String path) throws Exception {
        final String response = mockMvc.perform(get(path)
                                                        .with(authentication(adminAuth))
                                                        .with(csrf())
                                                        .with(testSecurityContext()))
                                       .andExpect(status().isOk())
                                       .andReturn()
                                       .getResponse()
                                       .getContentAsString();
        assertThat(mapper.readTree(response).isArray(), is(true));
        return response;
    }

    /**
     * Loads each container in a fresh session, so its collections are read for it alone as they were before lists
     * were read in batches, and scrubs {@code XNAT_PASS} as the list endpoints do. In id order, as the lists are.
     */
    private List<Container> loadOneAtATime(final List<Long> ids) throws NotFoundException {
        final List<Container> containers = new ArrayList<>();
        for (final long id : ids.stream().sorted().collect(Collectors.toList())) {
            sessionFactory.getCurrentSession().clear();
            final Container container = containerService.get(id);
            // Scrubbed the way the endpoints scrub it, so the keys come out in the same order.
            final Map<String, String> env = new HashMap<>();
            for (final Map.Entry<String, String> variable : container.environmentVariables().entrySet()) {
                env.put(variable.getKey(), variable.getKey().equals(XNAT_PASS) ? "******" : variable.getValue());
            }
            containers.add(container.toBuilder().environmentVariables(env).build());
        }
        sessionFactory.getCurrentSession().clear();
        return containers;
    }

    /**
     * The ids of the containers an existing list method returned. Clears the session afterwards, so the list under
     * test reads from the database rather than from containers this one loaded.
     */
    private List<Long> idsOf(final List<Container> containers) {
        sessionFactory.getCurrentSession().clear();
        return containers.stream().map(Container::databaseId).collect(Collectors.toList());
    }

    private static String containerName(final String project, final int index) {
        return project + "-" + index;
    }

    @SuppressWarnings("deprecation") // mount input files are deprecated but still stored and serialized
    private static Container container(final String project, final int index) {
        final Date launched = new Date(1_700_000_000_000L + index * 60_000L);
        return Container.builder()
                        .commandId(1)
                        .wrapperId(1)
                        .userId(ADMIN)
                        .project(project)
                        .backend(Backend.DOCKER)
                        .containerId("cid-" + project + "-" + index)
                        .containerName(containerName(project, index))
                        .dockerImage("xnat/mrqy:1.0")
                        .commandLine("run --session /input/" + index)
                        .status("Complete")
                        .statusTime(launched)
                        .environmentVariables(ImmutableMap.of("XNAT_HOST", "https://xnat.example",
                                                              "XNAT_USER", "alias-" + index,
                                                              XNAT_PASS, "secret-" + index))
                        .ports(ImmutableMap.of("8080", String.valueOf(9000 + index)))
                        .containerLabels(ImmutableMap.of("run", String.valueOf(index)))
                        .logPaths(Arrays.asList("/logs/" + index + "/stdout.log", "/logs/" + index + "/stderr.log"))
                        .swarmConstraints(Arrays.asList("node.role==worker", "node.labels.slot==" + index))
                        .mounts(Arrays.asList(
                                Container.ContainerMount.builder()
                                                        .databaseId(0)
                                                        .name("input")
                                                        .writable(false)
                                                        .xnatHostPath("/data/archive/" + index)
                                                        .containerHostPath("/data/archive/" + index)
                                                        .containerPath("/input")
                                                        .inputFiles(Arrays.asList(
                                                                Container.ContainerMountFiles.create(0, "session", "/experiments/E" + index, "/data/archive/" + index, "scans"),
                                                                Container.ContainerMountFiles.create(0, "session", "/experiments/E" + index, "/data/archive/" + index, "resources")))
                                                        .build(),
                                Container.ContainerMount.builder()
                                                        .databaseId(0)
                                                        .name("output")
                                                        .writable(true)
                                                        .xnatHostPath("/data/build/" + index)
                                                        .containerHostPath("/data/build/" + index)
                                                        .containerPath("/output")
                                                        .inputFiles(new ArrayList<>())
                                                        .build()))
                        .inputs(Arrays.asList(
                                Container.ContainerInput.create(0, ContainerInputType.RAW, "session", "/archive/experiments/E" + index, false),
                                Container.ContainerInput.create(0, ContainerInputType.COMMAND, "session-id", "E" + index, false)))
                        .outputs(Arrays.asList(
                                Container.ContainerOutput.builder()
                                                         .databaseId(0)
                                                         .name("report")
                                                         .type("Resource")
                                                         .required(true)
                                                         .mount("output")
                                                         .label("MRQY")
                                                         .tags(Arrays.asList("qc", "run-" + index))
                                                         .handledBy("session")
                                                         .build()))
                        .history(Arrays.asList(
                                Container.ContainerHistory.builder()
                                                          .status("Created")
                                                          .entityType("user")
                                                          .entityId(ADMIN)
                                                          .timeRecorded(launched)
                                                          .build(),
                                Container.ContainerHistory.builder()
                                                          .status("Complete")
                                                          .entityType("event")
                                                          .timeRecorded(new Date(launched.getTime() + 30_000L))
                                                          .exitCode("0")
                                                          .build()))
                        .build();
    }
}
