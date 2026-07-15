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
import org.matsim.api.core.v01.events.Event;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;

import java.util.Map;

/**
 * Fired when a vehicle is deactivated from remote guidance, i.e. taken out of service (its virtual driver shift ends
 * and it returns to a hub). Carries <em>no</em> operator id (D15 — no operator&harr;vehicle binding). The
 * {@link #getReason()} records which extensive-margin trigger caused the deactivation (D16): the aggregate capacity
 * dropped below the active count ({@link DeactivationReason#capacityExceeded}), the vehicle sat idle in service for
 * too long ({@link DeactivationReason#idleTimeout}), or it otherwise returned to a hub.
 *
 * @author nkuehnel / MOIA
 */
public class VehicleDeactivatedForRemoteGuidanceEvent extends Event {

	public static final String EVENT_TYPE = "remote guidance vehicle deactivated";

	public static final String ATTRIBUTE_MODE = "mode";
	public static final String ATTRIBUTE_VEHICLE_ID = "vehicle";
	public static final String ATTRIBUTE_REASON = "reason";

	/**
	 * The extensive-margin trigger that deactivated the vehicle (D16).
	 */
	public enum DeactivationReason {
		/** The aggregate operator capacity {@code Σκ(t)} dropped below the active vehicle count (an operator went off-duty). */
		capacityExceeded,
		/** The vehicle was idle in service (a last-task stay) for longer than the configured idle timeout. */
		idleTimeout,
		/** The vehicle otherwise returned to a hub. */
		vehicleReturned
	}

	private final String mode;
	private final Id<DvrpVehicle> vehicleId;
	private final DeactivationReason reason;

	public VehicleDeactivatedForRemoteGuidanceEvent(double time, String mode, Id<DvrpVehicle> vehicleId,
													DeactivationReason reason) {
		super(time);
		this.mode = mode;
		this.vehicleId = vehicleId;
		this.reason = reason;
	}

	public String getMode() {
		return mode;
	}

	public Id<DvrpVehicle> getVehicleId() {
		return vehicleId;
	}

	public DeactivationReason getReason() {
		return reason;
	}

	@Override
	public String getEventType() {
		return EVENT_TYPE;
	}

	@Override
	public Map<String, String> getAttributes() {
		Map<String, String> attr = super.getAttributes();
		attr.put(ATTRIBUTE_MODE, mode);
		attr.put(ATTRIBUTE_VEHICLE_ID, vehicleId + "");
		attr.put(ATTRIBUTE_REASON, reason.toString());
		return attr;
	}
}
