package org.matsim.contrib.drt.extension.flexibleTransit;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;

import java.util.Map;

/**
 * @author nkuehnel / MOIA
 */
public class LineServiceScheduledEvent extends Event {

    private final String mode;
    private final Id<DvrpVehicle> vehicleId;
    private final Id<TransitLine> transitLineId;
    private final Id<TransitRoute> transitRouteId;
    private final double departureTime;

    public static final String ATTRIBUTE_VEHICLE_ID = "vehicle";
    public static final String ATTRIBUTE_LINE_ID = "line";
    public static final String ATTRIBUTE_ROUTE_ID = "route";
    public static final String ATTRIBUTE_DEPARTURE_TIME = "departureTime";
    public static final String EVENT_TYPE = "Flexible line service scheduled";

    public LineServiceScheduledEvent(double time, String mode, Id<DvrpVehicle> vehicleId,
                                     Id<TransitLine> transitLineId, Id<TransitRoute> transitRouteId, double departureTime) {
        super(time);
        this.mode = mode;
        this.vehicleId = vehicleId;
        this.transitLineId = transitLineId;
        this.transitRouteId = transitRouteId;
        this.departureTime = departureTime;
    }

    public Id<DvrpVehicle> getVehicleId() {
        return this.vehicleId;
    }


    public String getEventType() {
        return EVENT_TYPE;
    }

    public String getMode() {
        return mode;
    }

    public Id<TransitLine> getTransitLineId() {
        return transitLineId;
    }

    public Id<TransitRoute> getTransitRouteId() {
        return transitRouteId;
    }

    public double getDepartureTime() {
        return departureTime;
    }

    public Map<String, String> getAttributes() {
        Map<String, String> attr = super.getAttributes();
        attr.put(ATTRIBUTE_VEHICLE_ID, "" + this.vehicleId);
        attr.put(ATTRIBUTE_LINE_ID, "" + this.transitLineId);
        attr.put(ATTRIBUTE_ROUTE_ID, "" + this.transitRouteId);
        attr.put(ATTRIBUTE_DEPARTURE_TIME, "" + this.departureTime);
        return attr;
    }
}

