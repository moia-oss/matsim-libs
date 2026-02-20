package org.matsim.contrib.drt.extension.flexibleTransit;

import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.annotations.XYLineAnnotation;
import org.jfree.chart.annotations.XYTextAnnotation;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.ui.TextAnchor;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.events.EventsManagerImpl;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.router.costcalculators.FreespeedTravelTimeAndDisutility;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.misc.OptionalTime;
import org.matsim.pt.transitSchedule.api.*;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;

/**
 * Time–distance diagram for a single TransitRoute.
 */
public class TransitTimeDistancePlot {

    public static final String EVENTS_FILE = "/Users/nico.kuehnel/Documents/simulation/kelheim/20251204/flex_moiaCostPrebookingtrue_optionaltrueschedule_rotErwKHStarSandMitterfGronsAuMFliedHeidackerEinkaufsz35min/output_events.xml.gz";
    public static final String NETWORK_FILE = "/Users/nico.kuehnel/Documents/simulation/kelheim/kelheim-drt.network.xml.gz";
    public static final String SCHEDULE_FILE = "/Users/nico.kuehnel/Documents/simulation/kelheim/schedule_rotErwKHStarSandMitterfGronsAuMFliedHeidackerEinkaufsz35min.xml";

    /** Container for the values we need per stop. */
    private static class StopProfile {
        final TransitRouteStop stop;
        final double distance;           // [m] from first stop
        final double earliestArrival;    // [s]
        final double earliestDeparture;  // [s]
        final double latestArrival;      // [s]
        final double latestDeparture;    // [s]
        private final Map<Id<Departure>, List<Double>> byDepartureArrivals;

        StopProfile(TransitRouteStop stop,
                    double distance,
                    double earliestArrival,
                    double earliestDeparture,
                    double latestArrival,
                    double latestDeparture, Map<Id<Departure>, List<Double>> byDepartureArrivals) {
            this.stop = stop;
            this.distance = distance;
            this.earliestArrival = earliestArrival;
            this.earliestDeparture = earliestDeparture;
            this.latestArrival = latestArrival;
            this.latestDeparture = latestDeparture;
            this.byDepartureArrivals = byDepartureArrivals;
        }
    }


    public static void main(String[] args) {
        Network network = NetworkUtils.readNetwork(NETWORK_FILE);
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new TransitScheduleReader(scenario).readFile(SCHEDULE_FILE);


        Map<Id<TransitLine>, Map<Id<TransitRoute>, Map<Id<TransitStopFacility>, Map<Id<Departure>, List<Double>>>>> actualArrivals = new HashMap<>();
        if (true) {
            EventsManagerImpl events = new EventsManagerImpl();

            events.addHandler((FixedStopArrivalEventHandler) event -> {
                Map<Id<TransitRoute>, Map<Id<TransitStopFacility>, Map<Id<Departure>, List<Double>>>> byRoute = actualArrivals.computeIfAbsent(event.getLine(), k -> new HashMap<>());
                Map<Id<TransitStopFacility>, Map<Id<Departure>, List<Double>>> byDeparture = byRoute.computeIfAbsent(event.getRoute(), k -> new HashMap<>());
                Map<Id<Departure>, List<Double>> arrivalsByStop = byDeparture.computeIfAbsent(event.getStopFacility(), k -> new HashMap<>());

                double departureTime = scenario.getTransitSchedule().getTransitLines().get(event.getLine()).getRoutes().get(event.getRoute()).getDepartures().get(event.getDeparture()).getDepartureTime();
                double v = event.getTime() - departureTime;
                arrivalsByStop.computeIfAbsent(event.getDeparture(), k -> new ArrayList<>()).add(v);
            });
            events.initProcessing();


            MatsimEventsReader matsimEventsReader = new MatsimEventsReader(events);
            matsimEventsReader.addCustomEventMapper(FixedStopArrivalEvent.EVENT_TYPE, event -> {
                Map<String, String> attributes = event.getAttributes();
                return new FixedStopArrivalEvent(event.getTime(),
                        attributes.get(FixedStopArrivalEvent.ATTRIBUTE_MODE),
                        Id.create(attributes.get(FixedStopArrivalEvent.ATTRIBUTE_LINE), TransitLine.class),
                        Id.create(attributes.get(FixedStopArrivalEvent.ATTRIBUTE_ROUTE), TransitRoute.class),
                        Id.create(attributes.get(FixedStopArrivalEvent.ATTRIBUTE_DEPARTURE), Departure.class),
                        Id.create(attributes.get(FixedStopArrivalEvent.ATTRIBUTE_STOP), TransitStopFacility.class),
                        Id.create(attributes.get(FixedStopArrivalEvent.ATTRIBUTE_VEHICLE_ID), DvrpVehicle.class),
                        Id.createLinkId(attributes.get(FixedStopArrivalEvent.ATTRIBUTE_LINK))
                );
            });
            matsimEventsReader.readFile(EVENTS_FILE);
        }

        for (TransitLine line : scenario.getTransitSchedule().getTransitLines().values()) {
            for (TransitRoute route : line.getRoutes().values()) {
                try {
                    writePng(network, line, route, actualArrivals, new File("flexRouteProfile_" + line.getName() + "_" + route.getId() + ".png"));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    /**
     * Create the chart for a single route.
     *
     * Earliest arrival is computed from free-speed travel time along the
     * underlying NetworkRoute. If you want something else (e.g. a Dijkstra
     * with dynamic travel times) you can just plug in different values.
     */
    public static JFreeChart createTimeDistanceChart(Network network, TransitLine line, TransitRoute route,
                                                     Map<Id<TransitLine>, Map<Id<TransitRoute>, Map<Id<TransitStopFacility>, Map<Id<Departure>, List<Double>>>>> actualArrivals) {

        List<StopProfile> profiles = buildStopProfiles(network, line, route, true, actualArrivals);

        // ---- Pre-compute actual arrivals for each profile ----
        // Key: profile index -> departure ID -> actual arrival time
        Map<Integer, Map<Id<Departure>, Double>> actualArrivalsByProfileIndex = new HashMap<>();

        // Track which visit number we're at for each (stop, departure) combination
        Map<Id<TransitStopFacility>, Map<Id<Departure>, Integer>> visitCounter = new HashMap<>();

        for (int i = 0; i < profiles.size(); i++) {
            StopProfile p = profiles.get(i);
            Map<Id<Departure>, Double> actualsForThisProfile = new HashMap<>();

            for (Map.Entry<Id<Departure>, List<Double>> byDeparture : p.byDepartureArrivals.entrySet()) {
                Id<Departure> depId = byDeparture.getKey();
                List<Double> arrivalTimes = byDeparture.getValue();

                // Get the current visit index for this stop+departure combination
                Map<Id<Departure>, Integer> stopVisits = visitCounter.computeIfAbsent(
                    p.stop.getStopFacility().getId(),
                    k -> new HashMap<>()
                );
                int visitIndex = stopVisits.getOrDefault(depId, 0);

                // Get the actual arrival time for this visit
                double actualTime = arrivalTimes.get(visitIndex);
                actualsForThisProfile.put(depId, actualTime);

                // Increment visit counter for next occurrence of this stop+departure
                stopVisits.put(depId, visitIndex + 1);
            }

            actualArrivalsByProfileIndex.put(i, actualsForThisProfile);
        }

        // ---- Build datasets for the four point types ----

        XYSeries earliestArrivals = new XYSeries("earliest arrival");
        XYSeries earliestDepartures = new XYSeries("earliest departure");
        XYSeries latestDepartures = new XYSeries("latest departure");
        XYSeries latestArrivals = new XYSeries("latest arrival");
        Map<Id<Departure>, XYSeries> seriesByDeparture = new HashMap<>();

        for (int i = 0; i < profiles.size(); i++) {
            StopProfile p = profiles.get(i);
            Map<Id<Departure>, Double> actuals = actualArrivalsByProfileIndex.get(i);

            earliestArrivals.add(p.distance, p.earliestArrival);
            earliestDepartures.add(p.distance, p.earliestDeparture);
            latestDepartures.add(p.distance, p.latestDeparture);
            latestArrivals.add(p.distance, p.latestArrival);

            for (Map.Entry<Id<Departure>, Double> actual : actuals.entrySet()) {
                //XYSeries xySeries = seriesByDeparture.computeIfAbsent(
                //    actual.getKey(),
                //    k -> new XYSeries("actual departure " + actual.getKey().toString())
                //);

                XYSeries xySeries = seriesByDeparture.computeIfAbsent(
                        actual.getKey(),
                        k -> new XYSeries(formatDepartureLabel(k))
                );
                xySeries.add(p.distance, actual.getValue());
            }
        }

        XYSeriesCollection dataset = new XYSeriesCollection();
        dataset.addSeries(earliestArrivals);   // 0
        dataset.addSeries(earliestDepartures); // 1
        dataset.addSeries(latestDepartures);   // 2
        dataset.addSeries(latestArrivals);     // 3
        for (XYSeries series : seriesByDeparture.values()) {
            dataset.addSeries(series);
        }


        NumberAxis xAxis = new NumberAxis("distance along line [m]");
        NumberAxis yAxis = new NumberAxis("seconds since departure");
        xAxis.setAutoRangeIncludesZero(true);
        yAxis.setAutoRangeIncludesZero(true);

        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer();

        // points only, no connecting lines for the four series above
        for (int i = 0; i < dataset.getSeriesCount(); i++) {
            renderer.setSeriesLinesVisible(i, false);
            renderer.setSeriesShapesVisible(i, true);
            renderer.setSeriesShape(i, new java.awt.geom.Ellipse2D.Double(-3, -3, 6, 6));
        }

        // colors are up to you – this matches your screenshot roughly
        renderer.setSeriesPaint(0, new Color(0, 160, 0));     // earliest arrival (green-ish)
        renderer.setSeriesPaint(1, new Color(0, 110, 0));     // earliest departure
        renderer.setSeriesPaint(2, new Color(200, 0, 0));     // latest departure (red)
        renderer.setSeriesPaint(3, new Color(150, 0, 0));     // latest arrival

        XYPlot plot = new XYPlot(dataset, xAxis, yAxis, renderer);

        // ---- add the green and red connection lines between stops ----

        BasicStroke stroke = new BasicStroke(2f);
        BasicStroke actualStroke = new BasicStroke(0.6f);

        for (int i = 0; i < profiles.size() - 1; i++) {
            StopProfile a = profiles.get(i);
            StopProfile b = profiles.get(i + 1);
            Map<Id<Departure>, Double> actualsA = actualArrivalsByProfileIndex.get(i);
            Map<Id<Departure>, Double> actualsB = actualArrivalsByProfileIndex.get(i + 1);

            // earliest: dep_i -> arr_(i+1)    (green)
            plot.addAnnotation(new XYLineAnnotation(
                    a.distance, a.earliestDeparture,
                    b.distance, b.earliestArrival,
                    stroke, new Color(0, 180, 0)));

            // latest:   dep_i -> arr_(i+1)    (red)
            plot.addAnnotation(new XYLineAnnotation(
                    a.distance, a.latestDeparture,
                    b.distance, b.latestArrival,
                    stroke, new Color(220, 0, 0)));

            // actual trajectories for each departure
            for (Id<Departure> departureId : seriesByDeparture.keySet()) {
                if (actualsA.containsKey(departureId) && actualsB.containsKey(departureId)) {
                    plot.addAnnotation(new XYLineAnnotation(
                            a.distance, actualsA.get(departureId),
                            b.distance, actualsB.get(departureId),
                            actualStroke, new Color(20, 0, 0)));
                }
            }
        }

        // ---- label the stops (name next to the points) ----
        for (int i = 0; i< profiles.size(); i++) {
            StopProfile p =  profiles.get(i);
            TransitStopFacility fac = p.stop.getStopFacility();
            String label = fac.getName() != null ? fac.getName() : fac.getId().toString();

            // put the label roughly between earliest and latest trajectories
            double labelY = 0.5 * (p.earliestArrival + p.latestArrival);
            XYTextAnnotation ann = new XYTextAnnotation(label, p.distance, labelY);
            ann.setFont(new Font("SansSerif", Font.PLAIN, 14));

            boolean isLast = (i == profiles.size() - 1);

            if (isLast) {
                // shift the label left a bit in data units (tune 50.0 as needed)
                ann.setX(p.distance - 200.0);
                ann.setTextAnchor(TextAnchor.TOP_CENTER);
            } else {
                ann.setTextAnchor(TextAnchor.CENTER_LEFT);
            }


            ann.setBackgroundPaint(Color.WHITE);
            plot.addAnnotation(ann);
        }

        JFreeChart chart = new JFreeChart(
                "Time–distance diagram: " + route.getId(),
                JFreeChart.DEFAULT_TITLE_FONT,
                plot,
                true);

        chart.setBackgroundPaint(Color.white);
        return chart;
    }

    /**
     * Computes distance along the route and the four timing values per stop.
     */
    private static List<StopProfile> buildStopProfiles(Network network, TransitLine line, TransitRoute route, boolean routing, Map<Id<TransitLine>, Map<Id<TransitRoute>, Map<Id<TransitStopFacility>, Map<Id<Departure>, List<Double>>>>> actualArrivals) {

        List<TransitRouteStop> stops = route.getStops();
        if (stops.isEmpty()) {
            return Collections.emptyList();
        }

        LeastCostPathCalculator pathCalculator = null;
        if(routing) {
            FreespeedTravelTimeAndDisutility freespeedTravelTimeAndDisutility = new FreespeedTravelTimeAndDisutility(new ScoringConfigGroup());
            pathCalculator = new SpeedyALTFactory().createPathCalculator(network, freespeedTravelTimeAndDisutility, freespeedTravelTimeAndDisutility);
        }

        // map link -> stops (except the first one, which we force to distance/time = 0)
        Map<Id<Link>, List<TransitRouteStop>> stopsByLink = new HashMap<>();
        for (int i = 1; i < stops.size(); i++) {
            TransitRouteStop s = stops.get(i);
            Id<Link> lid = s.getStopFacility().getLinkId();
            if (lid == null) continue;
            stopsByLink.computeIfAbsent(lid, k -> new ArrayList<>()).add(s);
        }

        List<Id<Link>> linkSequence = new ArrayList<>();
        if(!routing) {
            NetworkRoute netRoute = route.getRoute();
            linkSequence.add(netRoute.getStartLinkId());
            linkSequence.addAll(netRoute.getLinkIds());
            linkSequence.add(netRoute.getEndLinkId());
        }


        // finally assemble StopProfile list in stop order
        List<StopProfile> result = new ArrayList<>();

        double cumTime = 0;
        double cumDist = 0;
        Link prevLink = null;

        for (TransitRouteStop s : stops) {

            Link nextLink = network.getLinks().get(s.getStopFacility().getLinkId());

            double ea;
            double d;

            if(!routing) {

                if(prevLink != null) {
                    List<Id<Link>> linkIds = linkSequence.subList(linkSequence.indexOf(prevLink.getId()), linkSequence.indexOf(nextLink.getId()));
                    List<? extends Link> links = linkIds.stream().map(id -> network.getLinks().get(id)).toList();


                    cumDist += links.stream().mapToDouble(Link::getLength).sum();
                    cumTime += links.stream().mapToDouble(link -> link.getLength() / link.getFreespeed()).sum();

                }
            } else {
                if(prevLink != null) {
                    LeastCostPathCalculator.Path leastCostPath = pathCalculator.calcLeastCostPath(prevLink, nextLink, 0, null, null);
                    cumTime+= leastCostPath.travelTime;
                    cumDist += leastCostPath.links.stream().mapToDouble(Link::getLength).sum();
                }

            }
            ea = cumTime;
            d = cumDist;
            prevLink = nextLink;

            OptionalTime arrOffset = s.getArrivalOffset();
            OptionalTime depOffset = s.getDepartureOffset();

            double latestArr = arrOffset.isDefined()
                    ? arrOffset.seconds()
                    : 0;

            double latestDep = depOffset.isDefined()
                    ? depOffset.seconds()
                    : latestArr;  // last stop may only have arrival

            double earliestDep = s.isAwaitDepartureTime()
                    ? Math.max(latestArr , ea + s.getMinimumStopDuration())       // wait for schedule
                    : ea + s.getMinimumStopDuration();              // leave when you arrive

            cumTime = earliestDep;

            Map<Id<Departure>, List<Double>> byDepartureArrivals = actualArrivals
                    .getOrDefault(line.getId(), new HashMap<>())
                    .getOrDefault(route.getId(), new HashMap<>())
                    .getOrDefault(s.getStopFacility().getId(), new HashMap<>());
            result.add(new StopProfile(s, d, ea, earliestDep, latestArr, latestDep, byDepartureArrivals));
        }

        return result;
    }

    private static String formatDepartureLabel(Id<Departure> depId) {
        String raw = depId.toString(); // e.g. "dep-170500"
        String hhmmss = raw.replaceAll("\\D", ""); // extract digits only → "170500"

        if (hhmmss.length() >= 4) {
            int hh = Integer.parseInt(hhmmss.substring(0, 2));
            int mm = Integer.parseInt(hhmmss.substring(2, 4));
            return String.format("actual departure %02d:%02d", hh, mm);
        } else {
            return "actual departure";
        }
    }

    // --- tiny usage example --------------------------------------------------

    public static void writePng(Network network, TransitLine line, TransitRoute route,
                                Map<Id<TransitLine>, Map<Id<TransitRoute>, Map<Id<TransitStopFacility>, Map<Id<Departure>, List<Double>>>>> actualArrivals, File out) throws IOException {
        JFreeChart chart = createTimeDistanceChart(network, line, route, actualArrivals);
        ChartUtils.saveChartAsPNG(out, chart, 1200, 700);
    }
}
