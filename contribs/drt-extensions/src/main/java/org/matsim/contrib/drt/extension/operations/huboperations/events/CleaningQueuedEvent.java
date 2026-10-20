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
 * Fired when a vehicle due for cleaning arrives at the hub and joins the queue for a service worker. Only fired when hub
 * workers are modelled; without them a cleaning starts on arrival. A maintenance visit queues for a technician of the
 * maintenance pool instead, see {@link #isMaintenance()}.
 *
 * @author lpfeil / MOIA
 */
public class CleaningQueuedEvent extends AbstractHubOperationsEvent {

	public static final String EVENT_TYPE = "hub cleaning queued";

	public static final String ATTRIBUTE_FACILITY_ID = "facility";
	public static final String ATTRIBUTE_MAINTENANCE = "maintenance";

	private final Id<OperationFacility> facilityId;
	private final boolean maintenance;

	public CleaningQueuedEvent(double time, String mode, Id<DvrpVehicle> vehicleId, Id<OperationFacility> facilityId) {
		this(time, mode, vehicleId, facilityId, false);
	}

	public CleaningQueuedEvent(double time, String mode, Id<DvrpVehicle> vehicleId, Id<OperationFacility> facilityId,
							   boolean maintenance) {
		super(time, mode, vehicleId);
		this.facilityId = facilityId;
		this.maintenance = maintenance;
	}

	/** Whether the vehicle waits for a maintenance technician rather than for the hub crew. */
	public boolean isMaintenance() {
		return maintenance;
	}

	@Override
	public String getEventType() {
		return EVENT_TYPE;
	}

	public Id<OperationFacility> getFacilityId() {
		return facilityId;
	}

	@Override
	public Map<String, String> getAttributes() {
		Map<String, String> attr = super.getAttributes();
		attr.put(ATTRIBUTE_FACILITY_ID, facilityId + "");
		attr.put(ATTRIBUTE_MAINTENANCE, maintenance + "");
		return attr;
	}
}
