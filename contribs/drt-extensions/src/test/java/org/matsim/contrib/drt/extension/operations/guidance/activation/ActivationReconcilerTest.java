/*
 * Copyright (C) 2026 MOIA GmbH - All Rights Reserved
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.guidance.activation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the activation reconciliation (RF6): pure over a hand-built {@link GuidanceState}, no QSim. Documents
 * the default-trigger band that damps the low-demand sawtooth (the point of RF1 / D17).
 *
 * @author nkuehnel / MOIA
 */
public class ActivationReconcilerTest {

	private static final double NOW = 3600.0;

	/** The default RG activation policy: one ready buffer over the reconciler's floor (nMin). */
	private static ActivationReconciler defaultReconciler(int nMin) {
		return new ActivationReconciler(List.of(new IdleBufferActivation()), nMin);
	}

	@Test
	void idleBuffer_activatesOneWhenNoReadySpare() {
		// nothing active, no idle-in-service buffer, plenty idle at hub, capacity available → activate exactly one
		GuidanceState state = new GuidanceState(0, 10, 5, 0, 0.0);
		assertThat(defaultReconciler(0).toEmit(state, NOW)).isEqualTo(1);
	}

	@Test
	void idleBuffer_activatesNothingWhenBufferAlreadyPresent() {
		// one active vehicle idle in service = the ready buffer is satisfied → do not ramp up further
		GuidanceState state = new GuidanceState(1, 10, 5, 1, 0.0);
		assertThat(defaultReconciler(0).toEmit(state, NOW)).isZero();
	}

	@Test
	void idleBuffer_doesNotGreedilyFillCapacity() {
		// contrast with the greedy baseline: with 8 idle-at-hub vehicles and capacity 10, the default set keeps just one
		// buffer, whereas greedy would pull all 8 in.
		GuidanceState state = new GuidanceState(2, 10, 8, 0, 0.0);
		assertThat(defaultReconciler(0).toEmit(state, NOW)).isEqualTo(1);

		ActivationReconciler greedy = new ActivationReconciler(List.of(new GreedyIdleActivation()), 0);
		assertThat(greedy.toEmit(state, NOW)).isEqualTo(8);
	}

	@Test
	void floor_pullsFleetUpToNMin() {
		// hard floor of 4, nothing active, buffer already present (so IdleBuffer proposes nothing) → floor still emits 4
		GuidanceState state = new GuidanceState(0, 10, 6, 1, 0.0);
		assertThat(defaultReconciler(4).toEmit(state, NOW)).isEqualTo(4);
	}

	@Test
	void ceiling_clampsDesiredToActivationCapacity() {
		// floor 8 but capacity only 5 → never emit past the ceiling
		GuidanceState state = new GuidanceState(0, 5, 10, 0, 0.0);
		assertThat(defaultReconciler(8).toEmit(state, NOW)).isEqualTo(5);
	}

	@Test
	void noFreeCapacity_emitsNothing() {
		// already at the ceiling → nothing to emit even without a ready buffer
		GuidanceState state = new GuidanceState(10, 10, 3, 0, 0.0);
		assertThat(defaultReconciler(0).toEmit(state, NOW)).isZero();
	}

	@Test
	void limitedByIdleAtHub_cannotEmitMoreThanAvailableSource() {
		// floor wants 6 but only 2 vehicles idle at a hub can be activated this step
		GuidanceState state = new GuidanceState(0, 10, 2, 0, 0.0);
		assertThat(defaultReconciler(6).toEmit(state, NOW)).isEqualTo(2);
	}

	@Test
	void reconcilerNeverRecalls_returnsZeroNotNegativeWhenOverDesired() {
		// active count exceeds every trigger's desire (e.g. after a demand spike subsided) → emit 0, never negative;
		// bringing the fleet down is the deactivation side's job.
		GuidanceState state = new GuidanceState(7, 10, 3, 2, 0.0);
		assertThat(defaultReconciler(0).toEmit(state, NOW)).isZero();
	}
}
