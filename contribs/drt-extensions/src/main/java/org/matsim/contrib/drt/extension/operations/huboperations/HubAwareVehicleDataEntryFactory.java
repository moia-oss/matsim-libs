/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.huboperations;

import org.matsim.contrib.drt.optimizer.VehicleEntry;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;

/**
 * Excludes a vehicle from request insertion from the moment it is due for cleaning until its cleaning stop has ended.
 * <p>
 * A due vehicle must not take new riders, otherwise it would never become empty and could never be sent to the hub.
 * Excluding it from insertion achieves exactly that and nothing more: the riders already assigned to it stay in its
 * schedule and are served as planned, because insertion only concerns new requests. The due flag is cleared when the
 * cleaning ends, so the vehicle is offered to new requests again from the hub.
 * <p>
 * This mirrors {@code IncidentAwareVehicleDataEntryFactory} and wraps whatever entry factory is bound already, so it
 * composes with the shift and remote-guidance wrappers.
 *
 * @author lpfeil / MOIA
 */
public class HubAwareVehicleDataEntryFactory implements VehicleEntry.EntryFactory {

	private final VehicleEntry.EntryFactory delegate;
	private final SoilingTracker tracker;

	public HubAwareVehicleDataEntryFactory(VehicleEntry.EntryFactory delegate, SoilingTracker tracker) {
		this.delegate = delegate;
		this.tracker = tracker;
	}

	@Override
	public VehicleEntry create(DvrpVehicle vehicle, double currentTime) {
		if (tracker.isDue(vehicle.getId())) {
			return null;
		}
		return delegate.create(vehicle, currentTime);
	}
}
