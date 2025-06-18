package org.nrg.containers.services.impl;

import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.entity.CommandWrapperEntity;
import org.nrg.containers.model.orchestration.auto.Orchestration;
import org.nrg.containers.model.orchestration.auto.OrchestrationProject;
import org.nrg.containers.model.orchestration.entity.OrchestrationEntity;
import org.nrg.containers.services.CommandEntityService;
import org.nrg.containers.services.ContainerConfigService;
import org.nrg.containers.services.OrchestrationEntityService;
import org.nrg.containers.services.OrchestrationService;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xft.security.UserI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Nullable;
import javax.transaction.Transactional;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class OrchestrationServiceImpl implements OrchestrationService {
    private final OrchestrationEntityService orchestrationEntityService;
    private final CommandEntityService commandEntityService;
    private final ContainerConfigService containerConfigService;

    @Autowired
    public OrchestrationServiceImpl(final OrchestrationEntityService orchestrationEntityService,
                                    final CommandEntityService commandEntityService,

                                    final ContainerConfigService containerConfigService) {
        this.orchestrationEntityService = orchestrationEntityService;
        this.commandEntityService = commandEntityService;
        this.containerConfigService = containerConfigService;
    }

    @Override
    public Orchestration createOrUpdate(Orchestration orchestration)
            throws NotFoundException {
        List<CommandWrapperEntity> wrapperList = validatePojo(orchestration);
        return orchestrationEntityService.createOrUpdate(orchestration, wrapperList);
    }

    @Nullable
    public Orchestration retrieve(final long orchestrationId) throws NotFoundException {
       OrchestrationEntity oe =  orchestrationEntityService.get(orchestrationId);
       if (oe != null) {
           return oe.toPojo();
       }
       throw new NotFoundException("Could not find orchestration by id " + orchestrationId);
    }


    @Nullable
    @Override
    public Orchestration findWhereWrapperIsFirst(Orchestration.OrchestrationIdentifier oi) {
        long firstWrapperId = oi.firstWrapperId;
        if (firstWrapperId == 0L) {
            try {
                firstWrapperId = commandEntityService.getWrapper(oi.commandId, oi.wrapperName).getId();
            } catch (NotFoundException e) {
                return null;
            }
        }
        return orchestrationEntityService.findWhereWrapperIsFirst(oi.projectId, firstWrapperId);
    }

    @Nullable
    @Override
    public Command.CommandWrapper findNextWrapper(long orchestrationId, int currentStepIdx, long currentWrapperId) throws NotFoundException {
        return orchestrationEntityService.findNextWrapper(orchestrationId, currentStepIdx, currentWrapperId);
    }


    @Override
    public void setEnabled(long id, boolean enabled, UserI user) throws NotFoundException, ContainerConfigService.CommandConfigurationException {
        List<Long> wrapperIds = orchestrationEntityService.setEnabled(id, enabled);
        if (enabled) {
            for (Long wrapperId : wrapperIds) {
                containerConfigService.enableForSite(wrapperId, user.getUsername(), "Setup orchestration");
            }
        }
    }

    @Override
    public List<Orchestration> getAllPojos() {
        return orchestrationEntityService.getAllPojos();
    }

    @Override
    public OrchestrationProject getAvailableForProject(String project) {
        OrchestrationProject orchestrationProject =  orchestrationEntityService.getAvailableForProject(project);
        List<Orchestration> orchestrations = orchestrationProject.getAvailableOrchestrations();
        List<Orchestration> orchestrationsWithAllEnabledCommandsForProject = new ArrayList<>();
        for (Orchestration orch : orchestrations) {
            boolean allEnabled = orch.getWrapperIds().stream().allMatch(w -> containerConfigService.isEnabledForProject(project, w));
            if (allEnabled) {
                orchestrationsWithAllEnabledCommandsForProject.add(orch);
            }
        }
        Long selectedOrchestrationId = null;
        if (orchestrationProject.getSelectedOrchestrationId() != null && orchestrationsWithAllEnabledCommandsForProject.stream().anyMatch(o -> o.getId() == orchestrationProject.getSelectedOrchestrationId())) {
            selectedOrchestrationId = orchestrationProject.getSelectedOrchestrationId();
        }
        return new OrchestrationProject(orchestrationsWithAllEnabledCommandsForProject, selectedOrchestrationId);
    }

    @Override
    @Nullable
    public Orchestration findForProject(String project) {
        return orchestrationEntityService.findForProject(project);
    }

    @Override
    public void setProjectOrchestration(String project, long orchestrationId, UserI user)
            throws NotFoundException, ContainerConfigService.CommandConfigurationException {
        List<Long> wrapperIds = orchestrationEntityService.setProjectOrchestration(project, orchestrationId);
        for (Long wrapperId : wrapperIds) {
            containerConfigService.enableForProject(project, wrapperId, user.getUsername(), "Setup orchestration");
        }
    }

    @Override
    public void delete(long id) {
        orchestrationEntityService.delete(id);
    }

    @Override
    public void removeProjectOrchestration(String project) {
        orchestrationEntityService.removeProjectOrchestration(project);
    }


    private List<CommandWrapperEntity> validatePojo(Orchestration orchestration)
            throws InvalidParameterException, NotFoundException {
        if (orchestration.getWrapperIds().size() < 2) {
            throw new InvalidParameterException("Orchestration of fewer than two wrappers is not allowed");
        }

        Set<String> contexts = new HashSet<>();
        List<Long> wrapperIds = orchestration.getWrapperIds();
        List<CommandWrapperEntity> wrapperList = new ArrayList<>();
        for (int i = 0; i < wrapperIds.size(); i++) {
            long wrapperId = wrapperIds.get(i);
            if (!containerConfigService.isEnabledForSite(wrapperId)) {
                throw new InvalidParameterException("Wrapper " + wrapperId + " not enabled for site");
            }
            CommandWrapperEntity cwe = commandEntityService.getWrapper(wrapperId);
            if (i == 0) {
                contexts.addAll(cwe.getContexts());
            } else {
                contexts.retainAll(cwe.getContexts());
                if (contexts.isEmpty()) {
                    throw new InvalidParameterException("Wrappers must all have a common context");
                }
            }
            wrapperList.add(cwe);
        }

        return wrapperList;
    }
}