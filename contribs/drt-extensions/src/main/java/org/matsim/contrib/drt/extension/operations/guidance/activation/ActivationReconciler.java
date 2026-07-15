/*
 * Copyright (C) 2026 MOIA GmbH - All Rights Reserved
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.guidance.activation;

import java.util.List;

/**
 * Combines a set of {@link ActivationTrigger}s into the single decision the {@link
 * org.matsim.contrib.drt.extension.operations.guidance.RemoteGuidanceScheduler} acts on each step: <em>how many new
 * virtual shifts to emit</em>. This is pure policy over a {@link GuidanceState} snapshot — no fleet, no side effects —
 * so the whole reconciliation is unit-testable with a hand-built state (RF6).
 * <p>
 * Reconciliation (desired-absolute + max, OR semantics):
 * <pre>
 *     desired = max over triggers of trigger.desiredActive(state, now)   // any trigger may pull the fleet up
 *     desired = clamp(desired, nMin, state.activationCapacity())         // hard floor + hard ceiling
 *     toEmit  = max(0, desired - state.activeCount())                    // only ramp up; recall is the ShiftEndLogic's job
 *     toEmit  = min(toEmit, state.idleAtHub())                           // can only emit as many as there are idle-at-hub vehicles
 * </pre>
 * The reconciler never <em>recalls</em> — a lower desired count just means "emit nothing"; bringing the active count
 * back down is the deactivation side ({@code RemoteGuidanceShiftEndLogic}).
 *
 * @author nkuehnel / MOIA
 */
public final class ActivationReconciler {

	private final List<ActivationTrigger> triggers;
	private final int nMin;

	public ActivationReconciler(List<ActivationTrigger> triggers, int nMin) {
		this.triggers = List.copyOf(triggers);
		this.nMin = nMin;
	}

	/**
	 * @return how many new virtual shifts to emit at {@code now}, given the current {@code state}. Always {@code >= 0}
	 * and never more than the free activation capacity nor the number of idle-at-hub vehicles.
	 */
	public int toEmit(GuidanceState state, double now) {
		int desired = nMin;
		for (ActivationTrigger trigger : triggers) {
			desired = Math.max(desired, trigger.desiredActive(state, now));
		}
		// desired is already >= nMin (floor) from the seed above; cap it at the hard ceiling last, so the activation
		// capacity Σκ (a physical limit) always wins over the floor when the two conflict.
		desired = Math.min(desired, state.activationCapacity());
		int toEmit = Math.max(0, desired - state.activeCount());
		return Math.min(toEmit, state.idleAtHub());
	}
}
