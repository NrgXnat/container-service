package org.nrg.containers.jms.requests;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = false)
public class ContainerStagingRequest extends ContainerRequest implements Serializable {
    public static final String DESTINATION = "containerStagingRequest";

    private static final long serialVersionUID = -1608362951558668332L;

    private final String              project;
    private final long                wrapperId;
    private final long                commandId;
    private final String              wrapperName;
    private final Map<String, String> inputValues;
    private final String              username;
    private final String              workflowId;

    public String getDestination() {
        return DESTINATION;
    }
}
