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
 * Fired when a vehicle is taken off the charger: by a hub worker once it has reached its target, without a worker when
 * none will be on duty again, or because its hub visit was cut short.
 *
 * @author lpfeil / MOIA
 */
public class HubChargingUnpluggedEvent extends AbstractHubOperationsEvent {

	public static final String EVENT_TYPE = "hub charging unplugged";

	public static final String ATTRIBUTE_FACILITY_ID = "facility";
	public static final String ATTRIBUTE_CHARGER_ID = "charger";
	public static final String ATTRIBUTE_SOC = "soc";
	public static final String ATTRIBUTE_WORKER_ID = "worker";
	public static final String ATTRIBUTE_WAIT_FOR_UNPLUG = "waitForUnplug";
	/** Energy delivered in the session in Joule, as the {@code energy} attribute of ChargingTerminatedEvent. */
	public static final String ATTRIBUTE_ENERGY = "energy";
	public static final String ATTRIBUTE_EARLY = "early";

	private final Id<OperationFacility> facilityId;
	private final Id<Charger> chargerId;
	private final double soc;
	@Nullable
	private final Id<HubWorkers.HubWorker> workerId;
	private final double waitForUnplug;
	private final double energy;
	private boolean early;

	public HubChargingUnpluggedEvent(double time, String mode, Id<DvrpVehicle> vehicleId,
									 Id<OperationFacility> facilityId, Id<Charger> chargerId, double soc,
									 @Nullable Id<HubWorkers.HubWorker> workerId, double waitForUnplug,
									 double energy) {
		super(time, mode, vehicleId);
		this.facilityId = facilityId;
		this.chargerId = chargerId;
		this.soc = soc;
		this.workerId = workerId;
		this.waitForUnplug = waitForUnplug;
		this.energy = energy;
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

	/** State of charge when unplugged, in [0, 1]. */
	public double getSoc() {
		return soc;
	}

	/** The worker who unplugged it, or {@code null} without a worker. */
	@Nullable
	public Id<HubWorkers.HubWorker> getWorkerId() {
		return workerId;
	}

	/** Seconds the vehicle stood on the plug after reaching its target, waiting for a worker; NaN if never reached. */
	public double getWaitForUnplug() {
		return waitForUnplug;
	}

	/** Marks an early unplug before the target, to give the vehicle back to a tight fleet. */
	public HubChargingUnpluggedEvent withEarly(boolean early) {
		this.early = early;
		return this;
	}

	/** Whether the vehicle was unplugged before its target because the fleet was tight. */
	public boolean isEarly() {
		return early;
	}

	/** Energy charged during this visit, in Joule. */
	public double getEnergy() {
		return energy;
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
		attr.put(ATTRIBUTE_WAIT_FOR_UNPLUG, waitForUnplug + "");
		attr.put(ATTRIBUTE_ENERGY, energy + "");
		attr.put(ATTRIBUTE_EARLY, early + "");
		return attr;
	}
}
