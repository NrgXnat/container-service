package org.nrg.containers.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Sets;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.json.JsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import com.jayway.jsonpath.spi.mapper.MappingProvider;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.nrg.containers.api.ContainerControlApi;
import org.nrg.containers.config.LaunchRestApiTestConfig;
import org.nrg.containers.model.ResolvedCommand;
import org.nrg.containers.model.ResolvedDockerCommand;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.auto.Command.CommandInput;
import org.nrg.containers.model.container.entity.ContainerEntity;
import org.nrg.containers.model.server.docker.DockerServer;
import org.nrg.containers.model.server.docker.DockerServerPrefsBean;
import org.nrg.containers.services.CommandService;
import org.nrg.containers.services.ContainerEntityService;
import org.nrg.xdat.entities.AliasToken;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.security.services.RoleServiceI;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xdat.services.AliasTokenService;
import org.nrg.xft.security.UserI;
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
import org.springframework.web.context.WebApplicationContext;

import java.io.File;
import java.util.Set;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.testSecurityContext;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@RunWith(SpringJUnit4ClassRunner.class)
@WebAppConfiguration
@ContextConfiguration(classes = LaunchRestApiTestConfig.class)
public class LaunchRestApiTest {
    private UserI mockAdmin;
    private Authentication authentication;
    private MockMvc mockMvc;

    private final String INPUT_NAME = "stringInput";
    private final String INPUT_VALUE = "the super cool value";
    private final String INPUT_JSON = "{\"" + INPUT_NAME + "\": \"" + INPUT_VALUE + "\"}";
    private final String FAKE_CONTAINER_ID = "098zyx";
    private final long COMMAND_ID = 10L;

    private final MediaType JSON = MediaType.APPLICATION_JSON_UTF8;
    private final MediaType XML = MediaType.APPLICATION_XML;

    @Autowired private WebApplicationContext wac;
    @Autowired private ObjectMapper mapper;
    @Autowired private CommandService mockCommandService;
    @Autowired private RoleServiceI mockRoleService;
    @Autowired private ContainerControlApi mockDockerControlApi;
    @Autowired private ContainerEntityService mockContainerEntityService;
    @Autowired private AliasTokenService mockAliasTokenService;
    @Autowired private DockerServerPrefsBean mockDockerServerPrefsBean;
    @Autowired private SiteConfigPreferences mockSiteConfigPreferences;
    @Autowired private UserManagementServiceI mockUserManagementServiceI;

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
        final DockerServer dockerServer = DockerServer.create(containerServerName, containerHost, null);
        when(mockDockerServerPrefsBean.getName()).thenReturn(containerServerName);
        when(mockDockerServerPrefsBean.getHost()).thenReturn(containerHost);
        when(mockDockerServerPrefsBean.toPojo()).thenReturn(dockerServer);
        when(mockDockerControlApi.getServer()).thenReturn(dockerServer);

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

        final Command mockCommand = Command.builder()
                .name("command-to-launch")
                .id(COMMAND_ID)
                .addInput(CommandInput.builder().name(INPUT_NAME).build())
                .build();
        when(mockCommandService.get(COMMAND_ID)).thenReturn(mockCommand);

        // This ResolvedCommand will be used in an internal method to "launch" a container
        final String environmentVariablesJson = "{" +
                "\"XNAT_HOST\": \"" + url + "\"," +
                "\"XNAT_USER\": \"" + alias + "\"," +
                "\"XNAT_PASS\": \"" + secret + "\"" +
                "}";
        final String preparedResolvedCommandJson =
                "{\"command-id\": " + String.valueOf(COMMAND_ID) +"," +
                        "\"image\": \"abc123\"," +
                        "\"env\": " + environmentVariablesJson + "," +
                        "\"raw-input-values\": " + INPUT_JSON + "," +
                        "\"mounts\": []," +
                        "\"outputs\": []," +
                        "\"ports\": {}" +
                        "}";
        final ResolvedDockerCommand preparedResolvedCommand = mapper.readValue(preparedResolvedCommandJson, ResolvedDockerCommand.class);
        final ContainerEntity containerEntity = new ContainerEntity(preparedResolvedCommand, FAKE_CONTAINER_ID, username);

        // We have to match any resolved command because spring will add a csrf token to the inputs. I don't know how to get that token in advance.
        when(mockDockerControlApi.createContainer(any(ResolvedDockerCommand.class))).thenReturn(FAKE_CONTAINER_ID);
        doNothing().when(mockDockerControlApi).startContainer(FAKE_CONTAINER_ID);
        when(mockContainerEntityService.save(any(ResolvedCommand.class), eq(FAKE_CONTAINER_ID), eq(mockAdmin)))
                .thenReturn(containerEntity);
    }

    @Test
    public void testLaunchWithQueryParams() throws Exception {
        final String pathTemplate = "/commands/%d/launch";

        final String path = String.format(pathTemplate, COMMAND_ID);
        final MockHttpServletRequestBuilder request =
                post(path).param(INPUT_NAME, INPUT_VALUE)
                        .with(authentication(authentication))
                        .with(csrf())
                        .with(testSecurityContext());

        final String response = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response, is(FAKE_CONTAINER_ID));
    }

    @Test
    public void testLaunchWithParamsInBody() throws Exception {
        final String pathTemplate = "/commands/%d/launch";

        final String path = String.format(pathTemplate, COMMAND_ID);
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

        assertThat(response, is(FAKE_CONTAINER_ID));
    }
}