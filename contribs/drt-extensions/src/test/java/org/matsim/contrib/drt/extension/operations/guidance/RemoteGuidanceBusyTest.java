/*
 * Copyright (C) 2026 MOIA GmbH - All Rights Reserved
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.guidance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.OptionalDouble;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.drt.extension.operations.guidance.activation.ActivationReconciler;
import org.matsim.contrib.drt.extension.operations.guidance.activation.GuidanceState;

/**
 * Unit tests for {@link RemoteGuidanceScheduler#busy(int, int, int)}: the busy count fed into the shared
 * {@link BusyWindowTracker}. The bug this locks down: a live virtual vehicle that has already been recalled (routing
 * home on a materialised changeover tail — {@code leaving}) is NOT idle-in-service (its last task is no longer a stay
 * task), so before the fix it was counted as busy. That inflated the target ({@code busy + buffer}) and re-activated a
 * replacement for every vehicle going home, producing an end-of-day recall/re-activate runaway (observed: the fleet
 * ramped 40 → 400 at ~01:15 with zero real passenger work). Excluding {@code leaving} makes busy count only vehicles
 * doing passenger work, matching the deactivation side ({@link RemoteGuidanceShiftEndLogic}), which computes busy over
 * an active set that already excludes leaving vehicles.
 *
 * @author nkuehnel / MOIA
 */
public class RemoteGuidanceBusyTest {

	@Test
	void busy_excludesLeaving() {
		// the exact debugger frame at the ignition step (sim-second 90900): 40 live, 2 idle-in-service, 38 already
		// recalled (leaving) → true passenger-work busy is 0. Before the fix busy was 40 − 2 = 38.
		assertThat(RemoteGuidanceScheduler.busy(40, 2, 38)).isZero();
	}

	@Test
	void busy_noLeaving_isUnchanged() {
		// with no vehicle winding down, busy is the plain started − idleInService it always was (regression guard: the
		// leaving exclusion must be a no-op whenever leaving == 0, i.e. everywhere except during recalls).
		assertThat(RemoteGuidanceScheduler.busy(40, 2, 0)).isEqualTo(38);
		assertThat(RemoteGuidanceScheduler.busy(92, 15, 0)).isEqualTo(77);
	}

	@Test
	void busy_countsStartedNotQueueMembership() {
		// the SECOND end-of-day channel (debugger-confirmed at sim-second 91600 on the 10% sample): 93 virtual shifts in
		// the fleet's queues (activeCount), but only 20 actually STARTED — the other 73 were assigned to vehicles still
		// mid-recall (SHIFT_CHANGEOVER) and cannot start yet. Of the 20 started, 5 are idle-in-service and 15 are
		// leaving, so true passenger-work busy is 0. busy() must be fed the STARTED count (20), NOT the queue-membership
		// activeCount (93): busy(20, 5, 15) == 0. Passing 93 here would reproduce the residual runaway (see below).
		assertThat(RemoteGuidanceScheduler.busy(20, 5, 15)).isZero();
	}

	@Test
	void runawayReproducedWhenBusyUsesQueueMembership() {
		// counterfactual for the residual bug: if busy were computed over the queue-membership activeCount (93) instead
		// of the started count, busy = 93 − 5 − 15 = 73 → target 73 + buffer(20) = 93, so the fleet is held at 93 with
		// zero real work — exactly the 40 → 95 residual ramp observed on the 10% sample. Asserts the started-vs-live
		// distinction is load-bearing, not cosmetic.
		assertThat(RemoteGuidanceScheduler.busy(93, 5, 15)).isEqualTo(73);
	}

	@Test
	void busy_allIdleOrLeaving_isZero() {
		assertThat(RemoteGuidanceScheduler.busy(10, 4, 6)).isZero();
	}

	@Test
	void noRunaway_atIgnitionFrame() {
		// end-to-end: feed the corrected busy through the default buffered reconciler with the sweep's parameters
		// (minActiveFleet=40, readyBufferSize=20). With busy=0 the target must collapse to the floor (40) and — since 40
		// are already active — emit nothing. This is the property that breaks the runaway.
		ActivationReconciler reconciler = ActivationReconciler.createDefault(40, 20, OptionalDouble.empty());
		int busy = RemoteGuidanceScheduler.busy(40, 2, 38);
		GuidanceState state = new GuidanceState(40, 100_000, 375, 2, busy, 0.0);

		assertThat(reconciler.desired(state, 90900)).isEqualTo(40); // floor, not busy(0)+buffer(20)=20
		assertThat(reconciler.toEmit(state, 90900)).isZero();       // nothing emitted → no runaway
	}

	@Test
	void runawayReproducedWithoutTheFix() {
		// the counterfactual: the OLD busy (leaving counted as busy = 38) drives the target to 38 + buffer(20) = 58,
		// which with 40 active emits 18 replacements for vehicles that are going home — the +18/step ramp seen in the
		// activeVehicles time series. This asserts the fix is load-bearing, not cosmetic.
		ActivationReconciler reconciler = ActivationReconciler.createDefault(40, 20, OptionalDouble.empty());
		int oldBusy = 40 - 2; // pre-fix: live − idleInService, leaving NOT excluded
		GuidanceState state = new GuidanceState(40, 100_000, 375, 2, oldBusy, 0.0);

		assertThat(reconciler.desired(state, 90900)).isEqualTo(58);
		assertThat(reconciler.toEmit(state, 90900)).isEqualTo(18);
	}
}