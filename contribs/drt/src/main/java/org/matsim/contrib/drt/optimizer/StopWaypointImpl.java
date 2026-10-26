package org.matsim.contrib.drt.optimizer;

import jakarta.annotation.Nullable;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.drt.passenger.AcceptedDrtRequest;
import org.matsim.contrib.drt.schedule.DrtCapacityChangeTask;
import org.matsim.contrib.drt.schedule.DrtStopTask;
import org.matsim.contrib.dvrp.load.DvrpLoad;
import org.matsim.contrib.dvrp.load.DvrpLoadType;

import java.util.Optional;
import java.util.stream.DoubleStream;

/**
 * @author nkuehnel / MOIA
 */
public class StopWaypointImpl implements StopWaypoint {

    private final DrtStopTask task;
    private final double latestArrivalTime;// relating to max passenger drive time (for dropoff requests)
    private final double earliestArrivalTime;
    private final double latestDepartureTime;// relating to passenger max wait time (for pickup requests)
    private final double earliestDepartureTime;// earliest time vehicle can depart (all passengers served)
    private final DvrpLoad outgoingOccupancy;
    private final DvrpLoad emptyLoad;
    private final boolean scheduleWaitBeforeDrive;
    private double compression;

    @Nullable
    private final DvrpLoad changedCapacity;

    public StopWaypointImpl(DrtStopTask task, DvrpLoad outgoingOccupancy, DvrpLoadType loadType, boolean scheduleWaitBeforeDrive,
                            double latestArrivalTime, double earliestArrivalTime,
                            double latestDepartureTime, double earliestDepartureTime,
                            double compression) {
        this.task = task;
        this.outgoingOccupancy = outgoingOccupancy;
        this.emptyLoad = loadType.getEmptyLoad();
        this.scheduleWaitBeforeDrive = scheduleWaitBeforeDrive;
        this.compression = compression;
        this.changedCapacity = null;
        this.latestArrivalTime = latestArrivalTime;
        this.earliestArrivalTime = earliestArrivalTime;
        this.latestDepartureTime = latestDepartureTime;
        this.earliestDepartureTime = earliestDepartureTime;
    }

    public StopWaypointImpl(DrtStopTask task, double latestArrivalTime, double latestDepartureTime, DvrpLoad outgoingOccupancy,
                            DvrpLoadType loadType) {
        this.task = task;
        this.latestArrivalTime = latestArrivalTime;
        this.latestDepartureTime = latestDepartureTime;
        this.outgoingOccupancy = outgoingOccupancy;
        this.emptyLoad = loadType.getEmptyLoad();
        this.scheduleWaitBeforeDrive = false;
        this.changedCapacity = null;
        this.earliestArrivalTime = task.getBeginTime();
        this.earliestDepartureTime = task.calcEarliestDepartureTime();
    }

    public StopWaypointImpl(DrtCapacityChangeTask task, DvrpLoadType loadType) {
        this.task = task;
        this.latestArrivalTime = task.getBeginTime();
        this.latestDepartureTime = task.getEndTime();
        this.outgoingOccupancy = loadType.getEmptyLoad();
        this.emptyLoad = loadType.getEmptyLoad();
        this.changedCapacity = task.getChangedCapacity();
        this.scheduleWaitBeforeDrive = false;
        this.earliestArrivalTime = task.getBeginTime();
        this.earliestDepartureTime = task.getEndTime(); // No compression for capacity change tasks
    }

    @Override
    public Link getLink() {
        return task.getLink();
    }

    @Override
    public double getArrivalTime() {
        return task.getBeginTime();
    }

    @Override
    public double getDepartureTime() {
        return task.getEndTime();
    }

    @Override
    public DvrpLoad getOutgoingOccupancy() {
        return outgoingOccupancy;
    }

    @Override
    public double getLatestArrivalTime() {
        return latestArrivalTime;
    }

    @Override
    public double getLatestDepartureTime() {
        return latestDepartureTime;
    }

    @Override
    public double getEarliestArrivalTime() {
        return earliestArrivalTime;
    }

    @Override
    public DrtStopTask getTask() {
        return task;
    }

    @Override
    public DvrpLoad getOccupancyChange() {
        DvrpLoad pickedUp = task.getPickupRequests().values().stream().map(AcceptedDrtRequest::getLoad).reduce(DvrpLoad::add).orElse(emptyLoad);
        DvrpLoad droppedOff = task.getDropoffRequests().values().stream().map(AcceptedDrtRequest::getLoad).reduce(DvrpLoad::add).orElse(emptyLoad);
        return pickedUp.subtract(droppedOff);
    }

    @Override
    public Optional<DvrpLoad> getChangedCapacity() {
        return Optional.ofNullable(changedCapacity);
    }

    @Override
    public boolean scheduleWaitBeforeDrive() {
        return scheduleWaitBeforeDrive;
    }

    @Override
    public double getDurationCompressionSlack() {
        return compression;
    }

    @Override
    public String toString() {
        return "VehicleData.Stop for: " + task.toString();
    }
}
