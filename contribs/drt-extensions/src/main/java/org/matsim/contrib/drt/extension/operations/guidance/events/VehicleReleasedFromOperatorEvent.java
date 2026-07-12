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

import java.util.Map;

/**
 * Fired when a vehicle is released from a remote guidance operator's supervision, i.e. when its virtual driver shift
 * ends and the operator's capacity slot is freed. The {@link #getReason()} distinguishes whether the operator shift
 * itself ended (potentially triggering a handover) or the vehicle returned to a hub.
 *
 * @author nkuehnel / MOIA
 */
public class VehicleReleasedFromOperatorEvent extends AbstractRemoteGuidanceEvent {

	public static final String EVENT_TYPE = "remote guidance vehicle released";

	public static final String ATTRIBUTE_REASON = "reason";

	/**
	 * The reason a vehicle was released from an operator.
	 */
	public enum ReleaseReason {
		/** The operator shift reached its end. */
		operatorShiftEnded,
		/** The virtual driver shift ended for another reason (e.g. the vehicle returned to a hub). */
		vehicleReturned
	}

	private final ReleaseReason reason;

	public VehicleReleasedFromOperatorEvent(double time, String mode, Id<DrtShift> operatorId,
											Id<DvrpVehicle> vehicleId, ReleaseReason reason) {
		super(time, mode, operatorId, vehicleId);
		this.reason = reason;
	}

	public ReleaseReason getReason() {
		return reason;
	}

	@Override
	public String getEventType() {
		return EVENT_TYPE;
	}

	@Override
	public Map<String, String> getAttributes() {
		Map<String, String> attr = super.getAttributes();
		attr.put(ATTRIBUTE_REASON, reason.toString());
		return attr;
	}
}