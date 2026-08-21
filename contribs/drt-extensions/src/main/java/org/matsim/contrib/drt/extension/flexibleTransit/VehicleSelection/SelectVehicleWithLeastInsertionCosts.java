package org.matsim.contrib.drt.extension.flexibleTransit.VehicleSelection;

import com.google.common.base.Verify;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.drt.extension.flexibleTransit.LineService;
import org.matsim.contrib.drt.extension.flexibleTransit.LineServiceManager;
import org.matsim.contrib.drt.optimizer.StopWaypoint;
import org.matsim.contrib.drt.optimizer.VehicleEntry;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.load.DvrpLoad;
import org.matsim.contrib.dvrp.schedule.CapacityChangeTask;

import java.util.*;

public class SelectVehicleWithLeastInsertionCosts implements FlexibleTransitVehicleSelectionStrategy {

    private LineServiceInsertionTimeCalculator lineServiceInsertionTimeCalculator;
    private Network network;

    private final DvrpLoad requiredLoad;

    public SelectVehicleWithLeastInsertionCosts(LineServiceInsertionTimeCalculator lineServiceInsertionTimeCalculator, Network network, DvrpLoad requiredLoad) {
        this.lineServiceInsertionTimeCalculator = lineServiceInsertionTimeCalculator;
        this.network = network;
        this.requiredLoad = requiredLoad;
    }

    private static void validateInsertionInfoAndMemorize(VehicleEntry vehicleEntry,
                                                         LineServiceInsertionTimeCalculator.LineServiceInsertionTimeInfo info,
                                                         int i,
                                                         List<LineServiceInsertion> candidates) {
        if (!info.isFeasible()) {
            return;
        }

        if (vehicleEntry.getSlackTime(i) < info.getAdditionalTime()) {
            return;
        }

        candidates.add(new LineServiceInsertion(
                vehicleEntry,
                //TODO: discuss whether we want to include waiting time (Stay before start of LineService) as costs
                info.getAdditionalTime(),
                i));
    }

    @Override
    public Optional<LineServiceInsertion> selectVehicle(double now, Map<Id<DvrpVehicle>, VehicleEntry> vehicleEntries, LineServiceManager.LineServiceChain serviceChain) {
        List<LineService> allChainedServices = serviceChain.allServices();
        Verify.verify(!allChainedServices.isEmpty());

        double lastArrivalTimeProxy =  allChainedServices.getLast().getDeparture().getDepartureTime() +
                allChainedServices.getLast().getRoute().getStops().getLast().getArrivalOffset().seconds();

        List<LineServiceInsertion> candidates = new ArrayList<>();

        for (VehicleEntry vehicleEntry : vehicleEntries.values()) {

            //check vehicle service times
            if (vehicleEntry.vehicle.getServiceBeginTime() > now ||
                    lastArrivalTimeProxy > vehicleEntry.vehicle.getServiceEndTime()) {
                continue;
            }

            DvrpLoad capacity = vehicleEntry.vehicle.getCapacity();

            if (vehicleEntry.start.task.isPresent()
                    && vehicleEntry.start.task.get() instanceof CapacityChangeTask capacityChangeTask) {
                capacity = capacityChangeTask.getChangedCapacity();
            }

            if (vehicleEntry.stops.isEmpty()) {

                if (requiredLoad.fitsIn(capacity)) {

                    var info =
                            lineServiceInsertionTimeCalculator.calculateChainFromStart(
                                    vehicleEntry.start,
                                    null,
                                    allChainedServices);

                    validateInsertionInfoAndMemorize(
                            vehicleEntry,
                            info,
                            0,
                            candidates);
                }

            } else {

                if (requiredLoad.fitsIn(capacity)) {
                    // check insertion before first stop
                    var info =
                            lineServiceInsertionTimeCalculator.calculateChainFromStart(
                                    vehicleEntry.start,
                                    vehicleEntry.stops.getFirst(),
                                    allChainedServices);

                    validateInsertionInfoAndMemorize(vehicleEntry, info, 0, candidates);
                }


                // after existing stops
                for (int i = 1; i <= vehicleEntry.stops.size(); i++) {

                    Optional<DvrpLoad> changedCapacity =
                            vehicleEntry.stops.get(i -1).getChangedCapacity();

                    if (changedCapacity.isPresent()) {
                        capacity = changedCapacity.get();
                    }

                    if (requiredLoad.fitsIn(capacity)) {
                        StopWaypoint previousStop =
                                vehicleEntry.stops.get(i - 1);

                        StopWaypoint nextStop =
                                i < vehicleEntry.stops.size()
                                        ? vehicleEntry.stops.get(i)
                                        : null;

                        var info =
                                lineServiceInsertionTimeCalculator.calculateChainAfterStop(
                                        previousStop,
                                        nextStop,
                                        allChainedServices);

                        validateInsertionInfoAndMemorize(vehicleEntry, info, i, candidates);
                    }

                }
            }
        }

        return candidates.stream()
                .min(Comparator.comparingDouble(LineServiceInsertion::cost));
    }

}
