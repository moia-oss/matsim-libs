package org.matsim.contrib.drt.extension.alonso_mora.algorithm.function;

import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.drt.extension.alonso_mora.algorithm.AlonsoMoraRequest;
import org.matsim.contrib.drt.extension.alonso_mora.travel_time.TravelTimeEstimator;

import java.util.Map;
import java.util.Optional;

public class DefaultRouteTrackerFactory implements RouteTrackerFactory {

    private final TravelTimeEstimator travelTimeEstimator;

    public DefaultRouteTrackerFactory(TravelTimeEstimator travelTimeEstimator) {
        this.travelTimeEstimator = travelTimeEstimator;
    }

    @Override
    public RouteTracker createRouteTracker(Map<String, Integer> initialOccupancies, double stopDuration,
                                                                          double diversionTime, Link link) {
        return new DefaultRouteTracker(travelTimeEstimator, stopDuration, initialOccupancies,
                diversionTime, Optional.ofNullable(link));
    }

    @Override
    public RouteTracker createRouteTracker(Map<String, Integer> initialOccupancies, double stopDuration,
                                                                          double diversionTime, Link link,
                                                                          Map<AlonsoMoraRequest, Double> requiredPickupTimes,
                                                                          Map<AlonsoMoraRequest, Double> requiredDropoffTimes) {
        return new DefaultRouteTracker(travelTimeEstimator, stopDuration, initialOccupancies,
                diversionTime, Optional.ofNullable(link), requiredPickupTimes, requiredDropoffTimes);
    }
}
