package org.nrg.containers.jms.requests;


import org.apache.commons.lang3.StringUtils;

abstract public class ContainerRequest   {
	public static String inQueueStatusPrefix = "_";

	/**
	 * Returns true if workflow status indicates that the request is in the JMS queue
	 * @param workflowStatus the workflow status
	 * @return T/F
	 */
	public boolean inJMSQueue(String workflowStatus) {
		// We'd prefer to store the workflow & user as part of the request but since they're not serializable, we don't
		// have access to them. Thus, whatever calls this method has to do the work of retrieving the workflow status
		return StringUtils.startsWith(workflowStatus, inQueueStatusPrefix);
	}

	/**
	 * Return the status that'll indicate that this request is in the JMS queue
	 * @param workflowStatus the current status
	 * @return the status indicating queued
	 */
	public String makeJMSQueuedStatus(String workflowStatus) {
		return inQueueStatusPrefix + workflowStatus;
	}
}
