package org.matsim.core.router.speedy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.matsim.core.router.speedy.CHExactnessUnderJamsTest.DEPARTURES;
import static org.matsim.core.router.speedy.CHExactnessUnderJamsTest.JAM_DELAY;
import static org.matsim.core.router.speedy.CHExactnessUnderJamsTest.TOLERANCE;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.config.groups.TravelTimeCalculatorConfigGroup;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.router.speedy.CHExactnessUnderJamsTest.JamTravelTime;
import org.matsim.core.router.speedy.CHExactnessUnderJamsTest.Scenario;
import org.matsim.core.router.util.TravelDisutility;

/**
 * Exactness oracle for the one-to-many {@link CHPotentialTree} (DRT insertion searches), against
 * {@link LeastCostPathTree} on the jam scenarios of {@link CHExactnessUnderJamsTest}:
 *
 * <pre>{@code
 *   for every scenario, departure, source node and direction (forward / backward):
 *     reference: full LeastCostPathTree from the source
 *     tree:      CHPotentialTree to a target set, which stops once all targets are labelled
 *     every target: same cost and time, and the tree's link path leads from the source to it
 *
 *   target sets per source:  all other nodes  |  three random sets of five nodes
 * }</pre>
 *
 * Small target sets are where the goal direction matters: the potential is the distance to the nearest target, so
 * the search must still label far targets exactly after the near ones are done.
 */
public class CHPotentialTreeTest {

	@Test
	void targetLabelsMatchTheFullTree() {
		Network network = CHExactnessUnderJamsTest.createNetwork();
		SpeedyGraph graph = CCHPotentials.baseGraph(network);
		List<Node> nodes = new ArrayList<>(network.getNodes().values());
		nodes.sort((a, b) -> a.getId().toString().compareTo(b.getId().toString()));
		Random random = new Random(3);
		TravelTimeCalculatorConfigGroup bins = new TravelTimeCalculatorConfigGroup();
		long checked = 0;
		List<String> failures = new ArrayList<>();

		for (Scenario scenario : CHExactnessUnderJamsTest.scenarios(network)) {
			JamTravelTime tt = new JamTravelTime();
			tt.peakOnly = scenario.peakOnly();
			for (Link l : scenario.jammed()) tt.delay.put(l.getId(), JAM_DELAY);
			TravelDisutility td = new OnlyTimeDependentTravelDisutility(tt);
			CCHPotentials potentials = CCHPotentials.customize(graph, CCHPotentials.contraction(graph, td), td,
					bins.getTraveltimeBinSize(), bins.getMaxTime());
			LeastCostPathTree reference = new LeastCostPathTree(graph, tt, td);
			CHPotentialTree tree = new CHPotentialTree(potentials, tt, td);

			for (double departure : DEPARTURES) {
				for (Node source : nodes) {
					for (boolean forward : new boolean[] {true, false}) {
						// forward trees start at the end of a link into the source, backward ones at the start of a link out of it
						Link startLink = forward ? source.getInLinks().values().iterator().next() : source.getOutLinks().values().iterator().next();
						if (forward) reference.calculate(startLink, departure, null, null);
						else reference.calculateBackwards(startLink, departure, null, null);

						for (List<Node> targets : targetSets(nodes, source, random)) {
							if (forward) tree.calculateToTargets(startLink, departure, null, null, targets, null);
							else tree.calculateBackwardsToTargets(startLink, departure, null, null, targets, null);
							for (Node t : targets) {
								checked++;
								int i = graph.getNodeIndex(t);
								double expected = reference.getCost(i), actual = tree.getCost(i);
								String problem = Math.abs(expected - actual) > TOLERANCE ? String.format("cost %.3f vs %.3f", actual, expected)
										: Math.abs(reference.getTime(i).seconds() - tree.getTime(i).seconds()) > TOLERANCE ? "time differs"
										: pathProblem(tree, t, source, forward);
								if (problem != null && failures.size() < 8) {
									failures.add(String.format("%s, %s %s -> %s at %.0f s (%d targets): %s", scenario.name(), forward ? "forward" : "backward",
											source.getId(), t.getId(), departure, targets.size(), problem));
								}
								if (problem != null) failures.add(null);
							}
						}
					}
				}
			}
		}
		long failed = failures.stream().filter(s -> s == null).count();
		assertTrue(checked > 100_000, "too few checks to be meaningful: " + checked);
		assertEquals(0, failed, failed + " of " + checked + " target labels differ, e.g. " + failures.stream().filter(s -> s != null).toList());
	}

	private static List<List<Node>> targetSets(List<Node> nodes, Node source, Random random) {
		List<Node> others = new ArrayList<>(nodes);
		others.remove(source);
		List<List<Node>> sets = new ArrayList<>();
		sets.add(others);
		for (int i = 0; i < 3; i++) {
			List<Node> copy = new ArrayList<>(others);
			Collections.shuffle(copy, random);
			sets.add(copy.subList(0, 5));
		}
		return sets;
	}

	/** The tree's links, walked back from the target, must form a connected path that ends at the source. */
	private static String pathProblem(CHPotentialTree tree, Node target, Node source, boolean forward) {
		Node at = target;
		Iterator<Link> links = tree.getLinkPathIterator(target);
		while (links.hasNext()) {
			Link l = links.next();
			Node near = forward ? l.getToNode() : l.getFromNode();
			if (near != at) return "path not connected at " + at.getId();
			at = forward ? l.getFromNode() : l.getToNode();
		}
		return at == source ? null : "path ends at " + at.getId() + ", not at the source";
	}
}
