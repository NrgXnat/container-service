package org.nrg.containers.model.command.entity;

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.hibernate.envers.Audited;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.server.docker.DockerServerEntitySwarmConstraint;
import org.nrg.framework.orm.hibernate.AbstractHibernateEntity;

import javax.annotation.Nonnull;
import javax.persistence.*;
import java.util.*;

@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "type")
@Table(
        uniqueConstraints = {@UniqueConstraint(columnNames = {"name", "image", "version"})}
)
public abstract class CommandEntity extends AbstractHibernateEntity {
    public static CommandType DEFAULT_TYPE = CommandType.DOCKER;
    private String name;
    private String label;
    private String description;
    private String version;
    private String schemaVersion;
    private String infoUrl;
    private String image;
    private String workingDirectory;
    private String commandLine;
    private Boolean overrideEntrypoint;
    private List<CommandMountEntity> mounts;
    private Map<String, String> environmentVariables;
    private List<CommandInputEntity> inputs;
    private List<CommandOutputEntity> outputs;
    private List<CommandWrapperEntity> commandWrapperEntities;
    private Long reserveMemory;
    private Long limitMemory;
    private Double limitCpu;

    @Nonnull
    public static CommandEntity fromPojo(@Nonnull final Command command) {
        final CommandEntity commandEntity;
        switch (command.type()) {
            case "docker":
                commandEntity = new DockerCommandEntity();
                break;
            case "docker-setup":
                commandEntity = new DockerSetupCommandEntity();
                break;
            case "docker-wrapup":
                commandEntity = new DockerWrapupCommandEntity();
                break;
            default:
                // This should have been caught already, but still...
                throw new RuntimeException("Cannot instantiate command with type " + command.type());
        }
        return commandEntity.update(command);
    }


    @Nonnull
    public CommandEntity update(@Nonnull final Command command) {
        if (this.getId() == 0L || command.id() != 0L) {
            this.setId(command.id());
        }
        this.setName(command.name());
        this.setLabel(command.label());
        this.setDescription(command.description());
        this.setVersion(command.version());
        this.setSchemaVersion(command.schemaVersion());
        this.setInfoUrl(command.infoUrl());
        this.setImage(command.image());
        this.setWorkingDirectory(command.workingDirectory());
        this.setCommandLine(command.commandLine());
        this.setOverrideEntrypoint(command.overrideEntrypoint());
        this.setEnvironmentVariables(command.environmentVariables());
        this.setReserveMemory(command.reserveMemory());
        this.setLimitMemory(command.limitMemory());
        this.setLimitCpu(command.limitCpu());

        final List<CommandMountEntity> toRemoveMount = new ArrayList<>();
        final Map<String, Command.CommandMount> mountsByName = new HashMap<>();
        for (final Command.CommandMount commandMount : command.mounts()) {
            mountsByName.put(commandMount.name(), commandMount);
        }
        final List<CommandMountEntity> mountEntities = this.mounts == null ? Collections.<CommandMountEntity>emptyList() : this.mounts;
        for (final CommandMountEntity commandMountEntity : mountEntities) {
            if (mountsByName.containsKey(commandMountEntity.getName())) {
                commandMountEntity.update(mountsByName.get(commandMountEntity.getName()));
                mountsByName.remove(commandMountEntity.getName());
            } else {
                toRemoveMount.add(commandMountEntity);
            }
        }
        for (final Command.CommandMount commandMount : command.mounts()) {
            if (mountsByName.containsKey(commandMount.name())) {
                this.addMount(CommandMountEntity.fromPojo(commandMount));
            }
        }
        for (final CommandMountEntity commandMountEntity : toRemoveMount) {
            this.removeMount(commandMountEntity);
        }

        final List<CommandInputEntity> toRemoveInput = new ArrayList<>();
        final Map<String, Command.CommandInput> inputsByName = new HashMap<>();
        for (final Command.CommandInput commandInput : command.inputs()) {
            inputsByName.put(commandInput.name(), commandInput);
        }
        final List<CommandInputEntity> inputEntities = this.inputs == null ? Collections.<CommandInputEntity>emptyList() : this.inputs;
        for (final CommandInputEntity commandInputEntity : inputEntities) {
            if (inputsByName.containsKey(commandInputEntity.getName())) {
                commandInputEntity.update(inputsByName.get(commandInputEntity.getName()));
                inputsByName.remove(commandInputEntity.getName());
            } else {
                toRemoveInput.add(commandInputEntity);
            }
        }
        for (final Command.CommandInput commandInput : command.inputs()) {
            if (inputsByName.containsKey(commandInput.name())) {
                this.addInput(CommandInputEntity.fromPojo(commandInput));
            }
        }
        for (final CommandInputEntity commandInputEntity : toRemoveInput) {
            this.removeInput(commandInputEntity);
        }

        final List<CommandOutputEntity> toRemoveOutput = new ArrayList<>();
        final Map<String, Command.CommandOutput> outputsByName = new HashMap<>();
        for (final Command.CommandOutput commandOutput : command.outputs()) {
            outputsByName.put(commandOutput.name(), commandOutput);
        }
        final List<CommandOutputEntity> outputEntities = this.outputs == null ? Collections.<CommandOutputEntity>emptyList() : this.outputs;
        for (final CommandOutputEntity commandOutputEntity : outputEntities) {
            if (outputsByName.containsKey(commandOutputEntity.getName())) {
                commandOutputEntity.update(outputsByName.get(commandOutputEntity.getName()));
                outputsByName.remove(commandOutputEntity.getName());
            } else {
                toRemoveOutput.add(commandOutputEntity);
            }
        }
        for (final Command.CommandOutput commandOutput : command.outputs()) {
            if (outputsByName.containsKey(commandOutput.name())) {
                this.addOutput(CommandOutputEntity.fromPojo(commandOutput));
            }
        }
        for (final CommandOutputEntity commandOutputEntity : toRemoveOutput) {
            this.removeOutput(commandOutputEntity);
        }

        final List<CommandWrapperEntity> toRemoveWrapper = new ArrayList<>();
        final Map<String, Command.CommandWrapper> wrappersByName = new HashMap<>();
        for (final Command.CommandWrapper commandWrapper : command.xnatCommandWrappers()) {
            wrappersByName.put(commandWrapper.name(), commandWrapper);
        }
        final List<CommandWrapperEntity> commandWrapperEntities = this.commandWrapperEntities == null ? Collections.<CommandWrapperEntity>emptyList() : this.commandWrapperEntities;
        for (final CommandWrapperEntity commandWrapperEntity : commandWrapperEntities) {
            if (wrappersByName.containsKey(commandWrapperEntity.getName())) {
                commandWrapperEntity.update(wrappersByName.get(commandWrapperEntity.getName()));
                wrappersByName.remove(commandWrapperEntity.getName());
            } else {
                toRemoveWrapper.add(commandWrapperEntity);
            }
        }
        for (final Command.CommandWrapper commandWrapper : command.xnatCommandWrappers()) {
            if (wrappersByName.containsKey(commandWrapper.name())) {
                this.addWrapper(CommandWrapperEntity.fromPojo(commandWrapper));
            }
        }
        for (final CommandWrapperEntity commandWrapperEntity : toRemoveWrapper) {
            this.removeWrapper(commandWrapperEntity);
        }

        return this;
    }

    @Transient
    public abstract CommandType getType();

    // @javax.persistence.Id
    // @GeneratedValue(strategy = GenerationType.TABLE)
    // // @Override
    // public long getId() {
    //     return super.getId();
    // }

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(final String label) {
        this.label = label;
    }

    @Column(columnDefinition = "TEXT")
    public String getDescription() {
        return description;
    }

    public void setDescription(final String description) {
        this.description = description;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(final String version) {
        this.version = version;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(final String schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public String getInfoUrl() {
        return infoUrl;
    }

    public void setInfoUrl(final String infoUrl) {
        this.infoUrl = infoUrl;
    }

    public String getImage() {
        return image;
    }

    public void setImage(final String image) {
        this.image = image;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public void setWorkingDirectory(final String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    @Column(columnDefinition = "TEXT")
    public String getCommandLine() {
        return commandLine;
    }

    public void setCommandLine(final String commandLine) {
        this.commandLine = commandLine;
    }

    public Boolean getOverrideEntrypoint() {
        return overrideEntrypoint;
    }

    public void setOverrideEntrypoint(final Boolean overrideEntrypoint) {
        this.overrideEntrypoint = overrideEntrypoint;
    }

    public Long getReserveMemory() {
        return reserveMemory;
    }

    public void setReserveMemory(Long reserveMemory) {
        this.reserveMemory = reserveMemory;
    }

    public Long getLimitMemory() {
        return limitMemory;
    }

    public void setLimitMemory(Long limitMemory) {
        this.limitMemory = limitMemory;
    }

    public Double getLimitCpu() {
        return limitCpu;
    }

    public void setLimitCpu(Double limitCpu) {
        this.limitCpu = limitCpu;
    }

    @OneToMany(mappedBy = "commandEntity", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy
    public List<CommandMountEntity> getMounts() {
        return mounts;
    }

    public void setMounts(final List<CommandMountEntity> mounts) {
        this.mounts = mounts == null ?
                Lists.<CommandMountEntity>newArrayList() :
                mounts;
        for (final CommandMountEntity mount : this.mounts) {
            mount.setCommandEntity(this);
        }
    }

    public void addMount(final CommandMountEntity mount) {
        if (mount == null) {
            return;
        }
        mount.setCommandEntity(this);

        if (this.mounts == null) {
            this.mounts = Lists.newArrayList();
        }
        if (!this.mounts.contains(mount)) {
            this.mounts.add(mount);
        }
    }
    public void removeMount(final CommandMountEntity mount) {
        if (mount == null || this.mounts == null || !this.mounts.contains(mount)) {
            return;
        }
        this.mounts.remove(mount);
        mount.setCommandEntity(null);
    }

    @ElementCollection
    public Map<String, String> getEnvironmentVariables() {
        return environmentVariables;
    }

    public void setEnvironmentVariables(final Map<String, String> environmentVariables) {
        this.environmentVariables = environmentVariables == null ?
                Maps.<String, String>newHashMap() :
                environmentVariables;
    }

    @OneToMany(mappedBy = "commandEntity", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy
    public List<CommandInputEntity> getInputs() {
        return inputs;
    }

    public void setInputs(final List<CommandInputEntity> inputs) {
        this.inputs = inputs == null ?
                Lists.<CommandInputEntity>newArrayList() :
                inputs;
        for (final CommandInputEntity input : this.inputs) {
            input.setCommandEntity(this);
        }
    }

    public void addInput(final CommandInputEntity input) {
        if (input == null) {
            return;
        }
        input.setCommandEntity(this);

        if (this.inputs == null) {
            this.inputs = Lists.newArrayList();
        }
        if (!this.inputs.contains(input)) {
            this.inputs.add(input);
        }
    }
    public void removeInput(final CommandInputEntity input) {
        if (input == null || this.inputs == null || !this.inputs.contains(input)) {
            return;
        }
        this.inputs.remove(input);
        input.setCommandEntity(null);
    }

    @OneToMany(mappedBy = "commandEntity", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy
    public List<CommandOutputEntity> getOutputs() {
        return outputs;
    }

    public void setOutputs(final List<CommandOutputEntity> outputs) {
        this.outputs = outputs == null ?
                Lists.<CommandOutputEntity>newArrayList() :
                outputs;
        for (final CommandOutputEntity output : this.outputs) {
            output.setCommandEntity(this);
        }
    }

    public void addOutput(final CommandOutputEntity output) {
        if (output == null) {
            return;
        }
        output.setCommandEntity(this);

        if (this.outputs == null) {
            this.outputs = Lists.newArrayList();
        }
        if (!this.outputs.contains(output)) {
            this.outputs.add(output);
        }
    }
    public void removeOutput(final CommandOutputEntity output) {
        if (output == null || this.outputs == null || !this.outputs.contains(output)) {
            return;
        }
        this.outputs.remove(output);
        output.setCommandEntity(null);
    }

    @OneToMany(mappedBy = "commandEntity", cascade = CascadeType.ALL)
    @OrderBy
    public List<CommandWrapperEntity> getCommandWrapperEntities() {
        return commandWrapperEntities;
    }

    public void setCommandWrapperEntities(final List<CommandWrapperEntity> commandWrapperEntities) {
        this.commandWrapperEntities = commandWrapperEntities == null ?
                Lists.<CommandWrapperEntity>newArrayList() :
                commandWrapperEntities;
    }

    @Transient
    public void addWrapper(final CommandWrapperEntity commandWrapperEntity) {
        if (commandWrapperEntity == null) {
            return;
        }
        commandWrapperEntity.setCommandEntity(this);

        if (this.commandWrapperEntities == null) {
            this.commandWrapperEntities = Lists.newArrayList();
        }
        if (!this.commandWrapperEntities.contains(commandWrapperEntity)) {
            this.commandWrapperEntities.add(commandWrapperEntity);
        }
    }
    public void removeWrapper(final CommandWrapperEntity wrapper) {
        if (wrapper == null || this.commandWrapperEntities == null || !this.commandWrapperEntities.contains(wrapper)) {
            return;
        }
        this.commandWrapperEntities.remove(wrapper);
        wrapper.setCommandEntity(null);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final CommandEntity that = (CommandEntity) o;
        return Objects.equals(this.name, that.name) &&
                Objects.equals(this.version, that.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, version);
    }

    @Override
    public ToStringHelper addParentPropertiesToString(final ToStringHelper helper) {
        return super.addParentPropertiesToString(helper)
                .add("name", name)
                .add("label", label)
                .add("description", description)
                .add("version", version)
                .add("schemaVersion", schemaVersion)
                .add("infoUrl", infoUrl)
                .add("image", image)
                .add("workingDirectory", workingDirectory)
                .add("commandLine", commandLine)
                .add("overrideEntrypoint", overrideEntrypoint)
                .add("mounts", mounts)
                .add("environmentVariables", environmentVariables)
                .add("inputs", inputs)
                .add("outputs", outputs)
                .add("xnatCommandWrappers", commandWrapperEntities)
                .add("reserveMemory", reserveMemory)
                .add("limitMemory", limitMemory)
                .add("limitCpu", limitCpu);
    }

    @Override
    public String toString() {
        return addParentPropertiesToString(MoreObjects.toStringHelper(this))
                .toString();
    }
}
