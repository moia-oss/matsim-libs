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
 * Fired when a vehicle crosses the soiling threshold of one {@link CleaningType}. If both types cross on the same link
 * one event per type is fired. The first due event of a vehicle takes it out of service: from this moment the vehicle accepts no new requests and
 * finishes the passengers already assigned to it before it heads to a hub. Opens the cleaning lifecycle
 * {@code CleaningDueEvent} {@literal ->} {@link CleaningStartedEvent} {@literal ->} {@link CleaningEndedEvent}.
 * <p>
 * The gap to {@link CleaningStartedEvent} is the time the vehicle spends out of service before it is cleaned,
 * finishing its passengers and driving empty to the hub.
 *
 * @author lpfeil / MOIA
 */
public class CleaningDueEvent extends AbstractHubOperationsEvent {

	public static final String EVENT_TYPE = "hub cleaning due";

	public static final String ATTRIBUTE_PASSENGER_KM = "passengerKm";
	public static final String ATTRIBUTE_VEHICLE_KM = "vehicleKm";
	public static final String ATTRIBUTE_CLEANING_TYPE = "cleaningType";
	public static final String ATTRIBUTE_SOC = "soc";

	private final CleaningType cleaningType;
	private final double passengerKm;
	private final double vehicleKm;
	private final double soc;

	public CleaningDueEvent(double time, String mode, Id<DvrpVehicle> vehicleId, CleaningType cleaningType,
							double passengerKm, double vehicleKm) {
		this(time, mode, vehicleId, cleaningType, passengerKm, vehicleKm, Double.NaN);
	}

	public CleaningDueEvent(double time, String mode, Id<DvrpVehicle> vehicleId, CleaningType cleaningType,
							double passengerKm, double vehicleKm, double soc) {
		super(time, mode, vehicleId);
		this.soc = soc;
		this.cleaningType = cleaningType;
		this.passengerKm = passengerKm;
		this.vehicleKm = vehicleKm;
	}

	/** The type whose threshold was crossed. */
	public CleaningType getCleaningType() {
		return cleaningType;
	}

	/** State of charge when due, NaN for vehicles without a battery. */
	public double getSoc() {
		return soc;
	}

	/**
	 * Vehicle kilometres since the last exterior cleaning when the threshold was crossed, or since the last maintenance
	 * for a maintenance due event.
	 */
	public double getVehicleKm() {
		return vehicleKm;
	}

	@Override
	public String getEventType() {
		return EVENT_TYPE;
	}

	/** Passenger kilometres since the last interior cleaning when the threshold was crossed. */
	public double getPassengerKm() {
		return passengerKm;
	}

	@Override
	public Map<String, String> getAttributes() {
		Map<String, String> attr = super.getAttributes();
		attr.put(ATTRIBUTE_CLEANING_TYPE, cleaningType.name());
		attr.put(ATTRIBUTE_PASSENGER_KM, Double.toString(passengerKm));
		attr.put(ATTRIBUTE_VEHICLE_KM, Double.toString(vehicleKm));
		if (!Double.isNaN(soc)) {
			attr.put(ATTRIBUTE_SOC, Double.toString(soc));
		}
		return attr;
	}
}
