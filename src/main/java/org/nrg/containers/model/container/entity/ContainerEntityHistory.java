package org.nrg.containers.model.container.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.MoreObjects;

import lombok.extern.slf4j.Slf4j;

import org.hibernate.envers.Audited;
import org.nrg.containers.events.model.ContainerEvent;
import org.nrg.containers.events.model.DockerContainerEvent;
import org.nrg.containers.model.container.auto.Container;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.ManyToOne;
import java.util.Date;
import java.util.Objects;

@Slf4j
@Entity
@Audited
public class ContainerEntityHistory implements Comparable<ContainerEntityHistory>{
    private long id;
    @JsonIgnore private ContainerEntity containerEntity;
    private String status;
    private String entityType;
    private String entityId;
    private Date timeRecorded;
    private String externalTimestamp;
    private String exitCode;

    public ContainerEntityHistory() {}

    public static ContainerEntityHistory fromContainerEvent(final ContainerEvent containerEvent,
                                                            final ContainerEntity parent) {
        final ContainerEntityHistory history = new ContainerEntityHistory();
        history.status = containerEvent.status();
        history.entityType = "event";
        history.entityId = null;
        history.timeRecorded = new Date();
        history.exitCode = containerEvent.exitCode();
        if (containerEvent instanceof DockerContainerEvent) {
            history.externalTimestamp = String.valueOf(((DockerContainerEvent)containerEvent).timeNano());
        }
        history.containerEntity = parent;
        return history;
    }

    public static ContainerEntityHistory fromSystem(final String status,
                                                    final ContainerEntity parent) {
        final ContainerEntityHistory history = new ContainerEntityHistory();
        history.status = status;
        history.externalTimestamp = null;
        history.entityType = "system";
        history.entityId = null;
        history.timeRecorded = new Date();
        history.containerEntity = parent;
        return history;
    }

    public static ContainerEntityHistory fromUserAction(final String status,
                                                        final String username,
                                                        final ContainerEntity parent) {
        final ContainerEntityHistory history = new ContainerEntityHistory();
        history.status = status;
        history.externalTimestamp = null;
        history.entityType = "user";
        history.entityId = username;
        history.timeRecorded = new Date();
        history.containerEntity = parent;
        return history;
    }

    public static ContainerEntityHistory fromPojo(final Container.ContainerHistory containerHistoryPojo) {
        final ContainerEntityHistory containerEntityHistory = new ContainerEntityHistory();
        containerEntityHistory.update(containerHistoryPojo);
        return containerEntityHistory;
    }

    public ContainerEntityHistory update(final Container.ContainerHistory containerHistoryPojo) {
        this.setId(containerHistoryPojo.databaseId() == null ? 0L : containerHistoryPojo.databaseId());
        this.setStatus(containerHistoryPojo.status());
        this.setEntityType(containerHistoryPojo.entityType());
        this.setEntityId(containerHistoryPojo.entityId());
        this.setTimeRecorded(containerHistoryPojo.timeRecorded());
        this.setExternalTimestamp(containerHistoryPojo.externalTimestamp());
        this.setExitCode(containerHistoryPojo.exitCode());
        return this;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public long getId() {
        return id;
    }

    public void setId(final long id) {
        this.id = id;
    }

    @ManyToOne
    public ContainerEntity getContainerEntity() {
        return containerEntity;
    }

    public void setContainerEntity(final ContainerEntity containerEntity) {
        this.containerEntity = containerEntity;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(final String status) {
        this.status = status;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(final String entityType) {
        this.entityType = entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public void setEntityId(final String entityId) {
        this.entityId = entityId;
    }

    public Date getTimeRecorded() {
        return timeRecorded;
    }

    public void setTimeRecorded(final Date timeRecorded) {
        this.timeRecorded = timeRecorded;
    }

    public String getExternalTimestamp() {
        return externalTimestamp;
    }

    public void setExternalTimestamp(final String externalTimestamp) {
        this.externalTimestamp = externalTimestamp;
    }

    public String getExitCode() {
        return exitCode;
    }

    public void setExitCode(final String exitCode) {
        this.exitCode = exitCode;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final ContainerEntityHistory that = (ContainerEntityHistory) o;
        
        boolean match=Objects.equals(this.containerEntity, that.containerEntity) &&
                Objects.equals(this.status, that.status) &&
                Objects.equals(this.externalTimestamp, that.externalTimestamp);
        if(match){
        	if(log.isTraceEnabled()){
        		log.trace("containerEntity {}={},status {}={}, externalTimestamp{}={}",this.containerEntity.getId(),this.containerEntity.getId(),this.status,this.status,this.externalTimestamp,this.externalTimestamp);
        	}
        }

        return match;
    }

    @Override
    public int hashCode() {
        return Objects.hash(containerEntity, status,externalTimestamp);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("id", id)
                .add("status", status)
                .add("entityType", entityType)
                .add("entityId", entityId)
                .add("timeRecorded", timeRecorded)
                .add("externalTimestamp", externalTimestamp)
                .add("exitCode", exitCode)
                .toString();
    }

	@Override
	public int compareTo(ContainerEntityHistory o) {
		   return o.timeRecorded.compareTo(this.timeRecorded);
	}
}
