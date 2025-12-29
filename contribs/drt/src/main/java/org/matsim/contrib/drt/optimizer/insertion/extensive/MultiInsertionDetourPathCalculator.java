/*
 * *********************************************************************** *
 * project: org.matsim.*
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2022 by the members listed in the COPYING,        *
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
 * *********************************************************************** *
 */

package org.matsim.contrib.drt.optimizer.insertion.extensive;

import static org.matsim.contrib.drt.optimizer.insertion.InsertionGenerator.Insertion;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.annotation.Nullable;
import org.matsim.api.core.v01.IdMap;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.contrib.drt.optimizer.Waypoint;
import org.matsim.contrib.drt.passenger.DrtRequest;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.dvrp.path.OneToManyPathSearch;
import org.matsim.contrib.dvrp.path.OneToManyPathSearch.PathData;
import org.matsim.core.mobsim.dsim.NodeSingleton;
import org.matsim.core.mobsim.framework.events.MobsimBeforeCleanupEvent;
import org.matsim.core.mobsim.framework.listeners.MobsimBeforeCleanupListener;
import org.matsim.core.router.speedy.SpeedyGraph;
import org.matsim.core.router.speedy.SpeedyGraphBuilder;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;

import com.google.common.annotations.VisibleForTesting;

/**
 * @author michalm
 */
@NodeSingleton
class MultiInsertionDetourPathCalculator implements MobsimBeforeCleanupListener {
	public static final int MAX_THREADS = 4;

	private final OneToManyPathSearch toPickupPathSearch;
	private final OneToManyPathSearch fromPickupPathSearch;
	private final OneToManyPathSearch toDropoffPathSearch;
	private final OneToManyPathSearch fromDropoffPathSearch;

	private final ExecutorService executorService;

	MultiInsertionDetourPathCalculator(Network network, TravelTime travelTime, TravelDisutility travelDisutility,
			DrtConfigGroup drtCfg) {
		SpeedyGraph graph = SpeedyGraphBuilder.build(network);
		toPickupPathSearch = OneToManyPathSearch.createSearch(graph, travelTime, travelDisutility, allowsLazyPathCreation(drtCfg));
		fromPickupPathSearch = OneToManyPathSearch.createSearch(graph, travelTime, travelDisutility,  allowsLazyPathCreation(drtCfg));
		toDropoffPathSearch = OneToManyPathSearch.createSearch(graph, travelTime, travelDisutility,  allowsLazyPathCreation(drtCfg));
		fromDropoffPathSearch = OneToManyPathSearch.createSearch(graph, travelTime, travelDisutility,  allowsLazyPathCreation(drtCfg));
		executorService = Executors.newFixedThreadPool(Math.min(drtCfg.getNumberOfThreads(), MAX_THREADS));
	}

	private boolean allowsLazyPathCreation(DrtConfigGroup drtConfigGroup)
	{
		return drtConfigGroup.getDrtParallelInserterParams().isEmpty();
	}

	@VisibleForTesting
	MultiInsertionDetourPathCalculator(OneToManyPathSearch toPickupPathSearch, OneToManyPathSearch fromPickupPathSearch,
			OneToManyPathSearch toDropoffPathSearch, OneToManyPathSearch fromDropoffPathSearch, int numberOfThreads) {
		this.toPickupPathSearch = toPickupPathSearch;
		this.fromPickupPathSearch = fromPickupPathSearch;
		this.toDropoffPathSearch = toDropoffPathSearch;
		this.fromDropoffPathSearch = fromDropoffPathSearch;
		executorService = Executors.newFixedThreadPool(Math.min(numberOfThreads, MAX_THREADS));
	}

	DetourPathDataCache calculatePaths(DrtRequest drtRequest, List<Insertion> filteredInsertions) {
		// with vehicle insertion filtering -- pathsToPickup is the most computationally demanding task, while
		// pathsFromDropoff is the least demanding one

		// Group insertions by pickup and dropoff links for efficient path calculation
		Map<Link, List<Insertion>> insertionsByPickupLink = filteredInsertions.stream()
				.collect(Collectors.groupingBy(insertion -> insertion.pickup.newWaypoint.getLink()));
		Map<Link, List<Insertion>> insertionsByDropoffLink = filteredInsertions.stream()
				.collect(Collectors.groupingBy(insertion -> insertion.dropoff.newWaypoint.getLink()));

		var pathsToPickupFuture = executorService.submit(() -> calcPathsToPickup(drtRequest, insertionsByPickupLink));
		var pathsFromPickupFuture = executorService.submit(() -> calcPathsFromPickup(drtRequest, insertionsByPickupLink));
		var pathsToDropoffFuture = executorService.submit(() -> calcPathsToDropoff(drtRequest, insertionsByDropoffLink));
		var pathsFromDropoffFuture = executorService.submit(() -> calcPathsFromDropoff(drtRequest, insertionsByDropoffLink));

		try {
			return new DetourPathDataCache(pathsToPickupFuture.get(), pathsFromPickupFuture.get(),
					pathsToDropoffFuture.get(), pathsFromDropoffFuture.get(), PathData.EMPTY);
		} catch (InterruptedException | ExecutionException e) {
			throw new RuntimeException(e);
		}
	}

	private Map<Link, Map<Link, PathData>> calcPathsToPickup(DrtRequest drtRequest, Map<Link, List<Insertion>> insertionsByPickupLink) {
		// For each unique pickup link, calc backward dijkstra to ends of selected stops + starts
		double earliestPickupTime = drtRequest.getEarliestStartTime(); // optimistic

		Map<Link, Map<Link, PathData>> result = new HashMap<>();
		for (Map.Entry<Link, List<Insertion>> entry : insertionsByPickupLink.entrySet()) {
			Link pickupLink = entry.getKey();
			List<Insertion> insertions = entry.getValue();

			Collection<Link> toLinks = getDetourLinks(insertions.stream(),
					insertion -> insertion.pickup.previousWaypoint.getLink());
			Map<Link, PathData> pathsForPickupLink = toPickupPathSearch.calcPathDataMap(pickupLink, toLinks, earliestPickupTime, false);
			result.put(pickupLink, pathsForPickupLink);
		}
		return result;
	}

	private Map<Link, Map<Link, PathData>> calcPathsFromPickup(DrtRequest drtRequest, Map<Link, List<Insertion>> insertionsByPickupLink) {
		// For each unique pickup link, calc forward dijkstra to beginnings of selected stops + dropoff
		double earliestPickupTime = drtRequest.getEarliestStartTime(); // optimistic

		Map<Link, Map<Link, PathData>> result = new HashMap<>();
		for (Map.Entry<Link, List<Insertion>> entry : insertionsByPickupLink.entrySet()) {
			Link pickupLink = entry.getKey();
			List<Insertion> insertions = entry.getValue();

			Collection<Link> toLinks = getDetourLinks(insertions.stream(),
					insertion -> insertion.pickup.nextWaypoint.getLink());
			Map<Link, PathData> pathsForPickupLink = fromPickupPathSearch.calcPathDataMap(pickupLink, toLinks, earliestPickupTime, true);
			result.put(pickupLink, pathsForPickupLink);
		}
		return result;
	}

	private Map<Link, Map<Link, PathData>> calcPathsToDropoff(DrtRequest drtRequest, Map<Link, List<Insertion>> insertionsByDropoffLink) {
		// For each unique dropoff link, calc backward dijkstra to ends of selected stops
		double latestDropoffTime = drtRequest.getLatestArrivalTime(); // pessimistic

		Map<Link, Map<Link, PathData>> result = new HashMap<>();
		for (Map.Entry<Link, List<Insertion>> entry : insertionsByDropoffLink.entrySet()) {
			Link dropoffLink = entry.getKey();
			List<Insertion> insertions = entry.getValue();

			Collection<Link> toLinks = getDetourLinks(insertions.stream()
							.filter(insertion -> !(insertion.dropoff.previousWaypoint instanceof Waypoint.Pickup)),
					insertion -> insertion.dropoff.previousWaypoint.getLink());
			Map<Link, PathData> pathsForDropoffLink = toDropoffPathSearch.calcPathDataMap(dropoffLink, toLinks, latestDropoffTime, false);
			result.put(dropoffLink, pathsForDropoffLink);
		}
		return result;
	}

	private Map<Link, Map<Link, PathData>> calcPathsFromDropoff(DrtRequest drtRequest, Map<Link, List<Insertion>> insertionsByDropoffLink) {
		// For each unique dropoff link, calc forward dijkstra to beginnings of selected stops
		double latestDropoffTime = drtRequest.getLatestArrivalTime(); // pessimistic

		Map<Link, Map<Link, PathData>> result = new HashMap<>();
		for (Map.Entry<Link, List<Insertion>> entry : insertionsByDropoffLink.entrySet()) {
			Link dropoffLink = entry.getKey();
			List<Insertion> insertions = entry.getValue();

			Collection<Link> toLinks = getDetourLinks(insertions.stream()
							.filter(insertion -> !(insertion.dropoff.nextWaypoint instanceof Waypoint.End)),
					insertion -> insertion.dropoff.nextWaypoint.getLink());
			Map<Link, PathData> pathsForDropoffLink = fromDropoffPathSearch.calcPathDataMap(dropoffLink, toLinks, latestDropoffTime, true);
			result.put(dropoffLink, pathsForDropoffLink);
		}
		return result;
	}

	private Collection<Link> getDetourLinks(Stream<Insertion> filteredInsertions,
			Function<Insertion, Link> detourLinkExtractor) {
		IdMap<Link, Link> detourLinks = new IdMap<>(Link.class);
		filteredInsertions.map(detourLinkExtractor).forEach(link -> detourLinks.putIfAbsent(link.getId(), link));
		return detourLinks.values();
	}

	@Override
	public void notifyMobsimBeforeCleanup(@SuppressWarnings("rawtypes") MobsimBeforeCleanupEvent e) {
		executorService.shutdown();
	}
}
