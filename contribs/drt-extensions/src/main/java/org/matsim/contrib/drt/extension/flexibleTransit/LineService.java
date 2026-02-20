package org.matsim.contrib.drt.extension.flexibleTransit;

import com.google.common.base.Verify;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.IdMap;
import org.matsim.api.core.v01.network.Link;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

public class LineService {

    private final TransitLine line;
    private final TransitRoute route;
    private final Departure departure;

    private final Map<Id<Link>, TransitRouteStop> routeStopsByLink = new IdMap<>(Link.class);

    private TransitRouteStop lastCheckpoint;
    private int lastCheckpointIdx = -1;

    public LineService(TransitLine line, TransitRoute route, Departure departure) {
        this.line = line;
        this.route = route;
        this.departure = departure;

        for (TransitRouteStop stop : route.getStops()) {
            routeStopsByLink.put(stop.getStopFacility().getLinkId(), stop);
        }
    }

    public Id<Link> getStartLink() {
        return route.getRoute().getStartLinkId();
    }

    public Id<Link> getEndLink() {
        return route.getRoute().getEndLinkId();
    }

    public TransitLine getLine() {
        return line;
    }

    public TransitRoute getRoute() {
        return route;
    }

    public Departure getDeparture() {
        return departure;
    }

    public Optional<TransitRouteStop> getRouteStop(Link link) {
        return Optional.ofNullable(routeStopsByLink.get(link.getId()));
    }

    public boolean advance(TransitRouteStop stop) {
        lastCheckpointIdx++;
        Verify.verify(route.getStops().get(lastCheckpointIdx).equals(stop));
        if(route.getStops().size() - 1 == lastCheckpointIdx) {
            return true;
        }
        return false;
    }

    public Optional<TransitRouteStop> getLastCheckpoint() {
        return lastCheckpointIdx < 0 ? Optional.empty() : Optional.of(route.getStops().get(lastCheckpointIdx));
    }

    public OptionalInt getLastCheckpointIdx() {
        return lastCheckpointIdx < 0 ? OptionalInt.empty() : OptionalInt.of(lastCheckpointIdx);
    }
}
