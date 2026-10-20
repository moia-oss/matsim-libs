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
import org.matsim.contrib.drt.extension.operations.operationFacilities.OperationFacility;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Fired when a cleaning stop ends. The vehicle re-enters service and its soiling counter starts again from zero.
 *
 * @author lpfeil / MOIA
 */
public class CleaningEndedEvent extends AbstractHubOperationsEvent {

	public static final String EVENT_TYPE = "hub cleaning ended";

	public static final String ATTRIBUTE_FACILITY_ID = "facility";

	public static final String ATTRIBUTE_CLEANING_TYPES = "cleaningTypes";

	private final Id<OperationFacility> facilityId;
	private final Set<CleaningType> cleaningTypes;

	public CleaningEndedEvent(double time, String mode, Id<DvrpVehicle> vehicleId,
							  Id<OperationFacility> facilityId, Set<CleaningType> cleaningTypes) {
		super(time, mode, vehicleId);
		this.facilityId = facilityId;
		this.cleaningTypes = EnumSet.copyOf(cleaningTypes);
	}

	@Override
	public String getEventType() {
		return EVENT_TYPE;
	}

	public Id<OperationFacility> getFacilityId() {
		return facilityId;
	}

	/** The cleaning types done in this hub visit, one after the other. */
	public Set<CleaningType> getCleaningTypes() {
		return Collections.unmodifiableSet(cleaningTypes);
	}

	@Override
	public Map<String, String> getAttributes() {
		Map<String, String> attr = super.getAttributes();
		attr.put(ATTRIBUTE_FACILITY_ID, facilityId + "");
		attr.put(ATTRIBUTE_CLEANING_TYPES, CleaningType.format(cleaningTypes));
		return attr;
	}
}
