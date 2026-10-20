/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.huboperations;

import org.matsim.contrib.drt.extension.operations.shifts.dispatcher.AssignShiftToVehicleLogic;
import org.matsim.contrib.drt.extension.operations.shifts.fleet.ShiftDvrpVehicle;
import org.matsim.contrib.drt.extension.operations.shifts.shift.DrtShift;

/**
 * Refuses a shift to a vehicle that is on a hub visit, and otherwise defers to the wrapped logic. A refused shift stays
 * unassigned and is offered to another vehicle in the same step.
 *
 * @author lpfeil / MOIA
 */
public final class HubAwareAssignShiftToVehicleLogic implements AssignShiftToVehicleLogic {

	private final AssignShiftToVehicleLogic delegate;
	private final HubVisitRegistry registry;

	public HubAwareAssignShiftToVehicleLogic(AssignShiftToVehicleLogic delegate, HubVisitRegistry registry) {
		this.delegate = delegate;
		this.registry = registry;
	}

	@Override
	public boolean canAssignVehicleToShift(ShiftDvrpVehicle vehicle, DrtShift shift) {
		return !registry.isOnVisit(vehicle.getId()) && delegate.canAssignVehicleToShift(vehicle, shift);
	}
}
