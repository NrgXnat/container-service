package org.nrg.containers.model.command.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.google.common.base.Function;
import com.google.common.collect.Lists;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;

public enum CommandWrapperInputType {
    STRING("string"),
    BOOLEAN("boolean"),
    NUMBER("number"),
    DIRECTORY("Directory"),
    FILES("File[]"),
    FILE("File"),
    PROJECT("Project"),
    PROJECT_ASSET("ProjectAsset"),
    SUBJECT("Subject"),
    SESSION("Session"),
    SCAN("Scan"),
    ASSESSOR("Assessor"),
    RESOURCE("Resource"),
    CONFIG("Config");

    private final String name;

    @JsonCreator
    CommandWrapperInputType(final String name) {
        this.name = name;
    }

    @JsonValue
    public String getName() {
        return name;
    }

    public static List<String> names() {
        return Lists.transform(Arrays.asList(CommandWrapperInputType.values()), new Function<CommandWrapperInputType, String>() {
            @Nullable
            @Override
            public String apply(@Nullable final CommandWrapperInputType type) {
                return type != null ? type.getName() : "";
            }
        });
    }
}
