package org.nrg.containers.model.command.auto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.auto.value.AutoValue;
import org.nrg.containers.model.command.auto.Command.Input;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@AutoValue
public abstract class ResolvedInputTreeNode<T extends Input> implements Serializable {
    private static final long serialVersionUID = -2419743845264871527L;

    @JsonProperty("input") public abstract T input();
    @JsonProperty("values-and-children") public abstract List<ResolvedInputTreeValueAndChildren> valuesAndChildren();

    public static ResolvedInputTreeNode<? extends Input> create(final PreresolvedInputTreeNode<?> preresolvedInputTreeNode) {
        return create(preresolvedInputTreeNode.input(), new ArrayList<>());
    }

    @JsonCreator
    public static <X extends Input> ResolvedInputTreeNode<X> create(@JsonProperty("input") final X input,
                                                                    @JsonProperty("values-and-children") final List<ResolvedInputTreeValueAndChildren> valuesAndChildren) {
        return new AutoValue_ResolvedInputTreeNode<>(input,
                valuesAndChildren == null ? new ArrayList<>() : valuesAndChildren);
    }

    @AutoValue
    public static abstract class ResolvedInputTreeValueAndChildren implements Serializable {
        private static final long serialVersionUID = -8070962887603693910L;

        @JsonProperty("value") public abstract ResolvedInputValue resolvedValue();
        @JsonProperty("children") public abstract List<ResolvedInputTreeNode<? extends Input>> children();

        public static ResolvedInputTreeValueAndChildren create(final ResolvedInputValue resolvedValue) {
            return create(resolvedValue, new ArrayList<>());
        }

        @JsonCreator
        public static ResolvedInputTreeValueAndChildren create(@JsonProperty("value") final ResolvedInputValue resolvedValue,
                                                               @JsonProperty("children") final List<ResolvedInputTreeNode<? extends Input>> children) {
            return new AutoValue_ResolvedInputTreeNode_ResolvedInputTreeValueAndChildren(resolvedValue,
                    children == null ? new ArrayList<>() : children);
        }
    }
}
