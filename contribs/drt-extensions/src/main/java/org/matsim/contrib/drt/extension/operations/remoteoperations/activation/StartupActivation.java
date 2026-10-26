/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.remoteoperations.activation;

import java.util.function.DoubleUnaryOperator;

/**
 * Trigger that puts a starting fleet on the road as soon as the operators come on duty, so the service opens with the
 * labor rather than ramping up from the demand it has already seen. While operator coverage is younger than
 * {@code duration} (from the first operator of a stretch, e.g. the morning opening after a closed night), the target is
 * {@code fleet} vehicles; after that the trigger asks for nothing and the demand-driven triggers take over, and the
 * idle timeout recalls whatever the demand does not need.
 *
 * @author lpfeil / MOIA
 */
public final class StartupActivation implements ActivationTrigger {

	private final int fleet;
	private final double duration;
	private final DoubleUnaryOperator timeSinceCoverageStart;

	/**
	 * @param timeSinceCoverageStart time since the operators' current continuous coverage began at a given time, or
	 *                               {@code NaN} if none is on duty
	 */
	public StartupActivation(int fleet, double duration, DoubleUnaryOperator timeSinceCoverageStart) {
		this.fleet = fleet;
		this.duration = duration;
		this.timeSinceCoverageStart = timeSinceCoverageStart;
	}

	@Override
	public int desiredActive(GuidanceState state, double now) {
		double since = timeSinceCoverageStart.applyAsDouble(now);
		return !Double.isNaN(since) && since < duration ? fleet : 0;
	}
}
