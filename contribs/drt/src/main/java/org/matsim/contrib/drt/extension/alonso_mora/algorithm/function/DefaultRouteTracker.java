package org.matsim.contrib.drt.extension.alonso_mora.algorithm.function;

import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.drt.extension.alonso_mora.algorithm.AlonsoMoraRequest;
import org.matsim.contrib.drt.extension.alonso_mora.algorithm.AlonsoMoraStop;
import org.matsim.contrib.drt.extension.alonso_mora.algorithm.AlonsoMoraVehicle;
import org.matsim.contrib.drt.extension.alonso_mora.travel_time.TravelTimeEstimator;
import org.matsim.contrib.drt.schedule.DrtDriveTask;
import org.matsim.contrib.dvrp.schedule.DriveTask;

import java.util.*;

public class DefaultRouteTracker implements RouteTracker {
	private final TravelTimeEstimator estimator;
	private final double stopDuration;

	private final double initialDepartureTime;
	private final Optional<Link> initialLink;

	private final LinkedList<Double> departureTimes = new LinkedList<>();
	private final LinkedList<Double> arrivalTimes = new LinkedList<>();

	private final OccupancyInfo occupancyInfo = new OccupancyInfo();

	private final Map<AlonsoMoraRequest, Double> requiredPickupTimes;
	private final Map<AlonsoMoraRequest, Double> requiredDropoffTimes;

	public DefaultRouteTracker(TravelTimeEstimator estimator, double stopDuration, Map<String, Integer> initialOccupancies,
							   double initialDepartureTime, Optional<Link> initialLink) {
		this.estimator = estimator;
		this.stopDuration = stopDuration;
		this.initialDepartureTime = initialDepartureTime;
		initialOccupancies.forEach(occupancyInfo::setInitialOccupancy);
		this.initialLink = initialLink;

		this.requiredPickupTimes = Collections.emptyMap();
		this.requiredDropoffTimes = Collections.emptyMap();
	}

	public DefaultRouteTracker(TravelTimeEstimator estimator, double stopDuration, Map<String, Integer> initialOccupancies,
							   double initialDepartureTime, Optional<Link> initialLink, Map<AlonsoMoraRequest, Double> requiredPickupTimes,
							   Map<AlonsoMoraRequest, Double> requiredDropoffTimes) {
		this.estimator = estimator;
		this.stopDuration = stopDuration;
		this.initialDepartureTime = initialDepartureTime;
		initialOccupancies.forEach(occupancyInfo::setInitialOccupancy);
		this.initialLink = initialLink;

		this.requiredPickupTimes = requiredPickupTimes;
		this.requiredDropoffTimes = requiredDropoffTimes;
	}

	@Override
	public int update(List<AlonsoMoraStop> stops) {
		while (departureTimes.size() > stops.size() - 1) {
			departureTimes.removeLast();
			arrivalTimes.removeLast();
			occupancyInfo.getAllOccupancies().forEach((key, occupancies) -> occupancies.removeLast());
		}

		int partialIndex = departureTimes.size();

		for (int i = partialIndex; i < stops.size(); i++) {
			Link fromLink = null;
			double departureTime = Double.NaN;
			int occupancy = -1;

			if (i == 0) {
				fromLink = initialLink.orElseGet(stops.get(0)::getLink);
				departureTime = initialDepartureTime;
				occupancy = occupancyInfo.getInitialOccupancy(OccupancyInfo.REGULAR);
			} else {
				fromLink = stops.get(i - 1).getLink();
				departureTime = departureTimes.get(i - 1);
				occupancy = occupancyInfo.getOccupancies(OccupancyInfo.REGULAR).get(i -1);
			}

			Link toLink = stops.get(i).getLink();

			if (fromLink != toLink || i == 0) {
				double arrivalTimeThreshold = Double.POSITIVE_INFINITY;

				if (stops.get(i).getType().equals(AlonsoMoraStop.StopType.Pickup)) {
					arrivalTimeThreshold = requiredPickupTimes.getOrDefault(stops.get(i).getRequest(), Double.POSITIVE_INFINITY);
				} else {
					arrivalTimeThreshold = requiredDropoffTimes.getOrDefault(stops.get(i).getRequest(), Double.POSITIVE_INFINITY);
				}

				double arrivalTime = estimator.estimateTravelTime(fromLink, toLink, departureTime, arrivalTimeThreshold) + departureTime;

				arrivalTimes.add(arrivalTime);

				double stopDepartureTime = arrivalTime + stopDuration;

				// The following is only relevant for pre-booked requests
				double earliestDepartureTime = stops.get(i).getRequest().getEarliestPickupTime();
				stopDepartureTime = Math.max(stopDepartureTime, earliestDepartureTime);

				departureTimes.add(stopDepartureTime);
			} else {
				// We don't move, which means this will be one stop with one timing!

				arrivalTimes.add(arrivalTimes.get(i - 1));
				departureTimes.add(departureTimes.get(i - 1));
			}

			occupancyInfo.getOccupancies(OccupancyInfo.REGULAR).add(occupancy
					+ (stops.get(i).getType().equals(AlonsoMoraStop.StopType.Pickup) ? 1 : -1) * stops.get(i).getRequest().getSize());
		}

		for (int i = 0; i < stops.size(); i++) {
			/*
			 * In DRT, agents are dropped off right when the vehicle arrives and picked up
			 * after the stopDuration. The first corresponds to "arrivalTime" here and the
			 * latter to "departureTime".
			 */

			if (stops.get(i).getType().equals(AlonsoMoraStop.StopType.Pickup)) {
				stops.get(i).setTime(departureTimes.get(i));
			} else {
				stops.get(i).setTime(arrivalTimes.get(i));
			}
		}

		return partialIndex;
	}

	@Override
	public double getDepartureTime(int index) {
		return departureTimes.get(index);
	}

	@Override
	public double getArrivalTime(int index) {
		return arrivalTimes.get(index);
	}

	public int getOccupancyAfter(int index) {
		return occupancyInfo.getOccupancies(OccupancyInfo.REGULAR).get(index);
	}

	public int getOccupancyBefore(int index) {
		if (index == 0) {
			return occupancyInfo.getInitialOccupancy(OccupancyInfo.REGULAR);
		} else {
			return occupancyInfo.getOccupancies(OccupancyInfo.REGULAR).get(index - 1);
		}
	}

	private boolean isCurrentlyDriving = false;
	private boolean needsDrivingModeSwitch = false;

	/**
	 * Sets driving information for the vehicle. If the vehicle is driving it means
	 * that it can be diverted instead of departing from a stop. This has
	 * implications on the exact calculation of travel times in free-flow
	 * conditions. Likewise, if a vehicle is currently driving for relocation, the
	 * vehicle needs to be stopped to switch to a normal driving task. This also has
	 * implications on travel time.
	 */
	public void setDrivingState(AlonsoMoraVehicle vehicle) {
		isCurrentlyDriving = vehicle.getVehicle().getSchedule().getCurrentTask() instanceof DriveTask;

		if (isCurrentlyDriving) {
			DriveTask driveTask = (DriveTask) vehicle.getVehicle().getSchedule().getCurrentTask();
			needsDrivingModeSwitch = !driveTask.getTaskType().equals(DrtDriveTask.TYPE);
		}
	}
}
