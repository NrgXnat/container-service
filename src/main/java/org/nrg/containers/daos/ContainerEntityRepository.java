package org.nrg.containers.daos;

import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.hibernate.criterion.Criterion;
import org.hibernate.criterion.MatchMode;
import org.hibernate.criterion.Restrictions;
import org.nrg.containers.model.container.entity.ContainerEntity;
import org.nrg.containers.model.container.entity.ContainerEntityHistory;
import org.nrg.containers.model.container.entity.ContainerEntityMount;
import org.nrg.containers.services.impl.ContainerServiceImpl;
import org.nrg.framework.orm.hibernate.AbstractHibernateDAO;
import org.springframework.stereotype.Repository;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

@Slf4j
@Repository
public class ContainerEntityRepository extends AbstractHibernateDAO<ContainerEntity> {

    @Override
    @SuppressWarnings("deprecation")
    public void initialize(final ContainerEntity entity) {
        if (entity == null) {
            return;
        }
        Hibernate.initialize(entity);
        Hibernate.initialize(entity.getEnvironmentVariables());
        Hibernate.initialize(entity.getPorts());
        Hibernate.initialize(entity.getHistory());
        Hibernate.initialize(entity.getMounts());
        if (entity.getMounts() != null) {
            for (final ContainerEntityMount mount : entity.getMounts()) {
                Hibernate.initialize(mount.getInputFiles());
            }
        }
        Hibernate.initialize(entity.getCommandLine());
        Hibernate.initialize(entity.getInputs());
        Hibernate.initialize(entity.getOutputs());
        Hibernate.initialize(entity.getLogPaths());
        Hibernate.initialize(entity.getSwarmConstraints());

        initialize(entity.getParentContainerEntity());
    }

    @Nullable
    public ContainerEntity retrieveByContainerOrServiceId(final @Nonnull String containerId) {
        final ContainerEntity containerEntity = (ContainerEntity) getSession()
                .createCriteria(ContainerEntity.class)
                .add(Restrictions.disjunction()
                        .add(Restrictions.eq("containerId", containerId))
                        .add(Restrictions.eq("serviceId", containerId)))
                .uniqueResult();
        initialize(containerEntity);
        return containerEntity;
    }

    @Nullable
    public ContainerEntity retrieveByServiceId(final @Nonnull String serviceId) {
        return findByUniqueProperty("serviceId", serviceId);
    }

    public void addHistoryItem(final @Nonnull ContainerEntity containerEntity,
                               final @Nonnull ContainerEntityHistory containerEntityHistory) {
        containerEntity.addToHistory(containerEntityHistory);
        getSession().persist(containerEntityHistory);

        final boolean historyEntryIsMoreRecentThanContainerStatus =
                containerEntityHistory.getTimeRecorded() != null &&
                        (containerEntity.getStatusTime() == null ||
                                containerEntityHistory.getTimeRecorded().getTime() > containerEntity.getStatusTime().getTime());

        if (historyEntryIsMoreRecentThanContainerStatus && !containerEntity.statusIsTerminal()) {
            containerEntity.setStatusTime(containerEntityHistory.getTimeRecorded());
            containerEntity.setStatus(containerEntityHistory.getStatus());
            log.debug("Setting container entity {} status to \"{}\", based on history entry status \"{}\".",
                    containerEntity.getId(),
                    containerEntity.getStatus(),
                    containerEntityHistory.getStatus());
        }

        update(containerEntity);
    }

    @Nonnull
    public List<ContainerEntity> retrieveServices() {
        final List servicesResult = getSession()
                .createCriteria(ContainerEntity.class)
                .add(Restrictions.isNotNull("serviceId"))
                .list();
        return initializeAndReturnList(servicesResult);
    }

    @Nonnull
    public List<ContainerEntity> retrieveNonfinalizedServices() {
        final List servicesResult = getSession()
                .createCriteria(ContainerEntity.class)
                .add(Restrictions.conjunction()
                        .add(Restrictions.isNotNull("serviceId"))
                        .add(getNonFinalizedCriterion())
                )
                .list();
        List<ContainerEntity> ces = initializeAndReturnList(servicesResult);
        log.trace("FOLLOWING SERVICES ARE NOT FINAL: ");
        for (ContainerEntity ce:ces) {
        	log.trace("NONFINALIZED: " + ce.getServiceId() + " STATUS: " + ce.getStatus() + " " + " TASK: " + ce.getTaskId() + " WORKFLOW: " + ce.getWorkflowId() );
        }
        return ces;
    }
    
    @Nonnull
    public int howManyContainersAreWaiting() {
        int countOfContainersBeingWaiting = 0;
        List<ContainerEntity> ces = retrieveServicesInWaitingState();
        if (ces != null) {
        	countOfContainersBeingWaiting = ces.size();
        }if(log.isTraceEnabled()){
            log.trace("At present " + countOfContainersBeingWaiting + " are waiting");
        }
        return countOfContainersBeingWaiting;
    }

    @Nonnull
    public List<ContainerEntity> retrieveServicesInWaitingState() {
    	final List finalizingResult = getSession()
                .createCriteria(ContainerEntity.class)
                .add(Restrictions.conjunction()
                        .add(Restrictions.isNotNull("serviceId"))
                        .add(Restrictions.like("status", ContainerServiceImpl.WAITING))
                )
                .list();
        List<ContainerEntity> ces = initializeAndReturnList(finalizingResult);
        for (ContainerEntity ce:ces) {
        	if(log.isTraceEnabled()){
            	log.trace("WAITING STATE: " + ce.getServiceId() + " STATUS: " + ce.getStatus() + " " + " TASK: " + ce.getTaskId() + " WORKFLOW: " + ce.getWorkflowId() );
        	}
        }
        return ces;
    }
    @Nonnull
    public int howManyContainersAreBeingFinalized() {
        int countOfContainersBeingFinalized = 0;
        List<ContainerEntity> ces = retrieveServicesInFinalizingState();
        if (ces != null) {
        	countOfContainersBeingFinalized = ces.size();
        }
        log.trace("At present " + countOfContainersBeingFinalized + " are being finalized");
        return countOfContainersBeingFinalized;
    }

    @Nonnull
    public List<ContainerEntity> retrieveServicesInFinalizingState() {
    	final List finalizingResult = getSession()
                .createCriteria(ContainerEntity.class)
                .add(Restrictions.conjunction()
                        .add(Restrictions.isNotNull("serviceId"))
                        .add(Restrictions.like("status", ContainerServiceImpl.FINALIZING))
                )
                .list();
        List<ContainerEntity> ces = initializeAndReturnList(finalizingResult);
        for (ContainerEntity ce:ces) {
        	log.trace("FINALIZING STATE: " + ce.getServiceId() + " STATUS: " + ce.getStatus() + " " + " TASK: " + ce.getTaskId() + " WORKFLOW: " + ce.getWorkflowId() );
        }
        return ces;
    }

    
    @Nonnull
    public List<ContainerEntity> retrieveContainersForParentWithSubtype(final long parentId,
                                                                        final String subtype) {
        final List setupContainersResult = getSession()
                .createQuery("select c from ContainerEntity as c where c.parentContainerEntity.id = :parentId and c.subtype = :subtype")
                .setLong("parentId", parentId)
                .setString("subtype", subtype)
                .list();

        return initializeAndReturnList(setupContainersResult);
    }

    @Nonnull
    public List<ContainerEntity> getAll(final String project) {
        return initializeAndReturnList(findByProperty("project", project));
    }

    @Nonnull
    public List<ContainerEntity> getAllNonfinalized() {
        final List list = getSession()
                .createCriteria(ContainerEntity.class)
                .add(Restrictions.conjunction().add(getNonFinalizedCriterion()))
                .list();
        return initializeAndReturnList(list);
    }

    @Nonnull
    public List<ContainerEntity> getAllNonfinalized(final String project) {
        final List list = getSession()
                .createCriteria(ContainerEntity.class)
                .add(Restrictions.conjunction()
                        .add(Restrictions.eq("project", project))
                        .add(getNonFinalizedCriterion())
                )
                .list();
        return initializeAndReturnList(list);
    }

    private Criterion getNonFinalizedCriterion() {
        return Restrictions.not(Restrictions.disjunction()
                .add(Restrictions.like("status", "Complete"))
                .add(Restrictions.like("status", "Done"))
                .add(Restrictions.like("status", "Failed", MatchMode.START))
                .add(Restrictions.like("status", "Killed"))
                .add(Restrictions.like("status", "Finalizing")));
    }

    @SuppressWarnings("unchecked")
    @Nonnull
    private List<ContainerEntity> initializeAndReturnList(final List result) {
        if (result != null) {
            try {
                final List<ContainerEntity> toReturn = (List<ContainerEntity>) result;
                for (final ContainerEntity containerEntity : toReturn) {
                    initialize(containerEntity);
                }
                return toReturn;
            } catch (ClassCastException e) {
                log.error("Failed to cast results to ContainerEntity.", e);
            }
        }
        return Collections.emptyList();
    }
}
