/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.huboperations.analysis;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.contrib.common.timeprofile.TimeDiscretizer;
import org.matsim.contrib.drt.extension.operations.huboperations.CleaningType;
import org.matsim.contrib.drt.extension.operations.huboperations.events.AbstractHubOperationsEvent;
import org.matsim.contrib.drt.extension.operations.huboperations.events.CleaningAbandonedEvent;
import org.matsim.contrib.drt.extension.operations.huboperations.events.CleaningEndedEvent;
import org.matsim.contrib.drt.extension.operations.huboperations.events.CleaningQueuedEvent;
import org.matsim.contrib.drt.extension.operations.huboperations.events.CleaningStartedEvent;
import org.matsim.contrib.drt.extension.operations.huboperations.events.HubChargingPluggedEvent;
import org.matsim.contrib.drt.extension.operations.huboperations.events.HubChargingTargetReachedEvent;
import org.matsim.contrib.drt.extension.operations.huboperations.events.HubChargingUnpluggedEvent;
import org.matsim.contrib.drt.extension.operations.huboperations.schedule.CleaningTask;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.vrpagent.TaskEndedEvent;
import org.matsim.contrib.dvrp.vrpagent.TaskStartedEvent;
import org.matsim.contrib.ev.charging.ChargingStartEvent;
import org.matsim.core.events.handler.BasicEventHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits the vehicle time of hub visits into what the vehicle is doing, as a time profile in the units of
 * {@code occupancy_time_profiles}: vehicles per time bin.
 * <ul>
 *     <li>{@link #INTERIOR}, {@link #EXTERIOR}: a worker cleans the vehicle. The types of a visit are done one after the
 *         other, interior first, so at most one cleaning runs at a time.</li>
 *     <li>{@link #MAINTENANCE}: a technician of the maintenance pool works on the vehicle in the workshop. A maintenance
 *         visit does nothing else, so it never runs in parallel to cleaning or charging.</li>
 *     <li>{@link #CHARGING}: on the plug and charging.</li>
 *     <li>{@link #WAIT_FOR_PLUG}: plugged in by a worker, but queued by the ev module for a free plug.</li>
 *     <li>{@link #WAIT_FOR_WORKER}: at the hub with nothing running, waiting for a worker, to start or to unplug a
 *         charged vehicle.</li>
 * </ul>
 * Interior and exterior of one vehicle follow each other and never overlap. Charging runs in parallel to them: while a
 * vehicle is cleaned and charges, the two get half of it each. A charged vehicle still on the plug only waits, so it
 * adds nothing while it is cleaned and counts as waiting for a worker once the cleaning is done. Every vehicle at the hub thus counts once, and the columns add up to the vehicles on a hub
 * visit, i.e. the {@code CLEANING} column of the occupancy profile plus the parked visits on a wait task.
 * <p>
 * A visit opens with its queued or started event and closes with the end of its {@code CleaningTask}, or for a vehicle
 * parked at the hub with its ended or abandoned event.
 *
 * @author lpfeil / MOIA
 */
public final class HubTimeProfileCalculator implements BasicEventHandler {

	public static final String INTERIOR = "HUB_INTERIOR";
	public static final String EXTERIOR = "HUB_EXTERIOR";
	public static final String MAINTENANCE = "HUB_MAINTENANCE";
	public static final String CHARGING = "HUB_CHARGING";
	public static final String WAIT_FOR_PLUG = "HUB_WAIT_FOR_PLUG";
	public static final String WAIT_FOR_WORKER = "HUB_WAIT_FOR_WORKER";

	/** Column order of the profile and the stacked chart. */
	public static final List<String> COLUMNS = List.of(INTERIOR, EXTERIOR, MAINTENANCE, CHARGING, WAIT_FOR_PLUG,
			WAIT_FOR_WORKER);

	private enum Plug {none, waitingForPlug, charging, charged}

	/** What one vehicle on a hub visit is doing. */
	private static final class Visit {
		private boolean parked;
		private double since;
		private double interiorEnd = Double.NaN;
		private double exteriorEnd = Double.NaN;
		private double maintenanceEnd = Double.NaN;
		private Plug plug = Plug.none;
	}

	private final String mode;
	private final TimeDiscretizer timeDiscretizer;
	private final double interiorDuration;
	private final double exteriorDuration;
	private final double maintenanceDuration;
	private Map<String, double[]> profiles;
	// share of the hub columns that falls on visits of vehicles parked out of service, i.e. on a WaitForShiftTask
	private double[] parkedProfile;
	private Map<Id<DvrpVehicle>, Visit> visits = new HashMap<>();

	/**
	 * @param interiorDuration worker time of one interior cleaning, to split a visit's cleaning time by type
	 * @param exteriorDuration worker time of one exterior cleaning
	 * @param analysisEndTime  end of the profile, as in the occupancy profile
	 */
	public HubTimeProfileCalculator(String mode, int timeInterval, double analysisEndTime, double interiorDuration,
									double exteriorDuration) {
		this(mode, timeInterval, analysisEndTime, interiorDuration, exteriorDuration, 0.);
	}

	/**
	 * @param maintenanceDuration technician time of one maintenance
	 */
	public HubTimeProfileCalculator(String mode, int timeInterval, double analysisEndTime, double interiorDuration,
									double exteriorDuration, double maintenanceDuration) {
		this.mode = mode;
		this.timeDiscretizer = new TimeDiscretizer((int) Math.ceil(analysisEndTime), timeInterval);
		this.interiorDuration = interiorDuration;
		this.exteriorDuration = exteriorDuration;
		this.maintenanceDuration = maintenanceDuration;
		reset(0);
	}

	@Override
	public void handleEvent(Event event) {
		if (event instanceof TaskStartedEvent started) {
			// a visit from service: its CleaningTask is the whole time at the hub
			if (mode.equals(started.getDvrpMode()) && CleaningTask.TYPE.equals(started.getTaskType())) {
				open(started.getDvrpVehicleId(), event.getTime(), false);
			}
			return;
		}
		if (event instanceof TaskEndedEvent ended) {
			if (mode.equals(ended.getDvrpMode()) && CleaningTask.TYPE.equals(ended.getTaskType())) {
				close(ended.getDvrpVehicleId(), event.getTime());
			}
			return;
		}
		if (event instanceof ChargingStartEvent start) {
			// the ev logic plugged a vehicle in; it is ours if a worker plugged it in on a hub visit
			Visit visit = visits.get(Id.create(start.getVehicleId(), DvrpVehicle.class));
			if (visit != null && visit.plug == Plug.waitingForPlug) {
				change(visit, event.getTime(), Plug.charging);
			}
			return;
		}
		if (!(event instanceof AbstractHubOperationsEvent hubEvent) || !mode.equals(hubEvent.getMode())) {
			return;
		}
		Id<DvrpVehicle> vehicleId = hubEvent.getVehicleId();
		double now = event.getTime();
		if (event instanceof CleaningQueuedEvent) {
			if (!visits.containsKey(vehicleId)) {
				// no CleaningTask: a vehicle parked at the hub on its wait task
				open(vehicleId, now, true);
			}
		} else if (event instanceof CleaningStartedEvent started) {
			Visit visit = visits.containsKey(vehicleId) ? visits.get(vehicleId) : open(vehicleId, now, true);
			accrue(visit, now);
			double t = now;
			if (started.getCleaningTypes().contains(CleaningType.interior)) {
				t += interiorDuration;
				visit.interiorEnd = t;
			}
			if (started.getCleaningTypes().contains(CleaningType.exterior)) {
				visit.exteriorEnd = t + exteriorDuration;
			}
			if (started.getCleaningTypes().contains(CleaningType.maintenance)) {
				visit.maintenanceEnd = now + maintenanceDuration;
			}
		} else if (event instanceof HubChargingPluggedEvent && visits.containsKey(vehicleId)) {
			change(visits.get(vehicleId), now, Plug.waitingForPlug);
		} else if (event instanceof HubChargingTargetReachedEvent && visits.containsKey(vehicleId)) {
			change(visits.get(vehicleId), now, Plug.charged);
		} else if (event instanceof HubChargingUnpluggedEvent && visits.containsKey(vehicleId)) {
			change(visits.get(vehicleId), now, Plug.none);
		} else if (event instanceof CleaningEndedEvent || event instanceof CleaningAbandonedEvent) {
			// a CleaningTask visit closes with its task end, a parked visit here
			Visit visit = visits.get(vehicleId);
			if (visit != null && visit.parked) {
				close(vehicleId, now);
			}
		}
	}

	private Visit open(Id<DvrpVehicle> vehicleId, double now, boolean parked) {
		Visit visit = new Visit();
		visit.parked = parked;
		visit.since = now;
		visits.put(vehicleId, visit);
		return visit;
	}

	private void change(Visit visit, double now, Plug plug) {
		accrue(visit, now);
		visit.plug = plug;
	}

	private void close(Id<DvrpVehicle> vehicleId, double now) {
		Visit visit = visits.remove(vehicleId);
		if (visit != null) {
			accrue(visit, now);
		}
	}

	/** Adds the vehicle's state from {@code visit.since} to {@code until}, split at the cleaning type boundaries. */
	private void accrue(Visit visit, double until) {
		List<Double> cuts = new ArrayList<>();
		cuts.add(visit.since);
		for (double boundary : new double[]{visit.interiorEnd, visit.exteriorEnd, visit.maintenanceEnd}) {
			if (!Double.isNaN(boundary) && boundary > visit.since && boundary < until) {
				cuts.add(boundary);
			}
		}
		cuts.add(until);
		for (int i = 0; i + 1 < cuts.size(); i++) {
			accrueInterval(visit, cuts.get(i), cuts.get(i + 1));
		}
		visit.since = Math.max(visit.since, until);
	}

	/** Adds one interval in which the activities do not change, each active one with an equal share. */
	private void accrueInterval(Visit visit, double from, double until) {
		if (until <= from) {
			return;
		}
		double mid = (from + until) / 2;
		List<String> active = new ArrayList<>(2);
		if (!Double.isNaN(visit.interiorEnd) && mid < visit.interiorEnd) {
			active.add(INTERIOR);
		} else if (!Double.isNaN(visit.exteriorEnd) && mid < visit.exteriorEnd) {
			active.add(EXTERIOR);
		} else if (!Double.isNaN(visit.maintenanceEnd) && mid < visit.maintenanceEnd) {
			active.add(MAINTENANCE);
		}
		switch (visit.plug) {
			case waitingForPlug -> active.add(WAIT_FOR_PLUG);
			case charging -> active.add(CHARGING);
			// charged and still on the plug: only waiting, cleaning or for the worker to unplug it
			default -> {
			}
		}
		if (active.isEmpty()) {
			active.add(WAIT_FOR_WORKER);
		}
		double share = 1. / active.size();
		for (String column : active) {
			add(profiles.get(column), from, until, share);
		}
		if (visit.parked) {
			add(parkedProfile, from, until, 1.);
		}
	}

	private void add(double[] profile, double from, double until, double share) {
		double interval = timeDiscretizer.getTimeInterval();
		int last = profile.length - 1;
		int fromIdx = Math.min(timeDiscretizer.getIdx(Math.max(0, from)), last);
		int toIdx = Math.min(timeDiscretizer.getIdx(until), last);
		for (int i = fromIdx; i <= toIdx; i++) {
			double binStart = i * interval;
			double overlap = Math.min(until, binStart + interval) - Math.max(from, binStart);
			if (overlap > 0) {
				profile[i] += share * overlap / interval;
			}
		}
	}

	/** Closes the visits still open at the end of the iteration. */
	public void finish(double endTime) {
		visits.values().forEach(visit -> accrue(visit, endTime));
		visits.clear();
	}

	/** The profile per column, in vehicles per time bin. */
	public Map<String, double[]> getProfiles() {
		return profiles;
	}

	/** Vehicles per time bin on a hub visit while parked out of service, already counted in WAIT_FOR_SHIFT. */
	public double[] getParkedProfile() {
		return parkedProfile;
	}

	public TimeDiscretizer getTimeDiscretizer() {
		return timeDiscretizer;
	}

	@Override
	public void reset(int iteration) {
		profiles = new LinkedHashMap<>();
		COLUMNS.forEach(column -> profiles.put(column, new double[timeDiscretizer.getIntervalCount()]));
		parkedProfile = new double[timeDiscretizer.getIntervalCount()];
		visits = new HashMap<>();
	}
}
