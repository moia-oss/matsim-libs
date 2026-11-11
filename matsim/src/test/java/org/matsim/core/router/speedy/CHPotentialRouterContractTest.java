package org.matsim.core.router.speedy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.groups.TravelTimeCalculatorConfigGroup;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.router.speedy.CHExactnessUnderJamsTest.JamTravelTime;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.LeastCostPathCalculator.Path;
import org.matsim.core.router.util.LeastCostPathUtils.NoPathBehavior;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.vehicles.Vehicle;

/**
 * The parts of {@link CHPotentialRouter} that a simulation uses and {@link CHExactnessUnderJamsTest} does not
 * reach, each compared with an exact Dijkstra on the same network and travel times:
 *
 * <ul>
 *   <li>the link-to-link queries DRT calls, with a cost limit and with the no-path behaviours;</li>
 *   <li>a disutility that needs a person (randomized routing), whose bound is its minimum disutility;</li>
 *   <li>networks with turn restrictions, for point-to-point queries (from nodes and from links) and for
 *       {@link CHPotentialTree}, forward and backward.</li>
 * </ul>
 *
 * Networks are small and the query sets fixed, so the test costs well under a second.
 */
public class CHPotentialRouterContractTest {

	private static final double TOLERANCE = CHExactnessUnderJamsTest.TOLERANCE;
	private static final TravelTimeCalculatorConfigGroup BINS = new TravelTimeCalculatorConfigGroup();

	private static CHPotentialRouter router(Network network, TravelTime tt, TravelDisutility td) {
		SpeedyGraph graph = CCHPotentials.baseGraph(network);
		return new CHPotentialRouter(CCHPotentials.customize(graph, CCHPotentials.contraction(graph, td), tt, td,
				BINS.getTraveltimeBinSize(), BINS.getMaxTime()), tt, td);
	}

	private static LeastCostPathCalculator exact(Network network, TravelTime tt, TravelDisutility td) {
		return new SpeedyDijkstraFactory().createPathCalculator(network, td, tt);
	}

	/** The jam network with a delay on every fourth link, all day. */
	private static JamTravelTime jams(Network network) {
		JamTravelTime tt = new JamTravelTime();
		List<Link> links = new ArrayList<>(network.getLinks().values());
		links.sort((a, b) -> a.getId().toString().compareTo(b.getId().toString()));
		for (int i = 0; i < links.size(); i += 4) tt.delay.put(links.get(i).getId(), CHExactnessUnderJamsTest.JAM_DELAY);
		return tt;
	}

	/** Both routers find the same cost, or both find no path; returns whether a path exists. */
	private static boolean sameResult(Path expected, Path actual, String query) {
		assertEquals(expected == null, actual == null, query + ": Dijkstra " + (expected == null ? "finds no path" : "finds a path of cost " + expected.travelCost)
				+ ", CH-Potentials " + (actual == null ? "find none" : "find one of cost " + actual.travelCost));
		if (expected == null) return false;
		assertEquals(expected.travelCost, actual.travelCost, TOLERANCE, query);
		return true;
	}

	private static List<Link> sortedLinks(Network network) {
		List<Link> links = new ArrayList<>(network.getLinks().values());
		links.sort((a, b) -> a.getId().toString().compareTo(b.getId().toString()));
		return links;
	}

	@Test
	void linkToLinkQueriesAreExactAndStopAtTheCostLimit() {
		Network network = CHExactnessUnderJamsTest.createNetwork();
		JamTravelTime tt = jams(network);
		TravelDisutility td = new OnlyTimeDependentTravelDisutility(tt);
		CHPotentialRouter ch = router(network, tt, td);
		LeastCostPathCalculator dijkstra = exact(network, tt, td);
		List<Link> links = sortedLinks(network);
		Random random = new Random(11);
		int limited = 0;

		for (int i = 0; i < 150; i++) {
			Link from = links.get(random.nextInt(links.size())), to = links.get(random.nextInt(links.size()));
			double departure = CHExactnessUnderJamsTest.DEPARTURES[random.nextInt(CHExactnessUnderJamsTest.DEPARTURES.length)];
			Path expected = dijkstra.calcLeastCostPath(from, to, departure, null, null);

			Path actual = ch.calcLeastCostPath(from, to, departure, null, null);
			assertEquals(expected.travelCost, actual.travelCost, TOLERANCE, from.getId() + " -> " + to.getId());
			assertEquals(expected.travelTime, actual.travelTime, TOLERANCE);
			assertEquals(from.getToNode(), actual.nodes.getFirst(), "a link-to-link path starts at the end of the first link");
			assertEquals(to.getFromNode(), actual.nodes.getLast(), "and ends at the start of the last");

			// the same query with a limit just above the optimum is answered, just below it is not
			Path within = ch.calcLeastCostPath(from, to, departure, null, null, expected.travelCost + 1e-3);
			assertNotNull(within);
			assertEquals(expected.travelCost, within.travelCost, TOLERANCE);
			if (expected.travelCost > 1) {
				ch.setNoPathBehavior(NoPathBehavior.none);
				assertNull(ch.calcLeastCostPath(from, to, departure, null, null, expected.travelCost - 1), "limit below the optimum");
				limited++;
			}
		}
		assertTrue(limited > 100, "the limit was exercised only " + limited + " times");
	}

	@Test
	void anUnreachableTargetFollowsTheNoPathBehaviour() {
		Network network = NetworkUtils.createNetwork();
		Node a = NetworkUtils.createAndAddNode(network, Id.createNodeId("a"), new Coord(0, 0));
		Node b = NetworkUtils.createAndAddNode(network, Id.createNodeId("b"), new Coord(100, 0));
		Node c = NetworkUtils.createAndAddNode(network, Id.createNodeId("c"), new Coord(200, 0));
		Link ab = NetworkUtils.createAndAddLink(network, Id.createLinkId("ab"), a, b, 100, 10, 1000, 1);
		Link bc = NetworkUtils.createAndAddLink(network, Id.createLinkId("bc"), b, c, 100, 10, 1000, 1);
		TravelTime tt = (link, time, person, vehicle) -> link.getLength() / link.getFreespeed();
		CHPotentialRouter ch = router(network, tt, new OnlyTimeDependentTravelDisutility(tt));

		assertNotNull(ch.calcLeastCostPath(a, c, 0, null, null), "the way along the line exists");
		ch.setNoPathBehavior(NoPathBehavior.none);
		assertNull(ch.calcLeastCostPath(c, a, 0, null, null), "against the one-way line there is no path");
		assertNull(ch.calcLeastCostPath(bc, ab, 0, null, null));
		ch.setNoPathBehavior(NoPathBehavior.warning);
		assertNull(ch.calcLeastCostPath(c, a, 0, null, null), "a warning is logged and null is returned");
		ch.setNoPathBehavior(NoPathBehavior.exception);
		assertThrows(RuntimeException.class, () -> ch.calcLeastCostPath(c, a, 0, null, null));
		assertThrows(RuntimeException.class, () -> ch.calcLeastCostPath(bc, ab, 0, null, null));
	}

	/**
	 * Travel time times a factor of 1 to 1.75 that depends on the person and the link, as randomized routing does;
	 * like MATSim's randomizing disutility it refuses to be evaluated without a person. The factor is never below 1,
	 * so the free-flow time is a valid minimum.
	 */
	private static final class PersonDependent implements TravelDisutility {
		private final TravelTime tt;

		PersonDependent(TravelTime tt) {
			this.tt = tt;
		}

		@Override
		public double getLinkTravelDisutility(Link link, double time, Person person, Vehicle vehicle) {
			int factor = (person.getId().hashCode() * 31 + link.getId().hashCode()) & 3;
			return tt.getLinkTravelTime(link, time, person, vehicle) * (1 + 0.25 * factor);
		}

		@Override
		public double getLinkMinimumTravelDisutility(Link link) {
			return link.getLength() / link.getFreespeed();
		}
	}

	@Test
	void aDisutilityThatNeedsAPersonIsRoutedExactly() {
		Network network = CHExactnessUnderJamsTest.createNetwork();
		JamTravelTime tt = jams(network);
		TravelDisutility td = new PersonDependent(tt);
		CHPotentialRouter ch = router(network, tt, td);
		LeastCostPathCalculator dijkstra = exact(network, tt, td);
		List<Node> nodes = new ArrayList<>(network.getNodes().values());
		nodes.sort((x, y) -> x.getId().toString().compareTo(y.getId().toString()));
		Random random = new Random(5);
		int differentFromPlainTime = 0;

		for (int i = 0; i < 300; i++) {
			Person person = PopulationUtils.getFactory().createPerson(Id.createPersonId("p" + random.nextInt(7)));
			Node from = nodes.get(random.nextInt(nodes.size())), to = nodes.get(random.nextInt(nodes.size()));
			double departure = CHExactnessUnderJamsTest.DEPARTURES[random.nextInt(CHExactnessUnderJamsTest.DEPARTURES.length)];
			Path expected = dijkstra.calcLeastCostPath(from, to, departure, person, null);
			Path actual = ch.calcLeastCostPath(from, to, departure, person, null);
			assertEquals(expected.travelCost, actual.travelCost, TOLERANCE, person.getId() + " " + from.getId() + " -> " + to.getId());
			if (!expected.links.equals(exact(network, tt, new OnlyTimeDependentTravelDisutility(tt)).calcLeastCostPath(from, to, departure, null, null).links)) {
				differentFromPlainTime++;
			}
		}
		assertTrue(differentFromPlainTime > 10, "the person-dependent costs must change routes, else the test proves nothing: " + differentFromPlainTime);
	}

	/**
	 * A 5 x 5 grid of two-way links with uneven lengths, no U-turns, and eight more banned two-link sequences, so that
	 * several nodes have colored copies. The network stays strongly connected.
	 */
	private static Network gridWithTurnRestrictions() {
		Network network = NetworkUtils.createNetwork();
		int size = 5;
		Node[][] node = new Node[size][size];
		for (int x = 0; x < size; x++) {
			for (int y = 0; y < size; y++) node[x][y] = NetworkUtils.createAndAddNode(network, Id.createNodeId(x + "_" + y), new Coord(x * 100, y * 100));
		}
		Random random = new Random(21);
		for (int x = 0; x < size; x++) {
			for (int y = 0; y < size; y++) {
				if (x + 1 < size) pair(network, node[x][y], node[x + 1][y], random);
				if (y + 1 < size) pair(network, node[x][y], node[x][y + 1], random);
			}
		}
		for (Link link : network.getLinks().values()) {
			Link back = link.getToNode().getOutLinks().values().stream().filter(l -> l.getToNode() == link.getFromNode()).findFirst().orElseThrow();
			NetworkUtils.getOrCreateDisallowedNextLinks(link).addDisallowedLinkSequence("car", List.of(back.getId())); // no U-turn
		}
		List<Link> links = sortedLinks(network);
		for (int banned = 0; banned < 8; ) {
			Link first = links.get(random.nextInt(links.size()));
			List<Link> next = new ArrayList<>(first.getToNode().getOutLinks().values());
			Link second = next.get(random.nextInt(next.size()));
			if (second.getToNode() == first.getFromNode()) continue;
			NetworkUtils.getOrCreateDisallowedNextLinks(first).addDisallowedLinkSequence("car", List.of(second.getId()));
			banned++;
		}
		for (Link link : network.getLinks().values()) link.setAllowedModes(java.util.Set.of("car"));
		return network;
	}

	private static void pair(Network network, Node a, Node b, Random random) {
		double length = 100 + random.nextInt(100);
		NetworkUtils.createAndAddLink(network, Id.createLinkId(a.getId() + ">" + b.getId()), a, b, length, 10, 1000, 1);
		NetworkUtils.createAndAddLink(network, Id.createLinkId(b.getId() + ">" + a.getId()), b, a, length, 10, 1000, 1);
	}

	@Test
	void turnRestrictionsAreRespectedByQueriesAndTrees() {
		Network network = gridWithTurnRestrictions();
		TravelTime tt = (link, time, person, vehicle) -> link.getLength() / link.getFreespeed();
		TravelDisutility td = new OnlyTimeDependentTravelDisutility(tt);
		SpeedyGraph graph = CCHPotentials.baseGraph(network);
		assertTrue(graph.getTurnRestrictions().isPresent(), "the network must be built with turn restrictions");
		CHPotentialRouter ch = router(network, tt, td);
		LeastCostPathCalculator dijkstra = exact(network, tt, td);
		List<Link> links = sortedLinks(network);
		List<Node> nodes = new ArrayList<>(network.getNodes().values());
		nodes.sort((x, y) -> x.getId().toString().compareTo(y.getId().toString()));

		// every origin-destination pair, from nodes and from links; a restricted network may have unreachable pairs
		ch.setNoPathBehavior(NoPathBehavior.none);
		((SpeedyDijkstra) dijkstra).setNoPathBehavior(NoPathBehavior.none);
		int found = 0, unreachable = 0;
		for (Node from : nodes) {
			for (Node to : nodes) {
				if (from == to) continue;
				if (sameResult(dijkstra.calcLeastCostPath(from, to, 0, null, null), ch.calcLeastCostPath(from, to, 0, null, null), from.getId() + " -> " + to.getId())) found++;
				else unreachable++;
			}
		}
		for (Link from : links) {
			for (Link to : links) {
				if (from == to) continue;
				Path expected = dijkstra.calcLeastCostPath(from, to, 0, null, null), actual = ch.calcLeastCostPath(from, to, 0, null, null);
				if (!sameResult(expected, actual, from.getId() + " -> " + to.getId())) {
					unreachable++;
					continue;
				}
				for (int i = 0; i + 1 < actual.links.size(); i++) {
					assertTrue(actual.links.get(i + 1).getToNode() != actual.links.get(i).getFromNode(), "U-turn in " + actual.links);
				}
				found++;
			}
		}
		assertTrue(found > 500, "too few reachable pairs to be meaningful: " + found + " found, " + unreachable + " unreachable");

		// the one-to-many tree, forward and backward, against the plain tree on the same restricted graph
		LeastCostPathTree reference = new LeastCostPathTree(graph, tt, td);
		BINS.getTraveltimeBinSize();
		CCHPotentials potentials = CCHPotentials.customize(graph, CCHPotentials.contraction(graph, td), td, BINS.getTraveltimeBinSize(), BINS.getMaxTime());
		CHPotentialTree tree = new CHPotentialTree(potentials, tt, td);
		Random random = new Random(9);
		for (Link start : links) {
			List<Node> targets = new ArrayList<>(nodes);
			java.util.Collections.shuffle(targets, random);
			targets = targets.subList(0, 6);
			reference.calculate(start, 0, null, null);
			tree.calculateToTargets(start, 0, null, null, targets, null);
			for (Node t : targets) {
				assertEquals(reference.getCost(graph.getNodeIndex(t)), tree.getCost(graph.getNodeIndex(t)), TOLERANCE, "forward from " + start.getId() + " to " + t.getId());
			}
			reference.calculateBackwards(start, 0, null, null);
			tree.calculateBackwardsToTargets(start, 0, null, null, targets, null);
			for (Node t : targets) {
				assertEquals(reference.getCost(graph.getNodeIndex(t)), tree.getCost(graph.getNodeIndex(t)), TOLERANCE, "backward to " + start.getId() + " from " + t.getId());
			}
		}
	}

	@Test
	void customizationRejectsAPlainContractionAndNonPositiveBins() {
		Network network = CHExactnessUnderJamsTest.createNetwork();
		TravelDisutility td = new OnlyTimeDependentTravelDisutility(new JamTravelTime());
		SpeedyGraph graph = CCHPotentials.baseGraph(network);
		CHGraph plain = new CHBuilder(graph, td).build(); // not built by customizable contraction
		CHGraph cch = CCHPotentials.contraction(graph, td);
		double bin = BINS.getTraveltimeBinSize(), horizon = BINS.getMaxTime();

		assertThrows(IllegalStateException.class, () -> CCHPotentials.customize(graph, plain, td, bin, horizon));
		for (double[] invalid : new double[][] {{0, horizon}, {bin, 0}, {-bin, horizon}}) {
			IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> CCHPotentials.customize(graph, cch, td, invalid[0], invalid[1]));
			assertTrue(e.getMessage().contains("must be positive"), "rejected for another reason: " + e.getMessage());
		}
	}

	@Test
	void theTreeAlsoAnswersFullTreeQueries() {
		Network network = CHExactnessUnderJamsTest.createNetwork();
		JamTravelTime tt = jams(network);
		TravelDisutility td = new OnlyTimeDependentTravelDisutility(tt);
		SpeedyGraph graph = CCHPotentials.baseGraph(network);
		CHPotentialTree tree = new CHPotentialTree(CCHPotentials.customize(graph, CCHPotentials.contraction(graph, td), tt, td,
				BINS.getTraveltimeBinSize(), BINS.getMaxTime()), tt, td);
		LeastCostPathTree reference = new LeastCostPathTree(graph, tt, td);
		Link start = sortedLinks(network).get(17);

		tree.calculate(start, 7 * 3600, null, null);
		reference.calculate(start, 7 * 3600, null, null);
		for (Node n : network.getNodes().values()) {
			assertEquals(reference.getCost(graph.getNodeIndex(n)), tree.getCost(graph.getNodeIndex(n)), TOLERANCE, "forward, node " + n.getId());
		}
		tree.calculateBackwards(start, 7 * 3600, null, null);
		reference.calculateBackwards(start, 7 * 3600, null, null);
		for (Node n : network.getNodes().values()) {
			assertEquals(reference.getCost(graph.getNodeIndex(n)), tree.getCost(graph.getNodeIndex(n)), TOLERANCE, "backward, node " + n.getId());
		}
	}
}
