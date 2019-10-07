package org.nrg.containers.model.command.entity;

import com.google.common.base.Function;
import com.google.common.base.MoreObjects;
import com.google.common.collect.Lists;
import org.hibernate.envers.Audited;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.xft.security.UserI;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.persistence.*;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Entity
public class CommandWrapperOutputEntity {
    public static final Type DEFAULT_TYPE = Type.RESOURCE;

    private long id;
    private CommandWrapperEntity commandWrapperEntity;
    private String name;
    private String commandOutputName;
    private String wrapperInputName;
    private String viaWrapupCommand;
    private Type type;
    private String label;
    private String format;

    public static CommandWrapperOutputEntity fromPojo(final Command.CommandWrapperOutput commandWrapperOutput) {
        return new CommandWrapperOutputEntity().update(commandWrapperOutput);
    }

    @Nonnull
    public CommandWrapperOutputEntity update(final @Nonnull Command.CommandWrapperOutput commandWrapperOutput) {
        if (this.id == 0L || commandWrapperOutput.id() != 0L) {
            this.setId(commandWrapperOutput.id());
        }
        this.setName(commandWrapperOutput.name());
        this.setCommandOutputName(commandWrapperOutput.commandOutputName());
        this.setWrapperInputName(commandWrapperOutput.targetName());
        this.setViaWrapupCommand(commandWrapperOutput.viaWrapupCommand());
        this.setLabel(commandWrapperOutput.label());

        switch (commandWrapperOutput.type()) {
            case "Resource":
                this.setType(Type.RESOURCE);
                break;
            case "Assessor":
                this.setType(Type.ASSESSOR);
                break;
            case "Scan":
                this.setType(Type.SCAN);
                break;
            default:
                this.setType(DEFAULT_TYPE);
        }

        return this;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public long getId() {
        return id;
    }

    public void setId(final long id) {
        this.id = id;
    }

    @ManyToOne
    public CommandWrapperEntity getCommandWrapperEntity() {
        return commandWrapperEntity;
    }

    public void setCommandWrapperEntity(final CommandWrapperEntity commandWrapperEntity) {
        this.commandWrapperEntity = commandWrapperEntity;
    }

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public Type getType() {
        return type;
    }

    public void setType(final Type type) {
        this.type = type;
    }

    public String getCommandOutputName() {
        return commandOutputName;
    }

    public void setCommandOutputName(final String commandOutputName) {
        this.commandOutputName = commandOutputName;
    }

    public String getWrapperInputName() {
        return wrapperInputName;
    }

    public void setWrapperInputName(final String wrapperInputName) {
        this.wrapperInputName = wrapperInputName;
    }

    public String getViaWrapupCommand() {
        return viaWrapupCommand;
    }

    public void setViaWrapupCommand(final String viaWrapupCommand) {
        this.viaWrapupCommand = viaWrapupCommand;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(final String label) {
        this.label = label;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(final String format) {
        this.format = format;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final CommandWrapperOutputEntity that = (CommandWrapperOutputEntity) o;
        return Objects.equals(this.commandWrapperEntity, that.commandWrapperEntity) &&
                Objects.equals(this.name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commandWrapperEntity, name);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("id", id)
                .add("name", name)
                .add("commandOutputName", commandOutputName)
                .add("wrapperInputName", wrapperInputName)
                .add("viaWrapupCommand", viaWrapupCommand)
                .add("type", type)
                .add("label", label)
                .add("format", format)
                .toString();
    }

    public enum Type {
        RESOURCE("Resource"),
        ASSESSOR("Assessor"),
        SCAN("Scan");

        private final String name;

        private static List<String> supportedParentOutputTypeNames = Arrays.asList(
                ASSESSOR.getName(),
                SCAN.getName()
        );

        Type(final String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public static List<String> names() {
            return Lists.transform(Arrays.asList(Type.values()), new Function<Type, String>() {
                @Nullable
                @Override
                public String apply(@Nullable final Type type) {
                    return type != null ? type.getName() : "";
                }
            });
        }

        /**
         * @return list of Types that support children, aka for which another type can specify this to its "as-a-child-of" property
         */
        public static List<String> supportedParentOutputTypeNames() {
            return supportedParentOutputTypeNames;
        }

        /**
         * @return list of Types that ought to be uploaded via
         * {@link org.nrg.xnat.services.archive.CatalogService#insertXmlObject(UserI, File, boolean, Map, Integer)}
         */
        public static List<String> xmlUploadTypes() {
            // Currently, is the same as supportedParentOutputTypeNames, but make this separate method bc that
            // doesn't have to be the case
            return supportedParentOutputTypeNames;
        }
    }
}
