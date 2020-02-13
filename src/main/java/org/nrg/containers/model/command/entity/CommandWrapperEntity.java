package org.nrg.containers.model.command.entity;

import com.google.common.base.MoreObjects;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.envers.Audited;
import org.nrg.containers.model.command.auto.Command;

import javax.annotation.Nonnull;
import javax.persistence.*;
import java.io.Serializable;
import java.util.*;

@Entity
public class CommandWrapperEntity implements Serializable {
    private long id;
    private String name;
    private String label;
    private String description;
    private CommandEntity commandEntity;
    private Set<String> contexts;
    private List<CommandWrapperExternalInputEntity> externalInputs;
    private List<CommandWrapperDerivedInputEntity> derivedInputs;
    private List<CommandWrapperOutputEntity> outputHandlers;

    @Nonnull
    public static CommandWrapperEntity fromPojo(final @Nonnull Command.CommandWrapper commandWrapper) {
        return new CommandWrapperEntity().update(commandWrapper);
    }

    @Nonnull
    public CommandWrapperEntity update(final @Nonnull Command.CommandWrapper commandWrapper) {
        if (this.id == 0L || commandWrapper.id() != 0L) {
            this.setId(commandWrapper.id());
        }
        this.setName(commandWrapper.name());
        this.setLabel(commandWrapper.label());
        this.setDescription(commandWrapper.description());
        this.setContexts(commandWrapper.contexts());

        final List<CommandWrapperExternalInputEntity> toRemoveExternalInputs = new ArrayList<>();
        final Map<String, Command.CommandWrapperExternalInput> externalInputsByName = new HashMap<>();
        for (final Command.CommandWrapperExternalInput externalCommandWrapperInput : commandWrapper.externalInputs()) {
            externalInputsByName.put(externalCommandWrapperInput.name(), externalCommandWrapperInput);
        }
        final List<CommandWrapperExternalInputEntity> externalInputEntities = this.externalInputs == null ? Collections.<CommandWrapperExternalInputEntity>emptyList() : this.externalInputs;
        for (final CommandWrapperExternalInputEntity externalInputEntity : externalInputEntities) {
            if (externalInputsByName.containsKey(externalInputEntity.getName())) {
                externalInputEntity.update(externalInputsByName.get(externalInputEntity.getName()));
                externalInputsByName.remove(externalInputEntity.getName());
            } else {
                toRemoveExternalInputs.add(externalInputEntity);
            }
        }
        for (final Command.CommandWrapperExternalInput externalInput : commandWrapper.externalInputs()) {
            if (externalInputsByName.containsKey(externalInput.name())) {
                this.addExternalInput(CommandWrapperExternalInputEntity.fromPojo(externalInput));
            }
        }
        for (final CommandWrapperExternalInputEntity externalInputEntity : toRemoveExternalInputs) {
            this.removeExternalInput(externalInputEntity);
        }

        final List<CommandWrapperDerivedInputEntity> toRemoveDerivedInputs = new ArrayList<>();
        final Map<String, Command.CommandWrapperDerivedInput> derivedInputsByName = new HashMap<>();
        for (final Command.CommandWrapperDerivedInput derivedCommandWrapperInput : commandWrapper.derivedInputs()) {
            derivedInputsByName.put(derivedCommandWrapperInput.name(), derivedCommandWrapperInput);
        }
        final List<CommandWrapperDerivedInputEntity> derivedInputEntities = this.derivedInputs == null ? Collections.<CommandWrapperDerivedInputEntity>emptyList() : this.derivedInputs;
        for (final CommandWrapperDerivedInputEntity derivedInputEntity : derivedInputEntities) {
            if (derivedInputsByName.containsKey(derivedInputEntity.getName())) {
                derivedInputEntity.update(derivedInputsByName.get(derivedInputEntity.getName()));
                derivedInputsByName.remove(derivedInputEntity.getName());
            } else {
                toRemoveDerivedInputs.add(derivedInputEntity);
            }
        }
        for (final Command.CommandWrapperDerivedInput derivedInput : commandWrapper.derivedInputs()) {
            if (derivedInputsByName.containsKey(derivedInput.name())) {
                this.addDerivedInput(CommandWrapperDerivedInputEntity.fromPojo(derivedInput));
            }
        }
        for (final CommandWrapperDerivedInputEntity derivedInputEntity : toRemoveDerivedInputs) {
            this.removeDerivedInput(derivedInputEntity);
        }

        final List<CommandWrapperOutputEntity> toRemoveOutputHandlers = new ArrayList<>();
        final Map<String, Command.CommandWrapperOutput> outputsByName = new HashMap<>();
        for (final Command.CommandWrapperOutput commandWrapperOutput : commandWrapper.outputHandlers()) {
            outputsByName.put(commandWrapperOutput.name(), commandWrapperOutput);
        }
        final List<CommandWrapperOutputEntity> outputEntities = this.outputHandlers == null ? Collections.<CommandWrapperOutputEntity>emptyList() : this.outputHandlers;
        for (final CommandWrapperOutputEntity outputEntity : outputEntities) {
            if (outputsByName.containsKey(outputEntity.getName())) {
                outputEntity.update(outputsByName.get(outputEntity.getName()));
                outputsByName.remove(outputEntity.getName());
            } else {
                toRemoveOutputHandlers.add(outputEntity);
            }
        }
        for (final Command.CommandWrapperOutput output : commandWrapper.outputHandlers()) {
            if (outputsByName.containsKey(output.name())) {
                this.addOutputHandler(CommandWrapperOutputEntity.fromPojo(output));
            }
        }
        for (final CommandWrapperOutputEntity outputEntity : toRemoveOutputHandlers) {
            this.removeOutputHandler(outputEntity);
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

    @ManyToOne
    public CommandEntity getCommandEntity() {
        return commandEntity;
    }

    public void setCommandEntity(final CommandEntity commandEntity) {
        this.commandEntity = commandEntity;
    }

    @ElementCollection
    public Set<String> getContexts() {
        return contexts;
    }

    public void setContexts(final Set<String> contexts) {
        this.contexts = contexts == null ?
                Sets.<String>newHashSet() :
                contexts;
    }

    public void addContext(final String context) {
        if (StringUtils.isBlank(context)) {
            return;
        }
        if (this.contexts == null) {
            this.contexts = Sets.newHashSet();
        }
        this.contexts.add(context);
    }

    @OneToMany(mappedBy = "commandWrapperEntity", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy
    public List<CommandWrapperExternalInputEntity> getExternalInputs() {
        return externalInputs;
    }

    public void setExternalInputs(final List<CommandWrapperExternalInputEntity> externalInputs) {
        this.externalInputs = externalInputs == null ?
                Lists.<CommandWrapperExternalInputEntity>newArrayList() :
                externalInputs;
        for (final CommandWrapperExternalInputEntity externalInput : this.externalInputs) {
            externalInput.setCommandWrapperEntity(this);
        }
    }

    public void addExternalInput(final CommandWrapperExternalInputEntity externalInput) {
        if (externalInput == null) {
            return;
        }
        externalInput.setCommandWrapperEntity(this);

        if (this.externalInputs == null) {
            this.externalInputs = Lists.newArrayList();
        }
        if (!this.externalInputs.contains(externalInput)) {
            this.externalInputs.add(externalInput);
        }
    }
    public void removeExternalInput(final CommandWrapperExternalInputEntity externalInput) {
        if (externalInput == null || this.externalInputs == null || !this.externalInputs.contains(externalInput)) {
            return;
        }
        this.externalInputs.remove(externalInput);
        externalInput.setCommandWrapperEntity(null);
    }

    @OneToMany(mappedBy = "commandWrapperEntity", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy
    public List<CommandWrapperDerivedInputEntity> getDerivedInputs() {
        return derivedInputs;
    }

    public void setDerivedInputs(final List<CommandWrapperDerivedInputEntity> derivedInputs) {
        this.derivedInputs = derivedInputs == null ?
                Lists.<CommandWrapperDerivedInputEntity>newArrayList() :
                derivedInputs;

        for (final CommandWrapperDerivedInputEntity derivedInput : this.derivedInputs) {
            derivedInput.setCommandWrapperEntity(this);
        }
    }

    public void addDerivedInput(final CommandWrapperDerivedInputEntity derivedInput) {
        if (derivedInput == null) {
            return;
        }
        derivedInput.setCommandWrapperEntity(this);

        if (this.derivedInputs == null) {
            this.derivedInputs = Lists.newArrayList();
        }
        if (!this.derivedInputs.contains(derivedInput)) {
            this.derivedInputs.add(derivedInput);
        }
    }
    public void removeDerivedInput(final CommandWrapperDerivedInputEntity derivedInput) {
        if (derivedInput == null || this.derivedInputs == null || !this.derivedInputs.contains(derivedInput)) {
            return;
        }
        this.derivedInputs.remove(derivedInput);
        derivedInput.setCommandWrapperEntity(null);
    }

    @OneToMany(mappedBy = "commandWrapperEntity", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy
    public List<CommandWrapperOutputEntity> getOutputHandlers() {
        return outputHandlers;
    }

    public void setOutputHandlers(final List<CommandWrapperOutputEntity> outputHandlers) {
        this.outputHandlers = outputHandlers == null ?
                Lists.<CommandWrapperOutputEntity>newArrayList() :
                outputHandlers;

        for (final CommandWrapperOutputEntity commandWrapperOutputEntity : this.outputHandlers) {
            commandWrapperOutputEntity.setCommandWrapperEntity(this);
        }
    }

    public void addOutputHandler(final CommandWrapperOutputEntity outputHandler) {
        if (outputHandler == null) {
            return;
        }
        outputHandler.setCommandWrapperEntity(this);

        if (this.outputHandlers == null) {
            this.outputHandlers = Lists.newArrayList();
        }
        if (!this.outputHandlers.contains(outputHandler)) {
            this.outputHandlers.add(outputHandler);
        }
    }
    public void removeOutputHandler(final CommandWrapperOutputEntity outputHandler) {
        if (outputHandler == null || this.outputHandlers == null || !this.outputHandlers.contains(outputHandler)) {
            return;
        }
        this.outputHandlers.remove(outputHandler);
        outputHandler.setCommandWrapperEntity(null);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final CommandWrapperEntity that = (CommandWrapperEntity) o;
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
                .add("contexts", contexts)
                .add("externalInputs", externalInputs)
                .add("derivedInputs", derivedInputs)
                .add("outputHandlers", outputHandlers)
                .toString();
    }

}
