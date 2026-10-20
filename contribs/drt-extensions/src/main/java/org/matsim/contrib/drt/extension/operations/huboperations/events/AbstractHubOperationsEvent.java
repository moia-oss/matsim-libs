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
import org.matsim.api.core.v01.events.Event;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;

import java.util.Map;

/**
 * Base class for hub-operations events. Each event refers to one fleet vehicle of one DRT mode.
 *
 * @author lpfeil / MOIA
 */
public abstract class AbstractHubOperationsEvent extends Event {

	public static final String ATTRIBUTE_MODE = "mode";
	public static final String ATTRIBUTE_VEHICLE_ID = "vehicle";

	private final String mode;
	private final Id<DvrpVehicle> vehicleId;

	protected AbstractHubOperationsEvent(double time, String mode, Id<DvrpVehicle> vehicleId) {
		super(time);
		this.mode = mode;
		this.vehicleId = vehicleId;
	}

	public String getMode() {
		return mode;
	}

	public Id<DvrpVehicle> getVehicleId() {
		return vehicleId;
	}

	@Override
	public Map<String, String> getAttributes() {
		Map<String, String> attr = super.getAttributes();
		attr.put(ATTRIBUTE_MODE, mode);
		attr.put(ATTRIBUTE_VEHICLE_ID, vehicleId + "");
		return attr;
	}
}
