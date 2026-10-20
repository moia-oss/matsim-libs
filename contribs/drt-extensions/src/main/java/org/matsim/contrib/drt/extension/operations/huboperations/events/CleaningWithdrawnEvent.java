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
import org.matsim.contrib.drt.extension.operations.huboperations.CleaningType;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;

import java.util.Map;

/**
 * Fired when a due type is withdrawn before the vehicle has started its hub visit, e.g. an opportunistic charge after
 * the fleet became tight. If nothing else is due, the vehicle stays in service and takes new requests again.
 *
 * @author lpfeil / MOIA
 */
public class CleaningWithdrawnEvent extends AbstractHubOperationsEvent {

	public static final String EVENT_TYPE = "hub cleaning withdrawn";

	public static final String ATTRIBUTE_CLEANING_TYPE = "cleaningType";
	public static final String ATTRIBUTE_SOC = "soc";

	private final CleaningType cleaningType;
	private final double soc;

	public CleaningWithdrawnEvent(double time, String mode, Id<DvrpVehicle> vehicleId, CleaningType cleaningType,
								  double soc) {
		super(time, mode, vehicleId);
		this.cleaningType = cleaningType;
		this.soc = soc;
	}

	@Override
	public String getEventType() {
		return EVENT_TYPE;
	}

	public CleaningType getCleaningType() {
		return cleaningType;
	}

	public double getSoc() {
		return soc;
	}

	@Override
	public Map<String, String> getAttributes() {
		Map<String, String> attr = super.getAttributes();
		attr.put(ATTRIBUTE_CLEANING_TYPE, cleaningType.name());
		attr.put(ATTRIBUTE_SOC, soc + "");
		return attr;
	}
}
