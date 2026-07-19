/*
 * Copyright (C) 2026 MOIA GmbH - All Rights Reserved
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.guidance.config;

/**
 * Selects which remote guidance activation policy the {@code ActivationReconciler} is built from (RF1 / D17). Both
 * extensive-margin sides (the scheduler ramping up, the shift-end logic recalling down) build an identical reconciler
 * from this choice, so they always share one fleet-sizing target.
 *
 * @author nkuehnel / MOIA
 */
public enum ActivationPolicy {
	/**
	 * The default demand-responsive policy: the regulatory floor plus a single idle-in-service responsiveness buffer
	 * (plus a demand-driven rejection trigger iff {@code rejectionActivation} is configured). Keeps only a small ready
	 * buffer active during lulls; damps the low-demand activate&harr;recall sawtooth.
	 */
	buffered,
	/**
	 * The greedy baseline (original D4 behaviour): activate every idle-at-hub vehicle while capacity allows, driving the
	 * active fleet to the ceiling {@code Σκ} regardless of demand. Kept for comparison runs. The regulatory floor still
	 * applies (it is dominated by greedy anyway); the responsiveness buffer and rejection trigger are irrelevant under
	 * greedy and are not wired.
	 */
	greedy
}
