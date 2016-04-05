package org.nrg.containers.services;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.nrg.automation.services.ScriptService;
import org.nrg.containers.api.ContainerControlApi;
import org.nrg.containers.config.DefaultContainerServiceTestConfig;
import org.nrg.containers.exceptions.ContainerServerException;
import org.nrg.containers.exceptions.NoServerPrefException;
import org.nrg.containers.exceptions.NotFoundException;
import org.nrg.containers.metadata.service.ImageMetadataService;
import org.nrg.containers.model.ContainerHub;
import org.nrg.containers.model.ContainerHubPrefs;
import org.nrg.containers.model.ContainerServer;
import org.nrg.containers.model.Image;
import org.nrg.prefs.services.NrgPreferenceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.web.WebAppConfiguration;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.isIn;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(SpringJUnit4ClassRunner.class)
@WebAppConfiguration
@ContextConfiguration(classes = DefaultContainerServiceTestConfig.class)
public class DefaultContainerServiceTest {
    final static String MOCK_CONTAINER_HOST = "fake://host.url";
    final static String MOCK_CONTAINER_CERT_PATH = "/path/to/file";
    final static ContainerServer MOCK_CONTAINER_SERVER =
        new ContainerServer(MOCK_CONTAINER_HOST, MOCK_CONTAINER_CERT_PATH);

    final String CONTAINER_HUB_PREF_ID = ContainerHubPrefs.PREF_ID;

    final NotFoundException NOT_FOUND_EXCEPTION = new NotFoundException("Some cool message");
    final ContainerServerException CONTAINER_SERVER_EXCEPTION =
        new ContainerServerException("Your server dun goofed.");
    final NoServerPrefException NO_SERVER_PREF_EXCEPTION =
        new NoServerPrefException("Set your server, silly!.");

    @Autowired
    private ContainerControlApi mockContainerControlApi;

    @Autowired
    private ImageMetadataService mockImageMetadataService;

    @Autowired
    private ScriptService mockScriptService;

    @Autowired
    private ContainerHubPrefs containerHubPrefs;

    @Autowired
    private NrgPreferenceService mockPrefsService;

    @Autowired
    private ContainerService service;

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Before
    public void setup() throws Exception {
//        when(mockContainerServerPrefBean.host()).thenReturn(MOCK_CONTAINER_HOST);
//        when(mockContainerServerPrefBean.certPath()).thenReturn(MOCK_CONTAINER_CERT_PATH);

        when(mockContainerControlApi.getServer())
                .thenReturn(MOCK_CONTAINER_SERVER);
    }

    @Test
    public void testGetServer() throws Exception {
        // No need to mock method here, because we mocked it in setup()
        assertEquals(MOCK_CONTAINER_SERVER, service.getServer());
    }

//    @Test
//    public void testGetServerBlankPrefValue() throws Exception {
//        final Preference BLANK_PREFERENCE = new Preference();
//        when(mockPrefsService.getPreference(
//                DockerControlApi.SERVER_PREF_TOOL_ID,
//                DockerControlApi.SERVER_PREF_NAME))
//                .thenReturn(BLANK_PREFERENCE);
//
//        thrown.expect(NoServerPrefException.class);
//        thrown.expectMessage("No container server URI defined in preferences.");
//
//        service.getServer();
//    }

//    @Test
//    public void testGetServerNullPref() throws Exception {
//        when(mockPrefsService.getPreference(
//                DockerControlApi.SERVER_PREF_TOOL_ID,
//                DockerControlApi.SERVER_PREF_NAME))
//                .thenReturn(null);
//
//        thrown.expect(NoServerPrefException.class);
//        thrown.expectMessage("No container server URI defined in preferences.");
//
//        service.getServer();
//    }

    @Test
    public void testSetServer() throws Exception {
        // TODO
    }

    @Test
    public void testGetAllImages() throws Exception {
        final List<Image> mockImageList = Lists.newArrayList(new Image("first", "0", 0L, Lists.newArrayList("tag1", "tag2"), ImmutableMap.of("label0", "value0")), new Image("second", "1", 1L, Lists.newArrayList("tagX", "tagY"), ImmutableMap.of("label1", "value1")));

        when(mockContainerControlApi.getAllImages()).thenReturn(mockImageList);
        final List<Image> responseImageList = service.getAllImages();
        assertThat(responseImageList, equalTo(mockImageList));
    }

    @Test
    public void testGetImageByName() throws Exception {
        final String name = "foo";
        final Image mockImage = new Image("foo", "0", 0L, Lists.newArrayList("tag1", "tag2"), ImmutableMap.of("label0", "value0"));

        when(mockContainerControlApi.getImageByName(name)).thenReturn(mockImage);
        final Image responseImageByName = service.getImageByName(name);
        assertThat(responseImageByName, equalTo(mockImage));
    }

    @Test
    public void testGetImageById() throws Exception {
        final String id = "0";
        final Image mockImage = new Image("foo", "0", 0L, Lists.newArrayList("tag1", "tag2"), ImmutableMap.of("label0", "value0"));

        when(mockContainerControlApi.getImageById(id)).thenReturn(mockImage);
        final Image responseImageById = service.getImageById(id);
        assertThat(responseImageById, equalTo(mockImage));
    }

    @Test
    public void testDeleteImageById() throws Exception {
        // TODO
    }

    @Test
    public void testDeleteImageByName() throws Exception {
        // TODO
    }

    @Test
    public void testGetAllContainers() throws Exception {
        // TODO
    }

    @Test
    public void testGetContainerStatus() throws Exception {
        // TODO
    }

    @Test
    public void testGetContainer() throws Exception {
        // TODO
    }

    @Test
    public void testLaunch() throws Exception {
        // TODO
    }

    @Test
    public void testGetContainers() throws Exception {

    }

    @Test
    public void testLaunchOn() throws Exception {

    }

    @Test
    public void testLaunchFromScript() throws Exception {
        // We can't actually unit test this right now
        // It requires XFT can read the database and I don't know how to mock that.
//        final String scriptId = "123";
//        final String scriptName = "foo";
//        final String scriptContext = "pyxnat";
//        final String scriptContextVersion = "1.0";
//        final String scriptContent = "print \"What up, Earth?\"";
//        final Script script = new Script(scriptId, scriptName,
//            scriptContext, scriptContextVersion, scriptContent);
//
//        final String imageName = "bar";
//        final Set<String> mountsIn = Sets.newHashSet("/poo:/pee", "/data:/input");
//        final String mountsOut = "/data/xnat/stuuuuuuf:/output";
//        final String execution = "ls";
//        final String argName = "arg1";
//        final String argDescription = "The first arg";
//        final ImageMetadataArg arg =
//            ImageMetadataArg.builder()
//                .name(argName)
//                .description(argDescription)
//            .build();
//        final ImageMetadata metadata =
//            ImageMetadata.builder()
//                .imageId(imageName)
//                .mountsIn(mountsIn)
//                .mountsOut(mountsOut)
//                .execution(execution)
//                .arg(arg)
//            .build();
//
//        final Map<String, String> otherArgs =
//            ImmutableMap.of("arg1", "val1", "arg2", "val2");
//        final Boolean wait = false;
//
//
//
//
//        when(mockScriptService.getByScriptId(scriptId))
//            .thenReturn(script)
//            .thenReturn(null);
//
////        when(mockImageMetadataService.getMetadataFromContext(scriptContext))
////            .thenReturn(metadata);
//
//
//        // TODO
//        when(mockContainerControlApi.launchImage("bar", null, null))
//            .thenReturn("ok");
//
//        assertEquals("ok", service.launchFromScript(scriptId, otherArgs, wait));
    }

    @Test
    public void testGetContainerLogs() throws Exception {

    }

    @Test
    public void testVerbContainer() throws Exception {

    }

    @Test
    public void testGetHubs() throws Exception {
        setUpHubPrefs();

        final ContainerHub defaultContainerHub = ContainerHub.builder()
                .url("https://index.docker.io/v1/")
                .name("Docker Hub")
                .email("")
                .username("")
                .password("")
                .build();

        assertThat(defaultContainerHub, isIn(containerHubPrefs.getContainerHubs()));
    }

    @Test
    public void testSetHub() throws Exception {
        // TODO Move this to a ContainerHubPrefsTest, where the prefs service is real
//        when(mockPrefsService.getToolPropertyNames(CONTAINER_HUB_PREF_ID))
//            .thenReturn(Sets.newHashSet(CONTAINER_HUB_PREF_ID + "."));
//        when(mockPrefsService.getPreferenceValue(CONTAINER_HUB_PREF_ID, CONTAINER_HUB_PREF_ID + "."))
//            .thenReturn("{'url':'https://index.docker.io/v1/','name':'Docker Hub'," +
//                "'username':'','password':'','email':''}");
//        containerHubPrefs.initialize(mockPrefsService);
//
//        final ContainerHub newContainerHub = ContainerHub.builder()
//            .url("https://some.other.url")
//            .name("Test Hub")
//            .email("abc@123")
//            .username("foo")
//            .password("bar")
//            .build();
//
//        containerHubPrefs.setContainerHub(newContainerHub);
//        assertThat(newContainerHub, isIn(containerHubPrefs.getContainerHubs()));

    }

    @Test
    public void testPullByName() throws Exception {
        final ContainerHub hubNoAuth = ContainerHub.builder()
            .url("https://index.docker.io/v1/")
            .build();
        final ContainerHub hubWithAuth = ContainerHub.builder()
            .url("https://different.url")
            .username("foo")
            .password("bar")
            .build();
        final String image = "foo/bar";

        doNothing().when(mockContainerControlApi).pullImage(image, hubNoAuth);
        doNothing().when(mockContainerControlApi).pullImage(image, hubWithAuth);

        service.pullByName(image, hubNoAuth.url());

        verify(mockContainerControlApi, times(1)).pullImage(image, hubNoAuth);

        service.pullByName(image, hubWithAuth.url(), hubWithAuth.username(), hubWithAuth.password());

        verify(mockContainerControlApi, times(1)).pullImage(image, hubWithAuth);
    }

    @Test
    public void testPullFromSource() throws Exception {

    }

    @Test
    public void testSetMetadataByName() throws Exception {

    }

    @Test
    public void testSetMetadataById() throws Exception {

    }

    @Test
    public void testPing() throws Exception {
        when(mockContainerControlApi.pingServer())
            .thenReturn("OK")
            .thenThrow(CONTAINER_SERVER_EXCEPTION)
            .thenThrow(NO_SERVER_PREF_EXCEPTION);

        assertEquals("OK", service.pingServer());

        try {
            service.pingServer();
            fail("We should have caught a ContainerServerException.");
        } catch (ContainerServerException e) {
            assertEquals(CONTAINER_SERVER_EXCEPTION, e);
        }

        try {
            service.pingServer();
            fail("We should have caught a NoServerPrefException.");
        } catch (NoServerPrefException e) {
            assertEquals(NO_SERVER_PREF_EXCEPTION, e);
        }
    }

    private void setUpHubPrefs() {
        when(mockPrefsService.getToolPropertyNames(CONTAINER_HUB_PREF_ID))
            .thenReturn(Sets.newHashSet(CONTAINER_HUB_PREF_ID + "."));
        when(mockPrefsService.getPreferenceValue(CONTAINER_HUB_PREF_ID, CONTAINER_HUB_PREF_ID + "."))
            .thenReturn("{'url':'https://index.docker.io/v1/','name':'Docker Hub'," +
                "'username':'','password':'','email':''}");
        containerHubPrefs.initialize(mockPrefsService);
    }
}