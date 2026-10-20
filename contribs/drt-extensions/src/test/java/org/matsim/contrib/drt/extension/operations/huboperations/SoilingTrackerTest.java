/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.huboperations;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.drt.extension.operations.huboperations.charging.HubChargingPolicy;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.matsim.contrib.drt.extension.operations.huboperations.CleaningType.charging;
import static org.matsim.contrib.drt.extension.operations.huboperations.CleaningType.exterior;
import static org.matsim.contrib.drt.extension.operations.huboperations.CleaningType.interior;

/**
 * Accounting tests for {@link SoilingTracker}: passenger and vehicle kilometres, the threshold crossings, bundling and
 * the reset.
 *
 * @author lpfeil / MOIA
 */
public class SoilingTrackerTest {

	private static final Id<DvrpVehicle> V = Id.create("v", DvrpVehicle.class);
	private static final double OFF = Double.POSITIVE_INFINITY;

	@Test
	void emptyKilometresAccrueExteriorSoilingOnly() {
		SoilingTracker tracker = new SoilingTracker(1.0, OFF, 1.0);
		assertThat(tracker.addLink(V, 5_000)).isEmpty();
		assertThat(tracker.getPassengerKm(V)).isZero();
		assertThat(tracker.getVehicleKm(V)).isEqualTo(5.0);
	}

	@Test
	void interiorSoilingScalesWithPassengersOnBoard() {
		SoilingTracker tracker = new SoilingTracker(100.0, OFF, 1.0);
		tracker.pickUp(V);
		tracker.pickUp(V);
		tracker.addLink(V, 1_000);
		assertThat(tracker.getPassengerKm(V)).isEqualTo(2.0);
		tracker.dropOff(V);
		tracker.addLink(V, 1_000);
		assertThat(tracker.getPassengerKm(V)).isEqualTo(3.0);
		assertThat(tracker.getVehicleKm(V)).isEqualTo(2.0);
	}

	@Test
	void thresholdCrossingIsReportedOncePerType() {
		SoilingTracker tracker = new SoilingTracker(2.0, 3.0, 1.0);
		tracker.pickUp(V);
		assertThat(tracker.addLink(V, 1_500)).isEmpty();
		assertThat(tracker.addLink(V, 1_000)).containsExactly(interior); // 2.5 pkm crosses 2.0
		assertThat(tracker.isDue(V)).isTrue();
		assertThat(tracker.addLink(V, 1_000)).containsExactly(exterior); // 3.5 vkm crosses 3.0
		assertThat(tracker.addLink(V, 1_000)).isEmpty();
		assertThat(tracker.getDueTypes(V)).containsExactlyInAnyOrder(interior, exterior);
	}

	@Test
	void exteriorCrossesOnEmptyKilometres() {
		SoilingTracker tracker = new SoilingTracker(OFF, 0.7, 1.0);
		assertThat(tracker.addLink(V, 700)).containsExactly(exterior);
		assertThat(tracker.getDueTypes(V)).containsExactly(exterior);
	}

	@Test
	void typeNearItsThresholdIsBundled() {
		// interior due at 500 pkm; exterior at 600 of 700 vkm is above 0.8 * 700 = 560 and is cleaned along
		SoilingTracker tracker = new SoilingTracker(500, 700, 0.8);
		tracker.addLink(V, 100_000); // 100 empty vkm
		tracker.pickUp(V);
		assertThat(tracker.addLink(V, 500_000)).containsExactly(interior); // 600 vkm, 500 pkm
		assertThat(tracker.typesToClean(V)).containsExactlyInAnyOrder(interior, exterior);
	}

	@Test
	void typeFarFromItsThresholdIsNotBundled() {
		// exterior at 500 of 700 vkm is below 560 and waits for its own visit
		SoilingTracker tracker = new SoilingTracker(500, 700, 0.8);
		tracker.pickUp(V);
		tracker.addLink(V, 500_000);
		assertThat(tracker.typesToClean(V)).containsExactly(interior);
	}

	@Test
	void bundlingShareOneCleansOnlyDueTypes() {
		SoilingTracker tracker = new SoilingTracker(500, 700, 1.0);
		tracker.addLink(V, 190_000);
		tracker.pickUp(V);
		tracker.addLink(V, 500_000);
		assertThat(tracker.typesToClean(V)).containsExactly(interior);
	}

	@Test
	void resetClearsOnlyTheCleanedTypes() {
		SoilingTracker tracker = new SoilingTracker(1.0, 10.0, 1.0);
		tracker.pickUp(V);
		tracker.addLink(V, 2_000);
		assertThat(tracker.isDue(V)).isTrue();
		tracker.reset(V, EnumSet.of(interior));
		assertThat(tracker.isDue(V)).isFalse();
		assertThat(tracker.getPassengerKm(V)).isZero();
		assertThat(tracker.getVehicleKm(V)).as("exterior keeps counting").isEqualTo(2.0);
		assertThat(tracker.getOnBoard(V)).isEqualTo(1);
		// the next cycle crosses the interior threshold again
		assertThat(tracker.addLink(V, 1_000)).containsExactly(interior);
		tracker.reset(V, Set.of(interior, exterior));
		assertThat(tracker.getVehicleKm(V)).isZero();
	}

	@Test
	void chargingIsDueWhenTheBatteryFallsToTheThreshold() {
		SoilingTracker tracker = new SoilingTracker(OFF, OFF, 0.3, 1.0);
		assertThat(tracker.updateBattery(V, 0.5)).isFalse();
		assertThat(tracker.updateBattery(V, 0.3)).isTrue();
		assertThat(tracker.updateBattery(V, 0.2)).as("reported once").isFalse();
		assertThat(tracker.getDueTypes(V)).containsExactly(charging);
		tracker.reset(V, EnumSet.of(charging));
		assertThat(tracker.isDue(V)).isFalse();
		// still low after a visit that could not charge: not due again until it is charged above and falls again
		assertThat(tracker.updateBattery(V, 0.2)).isFalse();
		tracker.updateBattery(V, 0.9);
		assertThat(tracker.updateBattery(V, 0.25)).isTrue();
	}

	@Test
	void chargingIsBundledFromTheShareOfBatteryUsed() {
		// due at SOC 0.3, i.e. 70 percent used; bundled from 0.8 * 70 = 56 percent used, i.e. SOC 0.44
		SoilingTracker tracker = new SoilingTracker(500, OFF, 0.3, 0.8);
		tracker.updateBattery(V, 0.45);
		tracker.pickUp(V);
		tracker.addLink(V, 500_000);
		assertThat(tracker.typesToClean(V)).containsExactly(interior);
		tracker.updateBattery(V, 0.43);
		assertThat(tracker.typesToClean(V)).containsExactlyInAnyOrder(interior, charging);
	}

	@Test
	void cleaningIsBundledWithADueCharge() {
		SoilingTracker tracker = new SoilingTracker(500, 700, 0.3, 0.8);
		tracker.addLink(V, 600_000); // exterior at 600 of 700 vkm
		tracker.updateBattery(V, 0.2);
		assertThat(tracker.typesToClean(V)).containsExactlyInAnyOrder(exterior, charging);
	}

	@Test
	void vehicleWithoutBatteryNeverChargesAndZeroThresholdSwitchesItOff() {
		SoilingTracker tracker = new SoilingTracker(1, OFF, 0.3, 0.0);
		assertThat(tracker.typesToClean(V)).as("no battery read").doesNotContain(charging);
		SoilingTracker off = new SoilingTracker(1, OFF, 0., 0.0);
		assertThat(off.updateBattery(V, 0.)).isFalse();
		assertThat(off.typesToClean(V)).doesNotContain(charging);
	}

	@Test
	void policyDecidesWhenChargingIsDueAndWithdrawsAnOpportunisticCharge() {
		boolean[] relaxed = {true};
		HubChargingPolicy policy = new HubChargingPolicy() {
			@Override
			public boolean isDue(double soc) {
				return soc < 0.5 || (soc < 0.6 && relaxed[0]);
			}

			@Override
			public boolean isBundled(double soc) {
				return soc < 0.6;
			}
		};
		SoilingTracker tracker = new SoilingTracker(OFF, OFF, policy, 1.0);
		assertThat(tracker.updateBattery(V, 0.55)).as("opportunistic while relaxed").isTrue();
		relaxed[0] = false;
		assertThat(tracker.withdrawCharging(V, 0.55)).as("withdrawn once the fleet is tight").isTrue();
		assertThat(tracker.isDue(V)).isFalse();
		assertThat(tracker.updateBattery(V, 0.55)).isFalse();
		assertThat(tracker.updateBattery(V, 0.45)).as("critical is due whatever the fleet").isTrue();
		assertThat(tracker.withdrawCharging(V, 0.45)).as("a critical charge is never withdrawn").isFalse();
	}

	@Test
	void negativeOnBoardCountIsRejected() {
		SoilingTracker tracker = new SoilingTracker(1.0, OFF, 1.0);
		assertThatThrownBy(() -> tracker.dropOff(V)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void invalidParametersAreRejected() {
		assertThatThrownBy(() -> new SoilingTracker(0, 1, 1)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new SoilingTracker(1, 0, 1)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new SoilingTracker(1, 1, 1.5)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new SoilingTracker(1, 1, 1.0, 0.5)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void maintenanceHasItsOwnCounterAndIsNeverBundled() {
		SoilingTracker tracker = new SoilingTracker(500, 10, 0.0).withMaintenance(100);
		// 10 vkm: exterior due; bundling share 0 would add every type, but never maintenance
		assertThat(tracker.addLink(V, 10_000)).containsExactly(exterior);
		assertThat(tracker.typesToClean(V)).doesNotContain(CleaningType.maintenance);
		tracker.reset(V, EnumSet.of(exterior, interior));
		assertThat(tracker.getMaintenanceKm(V)).as("not reset by cleaning").isEqualTo(10.0);

		assertThat(tracker.addLink(V, 90_000)).contains(CleaningType.maintenance);
		assertThat(tracker.isMaintenanceDue(V)).isTrue();
		assertThat(tracker.typesToMaintain(V)).containsExactly(CleaningType.maintenance);
		assertThat(tracker.typesToClean(V)).as("other types wait for their own visit")
				.doesNotContain(CleaningType.maintenance).contains(exterior);

		tracker.reset(V, EnumSet.of(CleaningType.maintenance));
		assertThat(tracker.isMaintenanceDue(V)).isFalse();
		assertThat(tracker.getMaintenanceKm(V)).isZero();
		assertThat(tracker.isDue(V)).as("exterior is still due").isTrue();
	}

	@Test
	void maintenanceIsOffByDefault() {
		SoilingTracker tracker = new SoilingTracker(OFF, OFF, 1.0);
		assertThat(tracker.addLink(V, 1e9)).isEmpty();
		assertThat(tracker.isMaintenanceDue(V)).isFalse();
		assertThat(tracker.typesToMaintain(V)).isEmpty();
	}
}
