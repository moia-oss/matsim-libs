/*
 * Copyright (C) 2026 MOIA GmbH - All Rights Reserved
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.guidance.events;

import org.matsim.api.core.v01.Id;
import org.matsim.contrib.drt.extension.operations.shifts.shift.DrtShift;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;

/**
 * Fired when a remote guidance operator takes a vehicle under its supervision, i.e. when the vehicle occupies one of
 * the operator's capacity slots and is activated by means of a virtual driver shift.
 *
 * @author nkuehnel / MOIA
 */
public class VehicleAssignedToOperatorEvent extends AbstractRemoteGuidanceEvent {

	public static final String EVENT_TYPE = "remote guidance vehicle assigned";

	public VehicleAssignedToOperatorEvent(double time, String mode, Id<DrtShift> operatorId, Id<DvrpVehicle> vehicleId) {
		super(time, mode, operatorId, vehicleId);
	}

	@Override
	public String getEventType() {
		return EVENT_TYPE;
	}
}