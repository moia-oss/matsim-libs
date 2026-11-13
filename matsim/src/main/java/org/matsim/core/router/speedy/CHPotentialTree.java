/* *********************************************************************** *
 * project: org.matsim.*
 * CHPotentialTree.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2026 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */
package org.matsim.core.router.speedy;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.network.turnRestrictions.TurnRestrictionsContext;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.utils.misc.OptionalTime;
import org.matsim.vehicles.Vehicle;

/**
 * One-to-many tree with exact labels for a given set of targets, via multi-target CH-Potentials
 * ({@link CCHPotentialSearch}): h(v) = lower bound on the cost between v and the nearest target, so the A* only
 * explores towards the targets and stops once all are labelled.
 *
 * <p>Labels of the targets equal those of {@link LeastCostPathTree}. Other nodes may be unlabelled. Calls without a
 * target set (or with an opaque stop criterion) are answered by an internal {@link LeastCostPathTree}, so they behave
 * exactly as before. Not thread-safe.
 */
public final class CHPotentialTree implements ShortestPathTree {

	private final CCHPotentialSearch search;
	private final LeastCostPathTree plain;
	private final SpeedyGraph graph;
	private int[] targets = new int[16];
	private final int[] start = new int[1];
	private boolean lastWasPlain;

	public CHPotentialTree(CCHPotentials potentials, TravelTime tt, TravelDisutility td) {
		this.search = new CCHPotentialSearch(potentials, tt, td);
		this.plain = new LeastCostPathTree(potentials.graph, tt, td);
		this.graph = potentials.graph;
	}

	@Override
	public void calculate(Link startLink, double startTime, Person person, Vehicle vehicle) {
		lastWasPlain = true;
		plain.calculate(startLink, startTime, person, vehicle);
	}

	@Override
	public void calculate(Link startLink, double startTime, Person person, Vehicle vehicle, LeastCostPathTree.StopCriterion stopCriterion) {
		lastWasPlain = true;
		plain.calculate(startLink, startTime, person, vehicle, stopCriterion);
	}

	@Override
	public void calculateBackwards(Link arrivalLink, double arrivalTime, Person person, Vehicle vehicle) {
		lastWasPlain = true;
		plain.calculateBackwards(arrivalLink, arrivalTime, person, vehicle);
	}

	@Override
	public void calculateBackwards(Link arrivalLink, double arrivalTime, Person person, Vehicle vehicle, LeastCostPathTree.StopCriterion stopCriterion) {
		lastWasPlain = true;
		plain.calculateBackwards(arrivalLink, arrivalTime, person, vehicle, stopCriterion);
	}

	/** Searches towards the targets; {@code allReached} is implied by them and not consulted. */
	@Override
	public void calculateToTargets(Link startLink, double startTime, Person person, Vehicle vehicle, Collection<Node> targetNodes,
			LeastCostPathTree.StopCriterion allReached) {
		int s = graph.getNodeIndex(startLink.getToNode());
		if (graph.getTurnRestrictions().isPresent()) {
			TurnRestrictionsContext.ColoredLink replaced = graph.getTurnRestrictions().get().replacedLinks.get(startLink.getId());
			if (replaced != null) s = graph.getInternalIndex(replaced.toColoredNode.index());
		}
		start[0] = s;
		run(start, startTime, true, targetNodes, person, vehicle);
	}

	@Override
	public void calculateBackwardsToTargets(Link arrivalLink, double arrivalTime, Person person, Vehicle vehicle, Collection<Node> targetNodes,
			LeastCostPathTree.StopCriterion allReached) {
		run(backwardStarts(arrivalLink), arrivalTime, false, targetNodes, person, vehicle);
	}

	private void run(int[] starts, double time, boolean forward, Collection<Node> targetNodes, Person person, Vehicle vehicle) {
		lastWasPlain = false;
		if (targets.length < targetNodes.size()) targets = new int[targetNodes.size()];
		int n = 0;
		for (Node t : targetNodes) targets[n++] = graph.getNodeIndex(t);
		search.run(starts, time, forward, targets, n, Double.POSITIVE_INFINITY, person, vehicle);
		search.consolidateColoredCopies();
	}

	/** The arrival node and, with turn restrictions, its colored copies reachable by in-links, as in LeastCostPathTree. */
	private int[] backwardStarts(Link arrivalLink) {
		int arrivalNode = graph.getNodeIndex(arrivalLink.getFromNode());
		if (graph.getTurnRestrictions().isEmpty()) return new int[] {arrivalNode};
		TurnRestrictionsContext context = graph.getTurnRestrictions().get();
		java.util.List<Integer> starts = new java.util.ArrayList<>(List.of(arrivalNode));
		for (Link inLink : arrivalLink.getFromNode().getInLinks().values()) {
			TurnRestrictionsContext.ColoredLink replaced = context.replacedLinks.get(inLink.getId());
			if (replaced != null && replaced.toColoredNode != null) starts.add(graph.getInternalIndex(replaced.toColoredNode.index()));
			List<TurnRestrictionsContext.ColoredLink> colored = context.coloredLinksPerLinkMap.get(inLink.getId());
			if (colored != null) {
				for (TurnRestrictionsContext.ColoredLink c : colored) {
					if (c.toColoredNode != null) starts.add(graph.getInternalIndex(c.toColoredNode.index()));
				}
			}
		}
		return starts.stream().mapToInt(Integer::intValue).toArray();
	}

	@Override
	public int getNodeIndex(Node node) {
		return graph.getNodeIndex(node);
	}

	@Override
	public double getCost(int nodeIndex) {
		if (lastWasPlain) return plain.getCost(nodeIndex);
		return search.labelled(nodeIndex) ? search.cost[nodeIndex] : Double.POSITIVE_INFINITY;
	}

	@Override
	public OptionalTime getTime(int nodeIndex) {
		if (lastWasPlain) return plain.getTime(nodeIndex);
		return search.labelled(nodeIndex) ? OptionalTime.defined(search.time[nodeIndex]) : OptionalTime.undefined();
	}

	@Override
	public double getDistance(int nodeIndex) {
		if (lastWasPlain) return plain.getDistance(nodeIndex);
		return search.labelled(nodeIndex) ? search.distance[nodeIndex] : Double.POSITIVE_INFINITY;
	}

	/** Nodes from the given node's parent back to the root (the node itself excluded), as LeastCostPathTree. */
	@Override
	public Iterator<Node> getNodePathIterator(Node node) {
		if (lastWasPlain) return plain.getNodePathIterator(node);
		return new Iterator<>() {
			private int current = graph.getNodeIndex(node);

			@Override
			public boolean hasNext() {
				return search.labelled(current) && search.comingFrom[current] >= 0;
			}

			@Override
			public Node next() {
				if (!hasNext()) throw new NoSuchElementException();
				current = search.comingFrom[current];
				return graph.getNode(current);
			}
		};
	}

	/** Links from the one entering the given node back to the root, as LeastCostPathTree. */
	@Override
	public Iterator<Link> getLinkPathIterator(Node node) {
		if (lastWasPlain) return plain.getLinkPathIterator(node);
		return new Iterator<>() {
			private int current = graph.getNodeIndex(node);

			@Override
			public boolean hasNext() {
				return search.labelled(current) && search.fromLink[current] >= 0;
			}

			@Override
			public Link next() {
				if (!hasNext()) throw new NoSuchElementException();
				Link link = graph.getLink(search.fromLink[current]);
				current = search.comingFrom[current];
				return link;
			}
		};
	}
}
