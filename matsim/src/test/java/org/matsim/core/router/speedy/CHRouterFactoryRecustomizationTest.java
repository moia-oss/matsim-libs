package org.matsim.core.router.speedy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.vehicles.Vehicle;

/**
 * {@link CHRouterFactory} routes on live travel times, but its A* potentials come from lower bounds computed once per
 * iteration. If a link gets faster than its bound, the bound overestimates and A* can miss the faster route, so the
 * bounds must be recomputed when a new iteration starts.
 *
 * <p>The network is a 12x12 grid of 100 m links at 10 m/s (10 s per link), in both directions.
 */
public class CHRouterFactoryRecustomizationTest {

	private static final int GRID = 12;
	private static final double FREE_TIME = 10.0; // seconds per link
	private static final double JAM_TIME = 1000.0;

	/** Free-speed times plus per-link extra delay that the test can change between "iterations". */
	static class MutableTravelTime implements TravelTime {
		final Map<Id<Link>, Double> extra = new HashMap<>();

		@Override
		public double getLinkTravelTime(Link link, double time, Person person, Vehicle vehicle) {
			return link.getLength() / link.getFreespeed() + extra.getOrDefault(link.getId(), 0.0);
		}
	}

	private static Network createGrid() {
		Network network = NetworkUtils.createNetwork();
		Node[][] nodes = new Node[GRID][GRID];
		for (int x = 0; x < GRID; x++) {
			for (int y = 0; y < GRID; y++) {
				nodes[x][y] = NetworkUtils.createAndAddNode(network, Id.createNodeId("n" + x + "_" + y), new org.matsim.api.core.v01.Coord(x * 100.0, y * 100.0));
			}
		}
		for (int x = 0; x < GRID; x++) {
			for (int y = 0; y < GRID; y++) {
				if (x + 1 < GRID) addPair(network, nodes[x][y], nodes[x + 1][y]);
				if (y + 1 < GRID) addPair(network, nodes[x][y], nodes[x][y + 1]);
			}
		}
		return network;
	}

	private static void addPair(Network network, Node a, Node b) {
		for (Node[] ends : new Node[][] {{a, b}, {b, a}}) {
			Link link = NetworkUtils.createAndAddLink(network, Id.createLinkId(ends[0].getId() + "->" + ends[1].getId()), ends[0], ends[1], 100.0, 10.0, 1800.0, 1.0);
			link.setAllowedModes(java.util.Set.of("car"));
		}
	}

	/** Number of links of the route between the end nodes of {@code link}: 1 means it goes straight over the link. */
	private static int routeLinkCount(LeastCostPathCalculator router, Link link) {
		return router.calcLeastCostPath(link.getFromNode(), link.getToNode(), 0.0, null, null).links.size();
	}

	/**
	 * In iteration 0 link {@code n1_0->n2_0} is jammed, so the bypass {@code n0_0->n3_0} (35 s) is fastest and the
	 * bounds say the grid route costs at least 50 s. In iteration 1 the jam is gone: the grid route takes 30 s.
	 *
	 * <pre>{@code
	 *        bypass n0_0 -> n3_0, 35 s
	 *     o-----------------------------o
	 *     |                             |
	 *   n0_0 o-------o-------o-------o n3_0      grid route: 3 links, 30 s
	 *              n1_0 --X-- n2_0               X: jammed (+1000 s) in iteration 0 only
	 * }</pre>
	 *
	 * With the iteration-0 bounds, A* would reach n3_0 over the bypass (key 35) before expanding n1_0 (key 10 + 40),
	 * and return 35 s.
	 */
	@Test
	void linkThatGotFasterIsUsedAfterIterationStart() {
		Network network = createGridWithBypass();
		Node from = network.getNodes().get(Id.createNodeId("n0_0"));
		Node to = network.getNodes().get(Id.createNodeId("n3_0"));
		MutableTravelTime tt = new MutableTravelTime();
		TravelDisutility td = new OnlyTimeDependentTravelDisutility(tt);
		CHRouterFactory factory = new CHRouterFactory();

		Id<Link> jammed = Id.createLinkId("n1_0->n2_0");
		tt.extra.put(jammed, JAM_TIME);
		factory.notifyIterationStarts(null); // iteration 0
		assertEquals(35.0, factory.createPathCalculator(network, td, tt).calcLeastCostPath(from, to, 0.0, null, null).travelTime, 1e-9);

		tt.extra.remove(jammed);
		factory.notifyIterationStarts(null); // iteration 1
		assertEquals(30.0, factory.createPathCalculator(network, td, tt).calcLeastCostPath(from, to, 0.0, null, null).travelTime, 1e-9);
	}

	/**
	 * Two travel times of one class with different values (as two DRT modes with different speed caps, wrapped by the
	 * same lambda) must not share costs: the one without the jam takes the grid route (30 s), not the bypass (35 s).
	 */
	@Test
	void travelTimesOfOneClassWithDifferentValuesGetTheirOwnCosts() {
		Network network = createGridWithBypass();
		Node from = network.getNodes().get(Id.createNodeId("n0_0"));
		Node to = network.getNodes().get(Id.createNodeId("n3_0"));
		MutableTravelTime jammed = new MutableTravelTime();
		jammed.extra.put(Id.createLinkId("n1_0->n2_0"), JAM_TIME);
		MutableTravelTime free = new MutableTravelTime();
		CHRouterFactory factory = new CHRouterFactory();
		factory.notifyIterationStarts(null);

		double jammedTime = factory.createPathCalculator(network, new OnlyTimeDependentTravelDisutility(jammed), jammed)
				.calcLeastCostPath(from, to, 0.0, null, null).travelTime;
		double freeTime = factory.createPathCalculator(network, new OnlyTimeDependentTravelDisutility(free), free)
				.calcLeastCostPath(from, to, 0.0, null, null).travelTime;

		assertEquals(35.0, jammedTime, 1e-9);
		assertEquals(30.0, freeTime, 1e-9);
		assertEquals(2, factory.distinctCustomizations());
	}

	/** New travel time objects with the same values (as MATSim's per-injection wrappers) share one customization. */
	@Test
	void travelTimesWithEqualValuesShareOneCustomization() {
		Network network = createGrid();
		CHRouterFactory factory = new CHRouterFactory();
		factory.notifyIterationStarts(null);
		for (int i = 0; i < 3; i++) {
			MutableTravelTime tt = new MutableTravelTime();
			factory.createPathCalculator(network, new OnlyTimeDependentTravelDisutility(tt), tt);
		}
		assertEquals(1, factory.distinctCustomizations());
	}

	/** The grid plus a bypass n0_0 -> n3_0 of 35 s, against 30 s over the three grid links. */
	private static Network createGridWithBypass() {
		Network network = createGrid();
		Link bypass = NetworkUtils.createAndAddLink(network, Id.createLinkId("bypass"), network.getNodes().get(Id.createNodeId("n0_0")),
				network.getNodes().get(Id.createNodeId("n3_0")), 350.0, 10.0, 1800.0, 1.0);
		bypass.setAllowedModes(java.util.Set.of("car"));
		return network;
	}

	/**
	 * With one jammed link, the route must be the optimal three-link detour. The original CH returned a five-link
	 * detour (50 s) although a three-link one (30 s) exists, which SpeedyALT finds: its contraction had pruned the
	 * shortcut with free-flow times.
	 *
	 * <pre>{@code
	 *   y=2  n0_2 o-------o n1_2      CH (wrong, 50 s):  n0_0 n0_1 n0_2 n1_2 n1_1 n1_0
	 *             |       |           exact (30 s):      n0_0 n0_1 n1_1 n1_0
	 *   y=1  n0_1 o-------o n1_1
	 *             |       |
	 *   y=0  n0_0 o---X---o n1_0      X = jammed link n0_0->n1_0
	 *            x=0     x=1
	 * }</pre>
	 */
	@Test
	void routeAfterJamIsOptimal() {
		Network network = createGrid();
		MutableTravelTime tt = new MutableTravelTime();
		TravelDisutility td = new OnlyTimeDependentTravelDisutility(tt);
		Link victim = network.getLinks().get(Id.createLinkId("n0_0->n1_0"));
		tt.extra.put(victim.getId(), JAM_TIME);
		CHRouterFactory factory = new CHRouterFactory();
		factory.notifyIterationStarts(null);
		assertEquals(3, routeLinkCount(factory.createPathCalculator(network, td, tt), victim));
	}
}
