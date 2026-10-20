/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.huboperations.analysis;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.drt.extension.operations.huboperations.CleaningType;
import org.matsim.contrib.drt.extension.operations.huboperations.events.CleaningQueuedEvent;
import org.matsim.contrib.drt.extension.operations.huboperations.events.CleaningStartedEvent;
import org.matsim.contrib.drt.extension.operations.huboperations.events.HubChargingPluggedEvent;
import org.matsim.contrib.drt.extension.operations.huboperations.events.HubChargingTargetReachedEvent;
import org.matsim.contrib.drt.extension.operations.huboperations.events.HubChargingUnpluggedEvent;
import org.matsim.contrib.drt.extension.operations.huboperations.schedule.CleaningTask;
import org.matsim.contrib.drt.extension.operations.operationFacilities.OperationFacility;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.vrpagent.TaskEndedEvent;
import org.matsim.contrib.dvrp.vrpagent.TaskStartedEvent;
import org.matsim.contrib.ev.charging.ChargingStartEvent;
import org.matsim.contrib.ev.infrastructure.Charger;
import org.matsim.vehicles.Vehicle;

import java.util.EnumSet;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.matsim.contrib.drt.extension.operations.huboperations.analysis.HubTimeProfileCalculator.CHARGING;
import static org.matsim.contrib.drt.extension.operations.huboperations.analysis.HubTimeProfileCalculator.EXTERIOR;
import static org.matsim.contrib.drt.extension.operations.huboperations.analysis.HubTimeProfileCalculator.INTERIOR;
import static org.matsim.contrib.drt.extension.operations.huboperations.analysis.HubTimeProfileCalculator.WAIT_FOR_WORKER;

/**
 * The split of hub visit time into activities, with equal shares for activities that run in parallel.
 *
 * @author lpfeil / MOIA
 */
public class HubTimeProfileCalculatorTest {

	private static final String MODE = "drt";
	private static final int BIN = 300;
	private static final Id<DvrpVehicle> V = Id.create("v", DvrpVehicle.class);
	private static final Id<OperationFacility> HUB = Id.create("1", OperationFacility.class);
	private static final Id<Charger> CHARGER = Id.create("c", Charger.class);
	private static final Id<Link> LINK = Id.createLinkId("l");

	private static HubTimeProfileCalculator calculator() {
		// interior 600 s, exterior 300 s
		return new HubTimeProfileCalculator(MODE, BIN, 6000, 600, 300);
	}

	/** Vehicle seconds in a column over the whole profile. */
	private static double seconds(HubTimeProfileCalculator calc, String column) {
		double sum = 0;
		for (double v : calc.getProfiles().get(column)) {
			sum += v * BIN;
		}
		return sum;
	}

	private static void taskStarted(HubTimeProfileCalculator calc, double time) {
		calc.handleEvent(new TaskStartedEvent(time, MODE, V, null, CleaningTask.TYPE, 3, LINK));
	}

	private static void taskEnded(HubTimeProfileCalculator calc, double time) {
		calc.handleEvent(new TaskEndedEvent(time, MODE, V, null, CleaningTask.TYPE, 3, LINK));
	}

	@Test
	void cleaningWhileChargingSharesTheVehicleInHalves() {
		HubTimeProfileCalculator calc = calculator();
		taskStarted(calc, 0);
		calc.handleEvent(new CleaningQueuedEvent(0, MODE, V, HUB));
		// 300 s waiting for a worker
		calc.handleEvent(new CleaningStartedEvent(300, MODE, V, HUB,
				EnumSet.of(CleaningType.interior, CleaningType.exterior, CleaningType.charging)));
		calc.handleEvent(new HubChargingPluggedEvent(300, MODE, V, HUB, CHARGER, 0.3, null));
		calc.handleEvent(new ChargingStartEvent(300, CHARGER, Id.create(V, Vehicle.class), 0));
		// interior 300-900 and exterior 900-1200 run while charging: each half of the vehicle
		calc.handleEvent(new HubChargingTargetReachedEvent(1500, MODE, V, 0.9));
		// 1200-1500 charging alone, 1500-1800 waiting for a worker to unplug it
		calc.handleEvent(new HubChargingUnpluggedEvent(1800, MODE, V, HUB, CHARGER, 0.9, null, 300, 0));
		taskEnded(calc, 1800);

		assertThat(seconds(calc, WAIT_FOR_WORKER)).as("300 s to start plus 300 s to unplug")
				.isCloseTo(600, within(1e-6));
		assertThat(seconds(calc, INTERIOR)).isCloseTo(300, within(1e-6)); // 600 s at one half
		assertThat(seconds(calc, EXTERIOR)).isCloseTo(150, within(1e-6)); // 300 s at one half
		assertThat(seconds(calc, CHARGING)).isCloseTo(450 + 300, within(1e-6)); // halves while cleaning, then whole
		double total = HubTimeProfileCalculator.COLUMNS.stream().mapToDouble(c -> seconds(calc, c)).sum();
		assertThat(total).as("the vehicle counts once over the whole visit").isCloseTo(1800, within(1e-6));
	}

	@Test
	void interiorAndExteriorFollowEachOther() {
		HubTimeProfileCalculator calc = calculator();
		taskStarted(calc, 0);
		calc.handleEvent(new CleaningStartedEvent(0, MODE, V, HUB,
				EnumSet.of(CleaningType.interior, CleaningType.exterior)));
		taskEnded(calc, 900);
		double[] interior = calc.getProfiles().get(INTERIOR);
		double[] exterior = calc.getProfiles().get(EXTERIOR);
		// interior 0-600 in bins 0 and 1, exterior 600-900 in bin 2, each the whole vehicle
		assertThat(interior[0] + interior[1]).isCloseTo(2.0, within(1e-9));
		assertThat(exterior[0] + exterior[1]).isZero();
		assertThat(exterior[2]).isCloseTo(1.0, within(1e-9));
		assertThat(interior[2]).isZero();
	}

	@Test
	void chargedVehicleBeingCleanedCountsFullyAsCleaning() {
		HubTimeProfileCalculator calc = calculator();
		taskStarted(calc, 0);
		calc.handleEvent(new CleaningStartedEvent(0, MODE, V, HUB,
				EnumSet.of(CleaningType.interior, CleaningType.charging)));
		calc.handleEvent(new HubChargingPluggedEvent(0, MODE, V, HUB, CHARGER, 0.8, null));
		calc.handleEvent(new ChargingStartEvent(0, CHARGER, Id.create(V, Vehicle.class), 0));
		calc.handleEvent(new HubChargingTargetReachedEvent(300, MODE, V, 0.9));
		taskEnded(calc, 600);
		assertThat(seconds(calc, CHARGING)).isCloseTo(150, within(1e-6)); // 0-300 at one half
		assertThat(seconds(calc, INTERIOR)).isCloseTo(150 + 300, within(1e-6)); // half, then whole once charged
		assertThat(seconds(calc, WAIT_FOR_WORKER)).isZero();
	}

	@Test
	void cleaningWithoutChargingTakesTheWholeVehicle() {
		HubTimeProfileCalculator calc = calculator();
		taskStarted(calc, 0);
		calc.handleEvent(new CleaningStartedEvent(0, MODE, V, HUB, EnumSet.of(CleaningType.interior)));
		taskEnded(calc, 600);
		assertThat(seconds(calc, INTERIOR)).isCloseTo(600, within(1e-6));
		assertThat(seconds(calc, CHARGING)).isZero();
	}

	@Test
	void parkedVisitIsTrackedFromItsHubEventsAndReportedAsParked() {
		HubTimeProfileCalculator calc = calculator();
		calc.handleEvent(new CleaningQueuedEvent(0, MODE, V, HUB));
		calc.handleEvent(new CleaningStartedEvent(0, MODE, V, HUB, EnumSet.of(CleaningType.exterior)));
		calc.handleEvent(new org.matsim.contrib.drt.extension.operations.huboperations.events.CleaningEndedEvent(300,
				MODE, V, HUB, EnumSet.of(CleaningType.exterior)));
		assertThat(seconds(calc, EXTERIOR)).isCloseTo(300, within(1e-6));
		double parked = 0;
		for (double v : calc.getParkedProfile()) {
			parked += v * BIN;
		}
		assertThat(parked).as("moved out of WAIT_FOR_SHIFT").isCloseTo(300, within(1e-6));
	}

	@Test
	void binsHoldVehiclesNotSeconds() {
		HubTimeProfileCalculator calc = calculator();
		taskStarted(calc, 0);
		calc.handleEvent(new CleaningStartedEvent(0, MODE, V, HUB, EnumSet.of(CleaningType.interior)));
		taskEnded(calc, 600);
		Map<String, double[]> profiles = calc.getProfiles();
		assertThat(profiles.get(INTERIOR)[0]).isCloseTo(1.0, within(1e-9));
		assertThat(profiles.get(INTERIOR)[1]).isCloseTo(1.0, within(1e-9));
		assertThat(profiles.get(INTERIOR)[2]).isZero();
	}

	@Test
	void maintenanceVisitFillsItsOwnColumn() {
		HubTimeProfileCalculator calc = new HubTimeProfileCalculator(MODE, BIN, 6000, 600, 300, 900);
		taskStarted(calc, 0);
		calc.handleEvent(new CleaningQueuedEvent(0, MODE, V, HUB, true));
		calc.handleEvent(new CleaningStartedEvent(300, MODE, V, HUB, EnumSet.of(CleaningType.maintenance)));
		taskEnded(calc, 1200);

		assertThat(seconds(calc, HubTimeProfileCalculator.MAINTENANCE)).isCloseTo(900, within(1e-6));
		assertThat(seconds(calc, WAIT_FOR_WORKER)).isCloseTo(300, within(1e-6));
		assertThat(seconds(calc, INTERIOR) + seconds(calc, EXTERIOR) + seconds(calc, CHARGING)).isZero();
	}
}
