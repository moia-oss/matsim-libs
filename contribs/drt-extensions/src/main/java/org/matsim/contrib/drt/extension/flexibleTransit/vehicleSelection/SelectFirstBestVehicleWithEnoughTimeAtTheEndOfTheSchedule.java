package org.matsim.contrib.drt.extension.flexibleTransit.vehicleSelection;

import org.matsim.api.core.v01.Id;
import org.matsim.contrib.drt.extension.flexibleTransit.LineServiceManager;
import org.matsim.contrib.drt.optimizer.VehicleEntry;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;

import java.util.Map;
import java.util.Optional;

public class SelectFirstBestVehicleWithEnoughTimeAtTheEndOfTheSchedule implements FlexibleTransitVehicleSelectionStrategy {

    @Override
    public Optional<LineServiceInsertion> selectVehicle(double now, Map<Id<DvrpVehicle>, VehicleEntry> vehicleEntries, LineServiceManager.LineServiceChain serviceChain) {
        LineServiceInsertion selected = null;

        double departureTime = serviceChain.service().getDeparture().getDepartureTime();
        double lastArrivalTime =  serviceChain.chainedServices().getLast().getDeparture().getDepartureTime() +
                serviceChain.chainedServices().getLast().getRoute().getStops().getLast().getArrivalOffset().seconds();

        for (VehicleEntry vehicleEntry : vehicleEntries.values()) {

            //currently we have a hardcoded 900s time frame for empty drive from latest scheduled stop to start of line service
            if (vehicleEntry.vehicle.getServiceBeginTime() <= now && (vehicleEntry.stops.isEmpty()
                    || vehicleEntry.stops.getLast().getDepartureTime() < departureTime - 900)
                    && lastArrivalTime < vehicleEntry.vehicle.getServiceEndTime()) {
                selected = new LineServiceInsertion(
                        vehicleEntry,
                        Double.NEGATIVE_INFINITY,
                        vehicleEntry.stops.size());
                break;
            }
        }
        return Optional.ofNullable(selected);
    }

}
