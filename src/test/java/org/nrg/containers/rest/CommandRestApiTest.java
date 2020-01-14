package org.nrg.containers.rest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.nrg.config.entities.Configuration;
import org.nrg.config.services.ConfigService;
import org.nrg.containers.config.CommandRestApiTestConfig;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.auto.Command.CommandWrapper;
import org.nrg.containers.model.command.auto.CommandSummaryForContext;
import org.nrg.containers.model.configuration.CommandConfigurationInternal;
import org.nrg.containers.services.CommandService;
import org.nrg.containers.services.ContainerConfigService;
import org.nrg.containers.services.DockerServerService;
import org.nrg.framework.constants.Scope;
import org.nrg.xdat.entities.AliasToken;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.security.UserGroupI;
import org.nrg.xdat.security.UserGroupServiceI;
import org.nrg.xdat.security.helpers.AccessLevel;
import org.nrg.xdat.security.helpers.Permissions;
import org.nrg.xdat.security.services.RoleServiceI;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xdat.services.AliasTokenService;
import org.nrg.xft.security.UserI;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.modules.junit4.PowerMockRunnerDelegate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
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
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import static org.nrg.containers.services.ContainerConfigService.WRAPPER_CONFIG_PATH_TEMPLATE;
import static org.powermock.api.mockito.PowerMockito.mockStatic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.testSecurityContext;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@RunWith(PowerMockRunner.class)
@PowerMockRunnerDelegate(SpringJUnit4ClassRunner.class)
@PrepareForTest({Permissions.class})
@PowerMockIgnore({"org.apache.*", "java.*", "javax.*", "org.w3c.*", "com.sun.*"})
@WebAppConfiguration
@Transactional
@ContextConfiguration(classes = CommandRestApiTestConfig.class)
public class CommandRestApiTest {
    private Authentication ADMIN_AUTH;
    private Authentication NONADMIN_AUTH;
    private MockMvc mockMvc;

    private final String FAKE_URL = "mock://url";
    private final String FAKE_DOCKER_IMAGE = "abc123";
    private final MediaType JSON = MediaType.APPLICATION_JSON_UTF8;
    private final MediaType XML = MediaType.APPLICATION_XML;
    private final String NON_ADMIN_IS_OWNER_PROJECT = "projectowner";
    private final String NON_ADMIN_IS_MEMBER_PROJECT = "projectmember";
    private final String NON_ADMIN_IS_COLLABORATOR_PROJECT = "projectcollab";
    private final String NON_ADMIN_NON_PROJECT = "projectnon";

    @Autowired private WebApplicationContext wac;
    @Autowired private ObjectMapper mapper;
    @Autowired private CommandService commandService;
    @Autowired private RoleServiceI mockRoleService;
    @Autowired private AliasTokenService mockAliasTokenService;
    @Autowired private DockerServerService mockDockerServerService;
    @Autowired private SiteConfigPreferences mockSiteConfigPreferences;
    @Autowired private UserManagementServiceI mockUserManagementServiceI;
    @Autowired private ConfigService mockConfigService;
    @Autowired private UserGroupServiceI mockUserGroupService;

    @Rule public TemporaryFolder folder = new TemporaryFolder(new File("/tmp"));

    @Before
    public void setup() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();

        // Mock out the prefs bean
        final String containerServerName = "testy test";
        final String containerHost = "unix:///var/run/docker.sock";
        final DockerServer dockerServer = DockerServer.create(containerServerName, containerHost);
        when(mockDockerServerService.getServer()).thenReturn(dockerServer);

        // Mock the userI
        final UserI admin = mock(UserI.class);
        final String adminUsername = "admin";
        final String adminPassword = "admin-pass";
        when(admin.getLogin()).thenReturn(adminUsername);
        when(admin.getPassword()).thenReturn(adminPassword);
        when(mockRoleService.isSiteAdmin(admin)).thenReturn(true);
        when(mockUserManagementServiceI.getUser(adminUsername)).thenReturn(admin);
        ADMIN_AUTH = new TestingAuthenticationToken(admin, adminPassword);

        final UserI nonAdmin = mock(UserI.class);
        final String nonAdminUsername = "non-admin";
        final String nonAdminPassword = "non-admin-pass";
        final String nonAdminOwnerGroupId = NON_ADMIN_IS_OWNER_PROJECT + "_" + AccessLevel.Owner.code();
        final String nonAdminMemberGroupId = NON_ADMIN_IS_MEMBER_PROJECT + "_" + AccessLevel.Member.code();
        final String nonAdminCollaboratorGroupId = NON_ADMIN_IS_COLLABORATOR_PROJECT + "_" + AccessLevel.Collaborator.code();
        when(nonAdmin.getLogin()).thenReturn(nonAdminUsername);
        when(nonAdmin.getPassword()).thenReturn(nonAdminPassword);
        when(mockRoleService.isSiteAdmin(nonAdmin)).thenReturn(false);
        when(mockUserManagementServiceI.getUser(nonAdminUsername)).thenReturn(nonAdmin);
        final GrantedAuthority ownerAuthority = mock(GrantedAuthority.class);
        when(ownerAuthority.getAuthority()).thenReturn(nonAdminOwnerGroupId);
        final GrantedAuthority memberAuthority = mock(GrantedAuthority.class);
        when(memberAuthority.getAuthority()).thenReturn(nonAdminMemberGroupId);
        final GrantedAuthority collaboratorAuthority = mock(GrantedAuthority.class);
        when(collaboratorAuthority.getAuthority()).thenReturn(nonAdminCollaboratorGroupId);
        final List<GrantedAuthority> authorities = Lists.newArrayList(ownerAuthority, memberAuthority, collaboratorAuthority);
        // final List<? extends GrantedAuthority> authoritiesStupidGenericCopy = Lists.newArrayList(authorities);
        NONADMIN_AUTH = new TestingAuthenticationToken(nonAdmin, nonAdminPassword, authorities);
        doReturn(authorities).when(nonAdmin).getAuthorities();

        // Add new mocking behavior for 1.7.4
        final UserGroupI ownerGroup = mock(UserGroupI.class);
        when(ownerGroup.getId()).thenReturn(nonAdminOwnerGroupId);
        when(mockUserGroupService.getGroupsByTag(NON_ADMIN_IS_OWNER_PROJECT)).thenReturn(Collections.singletonList(ownerGroup));
        final UserGroupI memberGroup = mock(UserGroupI.class);
        when(memberGroup.getId()).thenReturn(nonAdminMemberGroupId);
        when(mockUserGroupService.getGroupsByTag(NON_ADMIN_IS_MEMBER_PROJECT)).thenReturn(Collections.singletonList(memberGroup));
        final UserGroupI collaboratorGroup = mock(UserGroupI.class);
        when(collaboratorGroup.getId()).thenReturn(nonAdminCollaboratorGroupId);
        when(mockUserGroupService.getGroupsByTag(NON_ADMIN_IS_COLLABORATOR_PROJECT)).thenReturn(Collections.singletonList(collaboratorGroup));
        when(mockUserGroupService.getGroupIdsForUser(nonAdmin)).thenReturn(Lists.newArrayList(nonAdminOwnerGroupId, nonAdminMemberGroupId, nonAdminCollaboratorGroupId));

        // Mock the aliasTokenService
        final AliasToken mockAliasToken = new AliasToken();
        mockAliasToken.setAlias("fake-alias");
        mockAliasToken.setSecret("fake-secret");
        when(mockAliasTokenService.issueTokenForUser(admin)).thenReturn(mockAliasToken);

        // Mock the site config preferences
        when(mockSiteConfigPreferences.getSiteUrl()).thenReturn(FAKE_URL);
        when(mockSiteConfigPreferences.getProperty("processingUrl", FAKE_URL)).thenReturn(FAKE_URL);
        when(mockSiteConfigPreferences.getBuildPath()).thenReturn(folder.newFolder().getAbsolutePath()); // transporter makes a directory under build
        when(mockSiteConfigPreferences.getArchivePath()).thenReturn(folder.newFolder().getAbsolutePath()); // container logs get stored under archive

        // Mock the permissions call
        // This may render some of the above moot
        // Added in 1.7.5 to avoid priming the cache
        // As of 1.7.6, canEditProject refers to actually being able to edit project metadata and is not relevant to
        // deciding whether a user can run a container. Project read permissions will do for now (though the strict
        // collaborator role would actually be blocked from editing any data via a container, so while he can launch,
        // he can't upload)
        mockStatic(Permissions.class);
        PowerMockito.when(Permissions.canReadProject(nonAdmin, NON_ADMIN_IS_OWNER_PROJECT)).thenReturn(true);
        PowerMockito.when(Permissions.canReadProject(nonAdmin, NON_ADMIN_IS_MEMBER_PROJECT)).thenReturn(true);
        PowerMockito.when(Permissions.canReadProject(nonAdmin, NON_ADMIN_IS_COLLABORATOR_PROJECT)).thenReturn(true);
        PowerMockito.when(Permissions.canReadProject(nonAdmin, NON_ADMIN_NON_PROJECT)).thenReturn(false);
        PowerMockito.when(Permissions.canReadProject(admin, NON_ADMIN_IS_OWNER_PROJECT)).thenReturn(true);
        PowerMockito.when(Permissions.canReadProject(admin, NON_ADMIN_IS_MEMBER_PROJECT)).thenReturn(true);
        PowerMockito.when(Permissions.canReadProject(admin, NON_ADMIN_IS_COLLABORATOR_PROJECT)).thenReturn(true);
    }

    @Test
    @DirtiesContext
    public void testGetAll() throws Exception {
        final String path = "/commands";

        final Command created = commandService.create(commandService.create(Command.builder()
                .name("one")
                .image(FAKE_DOCKER_IMAGE)
                .build()));

        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

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

        final List<Command> commands = mapper.readValue(response, new TypeReference<List<Command>>() {});
        assertThat(commands, hasSize(1));
        final Command command = commands.get(0);
        assertThat(command.id(), is(not(0L)));
        assertThat(command.id(), is(created.id()));
        assertThat(command.name(), is("one"));
        assertThat(command.image(), is(FAKE_DOCKER_IMAGE));
    }

    @Test
    @DirtiesContext
    public void testGet() throws Exception {
        final String pathTemplate = "/commands/%d";

        final Command created = commandService.create(Command.builder()
                .name("one")
                .image(FAKE_DOCKER_IMAGE)
                .build());
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        final String path = String.format(pathTemplate, created.id());

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

        final Command command = mapper.readValue(response, Command.class);
        assertThat(command.id(), is(not(0L)));
        assertThat(command, is(created));
    }

    @Test
    @DirtiesContext
    public void testCreate() throws Exception {
        final String path = "/commands";

        final String commandJson =
                "{\"name\": \"toCreate\", " +
                        "\"type\": \"docker\", " +
                        "\"image\":\"" + FAKE_DOCKER_IMAGE + "\", " +
                        "\"override-entrypoint\": true" +
                        "}";

        final MockHttpServletRequestBuilder request =
                post(path).content(commandJson).contentType(JSON)
                        .with(authentication(ADMIN_AUTH))
                        .with(csrf())
                        .with(testSecurityContext());

        final String response =
                mockMvc.perform(request)
                        .andExpect(status().isCreated())
                        .andExpect(content().contentType(JSON))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        final Long idResponse = Long.parseLong(response);
        assertThat(idResponse, is(not(0L)));

        final Command retrieved = commandService.retrieve(idResponse);
        assertThat(retrieved.id(), is(not(0L)));
        assertThat(idResponse, is(retrieved.id()));
        assertThat(retrieved.name(), is("toCreate"));
        assertThat(retrieved.image(), is(FAKE_DOCKER_IMAGE));
        assertThat(retrieved.overrideEntrypoint(), is(true));

        // Errors
        // No 'Content-type' header
        final MockHttpServletRequestBuilder noContentType =
                post(path).content(commandJson)
                        .with(authentication(ADMIN_AUTH))
                        .with(csrf())
                        .with(testSecurityContext());
        mockMvc.perform(noContentType)
                .andExpect(status().isUnsupportedMediaType());

        // Bad 'Accepts' header
        final MockHttpServletRequestBuilder badAccept =
                post(path).content(commandJson)
                        .contentType(JSON)
                        .accept(XML)
                        .with(authentication(ADMIN_AUTH))
                        .with(csrf())
                        .with(testSecurityContext());
        mockMvc.perform(badAccept)
                .andExpect(status().isNotAcceptable());

        // Blank command
        final String blankCommand = "{\"type\": \"docker\"}";
        final MockHttpServletRequestBuilder blankCommandRequest =
                post(path).content(blankCommand).contentType(JSON)
                        .with(authentication(ADMIN_AUTH))
                        .with(csrf())
                        .with(testSecurityContext());
        final String blankCommandResponse =
                mockMvc.perform(blankCommandRequest)
                        .andExpect(status().isBadRequest())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(blankCommandResponse,
                is("Invalid command:\n\tCommand name cannot be blank.\n\tCommand \"null\" - image name cannot be blank."));
    }

    @Test
    @DirtiesContext
    public void testDelete() throws Exception {
        final String pathTemplate = "/commands/%d";

        final Command command = commandService.create(Command.builder()
                .name("toDelete")
                .image(FAKE_DOCKER_IMAGE)
                .build());
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        final String path = String.format(pathTemplate, command.id());

        final MockHttpServletRequestBuilder request = delete(path)
                .with(authentication(ADMIN_AUTH))
                .with(csrf())
                .with(testSecurityContext());

        mockMvc.perform(request)
                .andExpect(status().isNoContent());

        assertThat(commandService.retrieve(command.id()), is(nullValue()));
    }

    @Test
    @DirtiesContext
    public void testAddWrapper() throws Exception {
        final String pathTemplate = "/commands/%d/wrappers";

        final String wrapperName = "some name, does not matter";
        final String commandWrapperJson = "{\"name\": \"" + wrapperName + "\"}";

        final Command command = commandService.create(Command.builder()
                .name("toCreate")
                .image(FAKE_DOCKER_IMAGE)
                .build());
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        final String path = String.format(pathTemplate, command.id());

        final MockHttpServletRequestBuilder request =
                post(path).content(commandWrapperJson).contentType(JSON)
                        .with(authentication(ADMIN_AUTH))
                        .with(csrf())
                        .with(testSecurityContext());

        final String response =
                mockMvc.perform(request)
                        .andExpect(status().isCreated())
                        .andExpect(content().contentType(JSON))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        final Long idResponse = Long.parseLong(response);
        assertThat(idResponse, is(not(0L)));

        CommandWrapper retrieved = null;
        final Command retrievedCommand = commandService.retrieve(command.id());
        for (final CommandWrapper wrapper : retrievedCommand.xnatCommandWrappers()) {
            if (wrapper.id() == idResponse) {
                retrieved = wrapper;
                break;
            }
        }
        assertThat(retrieved, is(not(nullValue())));
        assertThat(retrieved.id(), is(not(0L)));
        assertThat(idResponse, is(retrieved.id()));
        assertThat(retrieved.name(), is(wrapperName));

        // Errors

        // Blank command
        final String blankWrapper = "{}";
        final MockHttpServletRequestBuilder blankCommandRequest =
                post(path).content(blankWrapper).contentType(JSON)
                        .with(authentication(ADMIN_AUTH))
                        .with(csrf())
                        .with(testSecurityContext());
        final String blankCommandResponse =
                mockMvc.perform(blankCommandRequest)
                        .andExpect(status().isBadRequest())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(blankCommandResponse,
                is("Invalid command:\n\tCommand \"toCreate\" - Command wrapper name cannot be blank."));
    }

    @Test
    @DirtiesContext
    public void testUpdateWrapper() throws Exception {
        final String pathTemplate = "/commands/%d/wrappers/%d";

        final Command command = commandService.create(Command.builder()
                .name("toCreate")
                .image(FAKE_DOCKER_IMAGE)
                .addCommandWrapper(Command.CommandWrapper.builder()
                        .name("a name")
                        .description("ORIGINAL")
                        .build())
                .build());
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        final CommandWrapper created = command.xnatCommandWrappers().get(0);
        final long commandId = command.id();
        final long wrapperId = created.id();
        final String path = String.format(pathTemplate, commandId, wrapperId);

        final String newDescription = "UPDATED";
        final CommandWrapper updates = created.toBuilder().description(newDescription).build();

        final MockHttpServletRequestBuilder request =
                post(path).content(mapper.writeValueAsString(updates))
                        .contentType(JSON)
                        .with(authentication(ADMIN_AUTH))
                        .with(csrf())
                        .with(testSecurityContext());

        mockMvc.perform(request)
                .andExpect(status().isOk());
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        assertThat(commandService.retrieveWrapper(wrapperId), is(updates));
    }

    @Test
    @DirtiesContext
    public void testDeleteWrapper() throws Exception {
        final String pathTemplate = "/wrappers/%d";

        final Command command = commandService.create(Command.builder()
                .name("toCreate")
                .image(FAKE_DOCKER_IMAGE)
                .addCommandWrapper(Command.CommandWrapper.builder()
                        .name("a name")
                        .build())
                .build());
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        final CommandWrapper created = command.xnatCommandWrappers().get(0);
        final long commandId = command.id();
        final long wrapperId = created.id();
        final String path = String.format(pathTemplate, commandId, wrapperId);

        final MockHttpServletRequestBuilder request =
                delete(path)
                        .with(authentication(ADMIN_AUTH))
                        .with(csrf())
                        .with(testSecurityContext());

        mockMvc.perform(request)
                .andExpect(status().isNoContent());
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        assertThat(commandService.retrieveWrapper(wrapperId), is(nullValue()));
    }

    @Test
    public void testCreateEcatHeaderDump() throws Exception {
        // A User was attempting to create the command in this resource.
        // It failed, but Spring didn't tell us why. See CS-70.

        final String path = "/commands";

        final String dir = Paths.get(ClassLoader.getSystemResource("ecatHeaderDump").toURI()).toString().replace("%20", " ");
        final String commandJsonFile = dir + "/command.json";
        final Command ecatHeaderDump = mapper.readValue(new File(commandJsonFile), Command.class);
        final String commandJson = mapper.writeValueAsString(ecatHeaderDump);

        final MockHttpServletRequestBuilder request =
                post(path).content(commandJson).contentType(JSON)
                        .with(authentication(ADMIN_AUTH))
                        .with(csrf())
                        .with(testSecurityContext());

        final String response =
                mockMvc.perform(request)
                        .andExpect(status().isCreated())
                        .andExpect(content().contentType(JSON))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(response, is(not("0")));
    }

    @Test
    public void testSpringUnhelpfulError() throws Exception {
        // Deliberately trigger Spring's 400 'syntactically incorrect' error

        final String path = "/commands";

        final String badInputType = "fig newton";
        final String badInputTypeJson = "{" +
                "\"name\": \"a command name\", " +
                "\"type\": \"docker\", " +
                "\"image\": \"an image\", " +
                "\"inputs\": [" +
                    "{\"name\": \"an input name\", \"type\": \"" + badInputType + "\"}" +
                "}";

        final MockHttpServletRequestBuilder badInputTypeCommandRequest =
                post(path).content(badInputTypeJson).contentType(JSON)
                        .with(authentication(ADMIN_AUTH))
                        .with(csrf())
                        .with(testSecurityContext());

        final String badInputTypeCommandResponse =
                mockMvc.perform(badInputTypeCommandRequest)
                        .andExpect(status().isBadRequest())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(badInputTypeCommandResponse, is(""));
    }

    @Test
    @DirtiesContext
    @SuppressWarnings("unchecked")
    public void testProjectCommandsAvailable() throws Exception {
        final String path = "/commands/available";

        final String externalInputName = "input";
        final String xsiType = "xnat:imageScanData";
        final Command command = commandService.create(Command.builder()
                .name("toCreate")
                .type("docker")
                .image(FAKE_DOCKER_IMAGE)
                .addCommandWrapper(CommandWrapper.builder()
                        .name("a name")
                        .contexts(Sets.newHashSet(xsiType))
                        .addExternalInput(Command.CommandWrapperExternalInput.builder().name(externalInputName).type("string").build())
                        .build())
                .build()
        );
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();


        final CommandWrapper wrapper = command.xnatCommandWrappers().get(0);
        final long wrapperId = wrapper.id();
        final List<CommandSummaryForContext> available = Collections.singletonList(
                CommandSummaryForContext.create(command, wrapper, true, externalInputName)
        );

        final CommandConfigurationInternal commandConfiguration = CommandConfigurationInternal.create(
                Boolean.TRUE,
                Collections.<String, CommandConfigurationInternal.CommandInputConfiguration>emptyMap(),
                Collections.<String, CommandConfigurationInternal.CommandOutputConfiguration>emptyMap());
        final String commandConfigurationJson = mapper.writeValueAsString(commandConfiguration);
        final Configuration mockConfiguration = mock(Configuration.class);
        when(mockConfiguration.getContents()).thenReturn(commandConfigurationJson);
        final String configPath = String.format(WRAPPER_CONFIG_PATH_TEMPLATE, wrapperId);
        when(mockConfigService.getConfig(ContainerConfigService.TOOL_ID, configPath, Scope.Project, NON_ADMIN_IS_OWNER_PROJECT))
                .thenReturn(mockConfiguration);
        when(mockConfigService.getConfig(ContainerConfigService.TOOL_ID, configPath, Scope.Project, NON_ADMIN_IS_MEMBER_PROJECT))
                .thenReturn(mockConfiguration);
        when(mockConfigService.getConfig(ContainerConfigService.TOOL_ID, configPath, Scope.Project, NON_ADMIN_IS_COLLABORATOR_PROJECT))
                .thenReturn(mockConfiguration);
        when(mockConfigService.getConfig(ContainerConfigService.TOOL_ID, configPath, Scope.Site, null))
                .thenReturn(mockConfiguration);

        // Admin should be able to read all projects
        {
            final MockHttpServletRequestBuilder request =
                    get(path).param("project", NON_ADMIN_IS_OWNER_PROJECT)
                            .param("xsiType", xsiType)
                            .with(authentication(ADMIN_AUTH))
                            .with(csrf())
                            .with(testSecurityContext());
            final String response =
                    mockMvc.perform(request)
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();

            assertThat((List<CommandSummaryForContext>) mapper.readValue(response, new TypeReference<List<CommandSummaryForContext>>(){}), is(available));
        }

        {
            final MockHttpServletRequestBuilder request =
                    get(path).param("project", NON_ADMIN_IS_MEMBER_PROJECT)
                            .param("xsiType", xsiType)
                            .with(authentication(ADMIN_AUTH))
                            .with(csrf())
                            .with(testSecurityContext());
            final String response =
                    mockMvc.perform(request)
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();

            assertThat((List<CommandSummaryForContext>) mapper.readValue(response, new TypeReference<List<CommandSummaryForContext>>(){}), is(available));
        }

        {
            final MockHttpServletRequestBuilder request =
                    get(path).param("project", NON_ADMIN_IS_COLLABORATOR_PROJECT)
                            .param("xsiType", xsiType)
                            .with(authentication(ADMIN_AUTH))
                            .with(csrf())
                            .with(testSecurityContext());
            final String response =
                    mockMvc.perform(request)
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();

            assertThat((List<CommandSummaryForContext>) mapper.readValue(response, new TypeReference<List<CommandSummaryForContext>>(){}), is(available));
        }

        // Non-admin should be able to get available commands if they are member or owner
        {
            final MockHttpServletRequestBuilder request =
                    get(path).param("project", NON_ADMIN_IS_OWNER_PROJECT)
                            .param("xsiType", xsiType)
                            .with(authentication(NONADMIN_AUTH))
                            .with(csrf())
                            .with(testSecurityContext());
            final String response =
                    mockMvc.perform(request)
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();

            assertThat((List<CommandSummaryForContext>) mapper.readValue(response, new TypeReference<List<CommandSummaryForContext>>(){}), is(available));
        }

        {
            final MockHttpServletRequestBuilder request =
                    get(path).param("project", NON_ADMIN_IS_MEMBER_PROJECT)
                            .param("xsiType", xsiType)
                            .with(authentication(NONADMIN_AUTH))
                            .with(csrf())
                            .with(testSecurityContext());
            final String response =
                    mockMvc.perform(request)
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();

            assertThat((List<CommandSummaryForContext>) mapper.readValue(response, new TypeReference<List<CommandSummaryForContext>>(){}), is(available));
        }

        {
            final MockHttpServletRequestBuilder request =
                    get(path).param("project", NON_ADMIN_IS_COLLABORATOR_PROJECT)
                            .param("xsiType", xsiType)
                            .with(authentication(NONADMIN_AUTH))
                            .with(csrf())
                            .with(testSecurityContext());

            // TODO This is the correct behavior, but it does not work. See CS-266.
            // mockMvc.perform(request)
            //         .andExpect(status().isUnauthorized());

            final String response =
                    mockMvc.perform(request)
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();

            assertThat((List<CommandSummaryForContext>) mapper.readValue(response, new TypeReference<List<CommandSummaryForContext>>(){}), is(available));
        }

        // TODO: CS tests don't work with restrictTo annotation, although the XNAT site does, see CS-266.
        //  Probably need to adjust the test config somehow
        //{
        //    final MockHttpServletRequestBuilder request =
        //            get(path).param("project", NON_ADMIN_NON_PROJECT)
        //                    .param("xsiType", xsiType)
        //                    .with(authentication(NONADMIN_AUTH))
        //                    .with(csrf())
        //                    .with(testSecurityContext());
        //
        //    mockMvc.perform(request)
        //            .andExpect(status().isForbidden());
        //}
    }
}
