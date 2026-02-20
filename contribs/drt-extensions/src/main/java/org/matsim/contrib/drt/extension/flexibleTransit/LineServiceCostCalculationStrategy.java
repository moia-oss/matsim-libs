package org.matsim.contrib.drt.extension.flexibleTransit;

import org.matsim.contrib.drt.optimizer.StopWaypoint;
import org.matsim.contrib.drt.optimizer.VehicleEntry;
import org.matsim.contrib.drt.optimizer.insertion.CostCalculationStrategy;
import org.matsim.contrib.drt.optimizer.insertion.InsertionCostCalculator;
import org.matsim.contrib.drt.optimizer.insertion.InsertionDetourTimeCalculator;
import org.matsim.contrib.drt.optimizer.insertion.InsertionGenerator;
import org.matsim.contrib.drt.passenger.AcceptedDrtRequest;
import org.matsim.contrib.drt.passenger.DrtRequest;
import org.matsim.contrib.drt.stops.PassengerStopDurationProvider;

public class LineServiceCostCalculationStrategy implements CostCalculationStrategy {

    private final PassengerStopDurationProvider stopDurationProvider;
    private final boolean enforceMaxWait;

    public LineServiceCostCalculationStrategy(PassengerStopDurationProvider stopDurationProvider, boolean enforceMaxWait) {
        this.stopDurationProvider = stopDurationProvider;
        this.enforceMaxWait = enforceMaxWait;
    }

    @Override
    public double calcCost(DrtRequest request, InsertionGenerator.Insertion insertion,
                           InsertionDetourTimeCalculator.DetourTimeInfo detourTimeInfo) {

        double cost = 0;

        // initial cost for current request (departure time from pickup of current request)
        cost += detourTimeInfo.pickupDetourInfo.requestPickupTime - request.getEarliestStartTime();
        cost += detourTimeInfo.dropoffDetourInfo.requestDropoffTime - request.getEarliestStartTime();


        if (detourTimeInfo.pickupDetourInfo.requestPickupTime > request.getLatestStartTime() && enforceMaxWait) {
            //no extra time is lost => do not check if the current slack time is long enough (can be even negative)
            return InsertionCostCalculator.INFEASIBLE_SOLUTION_COST;
        }

        cost += detourTimeInfo.getTotalTimeLoss();


        int pickupIdx = insertion.pickup.index;
        int dropoffIdx = insertion.dropoff.index;

        VehicleEntry vehicleEntry = insertion.vehicleEntry;

        // relative cost change of existing requests
        for (int s = pickupIdx; s < vehicleEntry.stops.size(); s++) {
            StopWaypoint stop = vehicleEntry.stops.get(s);
            for (AcceptedDrtRequest pus : stop.getTask().getPickupRequests().values()) {
                double stopDuration = stopDurationProvider.calcPickupDuration(vehicleEntry.vehicle, pus.getRequest());
                double currentLateness = (stop.getArrivalTime() + stopDuration) - pus.getEarliestStartTime();
                double currentTardiness = Math.max(0, currentLateness);
                double timeLoss = s < dropoffIdx ? detourTimeInfo.pickupDetourInfo.pickupTimeLoss : detourTimeInfo.getTotalTimeLoss();
                double afterInsertionTardiness = Math.max(0, currentLateness + timeLoss);
                cost += (afterInsertionTardiness - currentTardiness);
            }
        }

        return cost;
    }
}

