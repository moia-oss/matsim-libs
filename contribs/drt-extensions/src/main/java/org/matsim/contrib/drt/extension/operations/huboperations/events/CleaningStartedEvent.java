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
import org.matsim.contrib.drt.extension.operations.huboperations.CleaningType;
import org.matsim.contrib.drt.extension.operations.huboperations.HubWorkers;
import org.matsim.contrib.drt.extension.operations.operationFacilities.OperationFacility;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Fired when the cleaning of a vehicle begins. Without hub workers that is the arrival at the hub. With hub workers it is
 * the moment a service worker takes the vehicle, and the event names the worker and the time the vehicle waited.
 *
 * @author lpfeil / MOIA
 */
public class CleaningStartedEvent extends AbstractHubOperationsEvent {

	public static final String EVENT_TYPE = "hub cleaning started";

	public static final String ATTRIBUTE_FACILITY_ID = "facility";
	public static final String ATTRIBUTE_WORKER_ID = "worker";
	public static final String ATTRIBUTE_QUEUE_DELAY = "queueDelay";
	public static final String ATTRIBUTE_WORKER_TIME = "workerTime";

	public static final String ATTRIBUTE_CLEANING_TYPES = "cleaningTypes";

	private final Id<OperationFacility> facilityId;
	private final Set<CleaningType> cleaningTypes;
	@Nullable
	private final Id<HubWorkers.HubWorker> workerId;
	private final double queueDelay;
	private double workerTime = Double.NaN;

	public CleaningStartedEvent(double time, String mode, Id<DvrpVehicle> vehicleId,
								Id<OperationFacility> facilityId, Set<CleaningType> cleaningTypes) {
		this(time, mode, vehicleId, facilityId, cleaningTypes, null, 0.);
	}

	public CleaningStartedEvent(double time, String mode, Id<DvrpVehicle> vehicleId, Id<OperationFacility> facilityId,
								Set<CleaningType> cleaningTypes, @Nullable Id<HubWorkers.HubWorker> workerId,
								double queueDelay) {
		super(time, mode, vehicleId);
		this.facilityId = facilityId;
		this.cleaningTypes = EnumSet.copyOf(cleaningTypes);
		this.workerId = workerId;
		this.queueDelay = queueDelay;
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

	/** The worker doing the cleaning, or {@code null} when hub workers are not modelled. */
	@Nullable
	public Id<HubWorkers.HubWorker> getWorkerId() {
		return workerId;
	}

	/** Sets the seconds the worker spends cleaning in this visit; plugging in and out take none. */
	public CleaningStartedEvent withWorkerTime(double workerTime) {
		this.workerTime = workerTime;
		return this;
	}

	/** Seconds the worker spends cleaning in this visit, NaN if not set. */
	public double getWorkerTime() {
		return workerTime;
	}

	/** Seconds the vehicle waited at the hub for a free worker. Zero when hub workers are not modelled. */
	public double getQueueDelay() {
		return queueDelay;
	}

	@Override
	public Map<String, String> getAttributes() {
		Map<String, String> attr = super.getAttributes();
		attr.put(ATTRIBUTE_FACILITY_ID, facilityId + "");
		attr.put(ATTRIBUTE_CLEANING_TYPES, CleaningType.format(cleaningTypes));
		if (workerId != null) {
			attr.put(ATTRIBUTE_WORKER_ID, workerId + "");
			attr.put(ATTRIBUTE_QUEUE_DELAY, queueDelay + "");
		}
		if (!Double.isNaN(workerTime)) {
			attr.put(ATTRIBUTE_WORKER_TIME, workerTime + "");
		}
		return attr;
	}
}
