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
 * Activation trigger keeping a small responsiveness buffer (D17): whenever <em>no</em> active vehicle is currently idle
 * in service (no ready spare to absorb an incoming request without a hub activation delay), pull in one more. When a
 * ready buffer already exists it proposes nothing (i.e. the current active count), so it does not keep ramping up.
 * <p>
 * <b>This is the oscillation damper.</b> It fires only on "no idle-in-service buffer", while the deactivation side
 * (idle-timeout recall) fires only on "idle in service beyond the timeout". Those two conditions act on different
 * states, so a min/max-idle band emerges naturally without an explicit cooldown/hysteresis: in a demand lull exactly
 * one buffer vehicle stays active instead of every idle-at-hub vehicle being greedily activated.
 *
 * @author nkuehnel / MOIA
 */
public final class IdleBufferActivation implements ActivationTrigger {

	@Override
	public int desiredActive(GuidanceState state, double now) {
		// no ready spare in service → want one more than are active now; otherwise the buffer is satisfied.
		return state.idleInService() == 0 ? state.activeCount() + 1 : state.activeCount();
	}
}
