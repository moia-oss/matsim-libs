/* *********************************************************************** *
 * project: org.matsim.*
 * CCHPotentialSearch.java
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

import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.vehicles.Vehicle;

/**
 * Exact A* to a set of targets on the real network, with CH-Potentials from {@link CCHPotentials}. Shared by the
 * point-to-point router ({@link CHPotentialRouter}) and the one-to-many tree ({@link CHPotentialTree}).
 *
 * <p>The relaxation is exactly that of {@link LeastCostPathTree}: forward searches evaluate a link when entering it,
 * backward searches evaluate it at the time of its downstream node and subtract. Nodes are ordered by
 * {@code cost + h}, where h is a consistent lower bound on the remaining cost to the nearest target, so every popped
 * node has its exact Dijkstra label and the search can stop once all targets are popped.
 *
 * <pre>{@code
 *   forward, targets T:  h(v) = min over T of dist(v -> T)      under the window's lower bounds
 *     1. backward upward search from all T in the hierarchy     seeds(x) = min over T of up-dist(x <- T)
 *     2. h(v) = min( seeds(v), min over up-edges v->y of w + h(y) )     lazily, memoized per query
 *
 *            y1 ---- top ---- y2          a node's potential walks up the hierarchy until it meets
 *           /                   \         the seeded search space of the targets
 *          v                     t
 *
 *   backward: the mirror image (upward search from T, recursion over incoming downward edges).
 * }</pre>
 *
 * <p>Window bounds hold only while link entries stay inside the departure window; if the search evaluates a link
 * outside it, the bounds may be too high there, so the query reruns with the all-day bounds.
 */
final class CCHPotentialSearch {

	private final CCHPotentials p;
	private final SpeedyGraph graph;
	private final CHGraph cch;
	private final TravelTime tt;
	private final TravelDisutility td;
	private final SpeedyGraph.LinkIterator outLI, inLI;

	// labels, valid where stamp == query
	final double[] cost, time, distance;
	final int[] comingFrom, fromLink;
	private final int[] stamp, settledStamp;
	int query;
	/** Nodes labelled in this query, in insertion order. */
	final int[] touched;
	int touchedCount;
	private final DAryMinHeap pq;

	// potentials
	private final double[] seed, h;
	private final int[] seedStamp, hStamp, targetStamp, cursor, stack;
	private final DAryMinHeap seedPq;
	private float[] w;
	private double windowLimit;
	private boolean forward, violated;

	CCHPotentialSearch(CCHPotentials potentials, TravelTime tt, TravelDisutility td) {
		this.p = potentials;
		this.graph = potentials.graph;
		this.cch = potentials.cch;
		this.tt = tt;
		this.td = td;
		int n = graph.nodeCount;
		this.outLI = graph.getOutLinkIterator();
		this.inLI = graph.getInLinkIterator();
		this.cost = new double[n];
		this.time = new double[n];
		this.distance = new double[n];
		this.comingFrom = new int[n];
		this.fromLink = new int[n];
		this.stamp = new int[n];
		this.settledStamp = new int[n];
		this.touched = new int[n];
		this.pq = new DAryMinHeap(n, 6);
		this.seed = new double[n];
		this.h = new double[n];
		this.seedStamp = new int[n];
		this.hStamp = new int[n];
		this.targetStamp = new int[n];
		this.cursor = new int[n];
		this.stack = new int[n];
		this.seedPq = new DAryMinHeap(n, 6);
	}

	boolean labelled(int node) {
		return stamp[node] == query;
	}

	/**
	 * Labels nodes from the starts until every target (internal indices) is popped or no target is reachable.
	 * Targets include their colored copies; the caller passes uncolored indices.
	 *
	 * @param maxCost prune nodes whose cost plus potential exceeds this (infinite for none)
	 * @return the first target popped, or -1 if none is reachable
	 */
	int run(int[] starts, double startTime, boolean forward, int[] targets, int targetCount, double maxCost, Person person, Vehicle vehicle) {
		int k = p.windowStart(startTime, forward);
		if (k >= 0 && p.windows[k] != p.allDay) {
			int first = run0(starts, startTime, forward, targets, targetCount, maxCost, person, vehicle, p.windows[k], p.windowLimit(k, forward));
			if (!violated) return first;
		}
		return run0(starts, startTime, forward, targets, targetCount, maxCost, person, vehicle, p.allDay,
				forward ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY);
	}

	private int run0(int[] starts, double startTime, boolean forward, int[] targets, int targetCount, double maxCost,
			Person person, Vehicle vehicle, float[] weights, double limit) {
		nextQuery();
		this.w = weights;
		this.windowLimit = limit;
		this.forward = forward;
		this.violated = false;

		int remaining = seedTargets(targets, targetCount);
		pq.clear();
		for (int s : starts) {
			if (labelled(s)) continue;
			double hs = potential(s);
			if (hs == Double.POSITIVE_INFINITY) continue;
			label(s, 0, startTime, 0, -1, -1);
			pq.insert(s, hs);
		}
		int first = -1;
		SpeedyGraph.LinkIterator li = forward ? outLI : inLI;
		while (!pq.isEmpty() && remaining > 0) {
			if (pq.peekCost() > maxCost) break;
			int v = pq.poll();
			settledStamp[v] = query;
			if (targetStamp[v] == query) {
				targetStamp[v] = 0;
				remaining--;
				if (first < 0) first = v;
				if (remaining == 0) break;
			}
			double tv = time[v], cv = cost[v], dv = distance[v];
			if (forward ? tv >= windowLimit : tv < windowLimit) {
				violated = true;
				return first;
			}
			li.reset(v);
			while (li.next()) {
				int linkIdx = li.getLinkIndex();
				Link link = graph.getLink(linkIdx);
				int x = forward ? li.getToNodeIndex() : li.getFromNodeIndex();
				double travelTime = tt.getLinkTravelTime(link, tv, person, vehicle);
				double newTime = forward ? tv + travelTime : tv - travelTime;
				double newCost = cv + td.getLinkTravelDisutility(link, tv, person, vehicle);
				if (labelled(x)) {
					// a settled label is final: the potential is consistent, so A* pops nodes in Dijkstra's order of
					// reduced costs (and DAryMinHeap does not support re-inserting popped nodes)
					if (settledStamp[x] != query && newCost < cost[x]) {
						label(x, newCost, newTime, dv + link.getLength(), v, linkIdx);
						pq.decreaseKey(x, newCost + h[x]);
					}
				} else {
					double hx = potential(x);
					if (hx == Double.POSITIVE_INFINITY || newCost + hx > maxCost) continue;
					label(x, newCost, newTime, dv + link.getLength(), v, linkIdx);
					pq.insert(x, newCost + hx);
				}
			}
		}
		return first;
	}

	/**
	 * With turn restrictions, gives each uncolored node the best label of its colored copies, as
	 * {@link LeastCostPathTree} does, so results can be read by uncolored node.
	 */
	void consolidateColoredCopies() {
		if (p.copies == null) return;
		for (int i = 0, n = touchedCount; i < n; i++) {
			int c = touched[i];
			int u = graph.getNodeIndex(graph.getNode(c));
			if (u == c) continue;
			if (!labelled(u) || cost[c] < cost[u]) label(u, cost[c], time[c], distance[c], comingFrom[c], fromLink[c]);
		}
	}

	private void label(int node, double c, double t, double d, int from, int link) {
		if (stamp[node] != query) {
			stamp[node] = query;
			touched[touchedCount++] = node;
		}
		cost[node] = c;
		time[node] = t;
		distance[node] = d;
		comingFrom[node] = from;
		fromLink[node] = link;
	}

	private void nextQuery() {
		if (++query == Integer.MAX_VALUE) {
			java.util.Arrays.fill(stamp, 0);
			java.util.Arrays.fill(settledStamp, 0);
			java.util.Arrays.fill(seedStamp, 0);
			java.util.Arrays.fill(hStamp, 0);
			java.util.Arrays.fill(targetStamp, 0);
			query = 1;
		}
		touchedCount = 0;
	}

	/**
	 * Upward search in the hierarchy from all targets (and their colored copies): seed(x) = lower-bound distance
	 * between x and the nearest target, through nodes above it. Forward A* needs distances x -> target, so it follows
	 * downward in-edges reversed (dn); backward A* needs target -> x, so it follows upward out-edges.
	 *
	 * @return the number of distinct target nodes to pop
	 */
	private int seedTargets(int[] targets, int targetCount) {
		seedPq.clear();
		int remaining = 0;
		for (int i = 0; i < targetCount; i++) {
			int t = targets[i];
			if (p.copies == null) {
				remaining += seedTarget(t);
			} else {
				for (int c : p.copies[t]) remaining += seedTarget(c);
			}
		}
		final int S = CHGraph.E_STRIDE;
		int[] off = forward ? cch.dnOff : cch.upOff, len = forward ? cch.dnLen : cch.upLen, edges = forward ? cch.dnEdges : cch.upEdges;
		while (!seedPq.isEmpty()) {
			int x = seedPq.poll();
			double dx = seed[x];
			for (int slot = off[x], end = slot + len[x]; slot < end; slot++) {
				double we = w[edges[slot * S + CHGraph.E_GIDX]];
				if (we == Double.POSITIVE_INFINITY) continue;
				int y = edges[slot * S + CHGraph.E_NODE];
				double d = dx + we;
				if (seedStamp[y] != query) {
					seedStamp[y] = query;
					seed[y] = d;
					seedPq.insert(y, d);
				} else if (d < seed[y]) {
					seed[y] = d;
					seedPq.decreaseKey(y, d);
				}
			}
		}
		return remaining;
	}

	private int seedTarget(int t) {
		if (targetStamp[t] == query) return 0;
		targetStamp[t] = query;
		seedStamp[t] = query;
		seed[t] = 0;
		seedPq.insert(t, 0);
		return 1;
	}

	/**
	 * h(v) = min(seed(v), min over hierarchy edges leaving v upward of w + h(y)), memoized. Forward: v's upward
	 * out-edges (up); backward: v's incoming edges from higher nodes (dn). Iterative, since the recursion goes as
	 * deep as the hierarchy.
	 */
	double potential(int v) {
		if (hStamp[v] == query) return h[v];
		final int S = CHGraph.E_STRIDE;
		int[] off = forward ? cch.upOff : cch.dnOff, len = forward ? cch.upLen : cch.dnLen, edges = forward ? cch.upEdges : cch.dnEdges;
		int top = 0;
		stack[top++] = v;
		cursor[v] = off[v];
		while (top > 0) {
			int x = stack[top - 1];
			int end = off[x] + len[x];
			boolean descended = false;
			while (cursor[x] < end) {
				int y = edges[cursor[x] * S + CHGraph.E_NODE];
				if (hStamp[y] != query) {
					stack[top++] = y;
					cursor[y] = off[y];
					descended = true;
					break;
				}
				cursor[x]++;
			}
			if (descended) continue;
			double best = seedStamp[x] == query ? seed[x] : Double.POSITIVE_INFINITY;
			for (int slot = off[x]; slot < end; slot++) {
				double we = w[edges[slot * S + CHGraph.E_GIDX]];
				if (we == Double.POSITIVE_INFINITY) continue;
				double c = we + h[edges[slot * S + CHGraph.E_NODE]];
				if (c < best) best = c;
			}
			h[x] = best;
			hStamp[x] = query;
			top--;
		}
		return h[v];
	}
}
