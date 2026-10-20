/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.huboperations.schedule;

import org.matsim.api.core.v01.Id;
import org.matsim.contrib.drt.extension.operations.huboperations.CleaningType;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.drt.extension.operations.operationFacilities.OperationFacility;
import org.matsim.contrib.drt.extension.operations.shifts.schedule.OperationalStop;
import org.matsim.contrib.drt.passenger.AcceptedDrtRequest;
import org.matsim.contrib.drt.schedule.DrtStopTask;
import org.matsim.contrib.drt.schedule.DrtTaskType;
import org.matsim.contrib.dvrp.optimizer.Request;
import org.matsim.contrib.dvrp.schedule.DefaultStayTask;
import org.matsim.contrib.evrp.ETask;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.Map;

import static org.matsim.contrib.drt.schedule.DrtTaskBaseType.STOP;

/**
 * A cleaning stop at a hub. The vehicle is due for at least one {@link CleaningType} and stands at the hub until the
 * cleaning of all its {@link #getTypes() types} is done, then re-enters service. The types are planned when the stop is
 * inserted and fixed when the cleaning starts, because the counters keep running on the way to the hub.
 * <p>
 * As a {@link STOP}-base {@link DrtStopTask} with empty pickup and dropoff sets, the standard
 * {@link org.matsim.contrib.drt.vrpagent.DrtActionCreator} turns it into a stop activity that simply waits until
 * {@link #getEndTime()}, so no dedicated activity is needed. It implements the {@link OperationalStop} marker, which
 * makes the shift insertion logic leave the vehicle alone while it is being cleaned.
 * <p>
 * It implements {@link ETask} directly so the eDRT energy bookkeeping, which casts every scheduled task to
 * {@link ETask}, works in electric fleets too. A vehicle at the hub is stationary, so it consumes no drive energy.
 *
 * @author lpfeil / MOIA
 */
public class CleaningTask extends DefaultStayTask implements DrtStopTask, OperationalStop, ETask {

	public static final DrtTaskType TYPE = new DrtTaskType("CLEANING", STOP);

	private final Id<OperationFacility> facilityId;
	private Set<CleaningType> types;

	public CleaningTask(double beginTime, double endTime, Link link, Id<OperationFacility> facilityId,
						Set<CleaningType> types) {
		super(TYPE, beginTime, endTime, link);
		this.facilityId = facilityId;
		this.types = EnumSet.copyOf(types);
	}

	public Id<OperationFacility> getFacilityId() {
		return facilityId;
	}

	/** The cleaning types done in this stop. */
	public Set<CleaningType> getTypes() {
		return Collections.unmodifiableSet(types);
	}

	public void setTypes(Set<CleaningType> types) {
		this.types = EnumSet.copyOf(types);
	}

	@Override
	public double getTotalEnergy() {
		return 0.0;
	}

	@Override
	public Map<Id<Request>, AcceptedDrtRequest> getDropoffRequests() {
		return Collections.emptyMap();
	}

	@Override
	public Map<Id<Request>, AcceptedDrtRequest> getPickupRequests() {
		return Collections.emptyMap();
	}

	@Override
	public void addDropoffRequest(AcceptedDrtRequest request) {
		throw new UnsupportedOperationException("Passenger dropoff is not possible during cleaning");
	}

	@Override
	public void addPickupRequest(AcceptedDrtRequest request) {
		throw new UnsupportedOperationException("Passenger pickup is not possible during cleaning");
	}

	@Override
	public void removePickupRequest(Id<Request> requestId) {
		throw new UnsupportedOperationException("Passenger pickup is not possible during cleaning");
	}

	@Override
	public void removeDropoffRequest(Id<Request> requestId) {
		throw new UnsupportedOperationException("Passenger dropoff is not possible during cleaning");
	}

	@Override
	public double calcEarliestArrivalTime() {
		return 0;
	}

	@Override
	public double calcLatestArrivalTime() {
		return Double.MAX_VALUE;
	}

	@Override
	public double calcEarliestDepartureTime() {
		return 0;
	}

	@Override
	public double calcLatestDepartureTime() {
		return Double.MAX_VALUE;
	}
}
