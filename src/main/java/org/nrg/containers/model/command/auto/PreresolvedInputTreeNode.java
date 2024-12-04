package org.nrg.containers.model.command.auto;

import com.google.auto.value.AutoValue;
import com.google.common.collect.Lists;
import org.nrg.containers.model.command.auto.Command.CommandInput;
import org.nrg.containers.model.command.auto.Command.CommandWrapperDerivedInput;
import org.nrg.containers.model.command.auto.Command.CommandWrapperExternalInput;
import org.nrg.containers.model.command.auto.Command.Input;

import java.io.Serializable;
import java.util.List;

@AutoValue
public abstract class PreresolvedInputTreeNode<T extends Command.Input> implements Serializable {
    private static final long serialVersionUID = -2965708997601698228L;

    public abstract T input();
    public abstract List<PreresolvedInputTreeNode<? extends Input>> children();

    public static PreresolvedInputTreeNode<CommandWrapperExternalInput> create(final CommandWrapperExternalInput externalInput) {
        return new AutoValue_PreresolvedInputTreeNode<>(externalInput, Lists.<PreresolvedInputTreeNode<? extends Input>>newArrayList());
    }

    public static PreresolvedInputTreeNode<CommandWrapperDerivedInput> create(final CommandWrapperDerivedInput derivedInput,
                                                                              final PreresolvedInputTreeNode<? extends Input> parent) {
        final PreresolvedInputTreeNode<CommandWrapperDerivedInput> node =
                new AutoValue_PreresolvedInputTreeNode<>(derivedInput, Lists.<PreresolvedInputTreeNode<? extends Input>>newArrayList());

        parent.children().add(node);

        return node;
    }

    public static PreresolvedInputTreeNode<CommandInput> create(final CommandInput commandInput) {
        return new AutoValue_PreresolvedInputTreeNode<>(commandInput, Lists.<PreresolvedInputTreeNode<? extends Input>>newArrayList());
    }

    public static PreresolvedInputTreeNode<CommandInput> create(final CommandInput commandInput,
                                                                final PreresolvedInputTreeNode<? extends Input> parent) {
        final PreresolvedInputTreeNode<CommandInput> node =
                new AutoValue_PreresolvedInputTreeNode<>(commandInput, Lists.<PreresolvedInputTreeNode<? extends Input>>newArrayList());
        parent.children().add(node);
        return node;
    }
}
