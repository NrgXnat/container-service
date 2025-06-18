package org.nrg.containers.model.orchestration.entity;

import lombok.extern.slf4j.Slf4j;
import org.nrg.containers.model.orchestration.auto.Orchestration;
import org.nrg.framework.orm.hibernate.AbstractHibernateEntity;

import javax.persistence.*;
import javax.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Entity
@Slf4j
public class OrchestrationEntity extends AbstractHibernateEntity {
    private String name;
    private boolean haltOnCommandFailure = true;
    private List<OrchestratedWrapperEntity> wrapperList = new ArrayList<>();
    private List<OrchestrationProjectEntity> projects = new ArrayList<>();

    @NotNull
    @Column(unique = true)
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @NotNull
    public boolean isHaltOnCommandFailure() {
        return haltOnCommandFailure;
    }

    public void setHaltOnCommandFailure(boolean halt) {
        this.haltOnCommandFailure = halt;
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

    @OneToMany(mappedBy = "orchestrationEntity",
            cascade = CascadeType.ALL,
            orphanRemoval = true)
    public List<OrchestrationProjectEntity> getProjects() {
        return projects;
    }

    public void setProjects(List<OrchestrationProjectEntity> projects) {
        this.projects = projects;
    }

    public void clearProjects() {
        this.projects.clear();
    }

    public void addProject(OrchestrationProjectEntity project) {
        projects.add(project);
        project.setOrchestrationEntity(this);
    }

    public void removeProject(OrchestrationProjectEntity project) {
        projects.remove(project);
    }

    public Orchestration toPojo() {
        Orchestration orchestration = new Orchestration();
        orchestration.setId(getId());
        orchestration.setName(getName());
        orchestration.setEnabled(isEnabled());
        orchestration.setHaltOnCommandFailure(isHaltOnCommandFailure());
        orchestration.setWrapperIds(wrapperList.stream()
                .map(OrchestratedWrapperEntity::wrapperId)
                .collect(Collectors.toList()));
        return orchestration;
    }

    public boolean usesWrapper(long wrapperId) {
        return getWrapperList().stream().anyMatch(owe -> owe.wrapperId() == wrapperId);
    }
}
