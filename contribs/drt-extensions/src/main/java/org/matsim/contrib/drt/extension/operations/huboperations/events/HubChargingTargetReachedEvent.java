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
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;

import java.util.Map;

/**
 * Fired when a plugged-in vehicle has reached its charging target. From now on it charges nothing and waits, still on
 * the plug, for a worker to unplug it.
 *
 * @author lpfeil / MOIA
 */
public class HubChargingTargetReachedEvent extends AbstractHubOperationsEvent {

	public static final String EVENT_TYPE = "hub charging target reached";

	public static final String ATTRIBUTE_SOC = "soc";

	private final double soc;

	public HubChargingTargetReachedEvent(double time, String mode, Id<DvrpVehicle> vehicleId, double soc) {
		super(time, mode, vehicleId);
		this.soc = soc;
	}

	@Override
	public String getEventType() {
		return EVENT_TYPE;
	}

	public double getSoc() {
		return soc;
	}

	@Override
	public Map<String, String> getAttributes() {
		Map<String, String> attr = super.getAttributes();
		attr.put(ATTRIBUTE_SOC, soc + "");
		return attr;
	}
}
