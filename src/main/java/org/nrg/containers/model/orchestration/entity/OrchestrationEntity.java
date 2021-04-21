package org.nrg.containers.model.orchestration.entity;

import lombok.extern.slf4j.Slf4j;
import org.nrg.containers.model.orchestration.auto.Orchestration;
import org.nrg.framework.constants.Scope;
import org.nrg.framework.orm.hibernate.AbstractHibernateEntity;

import javax.persistence.*;
import javax.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Entity
@Slf4j
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"scope", "scopedItemId"}))
public class OrchestrationEntity extends AbstractHibernateEntity {
    private String name;
    private Scope scope;
    private String scopedItemId;
    private List<OrchestratedWrapperEntity> wrapperList = new ArrayList<>();

    @NotNull
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @NotNull
    @Enumerated(EnumType.STRING)
    public Scope getScope() {
        return scope;
    }

    public void setScope(Scope scope) {
        this.scope = scope;
    }

    public String getScopedItemId() {
        return scopedItemId;
    }

    public void setScopedItemId(String project) {
        this.scopedItemId = project;
    }

    @OneToMany(mappedBy = "orchestrationEntity",
            cascade = CascadeType.ALL,
            orphanRemoval = true)
    @OrderBy(value = "orchestratedOrder")
    public List<OrchestratedWrapperEntity> getWrapperList() {
        return wrapperList;
    }

    public void setWrapperList(List<OrchestratedWrapperEntity> wrapperList) {
        this.wrapperList = wrapperList;
    }

    public void clearWrapperList() {
        this.wrapperList.forEach(owe -> owe.getCommandWrapperEntity().removeOrchestration(owe));
        this.wrapperList.clear();
    }

    public void addWrapper(OrchestratedWrapperEntity wrapper) {
        wrapperList.add(wrapper);
        wrapper.setOrchestrationEntity(this);
    }

    public void removeWrapper(OrchestratedWrapperEntity wrapper) {
        wrapperList.remove(wrapper);
        wrapper.setOrchestrationEntity(null);
    }

    public Orchestration toPojo() {
        Orchestration orchestration = new Orchestration();
        orchestration.setId(getId());
        orchestration.setName(getName());
        orchestration.setEnabled(isEnabled());
        orchestration.setScope(getScope().name());
        orchestration.setScopedItemId(getScopedItemId());
        orchestration.setWrapperIds(wrapperList.stream()
                .map(OrchestratedWrapperEntity::wrapperId)
                .collect(Collectors.toList()));
        return orchestration;
    }
}
