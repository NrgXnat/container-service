package org.nrg.containers.jms.requests;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

@Data
@EqualsAndHashCode(callSuper = false)
public class ContainerFinalizingRequest extends ContainerRequest implements Serializable {
    public static final String DESTINATION = "containerFinalizingRequest";

    private static final long serialVersionUID = 1388953760707461670L;

    private final String  exitCodeString;
    private final boolean isSuccessful;
    private final String  id;
    private final String  username;

    public String getDestination() {
        return DESTINATION;
    }
}
