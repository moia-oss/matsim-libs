package org.matsim.contrib.drt.extension.flexibleTransit;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.drt.optimizer.StopWaypoint;
import org.matsim.contrib.drt.optimizer.insertion.InsertionCostCalculator;
import org.matsim.contrib.drt.optimizer.insertion.InsertionDetourTimeCalculator;
import org.matsim.contrib.drt.optimizer.insertion.InsertionGenerator;
import org.matsim.contrib.drt.passenger.DrtRequest;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import java.util.HashMap;
import java.util.Map;

public class LineServiceInsertionCostCalculator implements InsertionCostCalculator {

    private final LineServiceManager lineServiceManager;
    private final InsertionCostCalculator delegate;
    private final boolean allowOnDemand;
    private final boolean mixed;
    private final Map<Id<Link>, TransitStopFacility> stopsByLink;

    public LineServiceInsertionCostCalculator(LineServiceManager lineServiceManager, InsertionCostCalculator delegate,
                                              boolean allowOnDemand, TransitSchedule transitSchedule, boolean mixed) {
        this.lineServiceManager = lineServiceManager;
        this.delegate = delegate;
        this.allowOnDemand = allowOnDemand;
        this.mixed = mixed;
        this.stopsByLink = new HashMap<>();

        for (TransitStopFacility stop : transitSchedule.getFacilities().values()) {
            stopsByLink.put(stop.getLinkId(), stop);
        }
    }


    @Override
    public double calculate(DrtRequest drtRequest, InsertionGenerator.Insertion insertion, InsertionDetourTimeCalculator.DetourTimeInfo detourTimeInfo) {
        InsertionGenerator.InsertionPoint pickup = insertion.pickup;
        InsertionGenerator.InsertionPoint dropoff = insertion.dropoff;
        // only active line service insertions
        //
        if (lineServiceManager.getActiveLineServiceVehicles().containsKey(insertion.vehicleEntry.vehicle.getId())) {
            if ((pickup.index < dropoff.index || allowOnDemand) &&
                    // stop may only be extended
                    (pickup.index > 0 || allowOnDemand)) {
                if (pickup.previousWaypoint.getLink().getId().equals(drtRequest.getFromLink().getId()) || allowOnDemand) {
                    if (dropoff.previousWaypoint.getLink().getId().equals(drtRequest.getToLink().getId()) || allowOnDemand) {

                        if (allowOnDemand) {

                            LineService lineService = lineServiceManager.getActiveLineServiceVehicles().get(insertion.vehicleEntry.vehicle.getId()).peek();

                            TransitStopFacility puStop = stopsByLink.get(pickup.newWaypoint.getLink().getId());
                            TransitStopFacility doStop = stopsByLink.get(dropoff.newWaypoint.getLink().getId());


                            boolean puFixedStop = lineService.getRoute().getStop(puStop) != null;
                            boolean doFixedStop = lineService.getRoute().getStop(doStop) != null;

                            if (puFixedStop) {
                                // is a fixed stop
                                if (!pickup.previousWaypoint.getLink().getId().equals(drtRequest.getFromLink().getId())) {
                                    return INFEASIBLE_SOLUTION_COST;
                                }
                            }

                            if (doFixedStop) {
                                // is a fixed stop
                                if (!dropoff.previousWaypoint.getLink().getId().equals(drtRequest.getToLink().getId())) {
                                    return INFEASIBLE_SOLUTION_COST;
                                }
                            }

                            if (puFixedStop && doFixedStop) {
                                return delegate.calculate(drtRequest, insertion, detourTimeInfo);

                            }

                            // wenn der naechste stop gleicer ort -> reject, da optional stop danach appended werden muss
                            if (pickup.nextWaypoint instanceof FixedStopWaypoint && pickup.nextWaypoint.getLink().equals(pickup.newWaypoint.getLink())) {
                                // rejected auch den ersten stop wenn zwei mal derselbe hintereinander kommt, vmtl ok für pickup
                                return INFEASIBLE_SOLUTION_COST;
                            }

                            // das gleiche bei dropoff (next kann null sein)
                            if (dropoff.nextWaypoint instanceof FixedStopWaypoint && dropoff.nextWaypoint.getLink().equals(dropoff.newWaypoint.getLink())) {
                                return INFEASIBLE_SOLUTION_COST;
                            }


                            FixedStopWaypoint beforePickupFixedStop = null;
                            for (int i = 0; i < pickup.index; i++) {
                                StopWaypoint stopWaypoint = insertion.vehicleEntry.stops.get(i);
                                if (stopWaypoint instanceof FixedStopWaypoint) {
                                    beforePickupFixedStop = (FixedStopWaypoint) stopWaypoint;
                                }
                            }

                            FixedStopWaypoint afterPickupFixedStop = null;
                            FixedStopWaypoint beforeDropoffFixedStop = null;
                            for (int i = pickup.index; i < dropoff.index; i++) {
                                StopWaypoint stopWaypoint = insertion.vehicleEntry.stops.get(i);
                                if (stopWaypoint instanceof FixedStopWaypoint) {
                                    if (afterPickupFixedStop == null) {
                                        afterPickupFixedStop = (FixedStopWaypoint) stopWaypoint;
                                    }
                                    beforeDropoffFixedStop = (FixedStopWaypoint) stopWaypoint;
                                }
                            }

                            FixedStopWaypoint afterDropoffFixedStop = null;
                            for (int i = dropoff.index; i < insertion.vehicleEntry.stops.size(); i++) {
                                StopWaypoint stopWaypoint = insertion.vehicleEntry.stops.get(i);
                                if (stopWaypoint instanceof FixedStopWaypoint) {
                                    afterDropoffFixedStop = (FixedStopWaypoint) stopWaypoint;
                                    break;
                                }
                            }


                            String previousAtt = "drt:" + lineService.getLine().getId().toString() + ":" + lineService.getRoute().getId().toString() + ":previous";
                            String nextAtt = "drt:" + lineService.getLine().getId().toString() + ":" + lineService.getRoute().getId().toString() + ":next";

                            Object puPrevAttribute = puStop.getAttributes().getAttribute(previousAtt);
                            Id<TransitStopFacility> puPreviousFixedStopId = puPrevAttribute == null ? null : Id.create((String) puPrevAttribute, TransitStopFacility.class);

                            Object puNextAttribute = puStop.getAttributes().getAttribute(nextAtt);
                            Id<TransitStopFacility> puNextFixedStopId = puNextAttribute == null ? null : Id.create((String) puNextAttribute, TransitStopFacility.class);

                            String doPrevAttribute = (String) doStop.getAttributes().getAttribute(previousAtt);
                            Id<TransitStopFacility> doPreviousFixedStopId = doPrevAttribute == null ? null : Id.create(doPrevAttribute, TransitStopFacility.class);

                            String doNextAttribute = (String) doStop.getAttributes().getAttribute(nextAtt);
                            Id<TransitStopFacility> doNextFixedStopId = doNextAttribute == null ? null : Id.create(doNextAttribute, TransitStopFacility.class);

                            // there is a previous stop requirement for pu
                            if (puPreviousFixedStopId != null) {
                                // there is another upcoming fixed stop
                                if (beforePickupFixedStop != null) {
                                    if (!((FixedStopTask) beforePickupFixedStop.getTask()).getStop().getStopFacility().getId().equals(puPreviousFixedStopId)) {
                                        return INFEASIBLE_SOLUTION_COST;
                                    }
                                }
                            }

                            // there is a next stop requirement for pu
                            if (puNextFixedStopId != null) {
                                if (afterPickupFixedStop != null) {
                                    if (!((FixedStopTask) afterPickupFixedStop.getTask()).getStop().getStopFacility().getId().equals(puNextFixedStopId)) {
                                        return INFEASIBLE_SOLUTION_COST;
                                    }
                                }
                            }

                            // there is a previous stop requirement for do
                            if (doPreviousFixedStopId != null) {
                                // there is another upcoming fixed stop
                                if (beforeDropoffFixedStop != null) {
                                    if (!((FixedStopTask) beforeDropoffFixedStop.getTask()).getStop().getStopFacility().getId().equals(doPreviousFixedStopId)) {
                                        return INFEASIBLE_SOLUTION_COST;
                                    }
                                }
                            }

                            // there is a next stop requirement for do
                            if (doNextFixedStopId != null) {
                                if (afterDropoffFixedStop != null) {
                                    if (!((FixedStopTask) afterDropoffFixedStop.getTask()).getStop().getStopFacility().getId().equals(doNextFixedStopId)) {
                                        return INFEASIBLE_SOLUTION_COST;
                                    }
                                }
                            }
                        }

                        return delegate.calculate(drtRequest, insertion, detourTimeInfo);
                    }
                    return InsertionCostCalculator.INFEASIBLE_SOLUTION_COST;
                }
                return InsertionCostCalculator.INFEASIBLE_SOLUTION_COST;
            } else {
                return InsertionCostCalculator.INFEASIBLE_SOLUTION_COST;
            }
        } else {
            if(mixed) {
                return delegate.calculate(drtRequest, insertion, detourTimeInfo);
            } else {
                 return InsertionCostCalculator.INFEASIBLE_SOLUTION_COST;
            }

        }
       // return InsertionCostCalculator.INFEASIBLE_SOLUTION_COST;
    }
}
