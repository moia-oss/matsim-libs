package org.matsim.contrib.drt.extension.alonso_mora.algorithm.function;

import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.drt.extension.alonso_mora.algorithm.AlonsoMoraRequest;

import java.util.Map;

public interface RouteTrackerFactory {

    RouteTracker createRouteTracker(Map<String, Integer> initialOccupancies, double stopDuration,
                                                                   double diversionTime, Link link);

    RouteTracker createRouteTracker(Map<String, Integer> initialOccupancies, double stopDuration,
                                                                   double diversionTime, Link link, Map<AlonsoMoraRequest, Double> requiredPickupTimes,
                                                                   Map<AlonsoMoraRequest, Double> requiredDropoffTimes);
}
