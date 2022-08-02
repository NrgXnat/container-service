package org.nrg.containers.events.model;

import org.nrg.framework.event.EventI;

import java.util.Map;

public interface ContainerEvent extends EventI {
    String backendId();
    String status();
    String details();
    String externalTimestamp();
    Map<String, String> attributes();
    boolean isExitStatus();
    String exitCode();
}
