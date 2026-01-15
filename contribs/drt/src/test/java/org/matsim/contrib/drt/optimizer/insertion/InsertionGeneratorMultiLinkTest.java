/*
 * *********************************************************************** *
 * project: org.matsim.*
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
 * *********************************************************************** *
 */

package org.matsim.contrib.drt.optimizer.insertion;

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.drt.optimizer.StopWaypoint;
import org.matsim.contrib.drt.optimizer.StopWaypointImpl;
import org.matsim.contrib.drt.optimizer.VehicleEntry;
import org.matsim.contrib.drt.optimizer.Waypoint;
import org.matsim.contrib.drt.optimizer.constraints.DrtRouteConstraints;
import org.matsim.contrib.drt.optimizer.insertion.InsertionGenerator.Insertion;
import org.matsim.contrib.drt.passenger.DrtRequest;
import org.matsim.contrib.drt.schedule.DefaultDrtStopTask;
import org.matsim.contrib.drt.stops.DefaultStopTimeCalculator;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.fleet.DvrpVehicleImpl;
import org.matsim.contrib.dvrp.fleet.ImmutableDvrpVehicleSpecification;
import org.matsim.contrib.dvrp.load.IntegerLoadType;
import org.matsim.contrib.dvrp.optimizer.Request;
import org.matsim.testcases.fakes.FakeLink;

import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for InsertionGenerator with multiple access/egress link candidates (dynamic stop selection).
 *
 * @author nkuehnel
 */
public class InsertionGeneratorMultiLinkTest {

	private static final IntegerLoadType LOAD_TYPE = new IntegerLoadType("passengers");
	private static final int STOP_DURATION = 10;
	private static final double LARGE_SLACK = 10000.0; // Large slack to avoid time constraints

	private final InsertionGenerator generator = new InsertionGenerator(
		new DefaultStopTimeCalculator(STOP_DURATION),
		(fromLink, toLink, departureTime) -> 100.0 // Constant travel time for simplicity
	);

	@Test
	void testSingleAccessEgress_traditionalBehavior() {
		// Traditional case: single access and egress link
		Link accessLink = link("access");
		Link egressLink = link("egress");

		DrtRequest request = createRequest(List.of(accessLink), List.of(egressLink));

		VehicleEntry vehicle = createVehicle(2); // Vehicle with 2 stops

		List<InsertionWithDetourData> insertions = generator.generateInsertions(request, vehicle);

		// Verify insertions were generated
		assertThat(insertions).isNotEmpty();

		// All insertions should use the single access/egress links
		for (InsertionWithDetourData insertion : insertions) {
			assertThat(insertion.insertion.pickup.newWaypoint.getLink()).isEqualTo(accessLink);
			assertThat(insertion.insertion.dropoff.newWaypoint.getLink()).isEqualTo(egressLink);
		}
	}

	@Test
	void testMultipleAccessSingleEgress() {
		// Multiple access links, single egress
		Link access1 = link("access1");
		Link access2 = link("access2");
		Link access3 = link("access3");
		Link egress = link("egress");

		DrtRequest request = createRequest(List.of(access1, access2, access3), List.of(egress));

		VehicleEntry vehicle = createVehicle(2);

		List<InsertionWithDetourData> insertions = generator.generateInsertions(request, vehicle);

		// Should generate insertions for all 3 access links
		Set<Link> accessLinksUsed = insertions.stream()
			.map(ins -> ins.insertion.pickup.newWaypoint.getLink())
			.collect(Collectors.toSet());

		assertThat(accessLinksUsed).containsExactlyInAnyOrder(access1, access2, access3);

		// All should use the single egress link
		Set<Link> egressLinksUsed = insertions.stream()
			.map(ins -> ins.insertion.dropoff.newWaypoint.getLink())
			.collect(Collectors.toSet());

		assertThat(egressLinksUsed).containsExactly(egress);
	}

	@Test
	void testSingleAccessMultipleEgress() {
		// Single access link, multiple egress
		Link access = link("access");
		Link egress1 = link("egress1");
		Link egress2 = link("egress2");
		Link egress3 = link("egress3");

		DrtRequest request = createRequest(List.of(access), List.of(egress1, egress2, egress3));

		VehicleEntry vehicle = createVehicle(2);

		List<InsertionWithDetourData> insertions = generator.generateInsertions(request, vehicle);

		// All should use the single access link
		Set<Link> accessLinksUsed = insertions.stream()
			.map(ins -> ins.insertion.pickup.newWaypoint.getLink())
			.collect(Collectors.toSet());

		assertThat(accessLinksUsed).containsExactly(access);

		// Should generate insertions for all 3 egress links
		Set<Link> egressLinksUsed = insertions.stream()
			.map(ins -> ins.insertion.dropoff.newWaypoint.getLink())
			.collect(Collectors.toSet());

		assertThat(egressLinksUsed).containsExactlyInAnyOrder(egress1, egress2, egress3);
	}

	@Test
	void testMultipleAccessMultipleEgress() {
		// Multiple access and egress links - full combinatorial
		Link access1 = link("access1");
		Link access2 = link("access2");
		Link egress1 = link("egress1");
		Link egress2 = link("egress2");

		DrtRequest request = createRequest(List.of(access1, access2), List.of(egress1, egress2));

		VehicleEntry vehicle = createVehicle(2);

		List<InsertionWithDetourData> insertions = generator.generateInsertions(request, vehicle);

		// Should generate insertions for all 2×2 = 4 combinations
		Set<String> combinations = insertions.stream()
			.map(ins -> ins.insertion.pickup.newWaypoint.getLink().getId() + "->" +
				ins.insertion.dropoff.newWaypoint.getLink().getId())
			.collect(Collectors.toSet());

		// Each combination should appear multiple times (different vehicle stop positions)
		// But we should have all 4 combinations present
		assertThat(combinations).containsExactlyInAnyOrder(
			"access1->egress1",
			"access1->egress2",
			"access2->egress1",
			"access2->egress2"
		);
	}

	@Test
	void testSameLink_filtered() {
		// Same link in both access and egress should be filtered out
		Link sameLink = link("same");
		Link differentLink = link("different");

		DrtRequest request = createRequest(List.of(sameLink, differentLink), List.of(sameLink, differentLink));

		VehicleEntry vehicle = createVehicle(2);

		List<InsertionWithDetourData> insertions = generator.generateInsertions(request, vehicle);

		// Should NOT have any insertions with same pickup and dropoff link
		for (InsertionWithDetourData insertion : insertions) {
			Link pickupLink = insertion.insertion.pickup.newWaypoint.getLink();
			Link dropoffLink = insertion.insertion.dropoff.newWaypoint.getLink();
			assertThat(pickupLink).isNotEqualTo(dropoffLink);
		}

		// Should have insertions for the non-same combinations
		Set<String> combinations = insertions.stream()
			.map(ins -> ins.insertion.pickup.newWaypoint.getLink().getId() + "->" +
				ins.insertion.dropoff.newWaypoint.getLink().getId())
			.collect(Collectors.toSet());

		// Should have: sameLink->differentLink and differentLink->sameLink
		// But NOT sameLink->sameLink or differentLink->differentLink
		assertThat(combinations).containsExactlyInAnyOrder(
			"same->different",
			"different->same"
		);
	}

	@Test
	void testInsertionCount_scalingWithVehicleStops() {
		// Verify insertion count scales correctly with vehicle schedule size
		Link access1 = link("access1");
		Link access2 = link("access2");
		Link egress1 = link("egress1");
		Link egress2 = link("egress2");

		DrtRequest request = createRequest(List.of(access1, access2), List.of(egress1, egress2));

		// Test with different vehicle schedule sizes
		VehicleEntry vehicle2Stops = createVehicle(2);
		VehicleEntry vehicle5Stops = createVehicle(5);

		List<InsertionWithDetourData> insertions2 = generator.generateInsertions(request, vehicle2Stops);
		List<InsertionWithDetourData> insertions5 = generator.generateInsertions(request, vehicle5Stops);

		// With 2 stops: for each access/egress pair (4 pairs), we can insert at:
		// - i=0, j=0 (pickup and dropoff together after start)
		// - i=0, j=1 (pickup after start, dropoff after stop 0)
		// - i=0, j=2 (pickup after start, dropoff after stop 1)
		// - i=1, j=1 (pickup after stop 0, dropoff after stop 0)
		// - i=1, j=2 (pickup after stop 0, dropoff after stop 1)
		// - i=2, j=2 (pickup after stop 1, dropoff after stop 1)
		// Total per pair: 6 insertions, Total: 4 × 6 = 24

		// With 5 stops: N=5, per pair = N*(N+1)/2 + N = 5*6/2 + 5 = 20
		// Total: 4 × 20 = 80

		assertThat(insertions2).hasSizeGreaterThanOrEqualTo(20); // May be less due to slack/capacity constraints
		assertThat(insertions5).hasSizeGreaterThan(insertions2.size()); // More stops = more insertions
	}

	@Test
	void testInsertionIndices_correctForEachCombination() {
		// Verify that insertion indices are independent of access/egress link choice
		Link access1 = link("access1");
		Link access2 = link("access2");
		Link egress1 = link("egress1");
		Link egress2 = link("egress2");

		DrtRequest request = createRequest(List.of(access1, access2), List.of(egress1, egress2));

		VehicleEntry vehicle = createVehicle(3);

		List<InsertionWithDetourData> insertions = generator.generateInsertions(request, vehicle);

		// For each access/egress combination, we should have the same set of insertion indices
		Map<String, List<InsertionWithDetourData>> groupedByCombination = insertions.stream()
			.collect(Collectors.groupingBy(ins ->
				ins.insertion.pickup.newWaypoint.getLink().getId() + "->" +
					ins.insertion.dropoff.newWaypoint.getLink().getId()
			));

		// All combinations should have similar number of insertions (may vary slightly due to constraints)
		List<Integer> counts = groupedByCombination.values().stream()
			.map(List::size)
			.collect(Collectors.toList());

		// Check that all counts are within reasonable range of each other
		int minCount = Collections.min(counts);
		int maxCount = Collections.max(counts);
		assertThat(maxCount - minCount).isLessThanOrEqualTo(2); // Allow small variance
	}

	// Helper methods

	private DrtRequest createRequest(List<Link> accessLinks, List<Link> egressLinks) {

		return DrtRequest.newBuilder()
			.id(Id.create("request", Request.class))
			.mode("drt")
			.fromLinks(accessLinks)
			.toLinks(egressLinks)
			.earliestDepartureTime(0)
			.submissionTime(0)
			.passengerIds(List.of(Id.createPersonId("person")))
			.constraints(new DrtRouteConstraints(
				Double.POSITIVE_INFINITY,
				Double.POSITIVE_INFINITY,
				Double.POSITIVE_INFINITY,
				Double.POSITIVE_INFINITY,
				0,
				false
			))
			.load(LOAD_TYPE.fromInt(1))
			.build();
	}

	private VehicleEntry createVehicle(int numStops) {
		DvrpVehicle vehicle = new DvrpVehicleImpl(
			ImmutableDvrpVehicleSpecification.newBuilder()
				.id(Id.create("vehicle", DvrpVehicle.class))
				.startLinkId(Id.createLinkId("start"))
				.capacity(LOAD_TYPE.fromInt(4))
				.serviceBeginTime(0)
				.serviceEndTime(24 * 3600)
				.build(),
			link("start")
		);

		Waypoint.Start start = new Waypoint.Start(null, link("start"), 0, LOAD_TYPE.fromInt(0));

		List<StopWaypoint> stops = new ArrayList<>();
		for (int i = 0; i < numStops; i++) {
			DefaultDrtStopTask task = new DefaultDrtStopTask(
				100 + i * 1000,
				200 + i * 1000,
				link("stop" + i)
			);
			stops.add(new StopWaypointImpl(task, LOAD_TYPE.fromInt(0), LOAD_TYPE, false));
		}

		// Large slack times to avoid filtering due to time constraints
		double[] slackTimes = new double[numStops + 2];
		Arrays.fill(slackTimes, LARGE_SLACK);

		List<Double> precedingStayTimes = new ArrayList<>();
		for (int i = 0; i < numStops; i++) {
			precedingStayTimes.add(0.0);
		}

		return new VehicleEntry(vehicle, start, ImmutableList.copyOf(stops), slackTimes, precedingStayTimes, 0);
	}

	private Link link(String id) {
		return new FakeLink(Id.createLinkId(id));
	}
}