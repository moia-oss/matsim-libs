package org.matsim.contrib.drt.extension.flexibleTransit;

import org.matsim.api.core.v01.Id;
import org.matsim.contrib.drt.passenger.AcceptedDrtRequest;
import org.matsim.contrib.drt.schedule.DrtStopTask;
import org.matsim.contrib.dvrp.optimizer.Request;
import org.matsim.contrib.dvrp.schedule.DefaultStayTask;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;

import java.util.Map;

public class FixedStopTask extends DefaultStayTask implements DrtStopTask {

    private final DrtStopTask delegate;
    private final TransitRouteStop stop;
    private final LineService lineService;

    public FixedStopTask(DrtStopTask delegate, TransitRouteStop stop, LineService lineService) {
        super(delegate.getTaskType(), delegate.getBeginTime(), delegate.getEndTime(), delegate.getLink());
        this.delegate = delegate;
        this.stop = stop;
        this.lineService = lineService;
    }

    @Override
    public Map<Id<Request>, AcceptedDrtRequest> getDropoffRequests() {
        return delegate.getDropoffRequests();
    }

    @Override
    public Map<Id<Request>, AcceptedDrtRequest> getPickupRequests() {
        return delegate.getPickupRequests();
    }

    @Override
    public void addDropoffRequest(AcceptedDrtRequest acceptedDrtRequest) {
        delegate.addDropoffRequest(acceptedDrtRequest);
    }

    @Override
    public void addPickupRequest(AcceptedDrtRequest acceptedDrtRequest) {
        delegate.addPickupRequest(acceptedDrtRequest);
    }

    @Override
    public void removePickupRequest(Id<Request> id) {
        delegate.removePickupRequest(id);
    }

    @Override
    public void removeDropoffRequest(Id<Request> id) {
        delegate.removeDropoffRequest(id);
    }

    @Override
    public double calcLatestArrivalTime() {
        double latestRequestArrivalTime = getDropoffRequests().values().stream().mapToDouble(request ->
                request.getLatestArrivalTime() - request.getDropoffDuration()).min().orElse(Double.MAX_VALUE);
        double latestScheduleArrivalTime = stop.getArrivalOffset().seconds() + getRouteDepartureTime();
        return Math.max(getBeginTime(), latestScheduleArrivalTime);
    }

    @Override
    public double calcEarliestArrivalTime() {
        return this.getBeginTime();
    }

    @Override
    public double calcEarliestDepartureTime() {

        // Schedule constraint
        double minimumStopDurationEndTime = getBeginTime() + stop.getMinimumStopDuration();
        double scheduleEarliest = stop.isAwaitDepartureTime() ?
                Math.max(lineService.getDeparture().getDepartureTime() + stop.getArrivalOffset().seconds(), minimumStopDurationEndTime) :
                minimumStopDurationEndTime;

        // Passenger constraint (for prebooked pickups)
        double passengerEarliest = getPickupRequests().values().stream()
                .mapToDouble(request -> Math.max(getBeginTime(), request.getEarliestStartTime()) + request.getPickupDuration())
                .max()
                .orElse(Double.NEGATIVE_INFINITY);

        double earliestDeparture = Math.max(scheduleEarliest, passengerEarliest);

        return Math.min(earliestDeparture, calcLatestDepartureTime());
    }

    @Override
    public double calcLatestDepartureTime() {
        double latestRequestDepartureTime = getPickupRequests().values()
                .stream()
                .mapToDouble(AcceptedDrtRequest::getLatestStartTime)
                .min()
                .orElse(Double.MAX_VALUE);
        double latestScheduleDepartureTime = stop.getDepartureOffset().seconds() + getRouteDepartureTime();
        return Math.max(getEndTime(), Math.min(latestRequestDepartureTime, latestScheduleDepartureTime));
    }

    public TransitRouteStop getStop() {
        return stop;
    }

    public double getRouteDepartureTime() {
        return lineService.getDeparture().getDepartureTime();
    }

    public LineService getLineService() {
        return lineService;
    }

    @Override
    public String toString() {
        return stop.getStopFacility().getName() + " - " + delegate.toString();
    }
}
