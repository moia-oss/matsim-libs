/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.huboperations;

import org.matsim.api.core.v01.Id;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;

import java.util.HashSet;
import java.util.Set;

/**
 * The vehicles that are on a hub visit right now and must not be put back into service until it is over: cleaned,
 * charged and unplugged. Shift assignment and remote-guidance activation consult it, so a vehicle waiting at the hub
 * for a worker or still plugged in is not handed a shift. The {@link CleaningDispatcher} keeps it up to date.
 * <p>
 * Bound in every run with shifts. Without hub operations it simply stays empty.
 *
 * @author lpfeil / MOIA
 */
public final class HubVisitRegistry {

	private final Set<Id<DvrpVehicle>> onVisit = new HashSet<>();

	public void start(Id<DvrpVehicle> vehicleId) {
		onVisit.add(vehicleId);
	}

	public void end(Id<DvrpVehicle> vehicleId) {
		onVisit.remove(vehicleId);
	}

	public boolean isOnVisit(Id<DvrpVehicle> vehicleId) {
		return onVisit.contains(vehicleId);
	}

	public void clear() {
		onVisit.clear();
	}
}
