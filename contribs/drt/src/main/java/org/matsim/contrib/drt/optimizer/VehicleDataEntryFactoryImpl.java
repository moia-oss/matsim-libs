/* *********************************************************************** *
 * project: org.matsim.*
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2018 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package org.matsim.contrib.drt.optimizer;

import com.google.common.collect.ImmutableList;
import org.matsim.contrib.drt.passenger.AcceptedDrtRequest;
import org.matsim.contrib.drt.schedule.DrtCapacityChangeTask;
import org.matsim.contrib.drt.schedule.DrtStopTask;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.load.DvrpLoad;
import org.matsim.contrib.dvrp.load.DvrpLoadType;
import org.matsim.contrib.dvrp.schedule.*;
import org.matsim.contrib.dvrp.schedule.Schedule.ScheduleStatus;
import org.matsim.contrib.dvrp.tracker.OnlineDriveTaskTracker;
import org.matsim.contrib.dvrp.util.LinkTimePair;

import java.util.ArrayList;
import java.util.List;

import static org.matsim.contrib.drt.schedule.DrtTaskBaseType.*;

/**
 * @author michalm
 */
public class VehicleDataEntryFactoryImpl implements VehicleEntry.EntryFactory {

	private final DvrpLoadType loadType;
	private final StopWaypointFactory stopWaypointFactory;

	public VehicleDataEntryFactoryImpl(DvrpLoadType loadType, StopWaypointFactory stopWaypointFactory) {
		this.loadType = loadType;
        this.stopWaypointFactory = stopWaypointFactory;
    }

	public VehicleEntry create(DvrpVehicle vehicle, double currentTime) {
		Schedule schedule = vehicle.getSchedule();
		final LinkTimePair start;
		final Task startTask;
		int nextTaskIdx;
		if (schedule.getStatus() == ScheduleStatus.STARTED) {
			startTask = schedule.getCurrentTask();
			start = switch (getBaseTypeOrElseThrow(startTask)) {
				case DRIVE -> {
					var driveTask = (DriveTask)startTask;
					var diversionPoint = ((OnlineDriveTaskTracker)driveTask.getTaskTracker()).getDiversionPoint();
					yield diversionPoint != null ? diversionPoint : //diversion possible
							new LinkTimePair(driveTask.getPath().getToLink(),
									driveTask.getEndTime());// too late for diversion
				}
				case STOP -> new LinkTimePair(((DrtStopTask)startTask).getLink(), startTask.getEndTime());
				case STAY -> new LinkTimePair(((StayTask)startTask).getLink(), currentTime);
			};

			nextTaskIdx = startTask.getTaskIdx() + 1;
		} else { // PLANNED
			start = new LinkTimePair(vehicle.getStartLink(), vehicle.getServiceBeginTime());
			startTask = null;
			nextTaskIdx = 0;
		}

		List<? extends Task> tasks = schedule.getTasks();
		List<DrtStopTask> stopTasks = new ArrayList<>();

		// find stop tasks and note down stay time before each task
		double accumulatedStayTime = 0.0;
		if (startTask != null && STAY.isBaseTypeOf(startTask)) {
			accumulatedStayTime = Math.max(0.0, startTask.getEndTime() - currentTime);
		}

		List<Double> precedingStayTimes = new ArrayList<>();

		for (Task task : tasks.subList(nextTaskIdx, tasks.size())) {
			if (STAY.isBaseTypeOf(task)) {
				accumulatedStayTime += task.getEndTime() - task.getBeginTime();
			} else if (STOP.isBaseTypeOf(task)) {
				stopTasks.add((DrtStopTask)task);
				precedingStayTimes.add(accumulatedStayTime);
				accumulatedStayTime = 0.0;
			}
		}

		StopWaypoint[] stops = new StopWaypoint[stopTasks.size()];
		DvrpLoad outgoingOccupancy = loadType.getEmptyLoad();

		for (int i = stops.length - 1; i >= 0; i--) {
				StopWaypoint s = stops[i] = stopWaypointFactory.createStopWaypoint(stopTasks.get(i), outgoingOccupancy, vehicle);
				outgoingOccupancy = s.getOutgoingOccupancy().subtract(s.getOccupancyChange());
		}

		StopWaypoint startStop = null;
		if(startTask != null && STOP.isBaseTypeOf(startTask)) {
			if(startTask instanceof DrtCapacityChangeTask capacityChangeTask) {
				startStop = stopWaypointFactory.createStopWaypoint(capacityChangeTask, loadType.getEmptyLoad(), vehicle);
			} else {
				startStop = stopWaypointFactory.createStopWaypoint((DrtStopTask) startTask, outgoingOccupancy, vehicle);
			}
		}

		var slackTimes = computeSlackTimes(vehicle, currentTime, stops, startStop, precedingStayTimes);

		return new VehicleEntry(vehicle, new Waypoint.Start(startTask, start.link, start.time, outgoingOccupancy),
				ImmutableList.copyOf(stops), slackTimes, precedingStayTimes, currentTime);
	}

	static double[] computeSlackTimes(DvrpVehicle vehicle, double now, StopWaypoint[] stops, StopWaypoint start,
									  List<Double> precedingStayTimes) {
		double[] slackTimes = new double[stops.length + 2];

		//vehicle
		double slackTime = calcVehicleSlackTime(vehicle, now);
		slackTimes[stops.length + 1] = slackTime;

		List<AcceptedDrtRequest> onboard = new ArrayList<>();

		//stops
		for (int i = stops.length - 1; i >= 0; i--) {

			StopWaypoint stop = stops[i];

			onboard.addAll(stop.getTask().getDropoffRequests().values());

			// Calculate base slacks without compression buffer
			double arrivalSlack = stop.getLatestArrivalTime() - stop.getTask().getBeginTime();
			double departureSlackBase = stop.getLatestDepartureTime() - stop.getTask().getEndTime();
			double compressionSlack = stop.getDurationCompressionSlack();

			// Calculate hard constraints (without buffers)
			double localConstraint = Math.min(arrivalSlack, departureSlackBase);

			// Apply ride duration constraints
			for (AcceptedDrtRequest req : onboard) {
				double plannedPickupTime = req.getRequestTiming().getPlannedPickupTime().orElseThrow(()
						-> new IllegalStateException("Accepted request should have a (planned) pickup time at this point."));
				double plannedDropoffTime = req.getRequestTiming().getPlannedDropoffTime().orElseThrow(()
						-> new IllegalStateException("Accepted request should have a (planned) dropoff time at this point."));
				double currentRideDuration = plannedDropoffTime - plannedPickupTime;
				double currentRideSlack = Math.max(0, req.getMaxRideDuration() - currentRideDuration);
				localConstraint = Math.min(localConstraint, currentRideSlack);
			}

			// Combine with downstream constraint
			slackTime = Math.min(localConstraint, slackTime);

			// Add compression buffer - limited by arrival constraint to prevent violating hard arrival limits
			double effectiveCompression = Math.min(compressionSlack, arrivalSlack);
			slackTime += effectiveCompression;

			// Add stay time buffer
			slackTime += precedingStayTimes.get(i);
			slackTimes[i + 1] = slackTime;

			onboard.removeAll(stop.getTask().getPickupRequests().values());
		}

		// start
		slackTimes[0] = start == null ? slackTime :
			Math.min(start.getLatestDepartureTime() - start.getTask().getEndTime(), slackTime);

		return slackTimes;
	}

	static double calcVehicleSlackTime(DvrpVehicle vehicle, double now) {
		var lastTask = Schedules.getLastTask(vehicle.getSchedule());
		//if the last task is started, take 'now', otherwise take the planned begin time
		double availableFromTime = Math.max(lastTask.getBeginTime(), now);
		//for an already delayed vehicle, assume slack is 0 (instead of a negative number)
		return Math.max(0, vehicle.getServiceEndTime() - availableFromTime);
	}
}
