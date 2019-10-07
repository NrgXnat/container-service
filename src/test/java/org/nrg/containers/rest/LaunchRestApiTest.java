package org.nrg.containers.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.json.JsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import com.jayway.jsonpath.spi.mapper.MappingProvider;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.nrg.containers.api.ContainerControlApi;
import org.nrg.containers.config.LaunchRestApiTestConfig;
import org.nrg.containers.model.command.auto.Command.CommandWrapper;
import org.nrg.containers.model.command.auto.LaunchReport;
import org.nrg.containers.model.command.auto.LaunchUi;
import org.nrg.containers.model.command.auto.ResolvedCommand;
import org.nrg.containers.model.configuration.CommandConfiguration;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.entity.ContainerEntity;
import org.nrg.containers.services.*;
import org.nrg.containers.utils.TestingUtils;
import org.nrg.xdat.entities.AliasToken;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.security.services.RoleServiceI;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xdat.services.AliasTokenService;
import org.nrg.xft.event.persist.PersistentWorkflowI;
import org.nrg.xft.security.UserI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.nrg.containers.model.server.docker.DockerServerBase.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.testSecurityContext;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@RunWith(SpringJUnit4ClassRunner.class)
@WebAppConfiguration
@ContextConfiguration(classes = LaunchRestApiTestConfig.class)
public class LaunchRestApiTest {
    private UserI mockAdmin;
    private PersistentWorkflowI mockWorkflow;
    private Authentication authentication;
    private MockMvc mockMvc;

    private final String INPUT_NAME = "stringInput";
    private final String INPUT_VALUE = "the super cool value";
    private final String FAKE_ROOT = "session";
    private final String FAKE_XNAT_ID = "XNAT_E00001";
    private final String INPUT_JSON = "{\"" + INPUT_NAME + "\": \"" + INPUT_VALUE + "\"}";
    private final String FAKE_CONTAINER_ID = "098zyx";
    private final String FAKE_WORKFLOW_ID = "123456";
    private final String QUEUED_WF_MSG = "To be assigned, see workflow " + FAKE_WORKFLOW_ID;
    private final String QUEUED_MSG = "To be assigned";

    private final long WRAPPER_ID = 10L;
    private final long COMMAND_ID = 15L;
    private final String WRAPPER_NAME = "I don't know";
    private final String COMMAND_NAME = "command-to-launch";
    private final String IMAGE = "abc123";

    private Container CONTAINER;
    private CommandWrapper COMMAND_WRAPPER;
    private ResolvedCommand RESOLVED_COMMAND;

    private final MediaType JSON = MediaType.APPLICATION_JSON_UTF8;
    private final MediaType XML = MediaType.APPLICATION_XML;

    @Autowired private WebApplicationContext wac;
    @Autowired private CommandService mockCommandService;
    @Autowired private RoleServiceI mockRoleService;
    @Autowired private ContainerControlApi mockDockerControlApi;
    @Autowired private ContainerEntityService mockContainerEntityService;
    @Autowired private CommandResolutionService mockCommandResolutionService;
    @Autowired private ContainerService mockContainerService;
    @Autowired private AliasTokenService mockAliasTokenService;
    @Autowired private DockerServerService mockDockerServerService;
    @Autowired private SiteConfigPreferences mockSiteConfigPreferences;
    @Autowired private UserManagementServiceI mockUserManagementServiceI;
    @Autowired private ObjectMapper mapper;

    @Rule public TemporaryFolder folder = new TemporaryFolder(new File("/tmp"));

    @Before
    public void setup() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();

        Configuration.setDefaults(new Configuration.Defaults() {

            private final JsonProvider jsonProvider = new JacksonJsonProvider();
            private final MappingProvider mappingProvider = new JacksonMappingProvider();

            @Override
            public JsonProvider jsonProvider() {
                return jsonProvider;
            }

            @Override
            public MappingProvider mappingProvider() {
                return mappingProvider;
            }

            @Override
            public Set<Option> options() {
                return Sets.newHashSet(Option.DEFAULT_PATH_LEAF_TO_NULL);
            }
        });

        // Mock out the prefs bean
        final String containerServerName = "testy test";
        final String containerHost = "unix:///var/run/docker.sock";
        final DockerServer dockerServer = DockerServer.create(containerServerName, containerHost);
        when(mockDockerServerService.getServer()).thenReturn(dockerServer);

        // Mock the userI
        final String url = "mock://url";
        final String username = "fakeuser";
        final String password = "fakepass";
        mockAdmin = Mockito.mock(UserI.class);
        when(mockAdmin.getLogin()).thenReturn(username);
        when(mockAdmin.getPassword()).thenReturn(password);
        when(mockRoleService.isSiteAdmin(mockAdmin)).thenReturn(true);

        authentication = new TestingAuthenticationToken(mockAdmin, password);

        // Mock the user management service
        when(mockUserManagementServiceI.getUser(username)).thenReturn(mockAdmin);

        // Mock the aliasTokenService
        final AliasToken mockAliasToken = new AliasToken();

        final String alias = "fakealias";
        final String secret = "fakesecret";
        mockAliasToken.setAlias(alias);
        mockAliasToken.setSecret(secret);
        when(mockAliasTokenService.issueTokenForUser(mockAdmin)).thenReturn(mockAliasToken);

        // Mock the site config preferences
        when(mockSiteConfigPreferences.getSiteUrl()).thenReturn(url);
        when(mockSiteConfigPreferences.getProperty("processingUrl", url)).thenReturn(url);
        when(mockSiteConfigPreferences.getBuildPath()).thenReturn(folder.newFolder().getAbsolutePath()); // transporter makes a directory under build
        when(mockSiteConfigPreferences.getArchivePath()).thenReturn(folder.newFolder().getAbsolutePath()); // container logs get stored under archive

        // Mock workflow
        mockWorkflow = Mockito.mock(PersistentWorkflowI.class);
        when(mockWorkflow.getWorkflowId()).thenReturn(Integer.valueOf(FAKE_WORKFLOW_ID));

        // Mock command
        COMMAND_WRAPPER = CommandWrapper.builder()
                .id(WRAPPER_ID)
                .name(WRAPPER_NAME)
                .build();
        when(mockCommandService.getWrapper(WRAPPER_ID)).thenReturn(COMMAND_WRAPPER);
        when(mockCommandService.retrieveWrapper(WRAPPER_ID)).thenReturn(COMMAND_WRAPPER);

        RESOLVED_COMMAND = ResolvedCommand.builder()
                .commandId(COMMAND_ID)
                .commandName(COMMAND_NAME)
                .wrapperId(WRAPPER_ID)
                .wrapperName(WRAPPER_NAME)
                .image(IMAGE)
                .commandLine("echo hello world")
                .addRawInputValue(INPUT_NAME, INPUT_VALUE)
                .build();
        CONTAINER = Container.containerFromResolvedCommand(RESOLVED_COMMAND, FAKE_CONTAINER_ID, username)
                .toBuilder().workflowId(FAKE_WORKFLOW_ID).build();
        final ContainerEntity containerEntity = ContainerEntity.fromPojo(CONTAINER);
        when(mockContainerEntityService.save(containerEntity, mockAdmin)).thenReturn(containerEntity);

        // Mock queuing
        when(mockContainerService.createContainerWorkflow(FAKE_XNAT_ID, FAKE_ROOT, WRAPPER_NAME, "", mockAdmin))
                .thenReturn(mockWorkflow);

        // We have to match any resolved command because spring will add a csrf token to the inputs. I don't know how to get that token in advance.
        when(mockDockerControlApi.createContainerOrSwarmService(any(ResolvedCommand.class), eq(mockAdmin))).thenReturn(CONTAINER);
        doNothing().when(mockDockerControlApi).startContainer(any(Container.class));
        // when(mockContainerEntityService.save(any(ResolvedCommand.class), eq(FAKE_CONTAINER_ID), any(String.class), eq(mockAdmin)))
        //         .thenReturn(CONTAINER_ENTITY);
    }

    @Test
    public void testLaunchWithQueryParams() throws Exception {
        final String pathTemplate = "/wrappers/%d/root/%s/launch";

        final String path = String.format(pathTemplate, WRAPPER_ID, FAKE_ROOT);
        final MockHttpServletRequestBuilder request =
                post(path).param(INPUT_NAME, INPUT_VALUE).param(FAKE_ROOT, FAKE_XNAT_ID)
                        .with(authentication(authentication))
                        .with(csrf())
                        .with(testSecurityContext());

        final String response = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        final String id = (new JSONObject(response)).get("container-id").toString();

        //Have a root element, so can make a workflow
        assertThat(id, is(QUEUED_WF_MSG));
    }

    @Test
    public void testLaunchWithParamsInBody() throws Exception {
        final String pathTemplate = "/wrappers/%d/root/%s/launch";

        final String path = String.format(pathTemplate, WRAPPER_ID, null);
        final MockHttpServletRequestBuilder request =
                post(path).content(INPUT_JSON).contentType(JSON)
                        .with(authentication(authentication))
                        .with(csrf())
                        .with(testSecurityContext());

        final String response = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        final String id = (new JSONObject(response)).get("container-id").toString();

        // No root element, no workflow
        assertThat(id, is(QUEUED_MSG));
    }

    @Test
    public void testBulkLaunch() throws Exception {
        final String pathTemplate = "/wrappers/%d/root/%s/bulklaunch";
        final String badId = "BAD";

        final Map<String, String> input = Maps.newHashMap();
        input.put(INPUT_NAME, INPUT_VALUE);
        input.put(FAKE_ROOT, mapper.writeValueAsString(Arrays.asList(FAKE_XNAT_ID, badId)));
        final String bulkInputJson = mapper.writeValueAsString(input);

        //final String exceptionMessage = "Unable to queue container launch";
        // Can go back to using the below when we allow different inputs for different targets
        //Mockito.doThrow(new Exception(exceptionMessage)).when(mockContainerService).queueResolveCommandAndLaunchContainer(
        //        isNull(String.class), eq(WRAPPER_ID), eq(0L), isNull(String.class),
        //        argThat(TestingUtils.isMapWithEntry(FAKE_ROOT, badId)), eq(mockAdmin), eq(mockWorkflow));


        final String path = String.format(pathTemplate, WRAPPER_ID, FAKE_ROOT);
        final MockHttpServletRequestBuilder request =
                post(path).content(bulkInputJson).contentType(JSON)
                        .with(authentication(authentication))
                        .with(csrf())
                        .with(testSecurityContext());

        final String response = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        final LaunchReport.BulkLaunchReport bulkLaunchReport = mapper.readValue(response, LaunchReport.BulkLaunchReport.class);
        assertThat(bulkLaunchReport.successes(), hasSize(2));
        // We are needing to return from bulk UI ASAP so that we don't get a proxy replay of the request. This means we don't get status info (success/failures) in the launch report.
        //assertThat(bulkLaunchReport.successes(), hasSize(1));
        //assertThat(bulkLaunchReport.failures(), hasSize(1));
        //final LaunchReport.Failure failure = bulkLaunchReport.failures().get(0);
        //assertThat(failure.launchParams(), hasEntry(FAKE_ROOT, badId));
        //assertThat(failure.message(), is(exceptionMessage));

        final LaunchReport.Success success = bulkLaunchReport.successes().get(0);
        assertThat(success.launchParams(), hasEntry(FAKE_ROOT, FAKE_XNAT_ID));
        assertThat(success.launchParams(), hasEntry(INPUT_NAME, INPUT_VALUE));
        assertThat(success, instanceOf(LaunchReport.ContainerSuccess.class));
        assertThat(((LaunchReport.ContainerSuccess) success).containerId(), is(QUEUED_MSG));
    }

    @Test
    public void testGetLaunchUI() throws Exception {
        final String pathTemplate = "/projects/%s/wrappers/%s/launch";
        final String project = "project";

        // Mock out command configuration (project)
        final CommandConfiguration mockCommandConfiguration = CommandConfiguration.builder()
                .addInput("name",
                        CommandConfiguration.CommandInputConfiguration.builder()
                                .defaultValue("value")
                                .build()
                )
                .build();
        when(mockCommandService.getProjectConfiguration(project, WRAPPER_ID)).thenReturn(mockCommandConfiguration);

        // Mock out command resolution service
        final ResolvedCommand.PartiallyResolvedCommand partiallyResolvedCommand = ResolvedCommand.PartiallyResolvedCommand.builder()
                .wrapperId(WRAPPER_ID)
                .wrapperName(WRAPPER_NAME)
                .commandId(COMMAND_ID)
                .commandName(COMMAND_NAME)
                .image(IMAGE)
                .build();
        when(mockCommandResolutionService.preResolve(eq(project), eq(WRAPPER_ID), anyMapOf(String.class, String.class), eq(mockAdmin)))
                .thenReturn(partiallyResolvedCommand);

        final LaunchUi expectedLaunchUi = LaunchUi.create(partiallyResolvedCommand,
                mockCommandConfiguration.inputs(), mockDockerServerService.getServer(), false);

        final String path = String.format(pathTemplate, project, WRAPPER_ID);
        final MockHttpServletRequestBuilder request = get(path)
                .with(authentication(authentication))
                .with(csrf())
                .with(testSecurityContext());

        final String response = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response, is(mapper.writeValueAsString(expectedLaunchUi)));
    }
}
