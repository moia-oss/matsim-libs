/*
 * Copyright (C) 2026 MOIA GmbH - All Rights Reserved
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.guidance.activation;

/**
 * A pluggable, OR-combinable activation policy for the remote guidance extensive margin (RF1 / D17). Each trigger reads
 * the current {@link GuidanceState} and proposes how many vehicles <em>should</em> be active according to its own
 * concern — an <b>absolute desired active count</b>, not a delta. The {@link ActivationReconciler} combines several
 * triggers by taking the maximum (OR semantics: any trigger may pull the fleet up), then clamps to the hard floor
 * {@code nMin} and the hard ceiling {@link GuidanceState#activationCapacity()}.
 * <p>
 * Absolute-desired (rather than delta) is deliberate: two triggers reacting to the same signal both name the same
 * target, so {@code max} de-duplicates them; summing deltas would double-count.
 *
 * @author nkuehnel / MOIA
 */
@FunctionalInterface
public interface ActivationTrigger {

	/**
	 * @return the number of vehicles this trigger wants active at {@code now}. Values outside {@code [0, capacity]} are
	 * fine — the reconciler clamps; a trigger should express its raw intent (e.g. {@code activeCount + 1} for one extra).
	 */
	int desiredActive(GuidanceState state, double now);
}
