package org.nrg.containers.model.orchestration.auto;

import com.fasterxml.jackson.annotation.JsonInclude;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

@JsonInclude
public class OrchestrationProject {
    private List<Orchestration> availableOrchestrations = new ArrayList<>();
    private Long selectedOrchestrationId;

    public OrchestrationProject() {}

    public OrchestrationProject(List<Orchestration> availableOrchestrations, Long selectedOrchestrationId) {
        this.availableOrchestrations = availableOrchestrations;
        this.selectedOrchestrationId = selectedOrchestrationId;
    }

    public List<Orchestration> getAvailableOrchestrations() {
        return availableOrchestrations;
    }

    public void setAvailableOrchestrations(List<Orchestration> availableOrchestrations) {
        this.availableOrchestrations = availableOrchestrations;
    }

    @Nullable
    public Long getSelectedOrchestrationId() {
        return selectedOrchestrationId;
    }

    public void setSelectedOrchestrationId(Long selectedOrchestrationId) {
        this.selectedOrchestrationId = selectedOrchestrationId;
    }
}
