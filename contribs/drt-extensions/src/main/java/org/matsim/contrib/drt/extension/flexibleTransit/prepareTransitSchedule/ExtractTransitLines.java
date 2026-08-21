package org.matsim.contrib.drt.extension.flexibleTransit.prepareTransitSchedule;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.TransportModeNetworkFilter;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.collections.QuadTree;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.pt.transitSchedule.api.*;

import java.util.*;

import static org.matsim.api.core.v01.TransportMode.drt;

public class ExtractTransitLines {

    public static void main(String[] args) {

//        Set<String> lines = Set.of(
//                "454"
//        );

        Set<Id<TransitLine>> lines = Set.of(
                Id.create("454---31092_3", TransitLine.class) //Schwarzer Berg
//                Id.create("201---76830_3", TransitLine.class) // Wernigerode
//                Id.create("873---14762_3", TransitLine.class) // Bad Harzburg

        );

        extractLines(
                "C:/Users/schl_t19/Projekte/IMoGer/Simulation/Braunschweig/v0.3/bs_v0.2_network-with-pt.xml.gz",
                "C:/Users/schl_t19/Projekte/IMoGer/Simulation/Braunschweig/v0.3/bs_v0.2_transitSchedule.xml.gz",
                "C:/Users/schl_t19/Projekte/IMoGer/Simulation/flexibleTransit/braunschweig/drt/454_schwarzerBerg_6bis8h.xml",
                lines,
                6*3600,
                8*3600
        );
    }

    public static void extractLines(
            String networkFile,
            String transitScheduleFile,
            String outputScheduleFile,
            Set<Id<TransitLine>> linesToKeep,
            double earliestDeparture,
            double latestDeparture
    ) {

        // --------------------------------------------------------------------
        // 1. Szenario laden
        // --------------------------------------------------------------------

        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());

        // --------------------------------------------------------------------
        // 2. Netzwerk und Schedule einlesen
        // --------------------------------------------------------------------

        new org.matsim.core.network.io.MatsimNetworkReader(scenario.getNetwork())
                .readFile(networkFile);

        new TransitScheduleReader(scenario).readFile(transitScheduleFile);

        TransitSchedule schedule = scenario.getTransitSchedule();
        Network carNetwork = NetworkUtils.createNetwork();
        new TransportModeNetworkFilter(scenario.getNetwork()).filter(carNetwork, Set.of(TransportMode.car));

        // --------------------------------------------------------------------
        // 3. TransitLines filtern
        // --------------------------------------------------------------------

        Set<Id<TransitLine>> allLines =
                new HashSet<>(schedule.getTransitLines().keySet());
        for (Id<TransitLine> lineId : allLines) {
            if (!linesToKeep.contains(lineId)) {
                schedule.removeTransitLine(schedule.getTransitLines().get(lineId));
            }
        }

//        Set<TransitLine> linesToDelete = schedule.getTransitLines().values().stream()
//                .filter(line -> !linesToKeep.contains(line.getName()))
//                .collect(Collectors.toSet());
//        linesToDelete.forEach(line -> schedule.removeTransitLine(line));

        // --------------------------------------------------------------------
        // 4. TransitRoutes bearbeiten
        // --------------------------------------------------------------------

        for (TransitLine line : schedule.getTransitLines().values()) {

            for (TransitRoute route : line.getRoutes().values()) {

                // a) Route löschen
                route.setRoute(null);

                // b) vehicleRefIds entfernen
                Set<Departure> departureRemovalSet = new HashSet<>();
                for (Departure departure : route.getDepartures().values()) {
                    departure.setVehicleId(null);
                    if (! (departure.getDepartureTime() >= earliestDeparture &&  departure.getDepartureTime() < latestDeparture) ) {
                        departureRemovalSet.add(departure);
                    }
                }
                departureRemovalSet.forEach(departure -> route.removeDeparture(departure));

                // c) transportMode löschen
                route.setTransportMode(drt);
            }
        }

        // --------------------------------------------------------------------
        // 5. Nicht benötigte Stops entfernen. Für alle Stops, die behalten werden, wird await departure auf false gesetzt.
        // --------------------------------------------------------------------

        Set<Id<TransitStopFacility>> usedStops = new HashSet<>();

        for (TransitLine line : schedule.getTransitLines().values()) {
            for (TransitRoute route : line.getRoutes().values()) {

                for (int i = 0; i < route.getStops().size(); i++) {
                    TransitRouteStop stop = route.getStops().get(i);
                    usedStops.add(stop.getStopFacility().getId());
                    stop.setAwaitDepartureTime(false);

                    Link nearestRightEntryLink = NetworkUtils.getNearestRightEntryLink(carNetwork, stop.getStopFacility().getCoord());
                    Link nearestLeftEntryLink = getNearestLeftEntryLink(carNetwork, stop.getStopFacility().getCoord());
//            Link oppositeLink = NetworkUtils.findLinkInOppositeDirection(nearestRightEntryLink);

                    Id<Link> linkId;
                    if (i < route.getStops().size() - 1) {
                        linkId = chooseLinkWithSmallestToNodeDistToStop(route.getStops().get(i + 1).getStopFacility(),
                                List.of(nearestRightEntryLink, nearestLeftEntryLink));
                    } else {
                        //TODO: for last stop we could do something like take the link with the LARGER distance to the last stop
                        linkId = nearestRightEntryLink.getId();
                    }

                    stop.getStopFacility().setLinkId(linkId);
                }

            }
        }

        Set<Id<TransitStopFacility>> allStops =
                new HashSet<>(schedule.getFacilities().keySet());

        for (Id<TransitStopFacility> stopId : allStops) {
            if (!usedStops.contains(stopId)) {
                schedule.removeStopFacility(schedule.getFacilities().get(stopId));
            }
        }

        // --------------------------------------------------------------------
        // 6. Jeden Stop auf nächstgelegenen Link mappen
        // --------------------------------------------------------------------

//        QuadTree<Link> quadTree = buildToNodeQuadTree(carNetwork);

//        for (TransitStopFacility stop : schedule.getFacilities().values()) {
//
////            Link link = quadTree.getClosest(
////                    stop.getCoord().getX(),
////                    stop.getCoord().getY()
////            );
//
//            //the problem is that often TransitStops for both directions share the same coordinates. We need more intelligence to get the right link...
//
//        }

        // --------------------------------------------------------------------
        // 7. Schedule schreiben
        // --------------------------------------------------------------------

        new TransitScheduleWriter(schedule).writeFile(outputScheduleFile);
    }

    private static Id<Link> chooseLinkWithSmallestToNodeDistToStop(TransitStopFacility stop, List<Link> links) {
        return links.stream()
                .min(Comparator.comparingDouble(link ->
                        CoordUtils.calcEuclideanDistance(
                                link.getToNode().getCoord(),
                                stop.getCoord())))
                .orElseThrow()
                .getId();
    }

    private static QuadTree<Link> buildToNodeQuadTree(Network network) {

        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;

        for (Link link : network.getLinks().values()) {

            var c = link.getToNode().getCoord();

            minX = Math.min(minX, c.getX());
            minY = Math.min(minY, c.getY());
            maxX = Math.max(maxX, c.getX());
            maxY = Math.max(maxY, c.getY());
        }

        QuadTree<Link> quadTree = new QuadTree<>(
                minX - 1,
                minY - 1,
                maxX + 1,
                maxY + 1
        );

        for (Link link : network.getLinks().values()) {

            quadTree.put(
                    link.getToNode().getCoord().getX(),
                    link.getToNode().getCoord().getY(),
                    link
            );
        }

        return quadTree;
    }

    /**
     * this is a copy of NetworkUtils.getNearestRightEntryLink where we only change the check for the cross product
     * @param network
     * @param coord
     * @return
     */
    private static Link getNearestLeftEntryLink(Network network, final Coord coord) {
        Link nearestLeftLink = null;
        Link nearestOverallLink = null;
        Node nearestNode = NetworkUtils.getNearestNode((network), coord);

        double[] coordVector = new double[2];
        coordVector[0] = nearestNode.getCoord().getX() - coord.getX();
        coordVector[1] = nearestNode.getCoord().getY() - coord.getY();

        // now find nearest link from the nearest node
        double shortestLeftDistance = Double.MAX_VALUE; // reset the value
        double shortestOverallDistance = Double.MAX_VALUE; // reset the value
        List<Link> incidentLinks = new ArrayList<>(nearestNode.getInLinks().values());
        incidentLinks.addAll(nearestNode.getOutLinks().values());
        for (Link link : incidentLinks) {
            double dist = CoordUtils.distancePointLinesegment(link.getFromNode().getCoord(), link.getToNode().getCoord(), coord);
            if (dist <= shortestLeftDistance) {
                // Generate a vector representing the link
                double[] linkVector = new double[2];
                linkVector[0] = link.getToNode().getCoord().getX()
                        - link.getFromNode().getCoord().getX();
                linkVector[1] = link.getToNode().getCoord().getY()
                        - link.getFromNode().getCoord().getY();

                // Calculate the z component of cross product of coordVector and the link
                double crossProductZ = coordVector[0] * linkVector[1] - coordVector[1] * linkVector[0];
                // If coord lies to the right of the directed link, i.e. if the z component
                // of the cross product is POSITIVE, set it as new nearest link
                if (crossProductZ > 0) {
                    if (dist < shortestLeftDistance) {
                        shortestLeftDistance = dist;
                        nearestLeftLink = link;
                    } else { // dist == shortestLeftDistance
                        if (link.getId().compareTo(nearestLeftLink.getId()) < 0) {
                            shortestLeftDistance = dist;
                            nearestLeftLink = link;
                        }
                    }
                }
            }
            if (dist < shortestOverallDistance) {
                shortestOverallDistance = dist;
                nearestOverallLink = link;
            } else if (dist == shortestOverallDistance) {
                if (link.getId().compareTo(nearestOverallLink.getId()) < 0) {
                    shortestOverallDistance = dist;
                    nearestOverallLink = link;
                }
            }
        }

        // Return the nearest overall link if there is no nearest link
        // such that the given coord is on the right side of it
        if (nearestLeftLink == null) {
            return nearestOverallLink;
        }
        return nearestLeftLink;
    }

}
