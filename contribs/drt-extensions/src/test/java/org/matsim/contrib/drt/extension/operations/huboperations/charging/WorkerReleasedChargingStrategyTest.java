/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.huboperations.charging;

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.ev.EvUnits;
import org.matsim.contrib.ev.charging.ChargingListener;
import org.matsim.contrib.ev.charging.ChargingPriority;
import org.matsim.contrib.ev.charging.ChargingStrategy;
import org.matsim.contrib.ev.charging.ChargingWithQueueingLogic;
import org.matsim.contrib.ev.charging.DefaultChargerPower;
import org.matsim.contrib.ev.charging.FastThenSlowCharging;
import org.matsim.contrib.ev.fleet.ElectricFleetUtils;
import org.matsim.contrib.ev.fleet.ElectricVehicle;
import org.matsim.contrib.ev.fleet.ElectricVehicleSpecification;
import org.matsim.contrib.ev.infrastructure.Charger;
import org.matsim.contrib.ev.infrastructure.ChargerSpecification;
import org.matsim.contrib.ev.infrastructure.ImmutableChargerSpecification;
import org.matsim.core.events.EventsUtils;
import org.matsim.vehicles.Vehicle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The vehicle stays on the plug after reaching its target, asks for no more energy, and is released by a cancel.
 *
 * @author lpfeil / MOIA
 */
public class WorkerReleasedChargingStrategyTest {

	private static final class Fake implements ChargingStrategy {
		private double remaining;

		private Fake(double remaining) {
			this.remaining = remaining;
		}

		@Override
		public boolean isChargingCompleted() {
			return remaining <= 0;
		}

		@Override
		public double calcRemainingEnergyToCharge() {
			return remaining;
		}

		@Override
		public double calcRemainingTimeToCharge() {
			return remaining / 10;
		}
	}

	@Test
	void chargesLikeTheDelegateUntilTheTarget() {
		WorkerReleasedChargingStrategy strategy = new WorkerReleasedChargingStrategy(new Fake(100));
		assertThat(strategy.isTargetReached()).isFalse();
		assertThat(strategy.isChargingCompleted()).isFalse();
		assertThat(strategy.calcRemainingEnergyToCharge()).isEqualTo(100);
		assertThat(strategy.calcRemainingTimeToCharge()).isEqualTo(10);
	}

	@Test
	void staysOnThePlugWithoutChargingAfterTheTarget() {
		Fake delegate = new Fake(0);
		WorkerReleasedChargingStrategy strategy = new WorkerReleasedChargingStrategy(delegate);
		assertThat(strategy.isTargetReached()).isTrue();
		assertThat(strategy.isChargingCompleted()).as("not ended by the ev logic").isFalse();
		assertThat(strategy.calcRemainingEnergyToCharge()).isZero();
		// a target the battery overshot asks for no negative energy either
		delegate.remaining = -5;
		assertThat(strategy.calcRemainingEnergyToCharge()).isZero();
		assertThat(strategy.calcRemainingTimeToCharge()).isZero();
	}

	@Test
	void cancelHandsTheEndBackToTheEvLogic() {
		WorkerReleasedChargingStrategy strategy = new WorkerReleasedChargingStrategy(new Fake(100));
		strategy.cancel();
		assertThat(strategy.isChargingCompleted()).isTrue();
	}

	/**
	 * A charge cancelled before the ev logic's charging step still holds the vehicle until a later step ends it. Adding
	 * the vehicle again within the same charging period plugs it in twice, which the hub dispatcher prevents by waiting
	 * for the ended charge.
	 */
	@Test
	void aChargeCancelledBeforeTheChargingStepHoldsTheVehicleUntilItEnds() {
		ChargerSpecification spec = ImmutableChargerSpecification.newBuilder()
				.id(Id.create("charger", Charger.class))
				.chargerType(ChargerSpecification.DEFAULT_CHARGER_TYPE)
				.linkId(Id.createLinkId("link"))
				.plugPower(EvUnits.kW_to_W(100))
				.plugCount(2)
				.build();
		double step = 10;
		ElectricVehicle ev = electricVehicle();
		boolean[] ended = {false};
		ChargingListener listener = new ChargingListener() {
			@Override
			public void notifyChargingEnded(ElectricVehicle vehicle, double now) {
				ended[0] = true;
			}
		};

		ChargingWithQueueingLogic logic = new ChargingWithQueueingLogic(spec, EventsUtils.createEventsManager(),
				ChargingPriority.FIFO.create(spec), new DefaultChargerPower(spec, step));
		WorkerReleasedChargingStrategy first = new WorkerReleasedChargingStrategy(new Fake(1e6));
		logic.addVehicle(ev, first, listener, 0);
		first.cancel(); // taken off before the charging step plugged it
		// a new visit adds it again within the same charging period: the step plugs the same vehicle twice
		logic.addVehicle(ev, new WorkerReleasedChargingStrategy(new Fake(1e6)), listener, 5);
		assertThatThrownBy(() -> logic.chargeVehicles(step, 9)).isInstanceOf(IllegalArgumentException.class);

		ChargingWithQueueingLogic fresh = new ChargingWithQueueingLogic(spec, EventsUtils.createEventsManager(),
				ChargingPriority.FIFO.create(spec), new DefaultChargerPower(spec, step));
		WorkerReleasedChargingStrategy cancelled = new WorkerReleasedChargingStrategy(new Fake(1e6));
		fresh.addVehicle(ev, cancelled, listener, 0);
		cancelled.cancel();
		fresh.chargeVehicles(step, 9); // plugs the cancelled charge in
		assertThat(ended[0]).as("still held by the charger").isFalse();
		fresh.chargeVehicles(step, 19); // ends the cancelled charge: the vehicle is free again
		assertThat(ended[0]).isTrue();
		fresh.addVehicle(ev, new WorkerReleasedChargingStrategy(new Fake(1e6)), listener, 20);
		fresh.chargeVehicles(step, 29);
		assertThat(fresh.getPluggedVehicles()).hasSize(1);
	}

	private static ElectricVehicle electricVehicle() {
		record Spec(Id<Vehicle> getId, Vehicle getMatsimVehicle, String getVehicleType,
					ImmutableList<String> getChargerTypes, double getBatteryCapacity,
					double getInitialSoc) implements ElectricVehicleSpecification {
		}
		var spec = new Spec(Id.create("ev", Vehicle.class), null, "vt",
				ImmutableList.of(ChargerSpecification.DEFAULT_CHARGER_TYPE), EvUnits.kWh_to_J(77), 0.3);
		return ElectricFleetUtils.create(spec, ev -> (link, travelTime, linkEnterTime) -> {
			throw new UnsupportedOperationException();
		}, ev -> (beginTime, duration, linkId) -> {
			throw new UnsupportedOperationException();
		}, ev -> new FastThenSlowCharging(ev.getBattery()));
	}
}
