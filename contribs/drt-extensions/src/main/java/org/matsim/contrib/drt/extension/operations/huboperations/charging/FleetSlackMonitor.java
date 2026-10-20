/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.huboperations.charging;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contrib.drt.extension.operations.huboperations.config.HubChargingSlackParams;
import org.matsim.contrib.drt.scheduler.EmptyVehicleRelocator;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.fleet.Fleet;
import org.matsim.contrib.dvrp.schedule.Schedule;
import org.matsim.contrib.dvrp.schedule.ScheduleInquiry;
import org.matsim.contrib.dvrp.schedule.Task;

import java.util.function.Predicate;

/**
 * Observes how much slack the fleet has and derives a state with hysteresis from it: RELAXED (charging allowed) or
 * TIGHT (opportunistic charging blocked, charging vehicles may be unplugged early).
 * <p>
 * Lifted from the slack-aware reactive charging controller of the MOIA Lucerne study ({@code FleetSlackMonitor} in
 * mob1337-lucerne) with two changes for the shift stack. The denominator is the vehicles <em>in service</em> by a
 * pluggable predicate, because with shifts, and with remote guidance in particular, a vehicle parked at the hub inside
 * its service window is not usable capacity. And the monitor is updated by its owner, the hub dispatcher, instead of
 * being its own QSim component, so its state is always read after the update of the same step.
 * <p>
 * Free means idle by the framework's {@link ScheduleInquiry} (with a minimum idle time, so vehicles that only stand
 * briefly between requests do not count) or on an empty relocation drive, which can be reassigned at any time.
 *
 * @author nkuehnel / MOIA (original), lpfeil / MOIA (shift adaptation)
 */
public final class FleetSlackMonitor {

	private static final Logger log = LogManager.getLogger(FleetSlackMonitor.class);

	public enum SlackState {RELAXED, TIGHT}

	private final Fleet fleet;
	private final ScheduleInquiry scheduleInquiry;
	private final HubChargingSlackParams params;
	private final ScheduleInquiry.IdleCriteria idleCriteria;
	private final Predicate<DvrpVehicle> inService;

	// the day starts at night with a completely free fleet
	private SlackState state = SlackState.RELAXED;
	private double stateSince = Double.NEGATIVE_INFINITY;
	private double smoothedFreeRatio = 1.;
	private int vehiclesInService = 0;
	private double lastUpdate = Double.NaN;
	private int stateChanges = 0;

	/**
	 * @param inService which vehicles count as fleet capacity, e.g. those with a running shift
	 */
	public FleetSlackMonitor(Fleet fleet, ScheduleInquiry scheduleInquiry, HubChargingSlackParams params,
							 Predicate<DvrpVehicle> inService) {
		this.fleet = fleet;
		this.scheduleInquiry = scheduleInquiry;
		this.params = params;
		this.inService = inService;
		this.idleCriteria = new ScheduleInquiry.IdleCriteria(params.getChargingMinIdleElapsed(), 0);
	}

	/** Idempotent per time step. */
	public void update(double now) {
		if (now == lastUpdate) {
			return;
		}
		double dt = Double.isNaN(lastUpdate) ? 1. : now - lastUpdate;
		lastUpdate = now;

		int inServiceCount = 0;
		int free = 0;
		for (DvrpVehicle vehicle : fleet.getVehicles().values()) {
			if (now < vehicle.getServiceBeginTime() || now >= vehicle.getServiceEndTime() || !inService.test(vehicle)) {
				continue;
			}
			inServiceCount++;
			if (isFree(vehicle)) {
				free++;
			}
		}
		vehiclesInService = inServiceCount;
		double rawFreeRatio = inServiceCount == 0 ? 1. : (double) free / inServiceCount;
		// exponentially weighted mean
		double alpha = Math.min(1., dt / Math.max(1., params.getSmoothingSeconds()));
		smoothedFreeRatio += alpha * (rawFreeRatio - smoothedFreeRatio);

		if (Double.isInfinite(stateSince)) {
			stateSince = now;
		}
		if (now - stateSince >= params.getMinDwellSeconds()) {
			if (state == SlackState.RELAXED && smoothedFreeRatio < params.getSlackLow()) {
				switchTo(SlackState.TIGHT, now, free, inServiceCount);
			} else if (state == SlackState.TIGHT && smoothedFreeRatio > params.getSlackHigh()) {
				switchTo(SlackState.RELAXED, now, free, inServiceCount);
			}
		}
	}

	private void switchTo(SlackState newState, double now, int free, int inServiceCount) {
		state = newState;
		stateSince = now;
		stateChanges++;
		log.info(String.format("Fleet slack -> %s at %.0f s (%.1f h): free %d/%d, smoothed %.3f", newState, now,
				now / 3600., free, inServiceCount, smoothedFreeRatio));
	}

	private boolean isFree(DvrpVehicle vehicle) {
		if (scheduleInquiry.isIdle(vehicle, idleCriteria)) {
			return true;
		}
		Schedule schedule = vehicle.getSchedule();
		if (schedule.getStatus() != Schedule.ScheduleStatus.STARTED) {
			return false;
		}
		Task.TaskType type = schedule.getCurrentTask().getTaskType();
		return EmptyVehicleRelocator.RELOCATE_VEHICLE_TO_DEPOT_TASK_TYPE.equals(type)
				|| EmptyVehicleRelocator.RELOCATE_VEHICLE_TASK_TYPE.equals(type);
	}

	public SlackState getState() {
		return state;
	}

	public boolean isRelaxed() {
		return state == SlackState.RELAXED;
	}

	public double smoothedFreeRatio() {
		return smoothedFreeRatio;
	}

	public int vehiclesInService() {
		return vehiclesInService;
	}

	public int stateChanges() {
		return stateChanges;
	}

	/** Resets the state for the next iteration. */
	public void reset() {
		state = SlackState.RELAXED;
		stateSince = Double.NEGATIVE_INFINITY;
		smoothedFreeRatio = 1.;
		vehiclesInService = 0;
		lastUpdate = Double.NaN;
		stateChanges = 0;
	}
}
