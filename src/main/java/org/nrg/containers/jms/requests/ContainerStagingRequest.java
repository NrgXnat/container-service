package org.nrg.containers.jms.requests;

import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Map;


public class ContainerStagingRequest extends ContainerRequest implements Serializable {


	private static final long serialVersionUID = 1L;
	public static final String destination = "containerStagingRequest";

	private String project;
	private long wrapperId;
	private long commandId;
	private String wrapperName;
	private Map<String, String> inputValues;
	private String username;
	private String workflowid;


	public ContainerStagingRequest(@Nullable String project,
								   long wrapperId,
								   long commandId,
								   @Nullable String wrapperName,
								   Map<String, String> inputValues,
								   String username, String workflowid) {
		this.setProject(project);
		this.setWrapperId(wrapperId);
		this.setCommandId(commandId);
		this.setWrapperName(wrapperName);
		this.setInputValues(inputValues);
		this.setUsername(username);
		this.setWorkflowid(workflowid);
	}

	public String getWorkflowid() {
		return workflowid;
	}

	public void setWorkflowid(String workflowid) {
		this.workflowid = workflowid;
	}

	public String getWrapperName() {
		return wrapperName;
	}

	public void setWrapperName(String wrapperName) {
		this.wrapperName = wrapperName;
	}

	public long getWrapperId() {
		return wrapperId;
	}

	public void setWrapperId(long wrapperId) {
		this.wrapperId = wrapperId;
	}

	public long getCommandId() {
		return commandId;
	}

	public void setCommandId(long commandId) {
		this.commandId = commandId;
	}

	public String getProject() {
		return project;
	}

	public void setProject(String project) {
		this.project = project;
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public Map<String, String> getInputValues() {
		return inputValues;
	}

	public void setInputValues(Map<String, String> inputValues) {
		this.inputValues = inputValues;
	}
	
	public String getDestination() {
		return destination;
	}
}
