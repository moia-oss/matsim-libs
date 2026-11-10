package org.matsim.core.router.speedy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.vehicles.Vehicle;

/**
 * Exactness oracle for {@link CHRouterFactory} when travel times change after the hierarchy was built, which
 * is how it is used in a simulation (built on free-flow times, jams appear in later iterations):
 *
 * <pre>{@code
 *   iteration n               iteration n+1
 *   +--------------------+    +-----------------------+    +----------------------------------+
 *   | build CH on        | -> | jam some links,       | -> | query every origin-destination   |
 *   | free-flow times    |    | signal iteration start|    | pair with CH and exact Dijkstra, |
 *   +--------------------+    +-----------------------+    | costs must be equal              |
 *                                                          +----------------------------------+
 * }</pre>
 *
 * <p>Coverage is by construction and not by sampling: every origin-destination pair of the network is queried
 * for every jam scenario and departure time, and the result is compared with an exact Dijkstra on the same live
 * travel times. The network has fast arterials so that the contraction really prunes shortcuts (a uniform grid
 * is full of ties and hides the problem). The test also checks that the scenarios exercise the jams: a minimum
 * share of queries must be rerouted by them, and most jammed links must lie on some free-flow optimal route.
 */
public class CHExactnessUnderJamsTest {

	private static final int GRID = 8;
	static final double HOUR = 3600.0;
	static final double JAM_DELAY = 600.0;
	/**
	 * Costs must match exactly. The jam windows start and end on 15-minute boundaries, the travel time bins on which
	 * the router's lower bounds are sampled.
	 */
	static final double TOLERANCE = 1e-6;
	/**
	 * Departures at bin starts and inside bins. 06:54 and 08:57 are a few minutes before the jam starts and ends, so
	 * many trips cross a travel-time change in the middle of a bin, which a per-bin shortcut travel time gets wrong.
	 */
	static final double[] DEPARTURES = {0.0, 6.75 * HOUR, 6.9 * HOUR, 7.0 * HOUR, 8.0 * HOUR, 8.75 * HOUR, 8.95 * HOUR, 9.0 * HOUR, 12.0 * HOUR};

	/**
	 * Free-flow time plus a delay on jammed links, either all day or only between 07:00 and 09:00. The test
	 * departs at the listed times, so routes start before, inside and after the jam window and some
	 * cross its edges:
	 *
	 * <pre>{@code
	 *   delay on a jammed link (peak-only scenarios)
	 *
	 *   600 s |                +----------------+
	 *         |                |                |
	 *     0 s +----------------+                +------------------------
	 *         0              07:00            09:00                 24:00
	 *
	 *   departures: 00:00, 06:45, 06:54, 07:00, 08:00, 08:45, 08:57, 09:00, 12:00
	 * }</pre>
	 */
	static class JamTravelTime implements TravelTime {
		final Map<Id<Link>, Double> delay = new HashMap<>();
		boolean peakOnly;

		@Override
		public double getLinkTravelTime(Link link, double time, Person person, Vehicle vehicle) {
			double free = link.getLength() / link.getFreespeed();
			Double d = delay.get(link.getId());
			if (d == null) return free;
			boolean inPeak = (time % (24 * HOUR)) >= 7 * HOUR && (time % (24 * HOUR)) < 9 * HOUR;
			return (peakOnly && !inPeak) ? free : free + d;
		}
	}

	/**
	 * 8x8 grid, links in both directions, irregular lengths (80-140 m). Arterials ({@code ===} and {@code #},
	 * 20 m/s) run along every third row and column, side streets ({@code ---} and {@code |}) are 8-10 m/s. The
	 * speed difference is what makes the contraction drop shortcuts: a side-street shortcut is often beaten by a
	 * detour over an arterial, until that arterial is jammed.
	 *
	 * <pre>{@code
	 *   y
	 *   7  o---o---o---o---o---o---o---o
	 *      #   |   |   #   |   |   #   |
	 *   6  o===o===o===o===o===o===o===o
	 *      #   |   |   #   |   |   #   |
	 *   5  o---o---o---o---o---o---o---o
	 *      #   |   |   #   |   |   #   |
	 *   4  o---o---o---o---o---o---o---o
	 *      #   |   |   #   |   |   #   |
	 *   3  o===o===o===o===o===o===o===o
	 *      #   |   |   #   |   |   #   |
	 *   2  o---o---o---o---o---o---o---o
	 *      #   |   |   #   |   |   #   |
	 *   1  o---o---o---o---o---o---o---o
	 *      #   |   |   #   |   |   #   |
	 *   0  o===o===o===o===o===o===o===o
	 *      0   1   2   3   4   5   6   7   x
	 *              "whole column" jams ^ all vertical links at x = 3
	 * }</pre>
	 *
	 * Node ids are {@code n<x>_<y>}, link ids {@code n<x>_<y>->n<x'>_<y'>}.
	 */
	static Network createNetwork() {
		Network network = NetworkUtils.createNetwork();
		Random random = new Random(42);
		Node[][] nodes = new Node[GRID][GRID];
		for (int x = 0; x < GRID; x++) {
			for (int y = 0; y < GRID; y++) {
				nodes[x][y] = NetworkUtils.createAndAddNode(network, Id.createNodeId("n" + x + "_" + y), new Coord(x * 100.0 + random.nextInt(30), y * 100.0 + random.nextInt(30)));
			}
		}
		for (int x = 0; x < GRID; x++) {
			for (int y = 0; y < GRID; y++) {
				if (x + 1 < GRID) addPair(network, nodes[x][y], nodes[x + 1][y], y % 3 == 0, random);
				if (y + 1 < GRID) addPair(network, nodes[x][y], nodes[x][y + 1], x % 3 == 0, random);
			}
		}
		return network;
	}

	private static void addPair(Network network, Node a, Node b, boolean arterial, Random random) {
		double length = 80 + random.nextInt(60);
		double speed = arterial ? 20.0 : 8.0 + random.nextInt(3);
		for (Node[] ends : new Node[][] {{a, b}, {b, a}}) {
			Link link = NetworkUtils.createAndAddLink(network, Id.createLinkId(ends[0].getId() + "->" + ends[1].getId()), ends[0], ends[1], length, speed, 1800.0, 1.0);
			link.setAllowedModes(Set.of("car"));
		}
	}

	record Scenario(String name, List<Link> jammed, boolean peakOnly) {}

	static List<Scenario> scenarios(Network network) {
		List<Link> all = new ArrayList<>(network.getLinks().values());
		all.sort((l1, l2) -> l1.getId().toString().compareTo(l2.getId().toString()));
		Random random = new Random(7);
		List<Scenario> out = new ArrayList<>();
		out.add(new Scenario("no jam (control)", List.of(), false));
		for (boolean peakOnly : new boolean[] {false, true}) {
			String suffix = peakOnly ? " (07:00-09:00)" : " (all day)";
			out.add(new Scenario("single link" + suffix, List.of(all.get(37)), peakOnly));
			out.add(new Scenario("five scattered links" + suffix, pick(all, 5, random), peakOnly));
			List<Link> corridor = new ArrayList<>();
			for (Link l : all) if (l.getFromNode().getId().toString().startsWith("n3_") && l.getToNode().getId().toString().startsWith("n3_")) corridor.add(l);
			out.add(new Scenario("whole column" + suffix, corridor, peakOnly));
			out.add(new Scenario("30% of all links" + suffix, pick(all, all.size() * 3 / 10, random), peakOnly));
		}
		return out;
	}

	private static List<Link> pick(List<Link> all, int n, Random random) {
		List<Link> copy = new ArrayList<>(all);
		java.util.Collections.shuffle(copy, random);
		return copy.subList(0, n);
	}

	@Test
	void chMatchesExactDijkstraForAllPairsUnderJams() {
		Network network = createNetwork();
		List<Node> nodes = new ArrayList<>(network.getNodes().values());
		List<String> failures = new ArrayList<>();
		Map<String, double[]> perScenario = new java.util.LinkedHashMap<>(); // failures, worst excess cost, most negative excess
		long queries = 0, rerouted = 0;
		Set<Id<Link>> jammedLinksOnFreeRoutes = new HashSet<>(), allJammed = new HashSet<>();

		for (Scenario scenario : scenarios(network)) {
			JamTravelTime tt = new JamTravelTime();
			tt.peakOnly = scenario.peakOnly();
			TravelDisutility td = new OnlyTimeDependentTravelDisutility(tt);

			// production order: hierarchy built on free-flow times, the jam arrives with a later iteration
			CHRouterFactory factory = new CHRouterFactory();
			factory.notifyIterationStarts(null);
			factory.createPathCalculator(network, td, tt);
			LeastCostPathCalculator freeFlowOracle = new SpeedyDijkstraFactory().createPathCalculator(network, td, tt);
			Map<String, List<Id<Link>>> freeRoutes = new HashMap<>();
			for (double departure : DEPARTURES) {
				for (Node from : nodes) {
					for (Node to : nodes) {
						if (from != to) freeRoutes.put(from.getId() + ">" + to.getId() + "@" + departure, ids(freeFlowOracle.calcLeastCostPath(from, to, departure, null, null)));
					}
				}
			}

			for (Link l : scenario.jammed()) {
				tt.delay.put(l.getId(), JAM_DELAY);
				allJammed.add(l.getId());
			}
			factory.notifyIterationStarts(null);
			LeastCostPathCalculator ch = factory.createPathCalculator(network, td, tt);
			LeastCostPathCalculator oracle = new SpeedyDijkstraFactory().createPathCalculator(network, td, tt);

			for (double departure : DEPARTURES) {
				for (Node from : nodes) {
					for (Node to : nodes) {
						if (from == to) continue;
						queries++;
						LeastCostPathCalculator.Path expected = oracle.calcLeastCostPath(from, to, departure, null, null);
						LeastCostPathCalculator.Path actual = ch.calcLeastCostPath(from, to, departure, null, null);
						List<Id<Link>> free = freeRoutes.get(from.getId() + ">" + to.getId() + "@" + departure);
						if (!ids(expected).equals(free)) rerouted++;
						for (Id<Link> l : free) if (tt.delay.containsKey(l)) jammedLinksOnFreeRoutes.add(l);
						if (actual == null || Math.abs(actual.travelCost - expected.travelCost) > TOLERANCE) {
							if (failures.size() < 8) {
								failures.add(String.format("%s, %s -> %s at %.0f s: CH %s vs exact %.1f", scenario.name(), from.getId(), to.getId(), departure,
										actual == null ? "no path" : String.format("%.1f", actual.travelCost), expected.travelCost));
							}
							failures.add(null); // count only
							double excess = actual == null ? Double.POSITIVE_INFINITY : actual.travelCost - expected.travelCost;
							double[] stat = perScenario.computeIfAbsent(scenario.name(), k -> new double[3]);
							stat[0]++;
							stat[1] = Math.max(stat[1], excess);
							stat[2] = Math.min(stat[2], excess); // negative would mean CH found something cheaper than exact: invalid path
						}
					}
				}
			}
		}

		long failureCount = failures.stream().filter(s -> s == null).count();
		List<String> shown = failures.stream().filter(s -> s != null).toList();
		// the scenarios must exercise the jams, otherwise a pass proves nothing
		assertTrue(rerouted >= queries / 20, "jams reroute too few queries to be a meaningful test: " + rerouted + " of " + queries);
		assertTrue(jammedLinksOnFreeRoutes.size() >= allJammed.size() * 0.9, "most jammed links must lie on a free-flow optimal route: "
				+ jammedLinksOnFreeRoutes.size() + " of " + allJammed.size());
		StringBuilder summary = new StringBuilder();
		perScenario.forEach((name, s) -> summary.append(String.format("%n  %s: %.0f wrong, worst excess %.1f s, most negative %.1f s", name, s[0], s[1], s[2])));
		assertEquals(0, failureCount, failureCount + " of " + queries + " queries differ from the exact result, e.g. " + shown + summary);
	}

	/**
	 * Designed bottleneck: two 6x6 districts separated by a river, joined only by three 500 m bridges (north,
	 * centre, south). Every cross-river trip must use a bridge, so jamming one or two of them forces large detours.
	 *
	 * <pre>{@code
	 *          west district                         east district
	 *   y   w0  w1  w2  w3  w4  w5      river      e0  e1  e2  e3  e4  e5
	 *   5   o---o---o---o---o---o ==== north ==== o---o---o---o---o---o
	 *       |   |   |   |   |   |      ~~~~~      |   |   |   |   |   |
	 *   4   o---o---o---o---o---o      ~~~~~      o---o---o---o---o---o
	 *       |   |   |   |   |   |      ~~~~~      |   |   |   |   |   |
	 *   3   o---o---o---o---o---o ==== centre === o---o---o---o---o---o
	 *       |   |   |   |   |   |      ~~~~~      |   |   |   |   |   |
	 *   2   o---o---o---o---o---o      ~~~~~      o---o---o---o---o---o
	 *       |   |   |   |   |   |      ~~~~~      |   |   |   |   |   |
	 *   1   o---o---o---o---o---o      ~~~~~      o---o---o---o---o---o
	 *       |   |   |   |   |   |      ~~~~~      |   |   |   |   |   |
	 *   0   o---o---o---o---o---o ==== south ==== o---o---o---o---o---o
	 * }</pre>
	 *
	 * (Arterials inside the districts as in {@link #createNetwork()} are omitted from the drawing.)
	 *
	 * <p>This test passes on the unfixed builder: every cross-river shortcut contains a bridge, and the build keeps
	 * them. It guards exactness for bottlenecks; the grid test is the one that exposes dropped shortcuts.
	 */
	@Test
	void chMatchesExactDijkstraWhenBridgesAreJammed() {
		Network network = NetworkUtils.createNetwork();
		Random random = new Random(3);
		int n = 6;
		Node[][] west = new Node[n][n], east = new Node[n][n];
		for (int x = 0; x < n; x++) {
			for (int y = 0; y < n; y++) {
				west[x][y] = NetworkUtils.createAndAddNode(network, Id.createNodeId("w" + x + "_" + y), new Coord(x * 100.0, y * 100.0));
				east[x][y] = NetworkUtils.createAndAddNode(network, Id.createNodeId("e" + x + "_" + y), new Coord(1000 + x * 100.0, y * 100.0));
			}
		}
		for (Node[][] d : new Node[][][] {west, east}) {
			for (int x = 0; x < n; x++) {
				for (int y = 0; y < n; y++) {
					if (x + 1 < n) addPair(network, d[x][y], d[x + 1][y], y % 3 == 0, random);
					if (y + 1 < n) addPair(network, d[x][y], d[x][y + 1], x % 3 == 0, random);
				}
			}
		}
		Map<String, List<Link>> bridges = new java.util.LinkedHashMap<>();
		for (int[] b : new int[][] {{0, 0}, {1, n / 2}, {2, n - 1}}) {
			String name = new String[] {"south", "centre", "north"}[b[0]];
			Node w = west[n - 1][b[1]], e = east[0][b[1]];
			Link there = NetworkUtils.createAndAddLink(network, Id.createLinkId("bridge_" + name + "_e"), w, e, 500.0, 15.0, 1800.0, 1.0);
			Link back = NetworkUtils.createAndAddLink(network, Id.createLinkId("bridge_" + name + "_w"), e, w, 500.0, 15.0, 1800.0, 1.0);
			there.setAllowedModes(Set.of("car"));
			back.setAllowedModes(Set.of("car"));
			bridges.put(name, List.of(there, back));
		}

		List<List<String>> jams = List.of(List.of(), List.of("centre"), List.of("south"), List.of("centre", "north"), List.of("south", "north"));
		List<Node> nodes = new ArrayList<>(network.getNodes().values());
		StringBuilder wrong = new StringBuilder();
		long queries = 0, failures = 0;
		for (List<String> jammed : jams) {
			JamTravelTime tt = new JamTravelTime();
			TravelDisutility td = new OnlyTimeDependentTravelDisutility(tt);
			CHRouterFactory factory = new CHRouterFactory();
			factory.notifyIterationStarts(null);
			factory.createPathCalculator(network, td, tt); // built on free-flow times
			for (String b : jammed) for (Link l : bridges.get(b)) tt.delay.put(l.getId(), JAM_DELAY);
			factory.notifyIterationStarts(null);
			LeastCostPathCalculator ch = factory.createPathCalculator(network, td, tt);
			LeastCostPathCalculator oracle = new SpeedyDijkstraFactory().createPathCalculator(network, td, tt);
			long scenarioFailures = 0;
			for (Node from : nodes) {
				for (Node to : nodes) {
					if (from == to) continue;
					queries++;
					LeastCostPathCalculator.Path expected = oracle.calcLeastCostPath(from, to, 0.0, null, null);
					LeastCostPathCalculator.Path actual = ch.calcLeastCostPath(from, to, 0.0, null, null);
					if (actual == null || Math.abs(actual.travelCost - expected.travelCost) > TOLERANCE) scenarioFailures++;
				}
			}
			failures += scenarioFailures;
			wrong.append(String.format("%n  jammed bridges %s: %d wrong", jammed, scenarioFailures));
		}
		assertEquals(0, failures, failures + " of " + queries + " queries differ from the exact result:" + wrong);
	}

	/**
	 * The reported travel time must belong to the reported route: re-driving the returned links from the
	 * departure time on the live travel times gives the same time, and the links form a connected route from
	 * origin to destination. (Whether the route is optimal is checked by the tests above.)
	 *
	 * <pre>{@code
	 *   path.links:  o --l1--> o --l2--> o --l3--> o
	 *                origin                         destination
	 *   re-drive:    t0 = departure, t(i+1) = t(i) + tt(l(i), t(i))   ==>   t3 - t0 == path.travelTime
	 * }</pre>
	 */
	@Test
	void reportedTravelTimeBelongsToTheReturnedRoute() {
		Network network = createNetwork();
		List<Node> nodes = new ArrayList<>(network.getNodes().values());
		long queries = 0, failures = 0;
		List<String> shown = new ArrayList<>();
		for (Scenario scenario : scenarios(network)) {
			JamTravelTime tt = new JamTravelTime();
			tt.peakOnly = scenario.peakOnly();
			TravelDisutility td = new OnlyTimeDependentTravelDisutility(tt);
			CHRouterFactory factory = new CHRouterFactory();
			factory.notifyIterationStarts(null);
			factory.createPathCalculator(network, td, tt);
			for (Link l : scenario.jammed()) tt.delay.put(l.getId(), JAM_DELAY);
			factory.notifyIterationStarts(null);
			LeastCostPathCalculator ch = factory.createPathCalculator(network, td, tt);
			for (double departure : DEPARTURES) {
				for (Node from : nodes) {
					for (Node to : nodes) {
						if (from == to) continue;
						queries++;
						LeastCostPathCalculator.Path path = ch.calcLeastCostPath(from, to, departure, null, null);
						String problem = routeProblem(path, from, to, departure, tt);
						if (problem != null) {
							failures++;
							if (shown.size() < 5) shown.add(scenario.name() + ", " + from.getId() + " -> " + to.getId() + " at " + departure + ": " + problem);
						}
					}
				}
			}
		}
		assertEquals(0, failures, failures + " of " + queries + " routes are inconsistent, e.g. " + shown);
	}

	/** {@code null} if the route is connected and its re-driven travel time equals the reported one, else a description. */
	private static String routeProblem(LeastCostPathCalculator.Path path, Node from, Node to, double departure, TravelTime tt) {
		if (path == null) return "no path";
		Node at = from;
		double time = departure;
		for (Link link : path.links) {
			if (link.getFromNode() != at) return "not connected at " + link.getId();
			time += tt.getLinkTravelTime(link, time, null, null);
			at = link.getToNode();
		}
		if (at != to) return "ends at " + at.getId();
		double driven = time - departure;
		return Math.abs(driven - path.travelTime) <= TOLERANCE ? null : String.format("reported %.1f s, re-driven %.1f s", path.travelTime, driven);
	}

	/**
	 * Simulations run past midnight (Hamburg: until 30:00), and travel times after 24:00 are their own time bins,
	 * not a copy of the early morning. A jam that exists only between 24:00 and 26:00 must be seen by trips that
	 * depart then, and must not affect trips at 00:00-02:00.
	 *
	 * <pre>{@code
	 *   delay on jammed links
	 *   600 s |                                              +--------+
	 *     0 s +----------------------------------------------+        +----
	 *         0     01:00                              24:00  25:00  26:00  30:00
	 *               ^ departure (must be free)                ^ departure (must see the jam)
	 * }</pre>
	 */
	@Test
	void travelTimesAfterMidnightAreNotWrappedToTheMorning() {
		Network network = createNetwork();
		List<Node> nodes = new ArrayList<>(network.getNodes().values());
		Set<Id<Link>> jammed = new HashSet<>();
		for (Link l : scenarios(network).get(4).jammed()) jammed.add(l.getId()); // "30% of all links"
		TravelTime tt = (link, time, person, vehicle) -> link.getLength() / link.getFreespeed()
				+ (jammed.contains(link.getId()) && time >= 24 * HOUR && time < 26 * HOUR ? JAM_DELAY : 0.0);
		TravelDisutility td = new OnlyTimeDependentTravelDisutility(tt);
		CHRouterFactory factory = new CHRouterFactory();
		factory.notifyIterationStarts(null);
		LeastCostPathCalculator ch = factory.createPathCalculator(network, td, tt);
		LeastCostPathCalculator oracle = new SpeedyDijkstraFactory().createPathCalculator(network, td, tt);
		long queries = 0, failures = 0;
		Map<Double, Long> failuresByDeparture = new java.util.TreeMap<>();
		for (double departure : new double[] {1 * HOUR, 25 * HOUR}) {
			for (Node from : nodes) {
				for (Node to : nodes) {
					if (from == to) continue;
					queries++;
					double expected = oracle.calcLeastCostPath(from, to, departure, null, null).travelCost;
					LeastCostPathCalculator.Path actual = ch.calcLeastCostPath(from, to, departure, null, null);
					if (actual == null || Math.abs(actual.travelCost - expected) > TOLERANCE) {
						failures++;
						failuresByDeparture.merge(departure / HOUR, 1L, Long::sum);
					}
				}
			}
		}
		assertEquals(0, failures, failures + " of " + queries + " queries differ from the exact result, by departure hour: " + failuresByDeparture);
	}

	private static List<Id<Link>> ids(LeastCostPathCalculator.Path path) {
		List<Id<Link>> out = new ArrayList<>();
		if (path != null) for (Link l : path.links) out.add(l.getId());
		return out;
	}
}
