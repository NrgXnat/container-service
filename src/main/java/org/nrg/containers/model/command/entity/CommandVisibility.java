package org.nrg.containers.model.command.entity;

import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.omg.CORBA.PRIVATE_MEMBER;


public enum  CommandVisibility {

    PUBLIC_CONTAINER("public"),
    PRIVATE_CONTAINER("private"),
    PROTECTED_CONTAINER("protected");

    private final String visibilityType;

    @JsonCreator
    CommandVisibility(final String visibilityType) {
         this.visibilityType = visibilityType;
    }

    @JsonValue
    public String getVisibilityType() {
        return visibilityType;
    }

    public static List<String> visibilityTypes() {
        return EnumSet.allOf(CommandVisibility.class).stream().map(CommandVisibility::getVisibilityType).collect(Collectors.toList());
    }

    public static CommandVisibility withVisibilityType(final String visibilityType) {
        for (final CommandVisibility type : EnumSet.allOf(CommandVisibility.class)) {
            if (type.visibilityType.equals(visibilityType)) {
                return type;
            }
        }
        return null;
    }

}
