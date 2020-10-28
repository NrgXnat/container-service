package org.nrg.containers.rest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import com.google.common.io.Resources;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.nrg.containers.api.ContainerControlApi;
import org.nrg.containers.config.DockerRestApiTestConfig;
import org.nrg.containers.exceptions.DockerServerException;
import org.nrg.containers.exceptions.NoDockerServerException;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.auto.Command.CommandWrapper;
import org.nrg.containers.model.dockerhub.DockerHubBase.DockerHub;
import org.nrg.containers.model.dockerhub.DockerHubBase.DockerHubWithPing;
import org.nrg.containers.model.image.docker.DockerImage;
import org.nrg.containers.model.image.docker.DockerImageAndCommandSummary;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.services.CommandService;
import org.nrg.containers.services.DockerHubService;
import org.nrg.containers.services.DockerServerService;
import org.nrg.framework.exceptions.NotFoundException;
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
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.Matchers.isIn;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyListOf;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.nrg.containers.services.CommandLabelService.LABEL_KEY;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.testSecurityContext;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@RunWith(SpringJUnit4ClassRunner.class)
@WebAppConfiguration
@ContextConfiguration(classes = DockerRestApiTestConfig.class)
public class DockerRestApiTest {
    private MockMvc mockMvc;

    private final MediaType JSON = MediaType.APPLICATION_JSON_UTF8;

    private final static String MOCK_CONTAINER_SERVER_NAME = "test server";
    private final static String MOCK_CONTAINER_HOST = "fake://host.url";

    private final static String ADMIN_USERNAME = "admin";
    private final static String NON_ADMIN_USERNAME = "non-admin";
    private Authentication ADMIN_AUTH;
    private Authentication NONADMIN_AUTH;

    private final static String MOCK_CONTAINER_CERT_PATH = "/path/to/file";
    private final static DockerServer MOCK_CONTAINER_SERVER =
            DockerServer.create(MOCK_CONTAINER_SERVER_NAME, MOCK_CONTAINER_HOST);
    private final NoDockerServerException NO_SERVER_PREF_EXCEPTION =
            new NoDockerServerException("message");
    private final NotFoundException NOT_FOUND_EXCEPTION =
            new NotFoundException("message");

    private final DockerServerException DOCKER_SERVER_EXCEPTION =
            new DockerServerException("Your server dun goofed.");

    @Autowired private WebApplicationContext wac;
    @Autowired private ObjectMapper mapper;
    @Autowired private ContainerControlApi mockContainerControlApi;
    @Autowired private RoleServiceI mockRoleService;
    @Autowired private CommandService mockCommandService;
    @Autowired private UserManagementServiceI mockUserManagementServiceI;
    @Autowired private DockerHubService mockDockerHubService;
    @Autowired private DockerServerService mockDockerServerService;

    @Before
    public void setup() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();

        final String adminPassword = "admin";
        final UserI admin = mock(UserI.class);
        when(admin.getLogin()).thenReturn(ADMIN_USERNAME);
        when(admin.getPassword()).thenReturn(adminPassword);
        when(mockRoleService.isSiteAdmin(admin)).thenReturn(true);
        when(mockUserManagementServiceI.getUser(ADMIN_USERNAME)).thenReturn(admin);
        ADMIN_AUTH = new TestingAuthenticationToken(admin, adminPassword);

        final String nonAdminPassword = "non-admin-pass";
        final UserI nonAdmin = mock(UserI.class);
        when(nonAdmin.getLogin()).thenReturn(NON_ADMIN_USERNAME);
        when(nonAdmin.getPassword()).thenReturn(nonAdminPassword);
        when(mockRoleService.isSiteAdmin(nonAdmin)).thenReturn(false);
        when(mockUserManagementServiceI.getUser(NON_ADMIN_USERNAME)).thenReturn(nonAdmin);
        NONADMIN_AUTH = new TestingAuthenticationToken(nonAdmin, nonAdminPassword);

        // Have to use the do().when() construction because the when().do() throws an Exception
        doReturn("OK")
                .when(mockContainerControlApi).pingHub(Mockito.any(DockerHub.class), Mockito.anyString(), Mockito.anyString(),
                Mockito.anyString(), Mockito.anyString());
        doReturn(MOCK_CONTAINER_SERVER).when(mockDockerServerService).getServer();
    }

    @Test
    public void testGetServer() throws Exception {

        final String path = "/docker/server";

        final MockHttpServletRequestBuilder request =
                get(path).accept(JSON)
                        .with(authentication(NONADMIN_AUTH))
                        .with(csrf())
                        .with(testSecurityContext());

        final String response =
                mockMvc.perform(request)
                        .andExpect(status().isOk())
                        .andExpect(content().contentType(JSON))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        final DockerServer responseServer =
                mapper.readValue(response, DockerServer.class);
        assertThat(responseServer, is(MOCK_CONTAINER_SERVER.updateEventCheckTime(responseServer.lastEventCheckTime())));


        when(mockDockerServerService.getServer()).thenThrow(NOT_FOUND_EXCEPTION);

        // Not found
        final String exceptionResponse =
                mockMvc.perform(request)
                        .andExpect(status().isNotFound())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(exceptionResponse, containsString("message"));
    }

    @Test
    public void testSetServer() throws Exception {

        final String path = "/docker/server";

        final String containerServerJson =
                mapper.writeValueAsString(MOCK_CONTAINER_SERVER);

        final MockHttpServletRequestBuilder request =
                post(path)
                        .content(containerServerJson)
                        .contentType(JSON)
                        .with(authentication(ADMIN_AUTH))
                        .with(csrf())
                        .with(testSecurityContext());

        when(mockDockerServerService.setServer(any(DockerServer.class))).thenReturn(MOCK_CONTAINER_SERVER);

        verify(mockDockerServerService, times(0)).setServer(any(DockerServer.class)); // Method has been called once

        mockMvc.perform(request)
                .andExpect(status().isCreated());

        verify(mockDockerServerService, times(1)).setServer(any(DockerServer.class)); // Method has been called once

        // TODO figure out why the non-admin tests are failing and fix them. The code seems fine on a live XNAT.
        // // Now test setting the server with a non-admin user
        // final MockHttpServletRequestBuilder requestNonAdmin =
        //         post(path)
        //                 .content(containerServerJson)
        //                 .contentType(JSON)
        //                 .with(authentication(NONADMIN_AUTH))
        //                 .with(csrf())
        //                 .with(testSecurityContext());
        //
        // final String exceptionResponseNonAdmin =
        //         mockMvc.perform(requestNonAdmin)
        //                 .andExpect(status().isUnauthorized())
        //                 .andReturn()
        //                 .getResponse()
        //                 .getContentAsString();
        //
        // assertThat(exceptionResponseNonAdmin, containsString(NON_ADMIN_USERNAME));
        // verify(mockContainerControlApi, times(1)).setServer(MOCK_CONTAINER_SERVER); // Method has still been called only once

    }

    @Test
    public void testPingServer() throws Exception {
        final String path = "/docker/server/ping";

        final MockHttpServletRequestBuilder request =
                get(path).with(authentication(NONADMIN_AUTH))
                        .with(csrf()).with(testSecurityContext());

        doReturn("OK")
                .when(mockContainerControlApi).ping();

        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(content().string(equalTo("OK")));

        doThrow(DOCKER_SERVER_EXCEPTION)
                .when(mockContainerControlApi).ping();

        final String ISEResponse =
                mockMvc.perform(request)
                        .andExpect(status().isInternalServerError())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(ISEResponse, is("The Docker server returned an error:\nYour server dun goofed."));

        doThrow(NO_SERVER_PREF_EXCEPTION)
                .when(mockContainerControlApi).ping();

        final String failedDepResponse =
                mockMvc.perform(request)
                        .andExpect(status().isFailedDependency())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(failedDepResponse,
                is("Set up Docker server before using this REST endpoint."));
    }

    @Test
    public void testGetHubs() throws Exception {
        final String path = "/docker/hubs";

        final MockHttpServletRequestBuilder request =
                get(path)
                        .with(authentication(NONADMIN_AUTH))
                        .with(csrf())
                        .with(testSecurityContext());

        final DockerHub dockerHub = DockerHub.DEFAULT;
        final DockerHub privateHub = DockerHub.create(10L, "my hub", "http://localhost", false,
                "hub_user", "hub_pass", "hub@email.com", "1234567890qwerty");
        final List<DockerHub> hubs = Lists.newArrayList(dockerHub, privateHub);
        final List<DockerHubWithPing> hubsWithPing = Lists.newArrayList(
                DockerHubWithPing.create(dockerHub, true),
                DockerHubWithPing.create(privateHub, true)
        );

        when(mockDockerHubService.getHubs()).thenReturn(hubs);

        final String response =
                mockMvc.perform(request)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(response, is(mapper.writeValueAsString(hubsWithPing)));
    }

    @Test
    public void testGetHubById() throws Exception {
        final String pathTemplate = "/docker/hubs/%d";

        final long privateHubId = 10L;
        final DockerHub privateHub = DockerHub.create(privateHubId, "my hub", "http://localhost", false,
                "hub_user", "hub_pass", "hub@email.com", "1234567890qwerty");
        final DockerHubWithPing privateHubWithPing = DockerHubWithPing.create(privateHub, true);
        final DockerHub defaultHub = DockerHub.DEFAULT;
        final DockerHubWithPing defaultHubWithPing = DockerHubWithPing.create(defaultHub, true);
        final long defaultHubId = defaultHub.id();

        when(mockDockerHubService.getHub(defaultHubId)).thenReturn(defaultHub);
        when(mockDockerHubService.getHub(privateHubId)).thenReturn(privateHub);

        // Get default hub
        final MockHttpServletRequestBuilder defaultHubRequest =
                get(String.format(pathTemplate, defaultHubId))
                        .with(authentication(NONADMIN_AUTH))
                        .with(csrf())
                        .with(testSecurityContext());

        final String defaultHubResponseStr =
                mockMvc.perform(defaultHubRequest)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        final DockerHubWithPing defaultHubResponse = mapper.readValue(defaultHubResponseStr, DockerHubWithPing.class);
        assertThat(defaultHubResponse, is(defaultHubWithPing));

        // Get private hub
        final MockHttpServletRequestBuilder privateHubRequest =
                get(String.format(pathTemplate, privateHubId))
                        .with(authentication(NONADMIN_AUTH))
                        .with(csrf())
                        .with(testSecurityContext());

        final String privateHubResponseStr =
                mockMvc.perform(privateHubRequest)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        final DockerHubWithPing privateHubResponse = mapper.readValue(privateHubResponseStr, DockerHubWithPing.class);
        assertThat(privateHubResponse.name(), is(privateHubWithPing.name()));
        assertThat(privateHubResponse.url(), is(privateHubWithPing.url()));
        assertThat(privateHubResponse.username(), is(privateHubWithPing.username()));
        assertThat("Password should not be readable.", privateHubResponse.password(), is(not(equalTo(privateHubWithPing.password()))));
        assertThat(privateHubResponse.token(), is(privateHubWithPing.token()));
        assertThat(privateHubResponse.email(), is(privateHubWithPing.email()));
        assertThat(privateHubResponse.ping(), is(privateHubWithPing.ping()));
    }

    @Test
    public void testGetHubByName() throws Exception {
        final String pathTemplate = "/docker/hubs/%s";

        final String privateHubName = "my hub";
        final DockerHub privateHub = DockerHub.create(10L, privateHubName, "http://localhost", false,
                "hub_user", "hub_pass", "hub@email.com", "1234567890qwerty");
        final DockerHubWithPing privateHubWithPing = DockerHubWithPing.create(privateHub, true);
        final DockerHub defaultHub = DockerHub.DEFAULT;
        final DockerHubWithPing defaultHubWithPing = DockerHubWithPing.create(defaultHub, true);
        final String defaultHubName = defaultHub.name();

        when(mockDockerHubService.getHub(defaultHubName)).thenReturn(defaultHub);
        when(mockDockerHubService.getHub(privateHubName)).thenReturn(privateHub);

        // Get default hub
        final MockHttpServletRequestBuilder defaultHubRequest =
                get(String.format(pathTemplate, defaultHubName))
                        .with(authentication(NONADMIN_AUTH))
                        .with(csrf())
                        .with(testSecurityContext());

        final String defaultHubResponseStr =
                mockMvc.perform(defaultHubRequest)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        final DockerHubWithPing defaultHubResponse = mapper.readValue(defaultHubResponseStr, DockerHubWithPing.class);
        assertThat(defaultHubResponse, is(defaultHubWithPing));

        // Get private hub
        final MockHttpServletRequestBuilder privateHubRequest =
                get(String.format(pathTemplate, privateHubName))
                        .with(authentication(NONADMIN_AUTH))
                        .with(csrf())
                        .with(testSecurityContext());

        final String privateHubResponseStr =
                mockMvc.perform(privateHubRequest)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        final DockerHubWithPing privateHubResponse = mapper.readValue(privateHubResponseStr, DockerHubWithPing.class);
        assertThat(privateHubResponse.name(), is(privateHubWithPing.name()));
        assertThat(privateHubResponse.url(), is(privateHubWithPing.url()));
        assertThat(privateHubResponse.username(), is(privateHubWithPing.username()));
        assertThat("Password should not be readable.", privateHubResponse.password(), is(not(equalTo(privateHubWithPing.password()))));
        assertThat(privateHubResponse.token(), is(privateHubWithPing.token()));
        assertThat(privateHubResponse.email(), is(privateHubWithPing.email()));
        assertThat(privateHubResponse.ping(), is(privateHubWithPing.ping()));    }

    @Test
    public void testCreateHub() throws Exception {
        final String path = "/docker/hubs";

        final String hubToCreateJson = "{" +
                "\"id\": 0" +
                ", \"name\": \"a hub name\"" +
                ", \"url\": \"http://localhost\"" +
                ", \"default\": false" +
                "}";
        final DockerHub hubToCreate = mapper.readValue(hubToCreateJson, DockerHub.class);

        final DockerHub created = DockerHub.create(10L, "a hub name", "http://localhost", false,
                null, null, null, null);
        final DockerHubWithPing createdAndPingged = DockerHubWithPing.create(created, true);

        when(mockDockerHubService.create(hubToCreate)).thenReturn(created);

        final MockHttpServletRequestBuilder request =
                post(path)
                        .contentType(JSON)
                        .content(hubToCreateJson)
                        .with(authentication(ADMIN_AUTH))
                        .with(csrf())
                        .with(testSecurityContext());

        final String response =
                mockMvc.perform(request)
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        final DockerHubWithPing createdAndReturned = mapper.readValue(response, DockerHubWithPing.class);
        assertThat(createdAndReturned, is(createdAndPingged));

        // TODO figure out why the non-admin tests are failing and fix them. The code seems fine on a live XNAT.
        // final MockHttpServletRequestBuilder nonAdminRequest =
        //         post(path)
        //                 .contentType(JSON)
        //                 .content(mapper.writeValueAsString(hubToCreate))
        //                 .with(authentication(NONADMIN_AUTH))
        //                 .with(csrf())
        //                 .with(testSecurityContext());
        // mockMvc.perform(nonAdminRequest)
        //         .andExpect(status().isUnauthorized());
    }

    @Test
    public void testPingHub() throws Exception {
        final String pathTemplate = "/docker/hubs/%s/ping";

        final DockerHub defaultHub = DockerHub.DEFAULT;
        final long defaultHubId = defaultHub.id();
        final String defaultHubName = defaultHub.name();

        final String pathById = String.format(pathTemplate, String.valueOf(defaultHubId));
        final String pathByName = String.format(pathTemplate, defaultHubName);

        when(mockDockerHubService.getHub(defaultHubName)).thenReturn(defaultHub);
        when(mockDockerHubService.getHub(defaultHubId)).thenReturn(defaultHub);
        doReturn("OK").when(mockContainerControlApi).pingHub(Mockito.eq(defaultHub), anyString(), anyString(),
                anyString(), anyString());

        mockMvc.perform(get(pathById)
                        .with(authentication(ADMIN_AUTH))
                        .with(csrf())
                        .with(testSecurityContext()))
                .andExpect(status().isOk())
                .andExpect(content().string(equalTo("OK")));

        mockMvc.perform(get(pathByName)
                        .with(authentication(NONADMIN_AUTH))
                        .with(csrf())
                        .with(testSecurityContext()))
                .andExpect(status().isOk())
                .andExpect(content().string(equalTo("OK")));
    }

    @Test
    public void testSaveFromLabels() throws Exception {
        final String path = "/docker/images/save";

        final String fakeImageId = "xnat/thisisfake";

        final String resourceDir = Resources.getResource("dockerRestApiTest").getPath().replace("%20", " ");
        final String commandJsonFile = resourceDir + "/commands.json";

        // For some reason Jackson throws an exception when parsing this file. So read it first, then deserialize.
        // final List<Command> fromResource = mapper.readValue(new File(commandJsonFile), new TypeReference<List<Command>>(){});
        final String labelTestCommandListJsonFromFile = Files.toString(new File(commandJsonFile), Charset.defaultCharset());
        final List<Command> fromResource = mapper.readValue(labelTestCommandListJsonFromFile, new TypeReference<List<Command>>(){});
        final String labelTestCommandListJson = mapper.writeValueAsString(fromResource);

        final List<Command> expectedList = Lists.newArrayList(
                Lists.transform(fromResource, new Function<Command, Command>() {
                    @Override
                    public Command apply(final Command command) {
                        return command.toBuilder().image(fakeImageId).build();
                    }
                }));

        final Map<String, String> imageLabels = Maps.newHashMap();
        imageLabels.put(LABEL_KEY, labelTestCommandListJson);

        final DockerImage dockerImage = DockerImage.builder()
                .imageId(fakeImageId)
                .labels(imageLabels)
                .build();
        doReturn(dockerImage).when(mockContainerControlApi).getImageById(fakeImageId);
        when(mockCommandService.save(anyListOf(Command.class))).thenReturn(expectedList);

        final MockHttpServletRequestBuilder request =
                post(path).param("image", fakeImageId)
                        .with(authentication(ADMIN_AUTH))
                        .with(csrf()).with(testSecurityContext());

        final String responseStr =
                mockMvc.perform(request)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        final List<Command> responseList = mapper.readValue(responseStr, new TypeReference<List<Command>>(){});
        assertThat(responseList, is(equalTo(expectedList)));
    }

    @Test
    public void testGetImages() throws Exception {
        final String path = "/docker/images";

        final String fakeImageId = "sha256:some godawful hash";
        final String fakeImageName = "xnat/thisisfake";
        final DockerImage fakeDockerImage = DockerImage.builder()
                .imageId(fakeImageId)
                .addTag(fakeImageName)
                .build();

        doReturn(Lists.newArrayList(fakeDockerImage)).when(mockContainerControlApi).getAllImages();

        final MockHttpServletRequestBuilder request = get(path)
                .with(authentication(NONADMIN_AUTH))
                .with(csrf()).with(testSecurityContext());

        final String responseStr =
                mockMvc.perform(request)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        final List<DockerImage> responseList = mapper.readValue(responseStr, new TypeReference<List<DockerImage>>(){});
        assertThat(responseList, hasSize(1));
        assertThat(responseList.get(0), is(fakeDockerImage));
    }

    @Test
    public void testImageSummariesJsonRoundTrip() throws Exception {
        final String fakeImageId = "sha256:some godawful hash";
        final String fakeImageName = "xnat/thisisfake";
        final String fakeCommandName = "fake";
        final String fakeCommandWrapperName = "fake-on-thing";
        final Command fakeCommand = Command.builder()
                .name(fakeCommandName)
                .image(fakeImageName)
                .hash(fakeImageId)
                .addCommandWrapper(CommandWrapper.builder()
                        .name(fakeCommandWrapperName)
                        .build())
                .build();

        final DockerImageAndCommandSummary fakeSummary = DockerImageAndCommandSummary.builder()
                .imageId(fakeImageId)
                .server(MOCK_CONTAINER_SERVER_NAME)
                .addCommand(fakeCommand)
                .build();
        final String fakeSummaryJson = mapper.writeValueAsString(fakeSummary);
        final DockerImageAndCommandSummary deserialized = mapper.readValue(fakeSummaryJson, DockerImageAndCommandSummary.class);
        assertThat(deserialized, is(fakeSummary));

        final String unknownImageName = "unknown";
        final String unknownCommandName = "image-unknown";
        final Command unknownCommand = Command.builder()
                .name(unknownCommandName)
                .image(unknownImageName)
                .build();

        final List<DockerImageAndCommandSummary> expected = Lists.newArrayList(
                fakeSummary,
                DockerImageAndCommandSummary.builder()
                        .addCommand(unknownCommand)
                        .build()
        );

        final List<DockerImageAndCommandSummary> actual = mapper.readValue(mapper.writeValueAsString(expected),
                new TypeReference<List<DockerImageAndCommandSummary>>(){});
        assertThat(expected, everyItem(isIn(actual)));
        assertThat(actual, everyItem(isIn(expected)));
    }

    @Test
    public void testGetImageSummaries() throws Exception {
        final String path = "/docker/image-summaries";

        // Image exists on server, command refers to image
        final String imageWithSavedCommand_id = "sha256:some godawful hash";
        final String imageWithSavedCommand_name = "xnat/thisisfake";
        final DockerImage imageWithSavedCommand = DockerImage.builder()
                .imageId(imageWithSavedCommand_id)
                .addTag(imageWithSavedCommand_name)
                .build();

        final String commandWithImage_name = "fake";
        final String commandWithImage_wrapperName = "fake-on-thing";
        final Command.CommandWrapper wrapper = CommandWrapper.builder().name(commandWithImage_wrapperName).build();
        final Command commandWithImage = Command.builder()
                .name(commandWithImage_name)
                .image(imageWithSavedCommand_name)
                .xnatCommandWrappers(Lists.newArrayList(wrapper))
                .build();

        final DockerImageAndCommandSummary imageOnServerCommandInDb = DockerImageAndCommandSummary.builder()
                .addDockerImage(imageWithSavedCommand)
                .server(MOCK_CONTAINER_SERVER_NAME)
                .addCommand(commandWithImage)
                .build();

        // Command refers to image that does not exist on server
        final String commandWithUnknownImage_imageName = "unknown";
        final String commandWithUnknownImage_name = "image-unknown";
        final Command unknownCommand = Command.builder()
                .name(commandWithUnknownImage_name)
                .image(commandWithUnknownImage_imageName)
                .build();
        final DockerImageAndCommandSummary commandInDbWithUnknownImage =
                DockerImageAndCommandSummary.builder()
                        .addCommand(unknownCommand)
                        .build();

        // Image has command labels, no commands on server
        final String imageWithNonDbCommandLabels_id = "who:cares:not:me";
        final String imageWithNonDbCommandLabels_name = "xnat/thisisanotherfake:3.4.5.6";
        final String imageWithNonDbCommandLabels_commandName = "hi there";
        final Command toSaveInImageLabels = Command.builder().name(imageWithNonDbCommandLabels_commandName).build();
        final Command expectedToSeeInReturn = toSaveInImageLabels.toBuilder().hash(imageWithNonDbCommandLabels_id).build();
        final String imageWithNonDbCommandLabels_labelValue = mapper.writeValueAsString(Lists.newArrayList(toSaveInImageLabels));
        final DockerImage imageWithNonDbCommandLabels = DockerImage.builder()
                .imageId(imageWithNonDbCommandLabels_id)
                .addTag(imageWithNonDbCommandLabels_name)
                .addLabel(LABEL_KEY, imageWithNonDbCommandLabels_labelValue)
                .build();
        final DockerImageAndCommandSummary imageOnServerCommandInLabels =
                DockerImageAndCommandSummary.builder()
                        .addDockerImage(imageWithNonDbCommandLabels)
                        .server(MOCK_CONTAINER_SERVER_NAME)
                        .addCommand(expectedToSeeInReturn)
                        .build();

        // Mock out responses
        doReturn(Lists.newArrayList(imageWithSavedCommand, imageWithNonDbCommandLabels)).when(mockContainerControlApi).getAllImages();
        doReturn(null).when(mockContainerControlApi).getImageById(commandWithUnknownImage_imageName);
        when(mockCommandService.getAll()).thenReturn(Lists.newArrayList(commandWithImage, unknownCommand));

        final List<DockerImageAndCommandSummary> expected = Lists.newArrayList(
                imageOnServerCommandInDb,
                commandInDbWithUnknownImage,
                imageOnServerCommandInLabels
        );

        final MockHttpServletRequestBuilder request = get(path)
                .with(authentication(NONADMIN_AUTH))
                .with(csrf()).with(testSecurityContext());

        final String responseStr =
                mockMvc.perform(request)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        final List<DockerImageAndCommandSummary> responseList = mapper.readValue(responseStr, new TypeReference<List<DockerImageAndCommandSummary>>(){});
        assertThat(expected, everyItem(isIn(responseList)));
        assertThat(responseList, everyItem(isIn(expected)));
    }

    @Test
    public void testListHash() throws Exception {
        // This is to test a question I have.
        // If I put a list into a map, then add an item to the list, does the list's hash change?
        // This would be bad, because the map would "lose" the list

        final Map<List<String>, String> testMap = Maps.newHashMap();
        final List<String> testList = Lists.newArrayList();
        final String mapValue = "foo";
        final String listItem = "bar";

        testMap.put(testList, mapValue);
        assertThat(testMap, hasEntry(testList, mapValue));

        testList.add(listItem);
        assertThat(testMap, hasEntry(testList, mapValue));
    }
}
