package org.nrg.containers.model.xnat;

import org.mockito.Mockito;
import org.nrg.xft.event.EventMetaI;
import org.nrg.xft.event.EventUtils;
import org.nrg.xft.event.persist.PersistentWorkflowI;
import org.nrg.xft.security.UserI;

import java.util.Date;

import static org.mockito.Mockito.when;

public class FakeWorkflow implements PersistentWorkflowI {

    /*
    This class is needed because the test environment cannot use XFTItems (doesn't use full db, doesn't initialize XFT),
    and thus it cannot populate attributes of any ItemWrapper-extending objects (WrkWorkflowData, XnatMrsessionData, etc)
    -- ItemWrapper attributes like id, etc are actually properties of the corresponding XFTItem.

    Since container service with staging queue no longer returns the container object upon launch (instead, it queues
    the request for launch and then a consumer does the launching asynchronously), we need a workflow object to get
    the containerId for our tests.
     */

    private int wfid = 123456;
    public static int eventId = 1;
    private String details;
    private String comments;
    private String justification;
    private EventUtils.TYPE type;
    private EventUtils.CATEGORY category;
    private String data_type;
    private String id;
    private String externalId;
    private String status;
    private String pipelineName;

    /**
     * @return Returns the details.
     */
    public String getDetails() {
        return details;
    }

    /**
     * Sets the value for details.
     * @param v Value to Set.
     */
    public void setDetails(String v) {
        details = v;
    }

    /**
     * @return Returns the comments.
     */
    public String getComments() {
        return comments;
    }

    /**
     * Sets the value for comments.
     * @param v Value to Set.
     */
    public void setComments(String v) {
        comments = v;
    }

    /**
     * @return Returns the justification.
     */
    public String getJustification() {
        return justification;
    }

    /**
     * Sets the value for justification.
     * @param v Value to Set.
     */
    public void setJustification(String v) {
        justification = v;
    }

    /**
     * @return Returns the type.
     */
    public String getType() {
        return type.toString();
    }

    /**
     * Sets the value for type.
     * @param v Value to Set.
     */
    public void setType(EventUtils.TYPE v) {
        type = v;
    }

    /**
     * @return Returns the type.
     */
    public String getCategory() {
        return category.toString();
    }

    /**
     * Sets the value for type.
     * @param v Value to Set.
     */
    public void setCategory(EventUtils.CATEGORY v) {
        category = v;
    }

    /**
     * @return Returns the data_type.
     */
    public String getDataType() {
        return data_type;
    }

    /**
     * Sets the value for data_type.
     * @param v Value to Set.
     */
    public void setDataType(String v) {
        data_type = v;
    }

    /**
     * @return Returns the ID.
     */
    public String getId() {
        return id;
    }

    /**
     * Sets the value for ID.
     * @param v Value to Set.
     */
    public void setId(String v) {
        id = v;
    }

    /**
     * @return Returns the ExternalID.
     */
    public String getExternalid() {
        return externalId;
    }

    /**
     * Sets the value for ExternalID.
     * @param v Value to Set.
     */
    public void setExternalid(String v) {
        externalId = v;
    }

    /**
     * @return Returns the current_step_launch_time.
     */
    public Object getCurrentStepLaunchTime() {
        return null;
    }

    /**
     * Sets the value for current_step_launch_time.
     * @param v Value to Set.
     */
    public void setCurrentStepLaunchTime(Object v) {

    }

    /**
     * @return Returns the current_step_id.
     */
    public String getCurrentStepId() {
        return null;
    }

    /**
     * Sets the value for current_step_id.
     * @param v Value to Set.
     */
    public void setCurrentStepId(String v) {

    }

    /**
     * @return Returns the status.
     */
    public String getStatus() {
        return status;
    }

    /**
     * Sets the value for status.
     * @param v Value to Set.
     */
    public void setStatus(String v) {
        status = v;
    }

    /**
     * @return Returns the create_user.
     */
    public String getCreateUser() {
        return "admin";
    }

    /**
     * Sets the value for create_user.
     * @param v Value to Set.
     */
    public void setCreateUser(String v) {

    }

    /**
     * @return Returns the pipeline_name.
     */
    public String getPipelineName() {
        return pipelineName;
    }

    /**
     * Sets the value for pipeline_name.
     * @param v Value to Set.
     */
    public void setPipelineName(String v) {
        pipelineName = v;
    }

    /**
     * @return Returns the step_description.
     */
    public String getStepDescription() {
        return "";
    }

    /**
     * Sets the value for step_description.
     * @param v Value to Set.
     */
    public void setStepDescription(String v) {

    }

    /**
     * @return Returns the launch_time.
     */
    public Object getLaunchTime() {
        return null;
    }

    /**
     * @return Returns the launch_time.
     */
    public Date getLaunchTimeDate(){
        return null;
    }
    /**
     * @return Returns the launch_time.
     */
    public String getOnlyPipelineName(){
        return pipelineName;
    }

    /**
     * Sets the value for launch_time.
     * @param v Value to Set.
     */
    public void setLaunchTime(Object v) {

    }

    /**
     * @return Returns the percentageComplete.
     */
    public String getPercentagecomplete() {
        return "";
    }

    /**
     * Sets the value for percentageComplete.
     * @param v Value to Set.
     */
    public void setPercentagecomplete(String v) {

    }

    public String getUsername() {
        return "admin";
    }

    public Integer getWorkflowId() {
        return wfid;
    }

    public EventMetaI buildEvent() {
        EventMetaI event = Mockito.mock(EventMetaI.class);
        when(event.getEventId()).thenReturn(eventId);
        return event;
    }

    public boolean save(UserI user, boolean overrideSecurity, boolean allowItemRemoval, EventMetaI c) throws Exception {
        return true;
    }

    public void postSave() throws Exception{}

    public void postSave(boolean triggerEvent) throws Exception{}

}
