package org.nrg.containers.events.model;

import org.nrg.framework.services.NrgEventService;
import org.nrg.xdat.XDAT;
import org.nrg.xnat.tracking.model.TrackableEvent;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;

public class BulkLaunchEvent implements TrackableEvent {
    private final String id;
    private Integer n = null;
    private Integer failures = null;
    private boolean success = false;
    private boolean completed = false;
    private String message = null;
    private Integer workflowId = null;
    private String workflowItemId = null;
    private String workflowStatus = null;
    private String workflowDetails = null;
    private String workflowContainerId = null;

    public BulkLaunchEvent(String id) {
        this.id = id;
    }

    public BulkLaunchEvent(String bulkLaunchId, Integer workflowId, String workflowItemId, String workflowStatus,
                           String workflowDetails, String workflowContainerId) {
        this(bulkLaunchId);
        this.workflowId = workflowId;
        this.workflowItemId = workflowItemId;
        this.workflowStatus = workflowStatus;
        this.workflowDetails = workflowDetails;
        this.workflowContainerId = workflowContainerId;
    }

    private BulkLaunchEvent(String bulkLaunchId, boolean success, String message) {
        this(bulkLaunchId);
        this.completed = true;
        this.success = success;
        this.message = message;
    }

    public static BulkLaunchEvent initial(String bulkLaunchId, int n) {
        BulkLaunchEvent ble = new BulkLaunchEvent(bulkLaunchId);
        ble.n = n;
        return ble;
    }

    public static BulkLaunchEvent executorServiceFailureCount(String bulkLaunchId, int failures) {
        BulkLaunchEvent ble = new BulkLaunchEvent(bulkLaunchId);
        ble.failures = failures;
        return ble;
    }

    @Nonnull
    @Override
    public String getTrackingId() {
        return id;
    }

    @Override
    public boolean isSuccess() {
        return success;
    }

    @Override
    public boolean isCompleted() {
        return completed;
    }

    @Nullable
    @Override
    public String getMessage() {
        return message;
    }

    @Override
    public String updateTrackingPayload(@Nullable String currentPayload) throws IOException {
        BulkLaunchLog statusLog;
        if (currentPayload != null) {
            statusLog = XDAT.getSerializerService().getObjectMapper()
                    .readValue(currentPayload, BulkLaunchLog.class);
        } else {
            statusLog = new BulkLaunchLog();
        }
        if (n != null) {
            statusLog.setTotal(n);
        }
        if (failures != null) {
            statusLog.addFailures(failures);
        }
        if (workflowId != null) {
            statusLog.addOrUpdateWorkflow(workflowId, workflowItemId, workflowStatus, workflowDetails, workflowContainerId);
        }
        if (statusLog.bulkLaunchComplete()) {
            XDAT.getContextService().getBean(NrgEventService.class).triggerEvent(
                    new BulkLaunchEvent(id, statusLog.bulkLaunchSuccess(), statusLog.bulkLaunchMessage()));
        }
        return XDAT.getSerializerService().getObjectMapper().writeValueAsString(statusLog);
    }
}
