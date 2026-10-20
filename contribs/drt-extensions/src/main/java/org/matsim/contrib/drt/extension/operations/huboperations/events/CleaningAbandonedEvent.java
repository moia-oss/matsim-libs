/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.huboperations.events;

import org.matsim.api.core.v01.Id;
import org.matsim.contrib.drt.extension.operations.operationFacilities.OperationFacility;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;

import java.util.Map;

/**
 * Fired when a vehicle waiting for cleaning gives up because no worker of its hub will be on duty again. The vehicle
 * stays at the hub uncleaned and out of service, so the event marks demand the hub workforce could not meet.
 *
 * @author lpfeil / MOIA
 */
public class CleaningAbandonedEvent extends AbstractHubOperationsEvent {

	public static final String EVENT_TYPE = "hub cleaning abandoned";

	public static final String ATTRIBUTE_FACILITY_ID = "facility";
	public static final String ATTRIBUTE_QUEUE_DELAY = "queueDelay";

	private final Id<OperationFacility> facilityId;
	private final double queueDelay;

	public CleaningAbandonedEvent(double time, String mode, Id<DvrpVehicle> vehicleId, Id<OperationFacility> facilityId,
								  double queueDelay) {
		super(time, mode, vehicleId);
		this.facilityId = facilityId;
		this.queueDelay = queueDelay;
	}

	@Override
	public String getEventType() {
		return EVENT_TYPE;
	}

	public Id<OperationFacility> getFacilityId() {
		return facilityId;
	}

	/** Seconds the vehicle waited at the hub before it gave up. */
	public double getQueueDelay() {
		return queueDelay;
	}

	@Override
	public Map<String, String> getAttributes() {
		Map<String, String> attr = super.getAttributes();
		attr.put(ATTRIBUTE_FACILITY_ID, facilityId + "");
		attr.put(ATTRIBUTE_QUEUE_DELAY, queueDelay + "");
		return attr;
	}
}
