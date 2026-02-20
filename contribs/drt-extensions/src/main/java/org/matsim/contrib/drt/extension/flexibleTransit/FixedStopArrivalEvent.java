package org.matsim.contrib.drt.extension.flexibleTransit;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import java.util.Map;

public class FixedStopArrivalEvent extends Event {

    private final String mode;
    private final Id<TransitLine> line;
    private final Id<TransitRoute> route;
    private final Id<Departure> departure;
    private final Id<TransitStopFacility> stopFacility;
    private final Id<DvrpVehicle> vehicleId;
    private final Id<Link> linkId;

    public static final String ATTRIBUTE_MODE = "mode";
    public static final String ATTRIBUTE_LINK = "link";
    public static final String ATTRIBUTE_LINE = "line";
    public static final String ATTRIBUTE_DEPARTURE = "departure";
    public static final String ATTRIBUTE_ROUTE = "route";
    public static final String ATTRIBUTE_STOP = "stop";
    public static final String ATTRIBUTE_VEHICLE_ID = "vehicle";

    public static final String EVENT_TYPE = "Fixed stop arrival";

    public FixedStopArrivalEvent(double time, String mode, Id<TransitLine> line, Id<TransitRoute> route, Id<Departure> departure,
                                 Id<TransitStopFacility> stopFacility, Id<DvrpVehicle> vehicleId, Id<Link> linkId) {
        super(time);
        this.mode = mode;
        this.line = line;
        this.route = route;
        this.departure = departure;
        this.stopFacility = stopFacility;
        this.vehicleId = vehicleId;
        this.linkId = linkId;
    }

    public Id<DvrpVehicle> getVehicleId() {
        return vehicleId;
    }

    public Id<Link> getLinkId() {
        return linkId;
    }

    @Override
    public String getEventType() {
        return EVENT_TYPE;
    }

    @Override
    public Map<String, String> getAttributes() {
        Map<String, String> attr = super.getAttributes();
        attr.put(ATTRIBUTE_MODE, mode + "");
        attr.put(ATTRIBUTE_VEHICLE_ID, vehicleId + "");
        attr.put(ATTRIBUTE_LINK, linkId + "");
        attr.put(ATTRIBUTE_STOP, stopFacility + "");
        attr.put(ATTRIBUTE_LINE, line + "");
        attr.put(ATTRIBUTE_ROUTE, route + "");
        attr.put(ATTRIBUTE_DEPARTURE, departure + "");
        return attr;
    }

    public String getMode() {
        return mode;
    }

    public Id<TransitLine> getLine() {
        return line;
    }

    public Id<TransitRoute> getRoute() {
        return route;
    }

    public Id<Departure> getDeparture() {
        return departure;
    }

    public Id<TransitStopFacility> getStopFacility() {
        return stopFacility;
    }
}

