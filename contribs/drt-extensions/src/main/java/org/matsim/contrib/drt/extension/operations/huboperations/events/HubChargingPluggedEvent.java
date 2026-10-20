/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.huboperations.events;

import jakarta.annotation.Nullable;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.drt.extension.operations.huboperations.HubWorkers;
import org.matsim.contrib.drt.extension.operations.operationFacilities.OperationFacility;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.ev.infrastructure.Charger;

import java.util.Map;

/**
 * Fired when a hub worker plugs a vehicle in. The ev module then plugs it at once or queues it for a free plug, see
 * its {@code ChargingStartEvent} and {@code QueuedAtChargerEvent}.
 *
 * @author lpfeil / MOIA
 */
public class HubChargingPluggedEvent extends AbstractHubOperationsEvent {

	public static final String EVENT_TYPE = "hub charging plugged";

	public static final String ATTRIBUTE_FACILITY_ID = "facility";
	public static final String ATTRIBUTE_CHARGER_ID = "charger";
	public static final String ATTRIBUTE_SOC = "soc";
	public static final String ATTRIBUTE_WORKER_ID = "worker";

	private final Id<OperationFacility> facilityId;
	private final Id<Charger> chargerId;
	private final double soc;
	@Nullable
	private final Id<HubWorkers.HubWorker> workerId;

	public HubChargingPluggedEvent(double time, String mode, Id<DvrpVehicle> vehicleId, Id<OperationFacility> facilityId,
								   Id<Charger> chargerId, double soc, @Nullable Id<HubWorkers.HubWorker> workerId) {
		super(time, mode, vehicleId);
		this.facilityId = facilityId;
		this.chargerId = chargerId;
		this.soc = soc;
		this.workerId = workerId;
	}

	@Override
	public String getEventType() {
		return EVENT_TYPE;
	}

	public Id<OperationFacility> getFacilityId() {
		return facilityId;
	}

	public Id<Charger> getChargerId() {
		return chargerId;
	}

	/** State of charge when plugged in, in [0, 1]. */
	public double getSoc() {
		return soc;
	}

	/** The worker who plugged it in, or {@code null} when hub workers are not modelled. */
	@Nullable
	public Id<HubWorkers.HubWorker> getWorkerId() {
		return workerId;
	}

	@Override
	public Map<String, String> getAttributes() {
		Map<String, String> attr = super.getAttributes();
		attr.put(ATTRIBUTE_FACILITY_ID, facilityId + "");
		attr.put(ATTRIBUTE_CHARGER_ID, chargerId + "");
		attr.put(ATTRIBUTE_SOC, soc + "");
		if (workerId != null) {
			attr.put(ATTRIBUTE_WORKER_ID, workerId + "");
		}
		return attr;
	}
}
