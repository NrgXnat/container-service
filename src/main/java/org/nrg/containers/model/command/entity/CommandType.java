package org.nrg.containers.model.command.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;

public enum CommandType {

    DOCKER("docker"),
    DOCKER_SETUP("docker-setup"),
    DOCKER_WRAPUP("docker-wrapup");

    private final String name;

    @JsonCreator
    CommandType(final String name) {
        this.name = name;
    }

    @JsonValue
    public String getName() {
        return name;
    }

    public String shortName() {
        // Remove docker
        return name.substring("docker".length());
    }

    public static List<String> names() {
        return EnumSet.allOf(CommandType.class).stream().map(CommandType::getName).collect(Collectors.toList());
    }

    public static CommandType withName(final String name) {
        for (final CommandType type : EnumSet.allOf(CommandType.class)) {
            if (type.name.equals(name)) {
                return type;
            }
        }
        return null;
    }

}
