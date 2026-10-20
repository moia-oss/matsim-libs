/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.huboperations;

import jakarta.annotation.Nullable;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.drt.extension.operations.huboperations.charging.WorkerReleasedChargingStrategy;
import org.matsim.contrib.drt.extension.operations.operationFacilities.OperationFacility;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.schedule.StayTask;
import org.matsim.contrib.ev.fleet.ElectricVehicle;
import org.matsim.contrib.ev.infrastructure.Charger;

import java.util.EnumSet;
import java.util.Set;

/**
 * State of one hub visit of one vehicle, from arrival until it may leave. A visit stands on a host stop: a
 * {@code CleaningTask} for a vehicle sent to the hub from service, or the {@code WaitForShiftTask} of a vehicle that is
 * already parked at the hub out of service.
 * <p>
 * Lifecycle: the vehicle waits for a worker ({@link Phase#waitingForWorker}); the worker plugs it in if it charges and
 * cleans its types ({@link Phase#inService}); a charging vehicle that is cleaned waits for its charge and then for a
 * worker to unplug it ({@link Phase#charging}, {@link Phase#waitingForUnplug}); the visit is done when it is cleaned and
 * unplugged. Mutable and package private, owned by the {@link CleaningDispatcher}.
 *
 * @author lpfeil / MOIA
 */
final class HubVisit {

	enum Phase {
		/** driving to the hub, the host stop has not started yet */
		approaching,
		/** at the hub, waiting for a worker to start */
		waitingForWorker,
		/** a worker plugged it in if needed and is cleaning it */
		inService,
		/** cleaned, still charging */
		charging,
		/** charged to its target, waiting for a worker to unplug it */
		waitingForUnplug,
		/** cleaned and unplugged; the host stop may end */
		done
	}

	final Id<DvrpVehicle> vehicleId;
	final Id<OperationFacility> hub;
	final StayTask host;
	/** whether the host is a parked vehicle's wait task rather than a cleaning stop */
	final boolean parked;
	/**
	 * whether this is a maintenance visit, served by the maintenance pool L_M and doing maintenance only; fixed when the
	 * visit is planned, so a type that becomes due on the way waits for a visit of its own
	 */
	final boolean maintenance;
	Phase phase = Phase.approaching;
	Set<CleaningType> types = EnumSet.noneOf(CleaningType.class);

	double arrivalTime = Double.NaN;
	double serviceStart = Double.NaN;
	double cleaningEnd = Double.NaN;
	double queueDelay = Double.NaN;
	@Nullable
	Id<HubWorkers.HubWorker> serviceWorker;

	// charging, only when types contains charging
	@Nullable
	ElectricVehicle ev;
	@Nullable
	Charger charger;
	@Nullable
	WorkerReleasedChargingStrategy strategy;
	double socAtPlug = Double.NaN;
	double chargeAtPlug = Double.NaN;
	double targetReachedTime = Double.NaN;
	double unplugTime = Double.NaN;
	@Nullable
	Id<HubWorkers.HubWorker> unplugWorker;
	/** unplugged before reaching its target, to give the vehicle back to a tight fleet */
	boolean earlyRelease;

	HubVisit(Id<DvrpVehicle> vehicleId, Id<OperationFacility> hub, StayTask host, boolean parked, boolean maintenance) {
		this.vehicleId = vehicleId;
		this.hub = hub;
		this.host = host;
		this.parked = parked;
		this.maintenance = maintenance;
	}

	/** The worker job that starts this visit. */
	HubWorkforce.Job serviceJob() {
		return maintenance ? HubWorkforce.Job.maintenance : HubWorkforce.Job.service;
	}

	boolean charges() {
		return types.contains(CleaningType.charging);
	}

	boolean isPlugged() {
		return charger != null && Double.isNaN(unplugTime);
	}
}
