/*
 * Copyright (C) 2026 MOIA GmbH - All Rights Reserved
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.guidance;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Identifiable;
import org.matsim.contrib.drt.extension.operations.shifts.shift.DrtShift;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Runtime entity representing a remote guidance operator and the vehicles it currently supervises. An operator is
 * derived from an operator {@link DrtShift} (i.e. a shift whose type equals the configured operator shift type) and
 * has a fixed capacity, i.e. the maximum number of vehicles it can supervise simultaneously.
 * <p>
 * Supervision is realized by means of "virtual" driver shifts emitted for idle vehicles: while such a virtual shift is
 * live (emitted and not yet ended), it occupies one of the operator's capacity slots. The operator therefore tracks
 * the set of live virtual shift ids rather than the vehicles directly, because a capacity slot is reserved already at
 * emission time, before the dispatcher has bound the shift to a concrete vehicle.
 *
 * @author nkuehnel / MOIA
 */
public final class RemoteGuidanceOperator implements Identifiable<DrtShift> {

	private final DrtShift operatorShift;
	private final int capacity;

	private final Set<Id<DrtShift>> liveVirtualShifts = new LinkedHashSet<>();

	public RemoteGuidanceOperator(DrtShift operatorShift, int capacity) {
		this.operatorShift = operatorShift;
		this.capacity = capacity;
	}

	public DrtShift getOperatorShift() {
		return operatorShift;
	}

	public double getStartTime() {
		return operatorShift.getStartTime();
	}

	public double getEndTime() {
		return operatorShift.getEndTime();
	}

	public int getCapacity() {
		return capacity;
	}

	/**
	 * @return true if the operator can supervise at least one additional vehicle
	 */
	public boolean hasFreeCapacity() {
		return liveVirtualShifts.size() < capacity;
	}

	public int getFreeCapacity() {
		return Math.max(0, capacity - liveVirtualShifts.size());
	}

	public int getCurrentLoad() {
		return liveVirtualShifts.size();
	}

	public Set<Id<DrtShift>> getLiveVirtualShifts() {
		return Collections.unmodifiableSet(liveVirtualShifts);
	}

	void reserveSlot(Id<DrtShift> virtualShiftId) {
		if (!hasFreeCapacity()) {
			throw new IllegalStateException("Operator " + getId() + " has no free capacity.");
		}
		liveVirtualShifts.add(virtualShiftId);
	}

	void releaseSlot(Id<DrtShift> virtualShiftId) {
		liveVirtualShifts.remove(virtualShiftId);
	}

	void clearReservations() {
		liveVirtualShifts.clear();
	}

	@Override
	public Id<DrtShift> getId() {
		return operatorShift.getId();
	}

	@Override
	public String toString() {
		return "RemoteGuidanceOperator " + getId() + " [" + getStartTime() + "-" + getEndTime() + "] "
				+ getCurrentLoad() + "/" + capacity;
	}
}