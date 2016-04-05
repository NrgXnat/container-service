package org.nrg.containers.services.impl;

import com.google.common.collect.Lists;
import org.apache.commons.io.FileUtils;
import org.nrg.automation.entities.Script;
import org.nrg.automation.services.ScriptService;
import org.nrg.containers.api.ContainerControlApi;
import org.nrg.containers.exceptions.ContainerServerException;
import org.nrg.containers.exceptions.NoHubException;
import org.nrg.containers.exceptions.NoServerPrefException;
import org.nrg.containers.exceptions.NotFoundException;
import org.nrg.containers.metadata.ImageMetadata;
import org.nrg.containers.metadata.service.ImageMetadataService;
import org.nrg.containers.model.Container;
import org.nrg.containers.model.ContainerHub;
import org.nrg.containers.model.ContainerHubPrefs;
import org.nrg.containers.model.ContainerServer;
import org.nrg.containers.model.Image;
import org.nrg.containers.services.ContainerService;
import org.nrg.prefs.exceptions.InvalidPreferenceName;
import org.nrg.transporter.TransportService;
import org.nrg.xft.XFT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

@Service
public class DefaultContainerService implements ContainerService {
    private static final Logger _log = LoggerFactory.getLogger(DefaultContainerService.class);

    @Autowired
    @SuppressWarnings("SpringJavaAutowiringInspection") // IntelliJ does not process the excludeFilter in ContainerServiceConfig @ComponentScan, erroneously marks this red
    private ContainerControlApi controlApi;

    @Autowired
    @SuppressWarnings("SpringJavaAutowiringInspection") // IntelliJ does not process the excludeFilter in ContainerServiceConfig @ComponentScan, erroneously marks this red
    private ImageMetadataService imageMetadataService;

    @Autowired
    @SuppressWarnings("SpringJavaAutowiringInspection") // IntelliJ does not process the excludeFilter in ContainerServiceConfig @ComponentScan, erroneously marks this red
    private ScriptService scriptService;

    @Autowired
    @SuppressWarnings("SpringJavaAutowiringInspection") // IntelliJ does not process the excludeFilter in ContainerServiceConfig @ComponentScan, erroneously marks this red
    private TransportService transportService;

    @Autowired
    @SuppressWarnings("SpringJavaAutowiringInspection") // IntelliJ does not process the excludeFilter in ContainerServiceConfig @ComponentScan, erroneously marks this red
    private ContainerHubPrefs containerHubPrefs;

    public List<Image> getAllImages() throws NoServerPrefException, ContainerServerException {
        return controlApi.getAllImages();
    }

    public Image getImageByName(final String name) throws NoServerPrefException, NotFoundException, ContainerServerException {
        return controlApi.getImageByName(name);
    }

    @Override
    public Image getImageById(String id) throws NoServerPrefException, NotFoundException, ContainerServerException {
        // TODO Figure out what to do with this. Not sure if we need to be fetching images by id.
        return controlApi.getImageById(id);
    }

    @Override
    public String deleteImageById(final String id, final Boolean onServer) throws NoServerPrefException, NotFoundException, ContainerServerException {
        // TODO
        return null;
    }

    @Override
    public String deleteImageByName(final String name, final Boolean onServer) throws NoServerPrefException, NotFoundException, ContainerServerException {
        // TODO
        return null;
    }

    @Override
    public List<Container> getContainers(final Map<String, List<String>> params) throws NoServerPrefException, ContainerServerException {
        // TODO do stuff with queryParams
        return controlApi.getAllContainers();
    }

    public String getContainerStatus(final String id) throws NoServerPrefException, NotFoundException, ContainerServerException {
        return controlApi.getContainerStatus(id);
    }

    public Container getContainer(final String id) throws NoServerPrefException, NotFoundException, ContainerServerException {
        return controlApi.getContainer(id);
    }

    @Override
    public String launch(final String imageName, final Map<String, String> params, Boolean wait)
            throws NoServerPrefException, NotFoundException, ContainerServerException {

        // Find image and metadata
        // Resolve all arguments
        // stage data
        // launch
        //return controlApi.launchImage(server(), imageName, params.getCommandArray(), params.getVolumesArray());
        return null;
    }

    @Override
    public String launchOn(final String imageName, final String xnatId, final String type, final Map<String, String> launchArguments, final Boolean wait) {
        // Find xnat object
        // Find image and metadata
        // Resolve all arguments
        // stage data
        // launch
        return null;
    }

    @Override
    public String launchFromScript(String scriptId, Map<String, String> launchArguments, Boolean wait)
        throws Exception {
        final Script script = scriptService.getByScriptId(scriptId);
        if (script == null) {
            throw new NotFoundException("Could not find script " + scriptId);
        }

        final String context = script.getLanguage();
        final ImageMetadata metadata = imageMetadataService.getMetadataFromContext(context);
        final String imageId = metadata.getImageId();

        // TODO Remove all the hard-coded stuff from sprint 4. Make this work generically.
//        List<ImageMetadataArg> args = metadata.getArgs();
        // String description = metadata.getDecription();
//        String execution = metadata.getExecution();
//        String imageId = metadata.getImageId();
//        List<String> mountsIn = Lists.newArrayList(metadata.getMountsIn());
//        List<String> mountsOut = Lists.newArrayList(metadata.getMountsOut());
        //String type = metadata.getType();

//        String sessionId = null;
//        XFTItem session = null;
//        for (final ImageMetadataArg arg : args) {
//            if (arg.getType().startsWith("xnat")) {
//                final String id = launchArguments.get(arg.getValue());
//                if (id == null || id.equals("")) {
//                    throw new Exception(
//                        String.format("Input argument %s is required, must contain a %s ID.",
//                            arg.getValue(), arg.getType()));
//                }
//
//                sessionId = id;
//                session = ItemSearch.GetItem(arg.getType() + ".id", id, Users.getUser("admin"), false);
//                launchArguments.remove(arg.getValue());
//                break;
//            }
//        }
//        if (sessionId == null) {
//            sessionId = "";
//        }
//        final String sessionId = launchArguments.get("sessionId");
//        final XFTItem session = ItemSearch.GetItem("xnat:mrSessionData.id", sessionId, Users.getUser("admin"), false);
//        final String mountIn = launchArguments.get("mountIn");
//        final String mountOut = launchArguments.get("mountOut");
//        final String imageId = launchArguments.get("imageId");
//        final String host = launchArguments.get("host");

        // Resolve args from launchArguments


        // Transport files
        final String server = controlApi.getServer().host();
//        final List<Path> paths = transportService.transport(server, session);

        final Calendar cal = Calendar.getInstance();
        final SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMdd_HHmmss");
        final String timestamp = formatter.format(cal.getTime());
        final String buildDir = XFT.getBuildPath() + timestamp;

        final String filename = "script";
        final File filenameWithPath = new File(buildDir, filename);
        FileUtils.writeStringToFile(filenameWithPath, script.getContent());
        final Path scriptPath = transportService.transport(server, filenameWithPath.toPath()).get(0);

//        final List<String> command = Lists.newArrayList("python", "/data/output/"+filename,
//            "-h", host, "-u", "admin", "-p", "admin",
//            "-s", sessionId);
        final List<String> command = Lists.newArrayList();
//        final List<String> volumes = Lists.newArrayList(
//            String.format("%s:%s", paths.get(0), mountIn),
//            String.format("%s:%s", buildDir, mountOut));
        final List<String> volumes = Lists.newArrayList();
        return controlApi.launchImage(imageId, command, volumes);
    }


    @Override
    public String getContainerLogs(final String id)
        throws NoServerPrefException, NotFoundException, ContainerServerException {
        return controlApi.getContainerLogs(id);
    }

    @Override
    public String verbContainer(final String id, final String status)
            throws NoServerPrefException, NotFoundException, ContainerServerException {
        // TODO
        return null;
    }

    @Override
    public List<ContainerHub> getHubs() {
        return containerHubPrefs.getContainerHubs();
    }

    @Override
    public void setHub(final ContainerHub hub) throws IOException {
        containerHubPrefs.setContainerHub(hub);
    }

//    @Override
//    public String search(final String term) throws NoHubException {
//        // TODO
//        return null;
//    }

    @Override
    public void pullByName(final String image, final String hub,
                           final String hubUsername, final String hubPassword)
        throws NoHubException, NotFoundException, ContainerServerException, IOException, NoServerPrefException {
        final ContainerHub hubWithAuth = ContainerHub.builder()
            .url(hub)
            .username(hubUsername)
            .password(hubPassword)
            .build();
        pullByName(image, hubWithAuth);
    }

    @Override
    public void pullByName(String image, String hub)
        throws NoHubException, NotFoundException, ContainerServerException, IOException, NoServerPrefException {
        final ContainerHub hubNoAuth = ContainerHub.builder().url(hub).build();
        pullByName(image, hubNoAuth);
    }

    private void pullByName(String image, ContainerHub hub)
        throws NoHubException, NotFoundException, ContainerServerException, IOException, NoServerPrefException {
        controlApi.pullImage(image, hub);
    }

    @Override
    public Image pullFromSource(final String source, final String name)
            throws NoHubException, NotFoundException, ContainerServerException {
        // TODO
        return null;
    }

    @Override
    public void setMetadataByName(final String imageName, final ImageMetadata metadata, final String project,
                                  final Boolean overwrite, final Boolean ignoreBlank)
            throws NoServerPrefException, NotFoundException, ContainerServerException {
        final Image image = getImageByName(imageName);
        imageMetadataService.setMetadata(image, metadata, project, overwrite, ignoreBlank);
    }

    @Override
    public void setMetadataById(final String imageId, final ImageMetadata metadata, final String project,
                                final Boolean overwrite, final Boolean ignoreBlank)
            throws NoServerPrefException, NotFoundException, ContainerServerException {
        final Image image = getImageById(imageId);
        imageMetadataService.setMetadata(image, metadata, project, overwrite, ignoreBlank);
    }

    @Override
    public ContainerServer getServer() throws NoServerPrefException, NotFoundException {
        return controlApi.getServer();
    }

    @Override
    public void setServer(final ContainerServer server) throws InvalidPreferenceName {
        controlApi.setServer(server);
    }

    @Override
    public String setMetadataById(String id, Map<String, String> metadata, String project, Boolean overwrite, Boolean ignoreBlank) throws NoServerPrefException, NotFoundException {
        return null;
    }

    @Override
    public String pingServer() throws NoServerPrefException, ContainerServerException {
        return controlApi.pingServer();
    }

    @Override
    public String pingHub(ContainerHub hub) throws ContainerServerException, NoServerPrefException {
        return controlApi.pingHub(hub);
    }
}
