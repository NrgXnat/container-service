package org.nrg.containers.model.command.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    SUBJECT_ASSESSOR("SubjectAssessor"),
    SESSION("Session"),
    SCAN("Scan"),
    ASSESSOR("Assessor"),
    RESOURCE("Resource"),
    CONFIG("Config"),
    FILE_INPUT("file");

    private final String name;

    private static final Map<String, CommandWrapperInputType> ENUM_MAP;
    static {
        Map<String, CommandWrapperInputType> map = new ConcurrentHashMap<>(CommandWrapperInputType.values().length);
        for (CommandWrapperInputType instance : CommandWrapperInputType.values()) {
            map.put(instance.getName().toLowerCase(), instance);
        }
        ENUM_MAP = Collections.unmodifiableMap(map);
    }

    @JsonCreator
    CommandWrapperInputType(final String name) {
        this.name = name;
    }

    @JsonValue
    public String getName() {
        return name;
    }

    public static Collection<String> names() {
        return ENUM_MAP.keySet();
    }

    @Nullable
    public static CommandWrapperInputType fromName(String name) {
        return ENUM_MAP.get(name.toLowerCase());
    }

    public static List<String> xnatTypeNames() {
        return Stream.of(
                DIRECTORY, FILE, FILES, PROJECT, PROJECT_ASSET, SUBJECT, SESSION, SCAN, ASSESSOR, RESOURCE, CONFIG
        ).map(CommandWrapperInputType::getName).collect(Collectors.toList());
    }
}
