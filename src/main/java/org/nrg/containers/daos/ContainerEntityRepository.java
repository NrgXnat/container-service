package org.nrg.containers.daos;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.Hibernate;
import org.hibernate.criterion.Criterion;
import org.hibernate.criterion.MatchMode;
import org.hibernate.criterion.Projection;
import org.hibernate.criterion.Projections;
import org.hibernate.criterion.Restrictions;
import org.hibernate.sql.JoinType;
import org.nrg.containers.model.container.ContainerBuildDirRow;
import org.nrg.containers.model.container.entity.ContainerEntity;
import org.nrg.containers.model.container.entity.ContainerEntityHistory;
import org.nrg.containers.model.container.entity.ContainerEntityMount;
import org.nrg.containers.model.server.docker.Backend;
import org.nrg.containers.services.impl.ContainerServiceImpl;
import org.nrg.framework.orm.hibernate.AbstractHibernateDAO;
import org.springframework.stereotype.Repository;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
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

    /**
     * Candidates for build directory cleanup: containers with a mount under the build root whose statusTime falls in
     * the given window. The caller intersects the returned paths against a listing of the build root before
     * expanding launch groups via {@link #retrieveBuildDirRowsForLaunchGroups(Collection, String)}.
     *
     * statusTimeBefore is a sound necessary condition, since a group's age is the maximum statusTime over its
     * members: a container newer than the smallest threshold cannot belong to an eligible group. Bounding both ends
     * keeps this a range scan.
     *
     * @param buildPathPrefix site build root plus separator plus "%", for a prefix LIKE
     */
    @Nonnull
    @SuppressWarnings("unchecked")
    public List<ContainerBuildDirRow> retrieveBuildDirCandidates(final Date statusTimeAfter,
                                                                 final Date statusTimeBefore,
                                                                 final String buildPathPrefix) {
        // No alias join to the parent: parentContainerEntity is a many-to-one, so its id is already a column here.
        final List<Object[]> rows = getSession()
                .createCriteria(getParameterizedType(), "c")
                .createAlias("c.mounts", "m", JoinType.INNER_JOIN)
                .add(Restrictions.between("c.statusTime", statusTimeAfter, statusTimeBefore))
                .add(Restrictions.like("m.xnatHostPath", buildPathPrefix))
                .setProjection(Projections.distinct(buildDirProjection()))
                .setCacheable(false)
                .list();
        return toBuildDirRows(rows);
    }

    /**
     * Every member of each given launch group, with build-path mounts left joined.
     *
     * The mount path condition sits in the join's ON clause, not in WHERE. That is load bearing: a WHERE condition
     * would drop members with no build mount, which are exactly the rows whose status decides whether the group is
     * safe to clean up.
     *
     * @param rootIds         launch group root ids, chunked by the caller
     * @param buildPathPrefix site build root plus separator plus "%", for a prefix LIKE
     * @return one row per launch group member, and per build mount for members that have any
     */
    @Nonnull
    @SuppressWarnings("unchecked")
    public List<ContainerBuildDirRow> retrieveBuildDirRowsForLaunchGroups(final Collection<Long> rootIds,
                                                                     final String buildPathPrefix) {
        if (rootIds == null || rootIds.isEmpty()) {
            // Restrictions.in on an empty collection produces degenerate SQL; there is nothing to ask for anyway.
            return Collections.emptyList();
        }
        final List<Object[]> rows = getSession()
                .createCriteria(getParameterizedType(), "c")
                .createAlias("c.mounts", "m", JoinType.LEFT_OUTER_JOIN,
                        Restrictions.like("m.xnatHostPath", buildPathPrefix))
                .add(Restrictions.or(
                        Restrictions.in("c.id", rootIds),
                        Restrictions.in("c.parentContainerEntity.id", rootIds)))
                .setProjection(buildDirProjection())
                .setCacheable(false)
                .list();
        return toBuildDirRows(rows);
    }

    private static Projection buildDirProjection() {
        return Projections.projectionList()
                .add(Projections.property("c.id"))
                .add(Projections.property("c.parentContainerEntity.id"))
                .add(Projections.property("c.status"))
                .add(Projections.property("c.statusTime"))
                .add(Projections.property("m.xnatHostPath"));
    }

    @Nonnull
    private static List<ContainerBuildDirRow> toBuildDirRows(final @Nullable List<Object[]> rows) {
        if (rows == null) {
            return Collections.emptyList();
        }
        final List<ContainerBuildDirRow> converted = new ArrayList<>(rows.size());
        for (final Object[] row : rows) {
            final Long containerId = (Long) row[0];
            final Long parentId    = (Long) row[1];
            converted.add(new ContainerBuildDirRow(
                    containerId,
                    parentId != null ? parentId : containerId,
                    (String) row[2],
                    (Date) row[3],
                    (String) row[4]));
        }
        return converted;
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
