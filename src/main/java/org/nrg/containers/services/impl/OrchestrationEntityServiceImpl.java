package org.nrg.containers.services.impl;

import lombok.extern.slf4j.Slf4j;
import org.nrg.containers.daos.OrchestrationEntityDao;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.entity.CommandWrapperEntity;
import org.nrg.containers.model.orchestration.auto.Orchestration;
import org.nrg.containers.model.orchestration.auto.OrchestrationProject;
import org.nrg.containers.model.orchestration.entity.OrchestratedWrapperEntity;
import org.nrg.containers.model.orchestration.entity.OrchestrationEntity;
import org.nrg.containers.model.orchestration.entity.OrchestrationProjectEntity;
import org.nrg.containers.services.*;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.framework.orm.hibernate.AbstractHibernateEntityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.security.InvalidParameterException;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
public class OrchestrationEntityServiceImpl extends AbstractHibernateEntityService<OrchestrationEntity, OrchestrationEntityDao>
        implements OrchestrationEntityService {
    private final OrchestrationProjectEntityService orchestrationProjectEntityService;

    @Autowired
    public OrchestrationEntityServiceImpl(final OrchestrationProjectEntityService orchestrationProjectEntityService) {
        this.orchestrationProjectEntityService = orchestrationProjectEntityService;
    }

    @Override
    public Orchestration createOrUpdate(Orchestration orchestration, List<CommandWrapperEntity> wrapperList)
            throws NotFoundException {
        boolean create = orchestration.getId() == 0L;
        OrchestrationEntity oe;
        if (create) {
            oe = new OrchestrationEntity();
            populate(oe, orchestration.getName(), wrapperList);
            OrchestrationEntity created = create(oe);
            return created.toPojo();
        } else {
            oe = get(orchestration.getId());
            populate(oe, orchestration.getName(), wrapperList);
            oe.setEnabled(true);
            update(oe);
            return oe.toPojo();
        }
    }

    @Nullable
    @Override
    public Command.CommandWrapper findNextWrapper(long orchestrationId, int currentStepIdx, long currentWrapperId) throws NotFoundException {
        OrchestrationEntity oe = get(orchestrationId);
        List<OrchestratedWrapperEntity> wrappers = oe.getWrapperList();
        if (!oe.isEnabled() || wrappers.get(currentStepIdx).getCommandWrapperEntity().getId() != currentWrapperId) {
            log.warn("Orchestration seems to have changed since this container was launched " +
                    "(either disabled or wrapper {} is no longer at step {}). Halting orchestration",
                    currentWrapperId, currentStepIdx);
            return null;
        }
        int nextStepIdx = currentStepIdx + 1;
        if (nextStepIdx >= wrappers.size()) {
            // This is the last wrapper in the orchestration
            return null;
        }
        return Command.CommandWrapper.create(wrappers.get(nextStepIdx).getCommandWrapperEntity());
    }

    @Override
    public List<Long> setEnabled(long id, boolean enabled) throws NotFoundException {
        OrchestrationEntity oe = get(id);
        setEnabled(oe, enabled);
        return oe.getWrapperList().stream().map(OrchestratedWrapperEntity::wrapperId).collect(Collectors.toList());
    }

    @Override
    public void setEnabled(OrchestrationEntity oe, boolean enabled) {
        oe.setEnabled(enabled);
        if (!enabled) {
            oe.clearProjects();
        }
        update(oe);
    }

    @Override
    public List<Orchestration> getAllPojos() {
        final List<OrchestrationEntity> list = getDao().findAllWithDisabledAndOrder();
        if (getInitialize()) {
            for (final OrchestrationEntity entity : list) {
                initialize(entity);
            }
        }
        return list.stream().map(OrchestrationEntity::toPojo).collect(Collectors.toList());
    }

    @Override
    public OrchestrationProject getAvailableForProject(String project) {
        List<Orchestration> orchestrations = getAllPojos();
        OrchestrationProjectEntity selected = orchestrationProjectEntityService.find(project);
        return new OrchestrationProject(orchestrations, selected == null ? null :
                selected.getOrchestrationEntity().getId());
    }

    @Override
    @Nonnull
    public List<Long> setProjectOrchestration(String project, long orchestrationId)
            throws NotFoundException {
        OrchestrationEntity oe = get(orchestrationId);
        if (!oe.isEnabled()) {
            throw new InvalidParameterException("Orchestration " + oe.getName() + " (" +
                    oe.getId()+ ") cannot be added to a project because it is not enabled.");
        }

        OrchestrationProjectEntity ope = orchestrationProjectEntityService.find(project);
        boolean create = ope == null;
        if (create) {
            ope = new OrchestrationProjectEntity();
            ope.setProjectId(project);
        }
        oe.addProject(ope);

        if (create) {
            orchestrationProjectEntityService.create(ope);
        } else {
            orchestrationProjectEntityService.update(ope);
        }
        return oe.getWrapperList().stream().map(OrchestratedWrapperEntity::wrapperId).collect(Collectors.toList());
    }

    @Override
    public void removeProjectOrchestration(String project) {
        OrchestrationProjectEntity ope = orchestrationProjectEntityService.find(project);
        if (ope == null) {
            return;
        }
        ope.getOrchestrationEntity().removeProject(ope);
        orchestrationProjectEntityService.delete(ope);
    }

    @Override
    public synchronized void checkAndDisable(long wrapperId) {
        for (OrchestrationEntity oe : getDao().findEnabledUsingWrapper(wrapperId)) {
            setEnabled(oe, false);
        }
        flush();
    }

    @Override
    @Nullable
    public Orchestration findForProject(String project) {
        OrchestrationProjectEntity ope = orchestrationProjectEntityService.find(project);
        if (ope == null) {
            return null;
        }
        return ope.getOrchestrationEntity().toPojo();
    }

    @Nullable
    public Orchestration findWhereWrapperIsFirst(String project, long firstWrapperId) {
        OrchestrationProjectEntity ope = orchestrationProjectEntityService.find(project);
        if (ope == null) {
            return null;
        }
        OrchestrationEntity oe = ope.getOrchestrationEntity();
        return oe.isEnabled() && oe.getWrapperList().get(0).wrapperId() == firstWrapperId ? oe.toPojo() : null;
    }


    private void populate(OrchestrationEntity oe, String name, List<CommandWrapperEntity> wrapperList) {
        oe.setName(name);
        oe.clearWrapperList();
        for (int i = 0; i < wrapperList.size(); i++) {
            OrchestratedWrapperEntity we;
            we = new OrchestratedWrapperEntity();
            we.setOrchestratedOrder(i);
            we.setCommandWrapperEntity(wrapperList.get(i));
            oe.addWrapper(we);
        }
    }
}
