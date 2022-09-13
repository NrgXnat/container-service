package org.nrg.containers.initialization.tasks;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.nrg.containers.exceptions.InvalidDefinitionException;
import org.nrg.containers.model.server.docker.DockerServerBase.DockerServer;
import org.nrg.containers.model.server.docker.DockerServerPrefsBean;
import org.nrg.containers.services.DockerServerService;
import org.nrg.xnat.initialization.tasks.AbstractInitializingTask;
import org.nrg.xnat.initialization.tasks.InitializingTaskException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class CheckDockerServerIsDefined extends AbstractInitializingTask {
    private final DockerServerService dockerServerService;
    private final DockerServerPrefsBean dockerServerPrefsBean;

    @Autowired
    public CheckDockerServerIsDefined(final DockerServerService dockerServerService,
                                      final DockerServerPrefsBean dockerServerPrefsBean) {
        this.dockerServerService = dockerServerService;
        this.dockerServerPrefsBean = dockerServerPrefsBean;
    }

    @Override
    public String getTaskName() {
        return "Check that a docker server is defined";
    }

    @Override
    protected void callImpl() throws InitializingTaskException {

       log.debug("Checking if any docker servers exist in database.");
       final List<DockerServer> servers = dockerServerService.getServers();
       final boolean anyServersInDb = !(servers == null || servers.isEmpty());
       final boolean oneServerInDb = (anyServersInDb && servers.size() == 1); // This is just to make the debug message nicer
       log.debug("{} server{} exist{} in db.", anyServersInDb ? servers.size() : "No", oneServerInDb ? "" : "s", oneServerInDb ? "s" : "");
       if (anyServersInDb) {
           log.debug("All is well.");
           return;
       }

        // We don't have any servers in the database. Do we have one defined as an old-style prefs bean?
        try {
            log.debug("Checking if a docker server exists as an old-style prefs bean.");
            final boolean serverPrefsBeanDefined = dockerServerPrefsBean != null && StringUtils.isNotBlank(dockerServerPrefsBean.getHost());
            if (serverPrefsBeanDefined) {
                log.debug("A docker server prefs bean does exist. Saving it in the database.");
                final DockerServer convertedPrefsBean = DockerServer.create(dockerServerPrefsBean);
                dockerServerService.setServer(convertedPrefsBean);
                log.debug("All done.");
                return;
            } else {
                log.debug("A docker server prefs bean does not exist.");
            }

            // Nothing in DB and no prefs beans. Just make the default.
            log.debug("Creating default docker server: default socket connection.");
                dockerServerService.setServer(DockerServer.DEFAULT_SOCKET);
        } catch (InvalidDefinitionException e) {
            throw new InitializingTaskException(InitializingTaskException.Level.Error,
                    "Unable to set server", e);
        }
    }
}
