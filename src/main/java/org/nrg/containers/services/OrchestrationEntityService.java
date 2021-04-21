package org.nrg.containers.services;

import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.orchestration.auto.Orchestration;
import org.nrg.containers.model.orchestration.entity.OrchestrationEntity;
import org.nrg.framework.constants.Scope;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.framework.orm.hibernate.BaseHibernateService;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface OrchestrationEntityService extends BaseHibernateService<OrchestrationEntity> {
    Orchestration createOrUpdate(Orchestration orchestration) throws NotFoundException;

    void disable(long id) throws NotFoundException;

    @Nonnull
    Orchestration find(Scope scope, String scopedItemId) throws NotFoundException;

    @Nullable
    Orchestration findWhereWrapperIsFirst(Orchestration.OrchestrationIdentifier oi);

    @Nullable
    Command.CommandWrapper findNextWrapper(long orchestrationId, int currentStepIdx, long currentWrapperId)
            throws NotFoundException;
}
