package org.nrg.containers.model.orchestration.auto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.nrg.framework.constants.Scope;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@JsonInclude
public class Orchestration {
    private long id;
    private List<Long> wrapperIds = new ArrayList<>();
    private String name;
    private boolean enabled;

    public Orchestration() {}

    public Orchestration(long id, String name, List<Long> wrapperIds) {
        this.id = id;
        this.wrapperIds = wrapperIds;
        this.name = name;
        this.enabled = true;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public List<Long> getWrapperIds() {
        return wrapperIds;
    }

    public void setWrapperIds(List<Long> wrapperIds) {
        this.wrapperIds = wrapperIds;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @JsonIgnore
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Orchestration that = (Orchestration) o;
        return id == that.id &&
                enabled == that.enabled &&
                wrapperIds.equals(that.wrapperIds) &&
                name.equals(that.name);
    }

    @JsonIgnore
    @Override
    public int hashCode() {
        return Objects.hash(id, wrapperIds, name, enabled);
    }

    @JsonIgnoreType
    public static class OrchestrationIdentifier {
        public String projectId;
        public long firstWrapperId;
        public long commandId;
        public String wrapperName;

        public OrchestrationIdentifier(String projectId, long firstWrapperId) {
            this.projectId = projectId;
            this.firstWrapperId = firstWrapperId;
        }

        public OrchestrationIdentifier(String projectId, long firstWrapperId, long commandId, String wrapperName) {
            this.projectId = projectId;
            this.firstWrapperId = firstWrapperId;
            this.commandId = commandId;
            this.wrapperName = wrapperName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            OrchestrationIdentifier that = (OrchestrationIdentifier) o;
            return firstWrapperId == that.firstWrapperId &&
                    commandId == that.commandId &&
                    projectId.equals(that.projectId) &&
                    Objects.equals(wrapperName, that.wrapperName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(projectId, firstWrapperId, commandId, wrapperName);
        }
    }
}
