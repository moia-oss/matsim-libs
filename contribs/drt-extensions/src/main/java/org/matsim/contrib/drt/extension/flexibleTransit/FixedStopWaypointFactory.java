package org.matsim.contrib.drt.extension.flexibleTransit;

import org.matsim.contrib.drt.optimizer.StopWaypoint;
import org.matsim.contrib.drt.optimizer.StopWaypointFactory;
import org.matsim.contrib.drt.schedule.DrtStopTask;
import org.matsim.contrib.drt.stops.StopTimeCalculator;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.load.DvrpLoad;
import org.matsim.contrib.dvrp.load.DvrpLoadType;

public class FixedStopWaypointFactory implements StopWaypointFactory {

    private final StopWaypointFactory delegate;
    private final DvrpLoadType loadType;

    private final StopTimeCalculator stopTimeCalculator;



    public FixedStopWaypointFactory(StopWaypointFactory delegate, DvrpLoadType loadType, StopTimeCalculator stopTimeCalculator) {
        this.delegate = delegate;
        this.loadType = loadType;
        this.stopTimeCalculator = stopTimeCalculator;
    }


    @Override
    public StopWaypoint createStopWaypoint(DrtStopTask drtStopTask, DvrpLoad outgoingOccupancy) {
        if(drtStopTask instanceof FixedStopTask fixedStopTask) {
         //   double compression = stopTimeCalculator.calculateArrivalSlackCompression(dvrpVehicle, drtStopTask);

            double latestWithMinimumStop = fixedStopTask.getEndTime()  - fixedStopTask.getStop().getMinimumStopDuration();
            double latestWithEarliestDeparture = Math.min(latestWithMinimumStop, fixedStopTask.calcEarliestDepartureTime());


            //double scheduleCompression = Math.max(0, latestWithEarliestDeparture - fixedStopTask.getBeginTime());
          //  scheduleCompression = Math.min(scheduleCompression, compression);
            return new FixedStopWaypoint(fixedStopTask, outgoingOccupancy, loadType, drtStopTask.getBeginTime(), 0);
        }
        return delegate.createStopWaypoint(drtStopTask, outgoingOccupancy);
    }
}
