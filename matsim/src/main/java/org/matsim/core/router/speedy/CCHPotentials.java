/* *********************************************************************** *
 * project: org.matsim.*
 * CCHPotentials.java
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

import java.lang.ref.SoftReference;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.stream.IntStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.turnRestrictions.TurnRestrictionsContext;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;

/**
 * Lower bounds on link costs for exact A* with CH-Potentials (Strasser and Zeitz, SEA 2021,
 * <a href="https://arxiv.org/abs/1910.12526">arXiv:1910.12526</a>), per departure window.
 *
 * <p>The A* in {@link CCHPotentialSearch} runs on the real network with the live travel disutility. Its potential
 * h(v) is the exact shortest distance from v to the targets under per-link <em>lower bounds</em>, which a customizable
 * contraction hierarchy ({@link CHBuilder#withCustomizableContraction()}) answers quickly. Any consistent lower bound
 * makes A* return exactly Dijkstra's labels, so routes are exact; tighter bounds only make the search smaller.
 *
 * <pre>{@code
 *   bins:   |  k  | k+1 | k+2 | k+3 | k+4 | ...          bin size and horizon from the travel time config
 *            <----- window k: W bins ----->
 *
 *   window k, link e:  lower(e) = min over bins j in [k, k+W) of disutility(e, j * binSize)
 *   window k, shortcut: lower(u->w) = min over lower triangles v of lower(u->v) + lower(v->w)
 *   all day:           the same, over every bin including the one at the horizon
 * }</pre>
 *
 * A query departing in bin k uses window k while all its link entries stay inside the window, and otherwise reruns
 * with the all-day bounds (see {@link CCHPotentialSearch}). A bound is only valid if the disutility is constant
 * within each bin, as for MATSim's travel time calculator and DVRP's estimates on the same bin size; the bin size and
 * horizon therefore come from the travel time configuration. The departure-window variant is our addition to
 * CH-Potentials, which uses a single all-day bound.
 *
 * <p>Weights are stored as floats rounded down, so they stay lower bounds.
 */
public final class CCHPotentials {

	private static final Logger LOG = LogManager.getLogger(CCHPotentials.class);

	/** Window length in bins. One hour at 900 s bins was the fastest of 1, 1.5 and 2 h on Hamburg (see logbook). */
	static final double WINDOW_SECONDS = 3600.0;

	/**
	 * Per-network caches, shared by all users. The values are held softly: a graph keeps its links, which keep their
	 * network, and a contraction keeps its graph, so a strongly held value would keep its own weak key alive and the
	 * entry would never be released. This way they are released under memory pressure once nothing else uses them.
	 */
	private static final Map<Network, SoftReference<SpeedyGraph>> BASE_GRAPHS = Collections.synchronizedMap(new WeakHashMap<>());
	private static final Map<SpeedyGraph, SoftReference<CHGraph>> CONTRACTIONS = Collections.synchronizedMap(new WeakHashMap<>());

	final SpeedyGraph graph;
	final CHGraph cch;
	final double binSize;
	/** Number of bins up to the horizon; sample index {@code bins} is the time at the horizon (constant after it). */
	final int bins;
	final int window;
	/** windows[k][cchEdge]: lower bound for entering links at times in bins [k, k + window). */
	final float[][] windows;
	final float[] allDay;
	/** Internal indices of all colored copies per node, by uncolored internal index; null without turn restrictions. */
	final int[][] copies;
	/** Per-bin link costs for the search to read directly; null when the costs cannot be sampled (person-dependent). */
	final FlatCosts flat;

	private CCHPotentials(SpeedyGraph graph, CHGraph cch, double binSize, int bins, int window, float[][] windows, float[] allDay, FlatCosts flat) {
		this.graph = graph;
		this.cch = cch;
		this.binSize = binSize;
		this.bins = bins;
		this.window = window;
		this.windows = windows;
		this.allDay = allDay;
		this.flat = flat;
		this.copies = graph.getTurnRestrictions().isPresent() ? coloredCopies(graph) : null;
	}

	/** The base graph (spatially ordered) for this network, shared by all CH-Potentials users. */
	public static SpeedyGraph baseGraph(Network network) {
		synchronized (BASE_GRAPHS) {
			SoftReference<SpeedyGraph> cached = BASE_GRAPHS.get(network);
			SpeedyGraph graph = cached == null ? null : cached.get();
			if (graph == null) {
				graph = SpeedyGraphBuilder.buildWithSpatialOrdering(network);
				BASE_GRAPHS.put(network, new SoftReference<>(graph));
			}
			return graph;
		}
	}

	/** The customizable contraction of this graph. It does not depend on costs, so it is computed once. */
	/** The node a search leaving {@code fromLink} starts at: its end node, or with turn restrictions the colored copy for that link. */
	int startNode(Link fromLink) {
		if (graph.getTurnRestrictions().isPresent()) {
			TurnRestrictionsContext.ColoredLink replaced = graph.getTurnRestrictions().get().replacedLinks.get(fromLink.getId());
			if (replaced != null) return graph.getInternalIndex(replaced.toColoredNode.index());
		}
		return graph.getNodeIndex(fromLink.getToNode());
	}

	public static CHGraph contraction(SpeedyGraph graph, TravelDisutility anyDisutility) {
		synchronized (CONTRACTIONS) {
			SoftReference<CHGraph> cached = CONTRACTIONS.get(graph);
			CHGraph cch = cached == null ? null : cached.get();
			if (cch == null) {
				cch = contract(graph, anyDisutility);
				CONTRACTIONS.put(graph, new SoftReference<>(cch));
			}
			return cch;
		}
	}

	private static CHGraph contract(SpeedyGraph g, TravelDisutility td) {
		long start = System.nanoTime();
		NetworkProfile profile = NetworkAnalyzer.analyze(g);
		InertialFlowCutter.NDOrderResult order = new InertialFlowCutter(g, RoutingParameterTuner.tuneIFCParams(profile)).computeOrderWithBatches();
		// td only seeds the builder's initial weights; a customizable contraction never prunes with them.
		CHBuilder builder = new CHBuilder(g, td, RoutingParameterTuner.tuneCHParams(profile)).withCustomizableContraction().withTimeDependence(false);
		CHGraph cch = builder.buildWithOrder(order.order);
		// graph.linkCount is the size of the link index space, shared by all modes; count the links this graph has
		int links = 0;
		for (int l = 0; l < g.linkCount; l++) if (g.getLink(l) != null) links++;
		LOG.info("[CCH] contraction of {} nodes, {} links: {} edges, {} triangles in {} s", g.nodeCount, links,
				cch.totalEdgeCount, cch.triLower1.length, secs(System.nanoTime() - start));
		return cch;
	}

	/**
	 * Lower bounds for the current disutility.
	 *
	 * @param binSize seconds per bin in which the disutility is constant (the travel time bin size)
	 * @param horizon time after which the disutility no longer changes (the travel time calculator's max time)
	 */
	public static CCHPotentials customize(SpeedyGraph graph, CHGraph cch, TravelDisutility td, double binSize, double horizon) {
		return customize(graph, cch, null, td, binSize, horizon);
	}

	/**
	 * As above, and with a travel time also samples the exact per-bin link costs, so searches read them from arrays
	 * ({@link FlatCosts}). Pass the same travel time and disutility the searches will use.
	 */
	public static CCHPotentials customize(SpeedyGraph graph, CHGraph cch, TravelTime tt, TravelDisutility td, double binSize, double horizon) {
		if (!cch.isCustomizable()) {
			throw new IllegalStateException("CH-Potentials need a customizable contraction (CHBuilder#withCustomizableContraction)");
		}
		if (!(binSize > 0) || !(horizon > 0)) {
			throw new IllegalArgumentException("bin size and horizon must be positive, got " + binSize + " and " + horizon);
		}
		long start = System.nanoTime();
		int bins = (int) Math.ceil(horizon / binSize);
		int window = Math.max(1, (int) Math.round(WINDOW_SECONDS / binSize));
		int edges = cch.totalEdgeCount;

		float[][] windows = new float[bins][];
		float[] allDay;
		FlatCosts flat = null;
		if (needsPerson(graph, td)) {
			// Person-dependent costs (e.g. randomized routing) have no valid per-time sample without a person; their
			// time-independent minimum is the bound by contract, as for SpeedyALT's landmarks.
			LOG.info("[CCH] {} needs a person: using its minimum disutility as a time-independent bound", td.getClass().getSimpleName());
			float[][] minimum = new float[1][graph.linkCount];
			for (int l = 0; l < graph.linkCount; l++) {
				Link link = graph.getLink(l);
				if (link != null) minimum[0][l] = down(td.getLinkMinimumTravelDisutility(link));
			}
			allDay = metric(cch, minimum, 0, 0);
			java.util.Arrays.fill(windows, allDay);
		} else {
			// samples[j][link]: disutility of entering the link at the start of bin j (j = bins is the horizon)
			float[][] samples = new float[bins + 1][graph.linkCount];
			if (tt != null) flat = new FlatCosts(graph.linkCount, binSize, bins);
			FlatCosts sampled = flat;
			IntStream.range(0, graph.linkCount).parallel().forEach(l -> {
				Link link = graph.getLink(l);
				if (link == null) return;
				double[] times = sampled == null ? null : new double[bins + 1];
				double[] costs = sampled == null ? null : new double[bins + 1];
				for (int j = 0; j <= bins; j++) {
					double cost = td.getLinkTravelDisutility(link, j * binSize, null, null);
					samples[j][l] = down(cost);
					if (sampled != null) {
						costs[j] = cost;
						times[j] = tt.getLinkTravelTime(link, j * binSize, null, null);
					}
				}
				if (sampled != null) sampled.set(l, times, costs);
			});
			IntStream.range(0, bins).parallel().forEach(k -> windows[k] = metric(cch, samples, k, Math.min(bins, k + window - 1)));
			allDay = metric(cch, samples, 0, bins);
		}

		LOG.info("[CCH] potentials: {} windows of {} bins ({} s each) up to {} h, {} edges, {} s", bins, window,
				binSize, horizon / 3600, edges, secs(System.nanoTime() - start));
		return new CCHPotentials(graph, cch, binSize, bins, window, windows, allDay, flat);
	}

	/** Lower-bound metric over samples [first, last] (inclusive): real edges from the samples, shortcuts bottom-up. */
	private static float[] metric(CHGraph cch, float[][] samples, int first, int last) {
		float[] w = new float[cch.totalEdgeCount];
		for (int e = 0; e < w.length; e++) {
			int link = cch.edgeOrigLink[e];
			if (link < 0) {
				w[e] = Float.POSITIVE_INFINITY;
				continue;
			}
			float min = Float.POSITIVE_INFINITY;
			for (int j = first; j <= last; j++) min = Math.min(min, samples[j][link]);
			w[e] = min;
		}
		// cchOrder lists shortcuts by the level of their lower endpoint: a triangle's edges are final when it is read
		for (int e : cch.cchOrder) {
			double best = Double.POSITIVE_INFINITY;
			for (int t = cch.triOff[e], end = cch.triOff[e + 1]; t < end; t++) {
				best = Math.min(best, (double) w[cch.triLower1[t]] + w[cch.triLower2[t]]);
			}
			w[e] = down(best);
		}
		return w;
	}

	/** Bounds for a search whose link entries start at {@code time}, forward or backward in time. */
	float[] windowFor(double time, boolean forward) {
		int k = windowStart(time, forward);
		return k < 0 ? allDay : windows[k];
	}

	/**
	 * First window bin for a search from {@code time}: forward searches enter links at or after it, backward searches
	 * (which evaluate links at the later end) at or before it. -1 means none applies: use the all-day bounds.
	 */
	int windowStart(double time, boolean forward) {
		int bin = (int) Math.floor(time / binSize);
		int k = forward ? bin : bin - window + 1;
		return k >= 0 && k < bins && bin < bins ? k : -1;
	}

	/** Forward: link entries at or after this time are outside window k. Backward: entries before this time are. */
	double windowLimit(int k, boolean forward) {
		if (forward) {
			return k + window >= bins + 1 ? Double.POSITIVE_INFINITY : (k + window) * binSize;
		}
		return k * binSize;
	}

	private static int[][] coloredCopies(SpeedyGraph g) {
		int[] count = new int[g.nodeCount];
		for (int i = 0; i < g.nodeCount; i++) {
			if (g.getNode(i) != null) count[g.getNodeIndex(g.getNode(i))]++;
		}
		int[][] copies = new int[g.nodeCount][];
		for (int i = 0; i < g.nodeCount; i++) copies[i] = new int[count[i]];
		int[] fill = new int[g.nodeCount];
		for (int i = 0; i < g.nodeCount; i++) {
			if (g.getNode(i) == null) continue;
			int u = g.getNodeIndex(g.getNode(i));
			copies[u][fill[u]++] = i;
		}
		return copies;
	}

	/**
	 * Whether the disutility refuses to be evaluated without a person. The TravelDisutility interface has no way to
	 * ask, so this tries one link: MATSim's randomizing disutility throws for a null person.
	 */
	private static boolean needsPerson(SpeedyGraph graph, TravelDisutility td) {
		for (int l = 0; l < graph.linkCount; l++) {
			Link link = graph.getLink(l);
			if (link == null) continue;
			try {
				td.getLinkTravelDisutility(link, 0, null, null);
				return false;
			} catch (RuntimeException e) {
				return true;
			}
		}
		return false;
	}

	/** Rounds down to float, so a lower bound stays a lower bound. */
	static float down(double x) {
		float f = (float) x;
		return f > x ? Math.nextDown(f) : f;
	}

	private static String secs(long nanos) {
		return String.format(Locale.US, "%.1f", nanos / 1e9);
	}
}
