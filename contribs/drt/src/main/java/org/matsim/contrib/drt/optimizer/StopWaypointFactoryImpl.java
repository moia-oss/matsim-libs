package org.matsim.contrib.drt.optimizer;

import com.google.common.base.Verify;
import org.matsim.contrib.drt.passenger.AcceptedDrtRequest;
import org.matsim.contrib.drt.schedule.DrtCapacityChangeTask;
import org.matsim.contrib.drt.schedule.DrtStopTask;
import org.matsim.contrib.drt.stops.StopTimeCalculator;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.load.DvrpLoad;
import org.matsim.contrib.dvrp.load.DvrpLoadType;

import java.util.stream.DoubleStream;

/**
 * @author nkuehnel / MOIA
 */
public class StopWaypointFactoryImpl implements StopWaypointFactory {

    private final DvrpLoadType loadType;
    private final boolean scheduleWaitBeforeDrive;
    private final StopTimeCalculator stopTimeCalculator;

    public StopWaypointFactoryImpl(DvrpLoadType loadType, boolean scheduleWaitBeforeDrive, StopTimeCalculator stopTimeCalculator) {
        this.loadType = loadType;
        this.scheduleWaitBeforeDrive = scheduleWaitBeforeDrive;
        this.stopTimeCalculator = stopTimeCalculator;
    }

    @Override
    public StopWaypoint createStopWaypoint(DrtStopTask task, DvrpLoad outgoingOccupancy, DvrpVehicle vehicle) {

        double earliestArrivalTime = calcEarliestArrivalTime(task);
        double earliestDepartureTime = calcEarliestDepartureTime(task, vehicle);
        double latestArrivalTime = calcLatestArrivalTime(task);
        double latestDepartureTime = calcLatestDepartureTime(task);
        double compression = stopTimeCalculator.calculateArrivalSlackCompression(vehicle, task);
        //  double compression = calculateCompression(task);

        if(task instanceof DrtCapacityChangeTask capacityChangeTask) {
            Verify.verify(outgoingOccupancy.isEmpty(), "occupancy SHOULD be empty at this point.");
            return new StopWaypointImpl(capacityChangeTask, loadType);
        } else {
            return new StopWaypointImpl(task, outgoingOccupancy, loadType, scheduleWaitBeforeDrive,
                    latestArrivalTime, earliestArrivalTime, latestDepartureTime, earliestDepartureTime, compression);
        }
    }

    private double calcLatestArrivalTime(DrtStopTask task) {
        // Pickup constraint: must arrive early enough to start boarding by latestStartTime
        double pickupConstraint = task.getPickupRequests().values()
                .stream()
                .mapToDouble(request -> request.getLatestStartTime() - request.getPickupDuration())
                .min()
                .orElse(Double.MAX_VALUE);

        // Dropoff constraint: must arrive early enough to complete dropoff
        double dropoffConstraint = task.getDropoffRequests().values()
                .stream()
                .mapToDouble(request -> request.getLatestArrivalTime() - request.getDropoffDuration())
                .min()
                .orElse(Double.MAX_VALUE);


        double temp = Math.max(Math.min(pickupConstraint, dropoffConstraint), task.getBeginTime());

        return temp;
       //7 return Math.max(Math.min(pickupConstraint, dropoffConstraint), task.getBeginTime());
    }

    private double calcLatestDepartureTime(DrtStopTask task) {
        double pickupConstraint =  getMaxTimeConstraint(
                task.getPickupRequests().values()
                        .stream()
                        .mapToDouble(AcceptedDrtRequest::getLatestStartTime),
                task.getEndTime()
        );

        return pickupConstraint;
    }


    public double calcEarliestArrivalTime(DrtStopTask task) {

        if(!task.getDropoffRequests().isEmpty()) {
            // we have dropoff requests, so we can arrive as early as possible
            return 0;
        }

        return task.getPickupRequests().values()
                .stream()
                .mapToDouble(AcceptedDrtRequest::getEarliestStartTime)
                .min()
                .orElse(0);
    }


    private double calcEarliestDepartureTime(DrtStopTask task, DvrpVehicle vehicle) {
        // Delegate to StopTimeCalculator to ensure consistency with the configured parallelism model
        // This automatically handles parallel vs sequential boarding, prebooking constraints, etc.
        return stopTimeCalculator.shiftEndTime(vehicle, task, task.getBeginTime());
    }

    private double getMaxTimeConstraint(DoubleStream latestAllowedTimes, double scheduledTime) {
        //XXX if task is already delayed beyond one or more of latestTimes, use scheduledTime as maxTime constraint
        //thus we can still add a new request to the already scheduled stops (as no further delays are incurred)
        //but we cannot add a new stop before the delayed task
        return Math.max(latestAllowedTimes.min().orElse(Double.MAX_VALUE), scheduledTime);
    }


}
