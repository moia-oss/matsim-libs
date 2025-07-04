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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.contrib.drt.optimizer.VehicleEntry;
import org.matsim.contrib.drt.optimizer.Waypoint;
import org.matsim.contrib.drt.optimizer.insertion.ForkJoinPoolExtension;
import org.matsim.contrib.drt.optimizer.insertion.InsertionCostCalculator;
import org.matsim.contrib.drt.optimizer.insertion.InsertionDetourTimeCalculator.DetourTimeInfo;
import org.matsim.contrib.drt.optimizer.insertion.InsertionDetourTimeCalculator.PickupDetourInfo;
import org.matsim.contrib.drt.optimizer.insertion.InsertionGenerator;
import org.matsim.contrib.drt.optimizer.insertion.InsertionGenerator.Insertion;
import org.matsim.contrib.drt.optimizer.insertion.InsertionWithDetourData;
import org.matsim.contrib.drt.optimizer.insertion.InsertionWithDetourData.InsertionDetourData;
import org.matsim.contrib.drt.passenger.DrtRequest;
import org.matsim.contrib.drt.stops.DefaultStopTimeCalculator;
import org.matsim.contrib.dvrp.load.IntegerLoadType;

/**
 * @author Michal Maciejewski (michalm)
 */
public class ExtensiveInsertionProviderTest {

	private final IntegerLoadType loadType = new IntegerLoadType("passengers");

	@RegisterExtension
	public final ForkJoinPoolExtension rule = new ForkJoinPoolExtension();

	@Test
	void getInsertions_noInsertionsGenerated() {
		var insertionProvider = new ExtensiveInsertionProvider(null, null, new InsertionGenerator(new DefaultStopTimeCalculator(120), null),
				rule.forkJoinPool);
		assertThat(insertionProvider.getInsertions(null, List.of())).isEmpty();
	}

	@Test
	void getInsertions_twoAtEndInsertionsGenerated_zeroNearestInsertionsAtEndLimit() {
		//the infeasible solution gets discarded in the first stage
		//the feasible solution gets discarded in the second stage (KNearestInsertionsAtEndFilter)
		getInsertions_twoInsertionsGenerated(0);
	}

	@Test
	void getInsertions_twoAtEndInsertionsGenerated_tenNearestInsertionsAtEndLimit() {
		//the infeasible solution gets discarded in the first stage
		//the feasible solution is NOT discarded in the second stage (KNearestInsertionsAtEndFilter)
		getInsertions_twoInsertionsGenerated(10);
	}

	private void getInsertions_twoInsertionsGenerated(int nearestInsertionsAtEndLimit) {
		var request = DrtRequest.newBuilder().build();
		var vehicleEntry = mock(VehicleEntry.class);

		// mock insertionGenerator
		var feasibleInsertion = new Insertion(vehicleEntry, insertionPoint(), insertionPoint(), loadType.fromInt(1));
		var infeasibleInsertion = new Insertion(vehicleEntry, insertionPoint(), insertionPoint(), loadType.fromInt(1));
		var insertionGenerator = mock(InsertionGenerator.class);
		when(insertionGenerator.generateInsertions(eq(request), eq(vehicleEntry)))//
				.thenReturn(List.of(insertionWithDetourData(feasibleInsertion),
						insertionWithDetourData(infeasibleInsertion)));

		//mock admissibleCostCalculator
		var admissibleCostCalculator = (InsertionCostCalculator)mock(InsertionCostCalculator.class);
		when(admissibleCostCalculator.calculate(eq(request), argThat(argument -> argument == feasibleInsertion),
				any())).thenReturn(1.);
		when(admissibleCostCalculator.calculate(eq(request), argThat(argument -> argument == infeasibleInsertion),
				any())).thenReturn(InsertionCostCalculator.INFEASIBLE_SOLUTION_COST);

		//test insertionProvider
		var params = new ExtensiveInsertionSearchParams();
		params.setNearestInsertionsAtEndLimit(nearestInsertionsAtEndLimit);
		//pretend all insertions are at end to check KNearestInsertionsAtEndFilter
		when(vehicleEntry.isAfterLastStop(anyInt())).thenReturn(true);
		var insertionProvider = new ExtensiveInsertionProvider(params, admissibleCostCalculator, insertionGenerator,
				rule.forkJoinPool);
		assertThat(insertionProvider.getInsertions(request, List.of(vehicleEntry))).isEqualTo(
				nearestInsertionsAtEndLimit == 0 ? List.of() : List.of(feasibleInsertion));
	}

	private InsertionGenerator.InsertionPoint insertionPoint() {
		return new InsertionGenerator.InsertionPoint(-1, mock(Waypoint.class), null, mock(Waypoint.class));
	}

	private InsertionWithDetourData insertionWithDetourData(Insertion insertion) {
		return new InsertionWithDetourData(insertion, new InsertionDetourData(null, null, null, null),
				new DetourTimeInfo(new PickupDetourInfo(11, 11, Double.NaN), null));
	}
}
