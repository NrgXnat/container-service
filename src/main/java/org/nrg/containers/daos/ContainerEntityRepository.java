package org.nrg.containers.daos;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.Hibernate;
import org.hibernate.criterion.Criterion;
import org.hibernate.criterion.MatchMode;
import org.hibernate.criterion.Projections;
import org.hibernate.criterion.Restrictions;
import org.nrg.containers.model.container.entity.ContainerEntity;
import org.nrg.containers.model.container.entity.ContainerEntityHistory;
import org.nrg.containers.model.container.entity.ContainerEntityMount;
import org.nrg.containers.model.server.docker.Backend;
import org.nrg.containers.services.impl.ContainerServiceImpl;
import org.nrg.framework.orm.hibernate.AbstractHibernateDAO;
import org.springframework.stereotype.Repository;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Date;
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
        Hibernate.initialize(entity.getContainerLabels());
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
    @SuppressWarnings("unchecked")
    public List<Long> retrieveNonfinalizedServiceIds() {
        final List<Long> ids = getSession()
                .createCriteria(ContainerEntity.class)
                .add(Restrictions.conjunction()
                        .add(Restrictions.isNotNull("serviceId"))
                        .add(getNonFinalizedCriterion())
                )
                .setProjection(Projections.property("id"))
                .list();
        return ids == null ? Collections.emptyList() : ids;
    }

    /**
     * Lightweight projection-based fetch used by the polling loop in
     * ContainerStatusUpdater. Returns a sparse ContainerEntity with only the
     * scalars the loop reads.
     */
    @Nullable
    public ContainerEntity retrieveServiceForPoll(final long id) {
        final Object[] row = (Object[]) getSession()
                .createCriteria(ContainerEntity.class)
                .add(Restrictions.eq("id", id))
                .setProjection(Projections.projectionList()
                        .add(Projections.property("id"))
                        .add(Projections.property("serviceId"))
                        .add(Projections.property("taskId"))
                        .add(Projections.property("containerId"))
                        .add(Projections.property("status"))
                        .add(Projections.property("statusTime"))
                        .add(Projections.property("workflowId"))
                        .add(Projections.property("userId"))
                        .add(Projections.property("backend"))
                        .add(Projections.property("project")))
                .uniqueResult();
        if (row == null) return null;
        final ContainerEntity entity = new ContainerEntity();
        entity.setId((Long) row[0]);
        entity.setServiceId((String) row[1]);
        entity.setTaskId((String) row[2]);
        entity.setContainerId((String) row[3]);
        entity.setStatus((String) row[4]);
        entity.setStatusTime((Date) row[5]);
        entity.setWorkflowId((String) row[6]);
        entity.setUserId((String) row[7]);
        entity.setBackend((Backend) row[8]);
        entity.setProject((String) row[9]);
        // Placeholders for non-@Nullable Container fields the poll loop never reads.
        entity.setDockerImage("");
        entity.setCommandLine("");
        return entity;
    }

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

    public int howManyContainersAreBeingFinalized() {
        int countOfContainersBeingFinalized = 0;
        List<ContainerEntity> ces = retrieveContainersInFinalizingState();
        if (ces != null) {
        	countOfContainersBeingFinalized = ces.size();
        }
        log.trace("At present " + countOfContainersBeingFinalized + " are being finalized");
        return countOfContainersBeingFinalized;
    }

    @Nonnull
    public List<ContainerEntity> retrieveContainersInFinalizingState() {
    	final List finalizingResult = getSession()
                .createCriteria(ContainerEntity.class)
                .add(Restrictions.like("status", ContainerServiceImpl.FINALIZING))
                .list();
        List<ContainerEntity> ces = initializeAndReturnList(finalizingResult);
        if (log.isTraceEnabled()) {
            for (ContainerEntity ce : ces) {
                String id = StringUtils.defaultIfBlank(ce.getServiceId(), ce.getContainerId());
                log.trace("FINALIZING STATE: " + id + " STATUS: " + ce.getStatus() + "  TASK: " + ce.getTaskId() +
                        " WORKFLOW: " + ce.getWorkflowId());
            }
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
