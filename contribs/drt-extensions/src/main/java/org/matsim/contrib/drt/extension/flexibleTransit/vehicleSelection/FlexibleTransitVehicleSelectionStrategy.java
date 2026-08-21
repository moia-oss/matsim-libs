package org.matsim.contrib.drt.extension.flexibleTransit.vehicleSelection;

import org.matsim.api.core.v01.Id;
import org.matsim.contrib.drt.extension.flexibleTransit.LineServiceManager;
import org.matsim.contrib.drt.optimizer.VehicleEntry;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;

import java.util.Map;
import java.util.Optional;

public interface FlexibleTransitVehicleSelectionStrategy {
    Optional<LineServiceInsertion> selectVehicle(double now, Map<Id<DvrpVehicle>, VehicleEntry> vehicleEntries, LineServiceManager.LineServiceChain schedulableService);

    record LineServiceInsertion(
            VehicleEntry vehicleEntry,
            double cost,
            int insertionIndex) {
    }
}
