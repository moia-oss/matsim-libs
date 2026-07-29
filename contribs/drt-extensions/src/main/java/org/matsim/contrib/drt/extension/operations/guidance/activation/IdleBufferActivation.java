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
 * Activation trigger keeping a responsiveness buffer of {@code desiredBuffer} vehicles idle in service (D17): it targets
 * enough active vehicles that, on top of those currently busy, {@code desiredBuffer} are free to absorb an incoming
 * request without a hub activation delay.
 * <p>
 * Its target is the <b>absolute</b> level {@code smoothedBusy + desiredBuffer}, where {@code smoothedBusy} is the busy
 * count {@code activeCount − idleInService} (vehicles doing committed work) as smoothed by {@code BusyWindowTracker} — its
 * trailing-window maximum, or, when no busy window is configured, exactly the instantaneous busy count. This is
 * deliberately expressed as an absolute count that may fall <em>below</em> the current active count: when demand drops
 * and too many vehicles sit idle, the target is lower than {@code active}, telling the deactivation side to recall the
 * surplus down to the buffer. The activation side clamps at {@code max(0, target − active)}, so on the way up this reads
 * exactly as "top the buffer back up to {@code desiredBuffer}"; the two sides thus share one target.
 * <p>
 * <b>This is the oscillation damper.</b> Because activation and deactivation read the same {@code smoothedBusy +
 * desiredBuffer} target, a demand lull settles at exactly {@code desiredBuffer} idle-in-service vehicles (stable —
 * neither activated further nor recalled), instead of every idle-at-hub vehicle being greedily activated and then
 * churned out by the idle-timeout. Feeding the <em>smoothed</em> (trailing-peak) busy count rather than the raw
 * instantaneous one additionally damps the peak-demand sawtooth: without smoothing, {@code busy} jitters second-to-second
 * during the daytime peak and the target chases every tick (the raw value is recovered exactly when no window is set).
 *
 * @author nkuehnel / MOIA
 */
public final class IdleBufferActivation implements ActivationTrigger {

	private final int desiredBuffer;

	public IdleBufferActivation(int desiredBuffer) {
		this.desiredBuffer = desiredBuffer;
	}

	@Override
	public int desiredActive(GuidanceState state, double now) {
		// hold desiredBuffer vehicles free on top of the (smoothed) busy ones. May be below activeCount (→ recall the idle
		// surplus); the activation side clamps negative gaps to 0, so ramping up this reads as "refill the buffer".
		return state.smoothedBusy() + desiredBuffer;
	}
}
