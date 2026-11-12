/* *********************************************************************** *
 * project: org.matsim.*
 * CHPotentialRouter.java
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.LeastCostPathUtils;
import org.matsim.core.router.util.LeastCostPathUtils.NoPathBehavior;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.vehicles.Vehicle;

/**
 * Exact point-to-point router: A* with CH-Potentials ({@link CCHPotentialSearch}). Minimizes the travel disutility
 * like {@link SpeedyDijkstra} and {@link SpeedyALT}, and returns the same cost for every query. Not thread-safe;
 * create one per thread via {@link CHRouterFactory}.
 */
public final class CHPotentialRouter implements LeastCostPathCalculator {

	private static final Logger LOG = LogManager.getLogger(CHPotentialRouter.class);

	private final CCHPotentialSearch search;
	private final CCHPotentials potentials;
	private final SpeedyGraph graph;
	private final int[] start = new int[1];
	private final int[] target = new int[1];
	private NoPathBehavior noPathBehavior = NoPathBehavior.warning;

	public CHPotentialRouter(CCHPotentials potentials, TravelTime tt, TravelDisutility td) {
		this.search = new CCHPotentialSearch(potentials, tt, td);
		this.potentials = potentials;
		this.graph = potentials.graph;
	}

	public void setNoPathBehavior(NoPathBehavior value) {
		this.noPathBehavior = value;
	}

	@Override
	public Path calcLeastCostPath(Node fromNode, Node toNode, double startTime, Person person, Vehicle vehicle) {
		Path path = route(graph.getNodeIndex(fromNode), graph.getNodeIndex(toNode), startTime, person, vehicle, Double.POSITIVE_INFINITY);
		if (path == null) LeastCostPathUtils.handleNotFound(noPathBehavior, LOG, fromNode, toNode, person, vehicle);
		return path;
	}

	@Override
	public Path calcLeastCostPath(Link fromLink, Link toLink, double startTime, Person person, Vehicle vehicle) {
		return calcLeastCostPath(fromLink, toLink, startTime, person, vehicle, Double.POSITIVE_INFINITY);
	}

	@Override
	public Path calcLeastCostPath(Link fromLink, Link toLink, double startTime, Person person, Vehicle vehicle, double maxCost) {
		Path path = route(potentials.startNode(fromLink), graph.getNodeIndex(toLink.getFromNode()), startTime, person, vehicle, maxCost);
		if (path == null) LeastCostPathUtils.handleNotFound(noPathBehavior, LOG, fromLink, toLink, person, vehicle);
		return path;
	}

	private Path route(int startIdx, int endIdx, double startTime, Person person, Vehicle vehicle, double maxCost) {
		start[0] = startIdx;
		target[0] = endIdx;
		int reached = search.run(start, startTime, true, target, 1, maxCost, person, vehicle);
		if (reached < 0) return null;

		List<Node> nodes = new ArrayList<>();
		List<Link> links = new ArrayList<>();
		for (int v = reached; v >= 0; v = search.comingFrom[v]) {
			nodes.add(graph.getNode(v));
			if (search.fromLink[v] >= 0) links.add(graph.getLink(search.fromLink[v]));
		}
		Collections.reverse(nodes);
		Collections.reverse(links);
		return new Path(nodes, links, search.time[reached] - startTime, search.cost[reached]);
	}
}
