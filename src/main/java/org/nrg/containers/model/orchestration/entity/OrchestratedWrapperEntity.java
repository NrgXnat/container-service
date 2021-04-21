package org.nrg.containers.model.orchestration.entity;

import lombok.extern.slf4j.Slf4j;
import org.nrg.containers.model.command.entity.CommandWrapperEntity;
import org.nrg.framework.orm.hibernate.AbstractHibernateEntity;

import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.ManyToOne;

@Entity
@Slf4j
public class OrchestratedWrapperEntity extends AbstractHibernateEntity {
    private OrchestrationEntity orchestrationEntity;
    private CommandWrapperEntity commandWrapperEntity;
    private int orchestratedOrder;

    public int getOrchestratedOrder() {
        return orchestratedOrder;
    }

    public void setOrchestratedOrder(int order) {
        this.orchestratedOrder = order;
    }

    @ManyToOne(fetch = FetchType.LAZY)
    public OrchestrationEntity getOrchestrationEntity() {
        return orchestrationEntity;
    }

    public void setOrchestrationEntity(OrchestrationEntity orchestrationEntity) {
        this.orchestrationEntity = orchestrationEntity;
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    public CommandWrapperEntity getCommandWrapperEntity() {
        return commandWrapperEntity;
    }

    public void setCommandWrapperEntity(CommandWrapperEntity commandEntity) {
        this.commandWrapperEntity = commandEntity;
    }

    public long wrapperId() {
        return commandWrapperEntity.getId();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OrchestratedWrapperEntity)) return false;
        return getId() == ((OrchestratedWrapperEntity) o).getId();
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
