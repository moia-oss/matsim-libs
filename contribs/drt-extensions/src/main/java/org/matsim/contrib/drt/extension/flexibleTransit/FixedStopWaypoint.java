package org.matsim.contrib.drt.extension.flexibleTransit;

import jakarta.annotation.Nullable;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.drt.optimizer.StopWaypoint;
import org.matsim.contrib.drt.passenger.AcceptedDrtRequest;
import org.matsim.contrib.drt.schedule.DrtStopTask;
import org.matsim.contrib.dvrp.load.DvrpLoad;
import org.matsim.contrib.dvrp.load.DvrpLoadType;

import java.util.Optional;

/**
 * @author nkuehnel / MOIA
 */
public class FixedStopWaypoint implements StopWaypoint {

    private final FixedStopTask task;
    private final double earliestArrivalTime;
    private final double latestArrivalTime;// relating to max passenger drive time (for dropoff requests)
    private final double earliestDepartureTime;
    private final double latestDepartureTime;// relating to passenger max wait time (for pickup requests)
    private final DvrpLoad outgoingOccupancy;
    private final DvrpLoad emptyLoad;
    private final double compressionSlack;
    private final boolean scheduleWaitBeforeDrive;

    @Nullable
    private final DvrpLoad changedCapacity;
    private final double arrivalSlack;
    private final double departureSlackBase;

    public FixedStopWaypoint(FixedStopTask task, DvrpLoad outgoingOccupancy, DvrpLoadType loadType,
                             double earliestArrivalTime, double compressionSlack) {
        this.task = task;
        this.outgoingOccupancy = outgoingOccupancy;
        this.emptyLoad = loadType.getEmptyLoad();
        this.compressionSlack = compressionSlack;
        this.scheduleWaitBeforeDrive = false;
        this.changedCapacity = null;
        this.earliestArrivalTime = earliestArrivalTime;
        this.latestArrivalTime = task.calcLatestArrivalTime();
        this.earliestDepartureTime = task.calcEarliestDepartureTime();
        this.latestDepartureTime = task.calcLatestDepartureTime();

        arrivalSlack = getLatestArrivalTime() - task.getBeginTime();
        departureSlackBase = getLatestDepartureTime() - task.getEndTime();
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

    public double getEarliestDepartureTime() {
        return earliestDepartureTime;
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

    //@Override
    //public double getDurationCompressionSlack() {
    //    return compressionSlack;
    //}


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
    public String toString() {
        return task.getStop().getStopFacility().getName() + "[arrival slack = " +arrivalSlack + " | departure slack = " +departureSlackBase + "]";
    }
}
