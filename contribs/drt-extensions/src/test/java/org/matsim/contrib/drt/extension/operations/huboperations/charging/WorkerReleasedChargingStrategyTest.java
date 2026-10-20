/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.huboperations.charging;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.ev.charging.ChargingStrategy;

import static org.assertj.core.api.Assertions.assertThat;

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
}
