package org.matsim.contrib.drt.extension.flexibleTransit.VehicleSelection;

import jakarta.annotation.Nullable;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.drt.extension.flexibleTransit.LineService;
import org.matsim.contrib.drt.optimizer.StopWaypoint;
import org.matsim.contrib.drt.optimizer.Waypoint;
import org.matsim.contrib.dvrp.path.VrpPathWithTravelData;
import org.matsim.contrib.dvrp.path.VrpPaths;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelTime;

import java.util.List;

public class LineServiceInsertionTimeCalculator {

    private final LeastCostPathCalculator router;
    private final TravelTime travelTime;
    private final Network network;

    public LineServiceInsertionTimeCalculator(
            LeastCostPathCalculator router,
            TravelTime travelTime, Network network) {
        this.router = router;
        this.travelTime = travelTime;
        this.network = network;
    }

    public LineServiceInsertionTimeInfo calculateChainFromStart(
            Waypoint.Start start,
            @Nullable StopWaypoint nextStop,
            List<LineService> services) {

        return calculateChain(
                start.getLink(),
                start.time,
                nextStop,
                services);
    }

    public LineServiceInsertionTimeInfo calculateChainAfterStop(
            StopWaypoint previousStop,
            @Nullable StopWaypoint nextStop,
            List<LineService> services) {

        return calculateChain(
                previousStop.getLink(),
                previousStop.getDepartureTime(),
                nextStop,
                services);
    }

    private LineServiceInsertionTimeInfo calculateChain(
            Link fromLink,
            double fromTime,
            @Nullable StopWaypoint nextStop,
            List<LineService> services) {

        double totalAccessTime = 0.0;
        double totalWaitTime = 0.0;
        double totalServiceDuration = 0.0;

        Link currentLink = fromLink;
        double currentTime = fromTime;

        for (LineService service : services) {

            double serviceStartTime =
                    service.getDeparture().getDepartureTime();

            double serviceDuration =
                    getServiceDuration(service);

            double serviceEndTime =
                    serviceStartTime + serviceDuration;

            Link serviceStartLink =
                    network.getLinks().get(
                            service.getRoute().getStops().getFirst()
                                    .getStopFacility().getLinkId());

            Link serviceEndLink =
                    network.getLinks().get(
                            service.getRoute().getStops().getLast()
                                    .getStopFacility().getLinkId());

            VrpPathWithTravelData accessPath =
                    VrpPaths.calcAndCreatePath(
                            currentLink,
                            serviceStartLink,
                            currentTime,
                            router,
                            travelTime);

            double accessTime = accessPath.getTravelTime();

            if (currentTime + accessTime > serviceStartTime) {
                return LineServiceInsertionTimeInfo.infeasible();
            }

            totalAccessTime += accessTime;
            totalWaitTime += serviceStartTime - currentTime - accessTime;
            totalServiceDuration += serviceDuration;

            currentLink = serviceEndLink;
            currentTime = serviceEndTime;
        }

        double returnTime = 0.0;
        double originalTravelTime = 0.0;

        if (nextStop != null) {

            VrpPathWithTravelData returnPath =
                    VrpPaths.calcAndCreatePath(
                            currentLink,
                            nextStop.getLink(),
                            currentTime,
                            router,
                            travelTime);

            returnTime = returnPath.getTravelTime();

            VrpPathWithTravelData originalPath =
                    VrpPaths.calcAndCreatePath(
                            fromLink,
                            nextStop.getLink(),
                            fromTime,
                            router,
                            travelTime);

            originalTravelTime = originalPath.getTravelTime();
        }

        return new LineServiceInsertionTimeInfo(
                totalAccessTime,
                totalWaitTime,
                returnTime,
                originalTravelTime,
                totalServiceDuration);
    }



    public LineServiceInsertionTimeInfo calculateAfterStop(
            StopWaypoint previousStop,
            @Nullable StopWaypoint nextStop,
            double serviceStartTime,
            double serviceEndTime,
            Link serviceStartLink,
            Link serviceEndLink) {

        double departureTime = previousStop.getDepartureTime();

        VrpPathWithTravelData accessPath =
                VrpPaths.calcAndCreatePath(
                        previousStop.getLink(),
                        serviceStartLink,
                        departureTime,
                        router,
                        travelTime);

        double accessTime = accessPath.getTravelTime();

        if (departureTime + accessTime > serviceStartTime) {
            return LineServiceInsertionTimeInfo.infeasible();
        }

        double waitTime =
                serviceStartTime - departureTime - accessTime;

        double returnTime = 0.0;
        double originalTravelTime = 0.0;

        if (nextStop != null) {

            VrpPathWithTravelData returnPath =
                    VrpPaths.calcAndCreatePath(
                            serviceEndLink,
                            nextStop.getLink(),
                            serviceEndTime,
                            router,
                            travelTime);

            returnTime = returnPath.getTravelTime();

            VrpPathWithTravelData originalPath =
                    VrpPaths.calcAndCreatePath(
                            previousStop.getLink(),
                            nextStop.getLink(),
                            departureTime,
                            router,
                            travelTime);

            originalTravelTime = originalPath.getTravelTime();
        }

        return new LineServiceInsertionTimeInfo(
                accessTime,
                waitTime,
                returnTime,
                originalTravelTime,
                serviceEndTime - serviceStartTime);
    }

    public LineServiceInsertionTimeInfo calculateFromStart(
            Waypoint.Start start,
            @Nullable StopWaypoint nextStop,
            double serviceStartTime,
            double serviceEndTime,
            Link serviceStartLink,
            Link serviceEndLink) {

        double departureTime = start.time;

        VrpPathWithTravelData accessPath =
                VrpPaths.calcAndCreatePath(
                        start.getLink(),
                        serviceStartLink,
                        departureTime,
                        router,
                        travelTime);

        double accessTime = accessPath.getTravelTime();

        if (departureTime + accessTime > serviceStartTime) {
            return LineServiceInsertionTimeInfo.infeasible();
        }

        double waitTime = serviceStartTime - departureTime - accessTime;

        double returnTime = 0.0;
        double originalTravelTime = 0.0;

        if (nextStop != null) {

            VrpPathWithTravelData returnPath =
                    VrpPaths.calcAndCreatePath(
                            serviceEndLink,
                            nextStop.getLink(),
                            serviceEndTime,
                            router,
                            travelTime);

            returnTime = returnPath.getTravelTime();

            VrpPathWithTravelData originalPath =
                    VrpPaths.calcAndCreatePath(
                            start.getLink(),
                            nextStop.getLink(),
                            departureTime,
                            router,
                            travelTime);

            originalTravelTime = originalPath.getTravelTime();
        }

        return new LineServiceInsertionTimeInfo(
                accessTime,
                waitTime,
                returnTime,
                originalTravelTime,
                serviceEndTime - serviceStartTime);
    }

    private double getServiceDuration(LineService service) {
        double lastArrivalOffset =
                service.getRoute().getStops().getLast()
                        .getArrivalOffset().seconds();

        NetworkRoute route = service.getRoute().getRoute();
        return route == null ? lastArrivalOffset : route.getTravelTime().orElse(lastArrivalOffset);
    }

    public record LineServiceInsertionTimeInfo(
            double accessTime,
            double waitTime,
            double returnTime,
            double originalTravelTime,
            double serviceDuration) {

        public static LineServiceInsertionTimeInfo infeasible() {
            return new LineServiceInsertionTimeInfo(
                    Double.POSITIVE_INFINITY,
                    Double.POSITIVE_INFINITY,
                    Double.POSITIVE_INFINITY,
                    0,
                    0);
        }

        public boolean isFeasible() {
            return Double.isFinite(accessTime);
        }

        /**
         * Additional time imposed on the existing vehicle schedule.
         */
        public double getAdditionalTime() {
            return accessTime
                    + serviceDuration
                    + waitTime
                    + returnTime
                    - originalTravelTime;
        }
    }
}
