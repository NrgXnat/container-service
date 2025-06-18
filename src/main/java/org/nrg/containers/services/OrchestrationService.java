package org.nrg.containers.services;

import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.orchestration.auto.Orchestration;
import org.nrg.containers.model.orchestration.auto.OrchestrationProject;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xft.security.UserI;

import javax.annotation.Nullable;
import java.util.List;

public interface OrchestrationService {
    Orchestration createOrUpdate(Orchestration orchestration) throws NotFoundException;

    @Nullable
    Orchestration retrieve(final long orchestrationId) throws NotFoundException;

    @Nullable
    Orchestration findWhereWrapperIsFirst(Orchestration.OrchestrationIdentifier oi);

    @Nullable
    Command.CommandWrapper findNextWrapper(long orchestrationId, int currentStepIdx, long currentWrapperId)
            throws NotFoundException;

    void setEnabled(long id, boolean enabled, UserI user) throws NotFoundException, ContainerConfigService.CommandConfigurationException;

    List<Orchestration> getAllPojos();

    OrchestrationProject getAvailableForProject(String project);

    @Nullable
    Orchestration findForProject(String project);

    void setProjectOrchestration(String project, long orchestrationId, UserI user) throws NotFoundException, ContainerConfigService.CommandConfigurationException;

    void removeProjectOrchestration(String project);

    void delete(long id);
}