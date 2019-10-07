package org.nrg.containers.model.command.entity;

import com.google.common.base.MoreObjects;
import org.nrg.containers.model.command.auto.Command;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.persistence.*;
import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

@Entity
public class CommandInputEntity implements Serializable {
    public static Type DEFAULT_TYPE = Type.STRING;

    private long id;
    private CommandEntity commandEntity;
    private String name;
    private String label;
    private String description;
    private Type type = DEFAULT_TYPE;
    private Boolean required;
    private String matcher;
    private String defaultValue;
    private String rawReplacementKey;
    private String commandLineFlag = "";
    private String commandLineSeparator = " ";
    private String trueValue;
    private String falseValue;
    private Boolean sensitive;
    private List<String> selectValues = Collections.emptyList();
    private MultipleDelimiter multipleDelimiter;

    public static CommandInputEntity fromPojo(final Command.CommandInput commandInput) {
        return new CommandInputEntity().update(commandInput);
    }

    @Nonnull
    public CommandInputEntity update(final Command.CommandInput commandInput) {
        if (this.id == 0L || commandInput.id() != 0L) {
            this.setId(commandInput.id());
        }
        this.setName(commandInput.name());
        this.setLabel(commandInput.label());
        this.setDescription(commandInput.description());
        this.setRequired(commandInput.required());
        this.setMatcher(commandInput.matcher());
        this.setDefaultValue(commandInput.defaultValue());
        this.setRawReplacementKey(commandInput.rawReplacementKey());
        this.setCommandLineFlag(commandInput.commandLineFlag());
        this.setCommandLineSeparator(commandInput.commandLineSeparator());
        this.setTrueValue(commandInput.trueValue());
        this.setFalseValue(commandInput.falseValue());
        this.setSensitive(commandInput.sensitive());
        this.setSelectValues(commandInput.selectValues());
        this.setMultipleDelimiterByName(commandInput.multipleDelimiter());

        switch (commandInput.type()) {
            case "string":
                this.setType(Type.STRING);
                break;
            case "boolean":
                this.setType(Type.BOOLEAN);
                break;
            case "number":
                this.setType(Type.NUMBER);
                break;
            case "select-one":
                this.setType(Type.SELECT);
                break;
            case "select-many":
                this.setType(Type.MULTISELECT);
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
    public CommandEntity getCommandEntity() {
        return commandEntity;
    }

    public void setCommandEntity(final CommandEntity commandEntity) {
        this.commandEntity = commandEntity;
    }

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    @Column(columnDefinition = "TEXT")
    public String getDescription() {
        return description;
    }

    public void setDescription(final String description) {
        this.description = description;
    }

    @Enumerated(EnumType.STRING)
    public Type getType() {
        return type;
    }

    public void setType(final Type type) {
        this.type = type;
    }

    public Boolean getRequired() {
        return required;
    }

    @Transient
    public boolean isRequired() {
        return required != null && required;
    }

    public void setRequired(final Boolean required) {
        this.required = required;
    }

    public String getMatcher() {
        return matcher;
    }

    public void setMatcher(final String matcher) {
        this.matcher = matcher;
    }

    @Column(columnDefinition = "TEXT")
    public String getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(final String value) {
        this.defaultValue = value;
    }

    public String getRawReplacementKey() {
        return rawReplacementKey;
    }

    public void setRawReplacementKey(final String rawReplacementKey) {
        this.rawReplacementKey = rawReplacementKey;
    }

    public String getCommandLineFlag() {
        return commandLineFlag;
    }

    public void setCommandLineFlag(final String commandLineFlag) {
        this.commandLineFlag = commandLineFlag;
    }

    public String getCommandLineSeparator() {
        return commandLineSeparator;
    }

    public void setCommandLineSeparator(final String commandLineSeparator) {
        this.commandLineSeparator = commandLineSeparator;
    }

    public String getTrueValue() {
        return trueValue;
    }

    public void setTrueValue(final String trueValue) {
        this.trueValue = trueValue;
    }

    public String getFalseValue() {
        return falseValue;
    }

    public void setFalseValue(final String falseValue) {
        this.falseValue = falseValue;
    }

    public Boolean getSensitive() {
        return sensitive;
    }

    public void setSensitive(final Boolean sensitive) {
        this.sensitive = sensitive;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    @Enumerated(EnumType.STRING)
    public MultipleDelimiter getMultipleDelimiter() {
        return multipleDelimiter;
    }

    public void setMultipleDelimiter(MultipleDelimiter multipleDelimiter) {
        this.multipleDelimiter = multipleDelimiter;
    }

    public void setMultipleDelimiterByName(String multipleDelimiterName) {
        if (multipleDelimiterName != null) {
            this.setMultipleDelimiter(MultipleDelimiter.getByName(multipleDelimiterName));
        } else {
            this.setMultipleDelimiter(null);
        }
    }

    @ElementCollection
    public List<String> getSelectValues() {
        return selectValues;
    }

    public void setSelectValues(List<String> selectValues) {
        this.selectValues = selectValues;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final CommandInputEntity that = (CommandInputEntity) o;
        return Objects.equals(this.commandEntity, that.commandEntity) &&
                Objects.equals(this.name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commandEntity, name);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("id", id)
                .add("name", name)
                .add("label", label)
                .add("description", description)
                .add("type", type)
                .add("required", required)
                .add("matcher", matcher)
                .add("defaultValue", defaultValue)
                .add("rawReplacementKey", rawReplacementKey)
                .add("commandLineFlag", commandLineFlag)
                .add("commandLineSeparator", commandLineSeparator)
                .add("trueValue", trueValue)
                .add("falseValue", falseValue)
                .add("sensitive", sensitive)
                .add("selectValues", selectValues)
                .add("multipleDelimiter", multipleDelimiter)
                .toString();
    }

    public enum Type {
        STRING("string"),
        BOOLEAN("boolean"),
        NUMBER("number"),
        SELECT("select-one"),
        MULTISELECT("select-many");

        public final String name;

        Type(final String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }


    public enum MultipleDelimiter {
        QUOTED_SPACE("quoted-space"),
        SPACE("space"),
        COMMA("comma"),
        FLAG("flag");

        public final String name;

        MultipleDelimiter(final String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        @Nonnull
        public static MultipleDelimiter getByName(@Nullable String name) {
            if (name == null) name="";
            switch (name) {
                case "quoted-space":
                    return MultipleDelimiter.QUOTED_SPACE;
                case "comma":
                    return MultipleDelimiter.COMMA;
                case "flag":
                    return MultipleDelimiter.FLAG;
                case "space":
                default:
                    return MultipleDelimiter.SPACE;
            }
        }

        public static List<String> names() {
            return Arrays.stream(MultipleDelimiter.values())
                    .map(MultipleDelimiter::getName)
                    .collect(Collectors.toList());
        }
    }
}