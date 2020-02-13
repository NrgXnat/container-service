package org.nrg.containers.services;

import org.nrg.containers.model.container.auto.Container;
import org.nrg.xft.security.UserI;

import javax.annotation.Nullable;
import java.util.List;

public interface ContainerFinalizeService {
    Container finalizeContainer(Container toFinalize,
                                UserI userI,
                                boolean isFailed, final List<Container> wrapupContainers);

    void sendContainerStatusUpdateEmail(UserI user,
                                        boolean completionStatus,
                                        String pipelineName,
                                        String xnatId,
                                        String xnatLabel,
                                        String project,
                                        @Nullable List<String> filePaths);
}
