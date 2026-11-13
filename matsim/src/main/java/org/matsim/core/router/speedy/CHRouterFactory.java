/* *********************************************************************** *
 * project: org.matsim.*
 * CHRouterFactory.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2025 by the members listed in the COPYING,        *
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.groups.TravelTimeCalculatorConfigGroup;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.listener.IterationStartsListener;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.LeastCostPathCalculatorFactory;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;

/**
 * Factory for the exact CH router ({@link CHPotentialRouter}): A* with CH-Potentials on a customizable contraction
 * hierarchy, returning the same costs as {@link SpeedyDijkstra}. See {@link CCHPotentials} for the references.
 *
 * <ol>
 *   <li>The customizable contraction is computed once per network ({@link CCHPotentials#contraction}); it does not
 *       depend on costs.</li>
 *   <li>Once per iteration, the lower bounds are computed for each network and kind of cost
 *       ({@link CCHPotentials#customize}), on the travel time bins of the {@link TravelTimeCalculatorConfigGroup}.</li>
 *   <li>Every call returns a new router; routers share the read-only bounds.</li>
 * </ol>
 *
 * <p>Within an iteration, bounds are shared by all calls whose travel time and disutility report the same values on
 * the same network. MATSim hands out a new {@link TravelTime} wrapper per injection (e.g.
 * {@code TravelTimeCalculator#getLinkTravelTimes}), so the first call with a new pair of objects samples their costs
 * and reuses a customization with equal samples. Objects of one class may report different values (e.g. two DRT modes
 * with different speed caps, wrapped by the same lambda), and then get their own bounds. Disutilities that depend on
 * the person get bounds for a {@code null} person.
 *
 * @author Steffen Axer
 */
@Singleton
public class CHRouterFactory implements LeastCostPathCalculatorFactory, IterationStartsListener {

	/** The objects of one call; records compare them by identity unless their classes define equals. */
	private record Key(Network network, TravelDisutility disutility, TravelTime travelTime) {
	}

	private final double binSize;
	private final double horizon;
	/** This iteration's potentials per call objects, so repeated calls with the same objects skip the sampling. */
	private final Map<Key, CCHPotentials> potentials = new HashMap<>();
	/** This iteration's distinct potentials, to share one customization between objects that report equal values. */
	private final List<CCHPotentials> distinct = new ArrayList<>();
	private int iteration = Integer.MIN_VALUE;
	private int potentialsIteration = Integer.MIN_VALUE;

	@Inject
	public CHRouterFactory(TravelTimeCalculatorConfigGroup travelTimeConfig) {
		this.binSize = travelTimeConfig.getTraveltimeBinSize();
		this.horizon = travelTimeConfig.getMaxTime();
	}

	/** With the default travel time configuration, for use without injection. */
	public CHRouterFactory() {
		this(new TravelTimeCalculatorConfigGroup());
	}

	/** Travel times change between iterations: the next call recomputes the bounds. */
	@Override
	public synchronized void notifyIterationStarts(IterationStartsEvent event) {
		iteration++;
	}

	@Override
	public LeastCostPathCalculator createPathCalculator(Network network, TravelDisutility travelCosts, TravelTime travelTimes) {
		return new CHPotentialRouter(potentials(network, travelCosts, travelTimes), travelTimes, travelCosts);
	}

	/** Number of customizations this iteration, to check that objects with equal values share one. */
	synchronized int distinctCustomizations() {
		return distinct.size();
	}

	private synchronized CCHPotentials potentials(Network network, TravelDisutility td, TravelTime tt) {
		if (potentialsIteration != iteration) {
			potentials.clear();
			distinct.clear();
			potentialsIteration = iteration;
		}
		return potentials.computeIfAbsent(new Key(network, td, tt), k -> {
			SpeedyGraph graph = CCHPotentials.baseGraph(network);
			CCHPotentials.Samples samples = CCHPotentials.sample(graph, tt, td, binSize, horizon);
			for (CCHPotentials p : distinct) {
				if (p.customizedFrom(samples)) return p;
			}
			CCHPotentials p = CCHPotentials.customize(CCHPotentials.contraction(graph, td), samples);
			distinct.add(p);
			return p;
		});
	}
}
