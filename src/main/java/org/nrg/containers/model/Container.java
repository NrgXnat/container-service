package org.nrg.containers.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModelProperty;
import org.apache.commons.lang.StringUtils;

import java.util.Map;

public class Container {

    public Container() {}

    public Container(final String id, final String status) {
        _id = id;
        _status = status;
    }

    /**
     * The container's id.
     **/
    @ApiModelProperty(value = "The container's id.")
    @JsonProperty("id")
    public String id() {
        return _id;
    }

    public void setId(final String id) {
        _id = id;
    }

    /**
     * The container's status.
     **/
    @ApiModelProperty(value = "The container's status.")
    @JsonProperty("status")
    public String status() {
        return _status;
    }

    public void setStatus(final String status) {
        _status = status;
    }

    public String toString() {
        return "Container{\n" +
                "\tid : " + _id + ",\n" +
                "\tstatus : " + _status + "\n}";
    }

    private String _id;
    private String _status;
}
