package org.matsim.core.router.speedy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.function.BiConsumer;

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
 * What a simulation calls on {@link CHPotentialRouter} and {@link CHExactnessUnderJamsTest} does not reach, each against
 * an exact Dijkstra on the same network and travel times: link-to-link queries (cost limit, no-path behaviour), a
 * disutility that needs a person, turn restrictions (queries and one-to-many trees), and argument validation.
 */
public class CHPotentialRouterContractTest {

	private static final double TOLERANCE = CHExactnessUnderJamsTest.TOLERANCE;
	private static final double[] DEPARTURES = CHExactnessUnderJamsTest.DEPARTURES;
	private static final TravelTimeCalculatorConfigGroup BINS = new TravelTimeCalculatorConfigGroup();

	/** A network with its travel times and disutility, the CH-Potentials built on them, and the exact router to compare with. */
	private record Setup(Network network, TravelTime tt, TravelDisutility td, SpeedyGraph graph, CHPotentialRouter ch,
			LeastCostPathCalculator dijkstra, List<Node> nodes, List<Link> links) {

		static Setup of(Network network, TravelTime tt, TravelDisutility td) {
			SpeedyGraph graph = CCHPotentials.baseGraph(network);
			CHPotentialRouter ch = new CHPotentialRouter(potentials(graph, tt, td), tt, td);
			SpeedyDijkstra dijkstra = (SpeedyDijkstra) new SpeedyDijkstraFactory().createPathCalculator(network, td, tt);
			ch.setNoPathBehavior(NoPathBehavior.none);
			dijkstra.setNoPathBehavior(NoPathBehavior.none);
			List<Node> nodes = new ArrayList<>(network.getNodes().values());
			List<Link> links = new ArrayList<>(network.getLinks().values());
			nodes.sort(Comparator.comparing(n -> n.getId().toString()));
			links.sort(Comparator.comparing(l -> l.getId().toString()));
			return new Setup(network, tt, td, graph, ch, dijkstra, nodes, links);
		}

		/** The jam network with a delay on every fourth link, all day, and plain travel time as the cost. */
		static Setup jammed() {
			Network network = CHExactnessUnderJamsTest.createNetwork();
			JamTravelTime tt = new JamTravelTime();
			List<Link> links = new ArrayList<>(network.getLinks().values());
			links.sort(Comparator.comparing(l -> l.getId().toString()));
			for (int i = 0; i < links.size(); i += 4) tt.delay.put(links.get(i).getId(), CHExactnessUnderJamsTest.JAM_DELAY);
			return of(network, tt, new OnlyTimeDependentTravelDisutility(tt));
		}

		/** Both routers find the same cost and time, or both find no path; returns whether a path exists. */
		boolean sameResult(Path expected, Path actual, String query) {
			assertEquals(expected == null, actual == null, query + ": Dijkstra " + (expected == null ? "finds no path" : "finds a path of cost " + expected.travelCost)
					+ ", CH-Potentials " + (actual == null ? "find none" : "find one of cost " + actual.travelCost));
			if (expected == null) return false;
			assertEquals(expected.travelCost, actual.travelCost, TOLERANCE, query);
			assertEquals(expected.travelTime, actual.travelTime, TOLERANCE, query);
			return true;
		}

		CHPotentialTree tree() {
			return new CHPotentialTree(potentials(graph, tt, td), tt, td);
		}
	}

	private static CCHPotentials potentials(SpeedyGraph graph, TravelTime tt, TravelDisutility td) {
		return CCHPotentials.customize(graph, CCHPotentials.contraction(graph, td), tt, td, BINS.getTraveltimeBinSize(), BINS.getMaxTime());
	}

	private static <T> T pick(List<T> list, Random random) {
		return list.get(random.nextInt(list.size()));
	}

	/** All ordered pairs of distinct elements. */
	private static <T> void forEachPair(List<T> items, BiConsumer<T, T> action) {
		for (T from : items) {
			for (T to : items) if (from != to) action.accept(from, to);
		}
	}

	@Test
	void linkToLinkQueriesAreExactAndStopAtTheCostLimit() {
		Setup s = Setup.jammed();
		Random random = new Random(11);
		int limited = 0;

		for (int i = 0; i < 150; i++) {
			Link from = pick(s.links, random), to = pick(s.links, random);
			double departure = pick(java.util.Arrays.stream(DEPARTURES).boxed().toList(), random);
			Path expected = s.dijkstra.calcLeastCostPath(from, to, departure, null, null);

			Path actual = s.ch.calcLeastCostPath(from, to, departure, null, null);
			assertTrue(s.sameResult(expected, actual, from.getId() + " -> " + to.getId()));
			assertEquals(from.getToNode(), actual.nodes.getFirst(), "a link-to-link path starts at the end of the first link");
			assertEquals(to.getFromNode(), actual.nodes.getLast(), "and ends at the start of the last");

			// the same query with a limit just above the optimum is answered, just below it is not
			Path within = s.ch.calcLeastCostPath(from, to, departure, null, null, expected.travelCost + 1e-3);
			assertNotNull(within);
			assertEquals(expected.travelCost, within.travelCost, TOLERANCE);
			if (expected.travelCost > 1) {
				assertNull(s.ch.calcLeastCostPath(from, to, departure, null, null, expected.travelCost - 1), "limit below the optimum");
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
		CHPotentialRouter ch = Setup.of(network, tt, new OnlyTimeDependentTravelDisutility(tt)).ch();

		assertNotNull(ch.calcLeastCostPath(a, c, 0, null, null), "the way along the line exists");
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
	private record PersonDependent(TravelTime tt) implements TravelDisutility {
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
		Setup plain = Setup.jammed();
		Setup s = Setup.of(plain.network(), plain.tt(), new PersonDependent(plain.tt()));
		Random random = new Random(5);
		int differentFromPlainTime = 0;

		for (int i = 0; i < 300; i++) {
			Person person = PopulationUtils.getFactory().createPerson(Id.createPersonId("p" + random.nextInt(7)));
			Node from = pick(s.nodes, random), to = pick(s.nodes, random);
			double departure = DEPARTURES[random.nextInt(DEPARTURES.length)];
			Path expected = s.dijkstra.calcLeastCostPath(from, to, departure, person, null);
			s.sameResult(expected, s.ch.calcLeastCostPath(from, to, departure, person, null), person.getId() + " " + from.getId() + " -> " + to.getId());
			if (!expected.links.equals(plain.dijkstra.calcLeastCostPath(from, to, departure, null, null).links)) differentFromPlainTime++;
		}
		assertTrue(differentFromPlainTime > 10, "the person-dependent costs must change routes, else the test proves nothing: " + differentFromPlainTime);
	}

	/** Bans every U-turn and eight more two-link sequences, so that several nodes get colored copies. */
	private static void addTurnRestrictions(Network network, List<Link> links, Random random) {
		for (Link link : links) {
			Link back = link.getToNode().getOutLinks().values().stream().filter(l -> l.getToNode() == link.getFromNode()).findFirst().orElseThrow();
			NetworkUtils.getOrCreateDisallowedNextLinks(link).addDisallowedLinkSequence("car", List.of(back.getId()));
		}
		for (int banned = 0; banned < 8; ) {
			Link first = pick(links, random), second = pick(new ArrayList<>(first.getToNode().getOutLinks().values()), random);
			if (second.getToNode() == first.getFromNode()) continue;
			NetworkUtils.getOrCreateDisallowedNextLinks(first).addDisallowedLinkSequence("car", List.of(second.getId()));
			banned++;
		}
	}

	/** The tree gives the same cost to every target as the full tree on the same graph, started at {@code start}. */
	private static void assertTargetsMatch(Setup s, LeastCostPathTree reference, CHPotentialTree tree, Link start, List<Node> targets, boolean forward, String what) {
		if (forward) {
			reference.calculate(start, 0, null, null);
			tree.calculateToTargets(start, 0, null, null, targets, null);
		} else {
			reference.calculateBackwards(start, 0, null, null);
			tree.calculateBackwardsToTargets(start, 0, null, null, targets, null);
		}
		for (Node t : targets) {
			int i = s.graph().getNodeIndex(t);
			assertEquals(reference.getCost(i), tree.getCost(i), TOLERANCE, (forward ? "forward from " : "backward to ") + start.getId() + ", " + what + " " + t.getId());
		}
	}

	@Test
	void turnRestrictionsAreRespectedByQueriesAndTrees() {
		Network network = CHExactnessUnderJamsTest.createNetwork();
		Random random = new Random(21);
		List<Link> sorted = new ArrayList<>(network.getLinks().values());
		sorted.sort(Comparator.comparing(l -> l.getId().toString()));
		addTurnRestrictions(network, sorted, random);
		TravelTime tt = (link, time, person, vehicle) -> link.getLength() / link.getFreespeed();
		Setup s = Setup.of(network, tt, new OnlyTimeDependentTravelDisutility(tt));
		assertTrue(s.graph().getTurnRestrictions().isPresent(), "the network must be built with turn restrictions");

		// every node pair, and 600 link pairs (a restricted network may have unreachable pairs)
		int[] found = {0};
		forEachPair(s.nodes(), (from, to) -> {
			if (s.sameResult(s.dijkstra().calcLeastCostPath(from, to, 0, null, null), s.ch().calcLeastCostPath(from, to, 0, null, null), from.getId() + " -> " + to.getId())) found[0]++;
		});
		for (int i = 0; i < 600; i++) {
			Link from = pick(s.links(), random), to = pick(s.links(), random);
			Path actual = s.ch().calcLeastCostPath(from, to, 0, null, null);
			if (!s.sameResult(s.dijkstra().calcLeastCostPath(from, to, 0, null, null), actual, from.getId() + " -> " + to.getId())) continue;
			for (int k = 0; k + 1 < actual.links.size(); k++) assertTrue(actual.links.get(k + 1).getToNode() != actual.links.get(k).getFromNode(), "U-turn in " + actual.links);
			found[0]++;
		}
		assertTrue(found[0] > 3000, "too few reachable pairs to be meaningful: " + found[0]);

		// the one-to-many tree, forward and backward, against the full tree on the same restricted graph
		LeastCostPathTree reference = new LeastCostPathTree(s.graph(), tt, s.td());
		CHPotentialTree tree = s.tree();
		for (int i = 0; i < 40; i++) {
			Link start = pick(s.links(), random);
			List<Node> targets = new ArrayList<>(s.nodes());
			Collections.shuffle(targets, random);
			assertTargetsMatch(s, reference, tree, start, targets.subList(0, 6), true, "target");
			assertTargetsMatch(s, reference, tree, start, targets.subList(0, 6), false, "source");
		}
	}

	@Test
	void customizationRejectsAPlainContractionAndNonPositiveBins() {
		Setup s = Setup.jammed();
		CHGraph plain = new CHBuilder(s.graph(), s.td()).build(); // not built by customizable contraction
		CHGraph cch = CCHPotentials.contraction(s.graph(), s.td());
		double bin = BINS.getTraveltimeBinSize(), horizon = BINS.getMaxTime();

		assertThrows(IllegalStateException.class, () -> CCHPotentials.customize(s.graph(), plain, s.td(), bin, horizon));
		for (double[] invalid : new double[][] {{0, horizon}, {bin, 0}, {-bin, horizon}}) {
			IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> CCHPotentials.customize(s.graph(), cch, s.td(), invalid[0], invalid[1]));
			assertTrue(e.getMessage().contains("must be positive"), "rejected for another reason: " + e.getMessage());
		}
	}
}
