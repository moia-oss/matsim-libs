package org.matsim.contrib.carsharing.relocation.qsim;

import java.util.List;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Route;
import org.matsim.core.router.LinkWrapperFacility;
import org.matsim.core.router.TripRouter;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.LeastCostPathCalculator.Path;
import org.matsim.core.utils.misc.OptionalTime;
import org.matsim.facilities.Facility;
import org.matsim.utils.objectattributes.attributable.AttributesImpl;

/**
 * Mostly copied from tutorial.programming.ownMobsimAgentUsingRouter.MyGuidance
 * 
 * Guidance. applied TripRouter.
 */

public class Guidance {
    private final TripRouter router;
    private LeastCostPathCalculator lcpc;

    public Guidance(TripRouter router, LeastCostPathCalculator lcpc) {
        this.router = router;
        this.lcpc = lcpc;
    }

    public synchronized Id<Link> getBestOutgoingLink(Link startLink, Link destinationLink, double now) {
        Person person = null; // does this work?
        double departureTime = now;
        String mainMode = TransportMode.car;
        Facility startFacility = new LinkWrapperFacility(startLink);
        Facility destinationFacility = new LinkWrapperFacility(destinationLink);
        List<? extends PlanElement> trip = router.calcRoute(mainMode, startFacility, destinationFacility, departureTime, person, new AttributesImpl());
        Path path = lcpc.calcLeastCostPath(startLink, destinationLink, now, person, null);
        if (path.links.size() == 0)
        	return destinationLink.getId();
        else
        	return path.links.get(0).getId();
       
    }

    public synchronized OptionalTime getExpectedTravelTime(Link startLink, Link destinationLink, double departureTime, String mode, Person person) {
        Facility startFacility = new LinkWrapperFacility(startLink);
        Facility destinationFacility = new LinkWrapperFacility(destinationLink);
        List<? extends PlanElement> trip = router.calcRoute(mode, startFacility, destinationFacility, departureTime, person, new AttributesImpl());
		Route route = ((Leg) trip.get(0)).getRoute();

		return route != null ? route.getTravelTime() : OptionalTime.undefined();
    }

    public synchronized double getExpectedTravelDistance(Link startLink, Link destinationLink, double departureTime, String mode, Person person) {
        Facility startFacility = new LinkWrapperFacility(startLink);
        Facility destinationFacility = new LinkWrapperFacility(destinationLink);
        List<? extends PlanElement> trip = router.calcRoute(mode, startFacility, destinationFacility, departureTime, person, new AttributesImpl());
		Route route = ((Leg) trip.get(0)).getRoute();

		double distance = route != null ? route.getDistance() : null;

		return distance;
    }
}
