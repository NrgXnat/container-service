package org.nrg.containers.model.command.entity;

import com.google.common.base.Function;
import com.google.common.base.MoreObjects;
import com.google.common.collect.Lists;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.xft.security.UserI;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.ManyToOne;
import java.io.File;
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
    private String xsiType;
    private String label;
    private String format;
    private String description;
    private String content;
    private List<String> tags;

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
        this.setFormat(commandWrapperOutput.format());
        this.setDescription(commandWrapperOutput.description());
        this.setContent(commandWrapperOutput.content());
        this.setTags(commandWrapperOutput.tags());
        this.setXsiType(commandWrapperOutput.xsiType());

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

    public String getXsiType() {
        return xsiType;
    }

    public void setXsiType(String xsiType) {
        this.xsiType = xsiType;
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

    public String getDescription() { return description; }

    public void setDescription(String description) { this.description = description; }

    public String getContent() { return content; }

    public void setContent(String content) { this.content = content; }

    @ElementCollection(fetch = FetchType.EAGER)
    public List<String> getTags() { return tags; }

    public void setTags(List<String> tags) { this.tags = tags; }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final CommandWrapperOutputEntity that = (CommandWrapperOutputEntity) o;
        return id == that.id &&
                Objects.equals(name, that.name) &&
                Objects.equals(commandOutputName, that.commandOutputName) &&
                Objects.equals(wrapperInputName, that.wrapperInputName) &&
                Objects.equals(viaWrapupCommand, that.viaWrapupCommand) &&
                type == that.type &&
                Objects.equals(xsiType, that.xsiType) &&
                Objects.equals(label, that.label) &&
                Objects.equals(format, that.format) &&
                Objects.equals(description, that.description) &&
                Objects.equals(content, that.content) &&
                Objects.equals(tags, that.tags);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id,
                name,
                commandOutputName,
                wrapperInputName,
                viaWrapupCommand,
                type,
                xsiType,
                label,
                format,
                description,
                content,
                tags);
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
                .add("description", description)
                .add("content", content)
                .add("tags", tags)
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

        public static Type fromName(String name) {
            for (Type t : values()) {
                if (t.name.equalsIgnoreCase(name)) {
                    return t;
                }
            }
            return null;
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
