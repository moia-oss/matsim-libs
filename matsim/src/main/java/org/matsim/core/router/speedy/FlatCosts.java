/* *********************************************************************** *
 * project: org.matsim.*
 * FlatCosts.java
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

/**
 * Travel time and disutility of every link per time bin, sampled once per customization, so that a query reads two
 * array cells instead of calling the (usually map-backed) {@link org.matsim.core.router.util.TravelTime} and
 * {@link org.matsim.core.router.util.TravelDisutility} on every link it relaxes.
 *
 * <pre>{@code
 *   link 17   constant:   ttFirst[17] = 31.0                      ttVar[17] = null
 *   link 18   varying:    ttFirst[18] = 12.0                      ttVar[18] = {12, 12, 40, 40, 25, ...}
 *                                                                   one value per bin, last = after the horizon
 * }</pre>
 *
 * Only about 3% of the links of a loaded network vary between bins, so varying links alone keep a per-bin array.
 * The values are exact doubles, not the rounded-down floats of the lower bounds. Valid under the same assumption as
 * the bounds: costs are constant within each bin and independent of person and vehicle; the search therefore only reads them for queries without person and vehicle.
 */
final class FlatCosts {

	private final double binSize;
	private final int bins;
	private final double[] ttFirst, costFirst;
	private final double[][] ttVar, costVar;

	FlatCosts(int linkCount, double binSize, int bins) {
		this.binSize = binSize;
		this.bins = bins;
		this.ttFirst = new double[linkCount];
		this.costFirst = new double[linkCount];
		this.ttVar = new double[linkCount][];
		this.costVar = new double[linkCount][];
	}

	/** Stores a link's values at the start of bins 0..bins (bin {@code bins} is the time at the horizon). Thread safe per link. */
	void set(int link, double[] travelTimes, double[] costs) {
		ttFirst[link] = travelTimes[0];
		costFirst[link] = costs[0];
		if (!constant(travelTimes)) ttVar[link] = travelTimes;
		if (!constant(costs)) costVar[link] = costs;
	}

	double travelTime(int link, double time) {
		double[] v = ttVar[link];
		return v == null ? ttFirst[link] : v[bin(time)];
	}

	double cost(int link, double time) {
		double[] v = costVar[link];
		return v == null ? costFirst[link] : v[bin(time)];
	}

	/** Bin of a link entry time; the last sample covers everything from the horizon on. */
	private int bin(double time) {
		return time <= 0 ? 0 : (int) Math.min(bins, time / binSize);
	}

	/** Whether both hold the same values for every link and bin. */
	boolean sameValues(FlatCosts other) {
		return binSize == other.binSize && bins == other.bins
				&& java.util.Arrays.equals(ttFirst, other.ttFirst) && java.util.Arrays.equals(costFirst, other.costFirst)
				&& java.util.Arrays.deepEquals(ttVar, other.ttVar) && java.util.Arrays.deepEquals(costVar, other.costVar);
	}

	private static boolean constant(double[] v) {
		for (int i = 1; i < v.length; i++) {
			if (v[i] != v[0]) return false;
		}
		return true;
	}
}
