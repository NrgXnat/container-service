package org.nrg.containers.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.nrg.config.entities.Configuration;
import org.nrg.config.services.ConfigService;
import org.nrg.containers.config.CommandConfigurationRestApiTestConfig;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.auto.Command.CommandInput;
import org.nrg.containers.model.command.auto.Command.CommandOutput;
import org.nrg.containers.model.command.auto.Command.CommandWrapper;
import org.nrg.containers.model.command.entity.CommandEntity;
import org.nrg.containers.model.configuration.CommandConfiguration;
import org.nrg.containers.model.configuration.CommandConfigurationInternal;
import org.nrg.containers.services.CommandEntityService;
import org.nrg.containers.services.ContainerConfigService;
import org.nrg.framework.constants.Scope;
import org.nrg.xdat.security.services.RoleServiceI;
import org.nrg.xdat.security.services.UserManagementServiceI;
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

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.testSecurityContext;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@RunWith(SpringJUnit4ClassRunner.class)
@WebAppConfiguration
@ContextConfiguration(classes = CommandConfigurationRestApiTestConfig.class)
public class CommandConfigurationRestApiTest {
    private UserI mockContainerManager;
    private Authentication authentication;
    private MockMvc mockMvc;
    private CommandConfiguration commandConfiguration;
    private String commandConfigurationJson;
    private String commandConfigurationInternalJson;
    private String commandConfigurationInternalDisabledJson;
    private Configuration mockConfig;
    // private Configuration mockConfigDisabled;
    private String configPath;
    private String siteConfigRestPath;
    private String projectConfigRestPath;
    private String siteEnableRestPath;
    private String projectEnableRestPath;
    private String siteDisableRestPath;
    private String projectDisbaleRestPath;

    private final String project = "some-project";
    private final String FAKE_USERNAME = "fakeuser";
    private final String FAKE_PASSWORD = "fakepass";
    private final MediaType JSON = MediaType.APPLICATION_JSON;
    private final MediaType XML = MediaType.APPLICATION_XML;

    @Autowired private WebApplicationContext wac;
    @Autowired private ObjectMapper mapper;
    @Autowired private CommandEntityService mockCommandEntityService;
    @Autowired private ConfigService mockConfigService;
    @Autowired private RoleServiceI mockRoleService;
    @Autowired private UserManagementServiceI mockUserManagementServiceI;

    @Rule public TemporaryFolder folder = new TemporaryFolder(new File("/tmp"));

    @Before
    //CS-1031
    public void setup() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();

        // Mock the userI
        mockContainerManager = Mockito.mock(UserI.class);
        when(mockContainerManager.getLogin()).thenReturn(FAKE_USERNAME);
        when(mockContainerManager.getPassword()).thenReturn(FAKE_PASSWORD);
        when(mockRoleService.checkRole(mockContainerManager, "ContainerManager")).thenReturn(true);
        when(mockRoleService.checkRole(mockContainerManager, "Privileged")).thenReturn(true);

        authentication = new TestingAuthenticationToken(mockContainerManager, FAKE_PASSWORD);

        // Mock the user management service
        when(mockUserManagementServiceI.getUser(FAKE_USERNAME)).thenReturn(mockContainerManager);

        // Command and wrapper
        final long commandId = 0L;
        final long wrapperId = 100L;
        final String wrapperName = "aWrapper";
        final String inputName = "input";
        final String outputName = "output";
        final CommandWrapper commandWrapper = CommandWrapper.builder()
                .id(wrapperId)
                .name(wrapperName)
                .build();
        final Command command = Command.builder()
                .id(commandId)
                .name("aCommand")
                .type("docker")
                .addCommandWrapper(commandWrapper)
                .addInput(CommandInput.builder().name(inputName).build())
                .addOutput(CommandOutput.builder().name(outputName).build())
                .build();
        when(mockCommandEntityService.getWrapperId(commandId, wrapperName)).thenReturn(wrapperId);
        when(mockCommandEntityService.getCommandByWrapperId(wrapperId)).thenReturn(CommandEntity.fromPojo(command));

        // Create a command configuration
        final CommandConfigurationInternal commandConfigurationInternal = CommandConfigurationInternal.builder()
                .enabled(true)
                .addInput(inputName,
                        CommandConfigurationInternal.CommandInputConfiguration.builder()
                                .defaultValue("whatever")
                                .matcher("anything")
                                .userSettable(true)
                                .advanced(false)
                                .build())
                .addOutput(outputName,
                        CommandConfigurationInternal.CommandOutputConfiguration.create("doesn't matter"))
                .build();
        commandConfigurationInternalJson = mapper.writeValueAsString(commandConfigurationInternal);
        final CommandConfigurationInternal commandConfigurationInternalDisabled =
                commandConfigurationInternal.toBuilder().enabled(false).build();
        commandConfigurationInternalDisabledJson =
                mapper.writeValueAsString(commandConfigurationInternalDisabled);

        commandConfiguration = CommandConfiguration.create(command, commandWrapper, commandConfigurationInternal);
        commandConfigurationJson = mapper.writeValueAsString(commandConfiguration);

        // mock out a org.nrg.config.Configuration
        mockConfig = mock(Configuration.class);
        when(mockConfig.getContents()).thenReturn(commandConfigurationInternalJson);
        // mockConfigDisabled = mock(Configuration.class);
        // when(mockConfigDisabled.getContents()).thenReturn(commandConfigurationInternalDisabledJson);

        configPath = String.format(ContainerConfigService.WRAPPER_CONFIG_PATH_TEMPLATE, wrapperId);

        // REST paths
        final String siteConfigRestPathTemplate = "/commands/%d/wrappers/%s/config";
        siteConfigRestPath = String.format(siteConfigRestPathTemplate, commandId, wrapperName);

        final String projectConfigRestPathTemplate = "/projects/%s/commands/%d/wrappers/%s/config";
        projectConfigRestPath = String.format(projectConfigRestPathTemplate, project, commandId, wrapperName);

        final String siteEnableRestPathTemplate = "/commands/%d/wrappers/%s/enabled";
        siteEnableRestPath = String.format(siteEnableRestPathTemplate, commandId, wrapperName);

        final String projectEnableRestPathTemplate = "/projects/%s/commands/%d/wrappers/%s/enabled";
        projectEnableRestPath = String.format(projectEnableRestPathTemplate, project, commandId, wrapperName);

        final String siteDisableRestPathTemplate = "/commands/%d/wrappers/%s/disabled";
        siteDisableRestPath = String.format(siteDisableRestPathTemplate, commandId, wrapperName);

        final String projectDisableRestPathTemplate = "/projects/%s/commands/%d/wrappers/%s/disabled";
        projectDisbaleRestPath = String.format(projectDisableRestPathTemplate, project, commandId, wrapperName);
    }

    @Test
    public void testCreateSiteConfig() throws Exception {
        when(mockConfigService
                .replaceConfig(
                        eq(FAKE_USERNAME),
                        anyString(),
                        eq(ContainerConfigService.TOOL_ID),
                        eq(configPath),
                        eq(commandConfigurationInternalJson),
                        eq(Scope.Site),
                        isNull(String.class)))
                .thenReturn(null);

        final MockHttpServletRequestBuilder request =
                post(siteConfigRestPath)
                        .content(commandConfigurationJson)
                        .contentType(JSON)
                        .with(authentication(authentication))
                        .with(csrf())
                        .with(testSecurityContext());

        mockMvc.perform(request)
                .andExpect(status().isCreated());
    }

    @Test
    public void testGetSiteConfig() throws Exception {
        when(mockConfigService
                .getConfig(
                        eq(ContainerConfigService.TOOL_ID),
                        eq(configPath),
                        eq(Scope.Site),
                        isNull(String.class)
                ))
                .thenReturn(mockConfig);

        final MockHttpServletRequestBuilder request =
                get(siteConfigRestPath)
                        .with(authentication(authentication))
                        .with(csrf())
                        .with(testSecurityContext());

        final String response =
                mockMvc.perform(request)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        final CommandConfiguration commandConfigurationResponse = mapper.readValue(response, CommandConfiguration.class);
        assertThat(commandConfigurationResponse, is(commandConfiguration));
    }

    @Test
    public void testDeleteSiteConfig() throws Exception {
        when(mockConfigService
                .getConfig(
                        eq(ContainerConfigService.TOOL_ID),
                        eq(configPath),
                        eq(Scope.Site),
                        isNull(String.class)
                ))
                .thenReturn(mockConfig);
        doNothing().when(mockConfigService).delete(mockConfig);

        final MockHttpServletRequestBuilder request =
                delete(siteConfigRestPath)
                        .with(authentication(authentication))
                        .with(csrf())
                        .with(testSecurityContext());

        mockMvc.perform(request)
                .andExpect(status().isNoContent());
    }

    @Test
    public void testCreateProjectConfig() throws Exception {
        when(mockConfigService
                .replaceConfig(
                        eq(FAKE_USERNAME),
                        anyString(),
                        eq(ContainerConfigService.TOOL_ID),
                        eq(configPath),
                        eq(commandConfigurationInternalJson),
                        eq(Scope.Project),
                        eq(project)))
                .thenReturn(null);

        final MockHttpServletRequestBuilder request =
                post(projectConfigRestPath)
                        .content(commandConfigurationJson)
                        .contentType(JSON)
                        .with(authentication(authentication))
                        .with(csrf())
                        .with(testSecurityContext());

        mockMvc.perform(request)
                .andExpect(status().isCreated());
    }

    @Test
    public void testGetProjectConfig() throws Exception {
        when(mockConfigService
                .getConfig(
                        eq(ContainerConfigService.TOOL_ID),
                        eq(configPath),
                        eq(Scope.Project),
                        eq(project)
                ))
                .thenReturn(mockConfig);

        final MockHttpServletRequestBuilder request =
                get(projectConfigRestPath)
                        .with(authentication(authentication))
                        .with(csrf())
                        .with(testSecurityContext());

        final String response =
                mockMvc.perform(request)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        final CommandConfiguration commandConfigurationResponse = mapper.readValue(response, CommandConfiguration.class);
        assertThat(commandConfigurationResponse, is(commandConfiguration));
    }

    @Test
    public void testDeleteProjectConfig() throws Exception {
        when(mockConfigService
                .getConfig(
                        eq(ContainerConfigService.TOOL_ID),
                        eq(configPath),
                        eq(Scope.Project),
                        eq(project)
                ))
                .thenReturn(mockConfig);
        doNothing().when(mockConfigService).delete(mockConfig);

        final MockHttpServletRequestBuilder request =
                delete(projectConfigRestPath)
                        .with(authentication(authentication))
                        .with(csrf())
                        .with(testSecurityContext());

        mockMvc.perform(request)
                .andExpect(status().isNoContent());
    }

    @Test
    public void testEnableForSite() throws Exception {
        when(mockConfigService
                .getConfig(
                        eq(ContainerConfigService.TOOL_ID),
                        eq(configPath),
                        eq(Scope.Site),
                        isNull(String.class)
                ))
                .thenReturn(mockConfig);

        final MockHttpServletRequestBuilder request =
                put(siteEnableRestPath)
                        .with(authentication(authentication))
                        .with(csrf())
                        .with(testSecurityContext());

        mockMvc.perform(request)
                .andExpect(status().isOk());

        // This method will be called to set the config to "enabled"
        verify(mockConfigService).replaceConfig(FAKE_USERNAME,
                null,
                ContainerConfigService.TOOL_ID,
                configPath,
                commandConfigurationInternalJson,
                Scope.Site,
                null);
    }

    @Test
    public void testDisableForSite() throws Exception {
        when(mockConfigService
                .getConfig(
                        eq(ContainerConfigService.TOOL_ID),
                        eq(configPath),
                        eq(Scope.Site),
                        isNull(String.class)
                ))
                .thenReturn(mockConfig);

        final MockHttpServletRequestBuilder request =
                put(siteDisableRestPath)
                        .with(authentication(authentication))
                        .with(csrf())
                        .with(testSecurityContext());

        mockMvc.perform(request)
                .andExpect(status().isOk());

        // This method will be called to set the config to "disabled"
        verify(mockConfigService).replaceConfig(FAKE_USERNAME,
                null,
                ContainerConfigService.TOOL_ID,
                configPath,
                commandConfigurationInternalDisabledJson,
                Scope.Site,
                null);
    }

    @Test
    public void testEnableForProject() throws Exception {
        when(mockConfigService
                .getConfig(
                        eq(ContainerConfigService.TOOL_ID),
                        eq(configPath),
                        eq(Scope.Project),
                        eq(project)
                ))
                .thenReturn(mockConfig);

        final MockHttpServletRequestBuilder request =
                put(projectEnableRestPath)
                        .with(authentication(authentication))
                        .with(csrf())
                        .with(testSecurityContext());

        mockMvc.perform(request)
                .andExpect(status().isOk());

        // This method will be called to set the config to "enabled"
        verify(mockConfigService).replaceConfig(FAKE_USERNAME,
                null,
                ContainerConfigService.TOOL_ID,
                configPath,
                commandConfigurationInternalJson,
                Scope.Project,
                project);
    }

    @Test
    public void testDisableForProject() throws Exception {
        when(mockConfigService
                .getConfig(
                        eq(ContainerConfigService.TOOL_ID),
                        eq(configPath),
                        eq(Scope.Project),
                        eq(project)
                ))
                .thenReturn(mockConfig);

        final MockHttpServletRequestBuilder request =
                put(projectDisbaleRestPath)
                        .with(authentication(authentication))
                        .with(csrf())
                        .with(testSecurityContext());

        mockMvc.perform(request)
                .andExpect(status().isOk());

        // This method will be called to set the config to "disabled"
        verify(mockConfigService).replaceConfig(FAKE_USERNAME,
                null,
                ContainerConfigService.TOOL_ID,
                configPath,
                commandConfigurationInternalDisabledJson,
                Scope.Project,
                project);
    }
}
