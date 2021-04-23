package org.nrg.containers.services;

import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.entity.CommandWrapperEntity;
import org.nrg.containers.model.orchestration.auto.Orchestration;
import org.nrg.containers.model.orchestration.auto.OrchestrationProject;
import org.nrg.containers.model.orchestration.entity.OrchestrationEntity;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.framework.orm.hibernate.BaseHibernateService;

import javax.annotation.Nullable;
import java.util.List;

public interface OrchestrationEntityService extends BaseHibernateService<OrchestrationEntity> {

    @Nullable
    Orchestration findWhereWrapperIsFirst(String project, long wrapperId);

    Orchestration createOrUpdate(Orchestration orchestration, List<CommandWrapperEntity> wrapperList)
            throws NotFoundException;

    @Nullable
    Command.CommandWrapper findNextWrapper(long orchestrationId, int currentStepIdx, long currentWrapperId)
            throws NotFoundException;

    List<Long> setEnabled(long id, boolean enabled) throws NotFoundException;

    void setEnabled(OrchestrationEntity oe, boolean enabled);

    List<Orchestration> getAllPojos();

    OrchestrationProject getAvailableForProject(String project);

    List<Long> setProjectOrchestration(String project, long orchestrationId) throws NotFoundException;

    void removeProjectOrchestration(String project);

    void checkAndDisable(long wrapperId);

    @Nullable
    Orchestration findForProject(String project);
}
