package org.nrg.containers.services.impl;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.daos.OrchestrationEntityDao;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.entity.CommandWrapperEntity;
import org.nrg.containers.model.orchestration.auto.Orchestration;
import org.nrg.containers.model.orchestration.entity.OrchestratedWrapperEntity;
import org.nrg.containers.model.orchestration.entity.OrchestrationEntity;
import org.nrg.containers.services.CommandEntityService;
import org.nrg.containers.services.OrchestrationEntityService;
import org.nrg.framework.constants.Scope;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.framework.orm.hibernate.AbstractHibernateEntityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@Transactional
public class OrchestrationEntityServiceImpl extends AbstractHibernateEntityService<OrchestrationEntity, OrchestrationEntityDao>
        implements OrchestrationEntityService {
    private final CommandEntityService commandEntityService;

    @Autowired
    public OrchestrationEntityServiceImpl(final CommandEntityService commandEntityService) {
        this.commandEntityService = commandEntityService;
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
    public Orchestration createOrUpdate(Orchestration orchestration)
            throws NotFoundException {
        boolean create = orchestration.getId() == 0L;
        validatePojo(orchestration, create);
        OrchestrationEntity oe;
        if (create) {
            oe = new OrchestrationEntity();
            populateFromPojo(oe, orchestration);
            OrchestrationEntity created = create(oe);
            return created.toPojo();
        } else {
            oe = get(orchestration.getId());
            populateFromPojo(oe, orchestration);
            oe.setEnabled(true);
            getDao().update(oe);
            return oe.toPojo();
        }
    }

    @Override
    @Nonnull
    public Orchestration find(Scope scope, String scopedItemId) throws NotFoundException {
        OrchestrationEntity oe = getDao().findScoped(scope, scopedItemId);
        if (oe == null) {
            throw new NotFoundException("Could not find entity with scope " + scope + " and ID " + scopedItemId);
        }
        return oe.toPojo();
    }

    @Override
    public void disable(long id) throws NotFoundException {
        OrchestrationEntity oe = get(id);
        oe.setEnabled(false);
        getDao().update(oe);
    }

    @Nullable
    public Orchestration findWhereWrapperIsFirst(Orchestration.OrchestrationIdentifier oi) {
        OrchestrationEntity oe = getDao().findScopedAndEnabled(oi.scope, oi.scopedItemId);
        if (oe == null) {
            return null;
        }
        if (oi.firstWrapperId == 0L) {
            try {
                oi.firstWrapperId = commandEntityService.getWrapperId(oi.commandId, oi.wrapperName);
            } catch (NotFoundException e) {
                return null;
            }
        }
        return oe.getWrapperList().get(0).wrapperId() == oi.firstWrapperId ? oe.toPojo() : null;
    }

    private void validatePojo(Orchestration orchestration, boolean create)
            throws InvalidParameterException {
        if (StringUtils.isBlank(orchestration.getScope())) {
            throw new InvalidParameterException("Scope not specified");
        }
        if (StringUtils.isBlank(orchestration.getScopedItemId()) && !Scope.Site.name().equals(orchestration.getScope())) {
            throw new InvalidParameterException("Scoped item ID required for scope " + orchestration.getScope());
        }
        if (create &&
                getDao().findScoped(Scope.valueOf(orchestration.getScope()), orchestration.getScopedItemId()) != null) {
            throw new InvalidParameterException(orchestration.getScope() + " " + orchestration.getScopedItemId() +
                    " already has orchestration configured: update using id or delete and recreate.");
        }
        if (orchestration.getWrapperIds().size() < 2) {
            throw new InvalidParameterException("Orchestration of fewer than two wrappers is not allowed");
        }
    }

    private void populateFromPojo(OrchestrationEntity oe, Orchestration orchestration) throws NotFoundException {
        oe.setName(orchestration.getName());
        oe.setScopedItemId(orchestration.getScopedItemId());
        oe.setScope(Scope.valueOf(orchestration.getScope()));
        oe.clearWrapperList();
        Set<String> contexts = new HashSet<>();
        List<Long> wrapperIds = orchestration.getWrapperIds();
        for (int i = 0; i < wrapperIds.size(); i++) {
            OrchestratedWrapperEntity we;
            we = new OrchestratedWrapperEntity();
            we.setOrchestratedOrder(i);
            CommandWrapperEntity cwe = commandEntityService.getWrapper(wrapperIds.get(i));
            if (i == 0) {
                contexts.addAll(cwe.getContexts());
            } else {
                contexts.retainAll(cwe.getContexts());
                if (contexts.isEmpty()) {
                    throw new InvalidParameterException("Wrappers must all have a common context");
                }
            }
            we.setCommandWrapperEntity(cwe);
            oe.addWrapper(we);
        }
    }
}
