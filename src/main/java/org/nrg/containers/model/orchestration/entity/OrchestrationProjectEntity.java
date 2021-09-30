package org.nrg.containers.model.orchestration.entity;

import lombok.extern.slf4j.Slf4j;
import org.nrg.framework.orm.hibernate.AbstractHibernateEntity;

import javax.persistence.*;
import javax.validation.constraints.NotNull;

@Entity
@Slf4j
public class OrchestrationProjectEntity extends AbstractHibernateEntity {
    private String projectId;
    private OrchestrationEntity orchestrationEntity;

    @NotNull
    @Column(unique = true)
    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    public OrchestrationEntity getOrchestrationEntity() {
        return orchestrationEntity;
    }

    public void setOrchestrationEntity(OrchestrationEntity orchestrationEntity) {
        this.orchestrationEntity = orchestrationEntity;
    }
}
