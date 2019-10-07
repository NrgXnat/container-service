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

    @Nullable
    public static CommandWrapperInputType fromName(String text) {
        for (CommandWrapperInputType e : CommandWrapperInputType.values()) {
            if (e.name.equalsIgnoreCase(text)) {
                return e;
            }
        }
        return null;
    }

    public static List<String> xnatTypeNames() {
        return Arrays.asList(DIRECTORY.getName(), FILE.getName(), FILES.getName(), PROJECT.getName(), SUBJECT.getName(),
                SESSION.getName(), SCAN.getName(), ASSESSOR.getName(), RESOURCE.getName(), CONFIG.getName());
    }
}
