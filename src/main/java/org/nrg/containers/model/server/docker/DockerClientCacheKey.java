package org.nrg.containers.model.server.docker;

import lombok.AllArgsConstructor;
import lombok.Value;

@Value
@AllArgsConstructor
public class DockerClientCacheKey {
    String host;
    String certPath;
    Backend backend;

    public DockerClientCacheKey(final DockerServerBase.DockerServer dockerServer) {
        this(dockerServer.host(), dockerServer.certPath(), dockerServer.backend());
    }
}
