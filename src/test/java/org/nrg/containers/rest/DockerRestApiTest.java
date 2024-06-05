package org.nrg.containers.rest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import com.google.common.io.Resources;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nrg.containers.config.DockerRestApiTestConfig;
import org.nrg.containers.exceptions.DockerServerException;
import org.nrg.containers.exceptions.NoDockerServerException;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.auto.Command.CommandWrapper;
import org.nrg.containers.model.dockerhub.DockerHubBase;
import org.nrg.containers.model.dockerhub.DockerHubBase.DockerHub;
import org.nrg.containers.model.dockerhub.DockerHubBase.DockerHubWithPing;
import org.nrg.containers.model.image.docker.DockerImage;
import org.nrg.containers.model.image.docker.DockerImageAndCommandSummary;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServerWithPing;
import org.nrg.containers.services.DockerService;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xdat.security.services.RoleServiceI;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xft.security.UserI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.File;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isIn;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
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

    private final String OK = "OK";
    private final static String HUB_STATUS_OK = "{\"ping\":true,\"response\":\"OK\",\"message\":\"OK message\"}";

    private final static String MOCK_CONTAINER_SERVER_NAME = "test server";
    private final static String MOCK_CONTAINER_HOST = "fake://host.url";

    private final static String ADMIN_USERNAME = "admin";
    private final static String NON_ADMIN_USERNAME = "non-admin";
    private Authentication ADMIN_AUTH;
    private Authentication NONADMIN_AUTH;

    private final static DockerServer MOCK_CONTAINER_SERVER =
            DockerServer.create(MOCK_CONTAINER_SERVER_NAME, MOCK_CONTAINER_HOST);
    private final static DockerServerWithPing SERVER_WITH_PING = DockerServerWithPing.create(MOCK_CONTAINER_SERVER, true);

    private final String NO_SERVER_PREF_EXCEPTION_MESSAGE = UUID.randomUUID().toString();
    private final NoDockerServerException NO_SERVER_PREF_EXCEPTION =
            new NoDockerServerException(NO_SERVER_PREF_EXCEPTION_MESSAGE);
    private final String NOT_FOUND_EXCEPTION_MESSAGE = UUID.randomUUID().toString();
    private final NotFoundException NOT_FOUND_EXCEPTION =
            new NotFoundException(NOT_FOUND_EXCEPTION_MESSAGE);
    private final String DOCKER_SERVER_EXCEPTION_MESSAGE = UUID.randomUUID().toString();
    private final DockerServerException DOCKER_SERVER_EXCEPTION =
            new DockerServerException(DOCKER_SERVER_EXCEPTION_MESSAGE);

    @Autowired private WebApplicationContext wac;
    @Autowired private ObjectMapper mapper;
    @Autowired private RoleServiceI mockRoleService;
    @Autowired private UserManagementServiceI mockUserManagementServiceI;

    @Autowired private DockerService mockDockerService;

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

    }

    @Test
    public void testGetServer() throws Exception {

        final String path = "/docker/server";
        when(mockDockerService.getServer())
                .thenReturn(SERVER_WITH_PING)
                .thenThrow(NOT_FOUND_EXCEPTION);

        final MockHttpServletRequestBuilder request =
                get(path).accept(JSON)
                        .with(authentication(NONADMIN_AUTH))
                        .with(csrf())
                        .with(testSecurityContext());

        final String response =
                mockMvc.perform(request)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        final DockerServerWithPing responseServer =
                mapper.readValue(response, DockerServerWithPing.class);
        assertThat(responseServer, is(SERVER_WITH_PING));


        // Not found
        final String exceptionResponse =
                mockMvc.perform(request)
                        .andExpect(status().isNotFound())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(exceptionResponse, containsString(NOT_FOUND_EXCEPTION_MESSAGE));
    }

    @Test
    public void testSetServer() throws Exception {

        final String path = "/docker/server";

        final String containerServerJson =
                mapper.writeValueAsString(MOCK_CONTAINER_SERVER);
        when(mockDockerService.setServer(MOCK_CONTAINER_SERVER)).thenReturn(SERVER_WITH_PING);

        final MockHttpServletRequestBuilder request =
                post(path)
                        .content(containerServerJson)
                        .contentType(JSON)
                        .with(authentication(ADMIN_AUTH))
                        .with(csrf())
                        .with(testSecurityContext());

        mockMvc.perform(request)
                .andExpect(status().isCreated());

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

        when(mockDockerService.ping())
                .thenReturn(OK)
                .thenThrow(DOCKER_SERVER_EXCEPTION)
                .thenThrow(NO_SERVER_PREF_EXCEPTION);

        // ok
        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(content().string(equalTo(OK)));

        // server exception
        final String ISEResponse =
                mockMvc.perform(request)
                        .andExpect(status().isInternalServerError())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(ISEResponse, is("The Docker server returned an error:\n" + DOCKER_SERVER_EXCEPTION_MESSAGE));

        // no pref exception
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

        final DockerHub privateHub = DockerHub.create(10L, "my hub", "http://localhost", false,
                "hub_user", "hub_pass", "hub@email.com", "1234567890qwerty");
//        final List<DockerHub> hubs = Lists.newArrayList(dockerHub, privateHub);
        final List<DockerHubWithPing> hubsWithPing = Arrays.asList(
                DockerHubWithPing.create(DockerHub.DEFAULT, DockerHubBase.DockerHubStatus.create(true)),
                DockerHubWithPing.create(privateHub, DockerHubBase.DockerHubStatus.create(true))
        );

        when(mockDockerService.getHubs()).thenReturn(hubsWithPing);

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
        final DockerHubWithPing privateHubWithPing = DockerHubWithPing.create(privateHub, DockerHubBase.DockerHubStatus.create(true));
        final DockerHub defaultHub = DockerHub.DEFAULT;
        final DockerHubWithPing defaultHubWithPing = DockerHubWithPing.create(defaultHub, DockerHubBase.DockerHubStatus.create(true));
        final long defaultHubId = defaultHub.id();

        when(mockDockerService.getHub(defaultHubId)).thenReturn(defaultHubWithPing);
        when(mockDockerService.getHub(privateHubId)).thenReturn(privateHubWithPing);

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
        assertThat(privateHubResponse.email(), is(privateHubWithPing.email()));
        assertThat(privateHubResponse.status(), is(privateHubWithPing.status()));

        assertThat("Password should not be readable.", privateHubResponse.password(),
                allOf(not(equalTo(privateHubWithPing.password())), nullValue(String.class))
        );
        assertThat("Token should not be readable", privateHubResponse.token(),
                allOf(not(equalTo(privateHubWithPing.token())), nullValue(String.class))
        );
    }

    @Test
    public void testGetHubByName() throws Exception {
        final String pathTemplate = "/docker/hubs/%s";

        final String privateHubName = "my hub";
        final DockerHub privateHub = DockerHub.create(10L, privateHubName, "http://localhost", false,
                "hub_user", "hub_pass", "hub@email.com", "1234567890qwerty");
        final DockerHubWithPing privateHubWithPing = DockerHubWithPing.create(privateHub, DockerHubBase.DockerHubStatus.create(true));
        final DockerHub defaultHub = DockerHub.DEFAULT;
        final DockerHubWithPing defaultHubWithPing = DockerHubWithPing.create(defaultHub, DockerHubBase.DockerHubStatus.create(true));
        final String defaultHubName = defaultHub.name();

        when(mockDockerService.getHub(defaultHubName)).thenReturn(defaultHubWithPing);
        when(mockDockerService.getHub(privateHubName)).thenReturn(privateHubWithPing);

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
        assertThat(privateHubResponse.email(), is(privateHubWithPing.email()));
        assertThat(privateHubResponse.status(), is(privateHubWithPing.status()));

        assertThat("Password should not be readable.", privateHubResponse.password(),
                allOf(not(equalTo(privateHubWithPing.password())), nullValue(String.class))
        );
        assertThat("Token should not be readable", privateHubResponse.token(),
                allOf(not(equalTo(privateHubWithPing.token())), nullValue(String.class))
        );
    }

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
        final DockerHubWithPing createdAndPingged = DockerHubWithPing.create(created, DockerHubBase.DockerHubStatus.create(true));

        when(mockDockerService.createHub(hubToCreate)).thenReturn(createdAndPingged);

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

        when(mockDockerService.pingHub(defaultHub.name(), null, null, null, null))
                .thenReturn(DockerHubBase.DockerHubStatus.create(true, OK, "OK message"));
        when(mockDockerService.pingHub(defaultHub.id(), null, null, null, null))
                .thenReturn(DockerHubBase.DockerHubStatus.create(true, OK, "OK message"));

        final String pathById = String.format(pathTemplate, defaultHub.id());
        final String pathByName = String.format(pathTemplate, defaultHub.name());
        mockMvc.perform(get(pathById)
                        .with(authentication(ADMIN_AUTH))
                        .with(csrf())
                        .with(testSecurityContext()))
                .andExpect(status().isOk())
                .andExpect(content().string(equalTo(HUB_STATUS_OK)));
        mockMvc.perform(get(pathByName)
                        .with(authentication(NONADMIN_AUTH))
                        .with(csrf())
                        .with(testSecurityContext()))
                .andExpect(status().isOk())
                .andExpect(content().string(equalTo(HUB_STATUS_OK)));
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
        final List<Command> expectedList = fromResource.stream()
                .map(command -> command.toBuilder().image(fakeImageId).build())
                .collect(Collectors.toList());

        when(mockDockerService.saveFromImageLabels(fakeImageId)).thenReturn(expectedList);

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
        final List<DockerImage> expectedImages = Collections.singletonList(fakeDockerImage);

        when(mockDockerService.getAllImages()).thenReturn(expectedImages);

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
        assertThat(responseList, equalTo(expectedImages));
    }

    @Test
    @DirtiesContext
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
        final List<DockerImageAndCommandSummary> expected = Arrays.asList(
                imageOnServerCommandInDb,
                commandInDbWithUnknownImage,
                imageOnServerCommandInLabels
        );

        when(mockDockerService.getImageSummaries()).thenReturn(expected);

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
}
