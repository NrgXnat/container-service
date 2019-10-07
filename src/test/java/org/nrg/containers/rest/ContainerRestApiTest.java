package org.nrg.containers.rest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.collect.Lists;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.nrg.containers.config.ContainerRestApiTestConfig;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.entity.ContainerEntity;
import org.nrg.containers.services.ContainerEntityService;
import org.nrg.containers.services.ContainerService;
import org.nrg.xdat.security.helpers.Permissions;
import org.nrg.xdat.security.services.RoleServiceI;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xft.security.UserI;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.modules.junit4.PowerMockRunnerDelegate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.testSecurityContext;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@RunWith(PowerMockRunner.class)
@PowerMockRunnerDelegate(SpringJUnit4ClassRunner.class)
@PrepareForTest({Permissions.class})
@PowerMockIgnore({"org.apache.*", "java.*", "javax.*", "org.w3c.*", "com.sun.*"})
@WebAppConfiguration
@Transactional
@ContextConfiguration(classes = ContainerRestApiTestConfig.class)
public class ContainerRestApiTest {
    private Authentication ADMIN_AUTH;
    private MockMvc mockMvc;

    private final MediaType JSON = MediaType.APPLICATION_JSON_UTF8;

    @Autowired private WebApplicationContext wac;
    @Autowired private ObjectMapper mapper;
    @Autowired private ContainerService containerService;
    @Autowired private ContainerEntityService containerEntityService;
    @Autowired private RoleServiceI mockRoleService;
    @Autowired private UserManagementServiceI mockUserManagementServiceI;

    @Rule public TemporaryFolder folder = new TemporaryFolder(new File("/tmp"));

    @Before
    public void setup() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();

        // Mock the userI
        final UserI admin = mock(UserI.class);
        final String adminUsername = "admin";
        final String adminPassword = "admin-pass";
        when(admin.getLogin()).thenReturn(adminUsername);
        when(admin.getPassword()).thenReturn(adminPassword);
        when(mockRoleService.isSiteAdmin(admin)).thenReturn(true);
        when(mockUserManagementServiceI.getUser(adminUsername)).thenReturn(admin);
        ADMIN_AUTH = new TestingAuthenticationToken(admin, adminPassword);

        // We only want to make the containers once, so first check if any exist.
        final List<ContainerEntity> containers = containerEntityService.getAll();
        if (containers.isEmpty()) {
            // Make some fake containers
            final String[] containerIds = new String[]{"cid1", null, "cid2", null, "cid3", null, "cid4", null};
            final String[] serviceIds = new String[]{null, "sid1", null, "sid2", null, "sid3", null, "sid4"};
            final String[] projects = new String[]{null, null, "project", "project", null, null, "project", "project"};
            final String[] states = new String[]{"Complete", "Complete", "Failed", "Failed", "Running", "Running", "Running-in-project", "Running-in-project"};
            for (int i = 0; i < 8; i++) {
                containerEntityService.create(ContainerEntity.fromPojo(
                        Container.builder()
                                .containerId(containerIds[i])
                                .serviceId(serviceIds[i])
                                .swarm(serviceIds[i] != null)
                                .status(states[i])
                                .project(projects[i])
                                .commandLine("")
                                .dockerImage("")
                                .userId(adminUsername)
                                .commandId(1)
                                .wrapperId(1)
                                .databaseId(i + 1)
                                .build()
                ));
            }

            TestTransaction.flagForCommit();
            TestTransaction.end();
            TestTransaction.start();
        }
    }

    @Test
    public void testSpringConfiguration() {
        assertThat(containerService, not(nullValue()));
    }

    @Test
    public void testGetAll() throws Exception {
        final String path = "/containers";

        {
            final MockHttpServletRequestBuilder request = get(path)
                    .with(authentication(ADMIN_AUTH))
                    .with(csrf())
                    .with(testSecurityContext());

            final String response =
                    mockMvc.perform(request)
                            .andExpect(status().isOk())
                            .andExpect(content().contentType(JSON))
                            .andReturn()
                            .getResponse()
                            .getContentAsString();

            final List<Container> containers = mapper.readValue(response, new TypeReference<List<Container>>() {});
            assertThat(containers, hasSize(8)); // It is enough to know we got them all
        }

        {
            final MockHttpServletRequestBuilder request = get(path)
                    .param("nonfinalized", "true")
                    .with(authentication(ADMIN_AUTH))
                    .with(csrf())
                    .with(testSecurityContext());

            final String response =
                    mockMvc.perform(request)
                            .andExpect(status().isOk())
                            .andExpect(content().contentType(JSON))
                            .andReturn()
                            .getResponse()
                            .getContentAsString();

            final List<Container> containers = mapper.readValue(response, new TypeReference<List<Container>>() {});
            assertThat(containers, hasSize(4)); // It is enough to know we got them all

            final List<String> states = Lists.transform(containers, new Function<Container, String>() {
                        @Override
                        public String apply(final Container input) {
                            return input.status();
                        }
                    }
            );
            final Set<String> uniqueStates = new HashSet<>(states);
            assertThat(uniqueStates, Matchers.<String>hasSize(2));
            assertThat(uniqueStates, containsInAnyOrder("Running", "Running-in-project"));
        }

        {
            final MockHttpServletRequestBuilder request = get("/projects/project" + path)
                    .with(authentication(ADMIN_AUTH))
                    .with(csrf())
                    .with(testSecurityContext());

            final String response =
                    mockMvc.perform(request)
                            .andExpect(status().isOk())
                            .andExpect(content().contentType(JSON))
                            .andReturn()
                            .getResponse()
                            .getContentAsString();

            final List<Container> containers = mapper.readValue(response, new TypeReference<List<Container>>() {});
            assertThat(containers, hasSize(4));

            final List<String> projects = Lists.transform(containers, new Function<Container, String>() {
                        @Override
                        public String apply(final Container input) {
                            return input.project();
                        }
                    }
            );
            final Set<String> uniqueProjects = new HashSet<>(projects);
            assertThat(uniqueProjects, Matchers.<String>hasSize(1));
            assertThat(uniqueProjects, contains("project"));

            final List<String> states = Lists.transform(containers, new Function<Container, String>() {
                        @Override
                        public String apply(final Container input) {
                            return input.status();
                        }
                    }
            );
            final Set<String> uniqueStates = new HashSet<>(states);
            assertThat(uniqueStates, Matchers.<String>hasSize(2));
            assertThat(uniqueStates, containsInAnyOrder("Failed", "Running-in-project"));
        }

        {
            final MockHttpServletRequestBuilder request = get("/projects/project" + path)
                    .param("nonfinalized", "true")
                    .with(authentication(ADMIN_AUTH))
                    .with(csrf())
                    .with(testSecurityContext());

            final String response =
                    mockMvc.perform(request)
                            .andExpect(status().isOk())
                            .andExpect(content().contentType(JSON))
                            .andReturn()
                            .getResponse()
                            .getContentAsString();

            final List<Container> containers = mapper.readValue(response, new TypeReference<List<Container>>() {});
            assertThat(containers, hasSize(2));

            final List<String> projects = Lists.transform(containers, new Function<Container, String>() {
                        @Override
                        public String apply(final Container input) {
                            return input.project();
                        }
                    }
            );
            final Set<String> uniqueProjects = new HashSet<>(projects);
            assertThat(uniqueProjects, Matchers.<String>hasSize(1));
            assertThat(uniqueProjects, contains("project"));

            final List<String> states = Lists.transform(containers, new Function<Container, String>() {
                        @Override
                        public String apply(final Container input) {
                            return input.status();
                        }
                    }
            );
            final Set<String> uniqueStates = new HashSet<>(states);
            assertThat(uniqueStates, Matchers.<String>hasSize(1));
            assertThat(uniqueStates, contains("Running-in-project"));
        }
    }
}
