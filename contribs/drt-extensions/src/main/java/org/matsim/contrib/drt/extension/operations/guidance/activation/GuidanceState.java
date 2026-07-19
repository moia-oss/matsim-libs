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
 * A narrow, immutable read-model of the remote guidance extensive margin at one simulation second (RF3). Every
 * {@link ActivationTrigger} decides purely from this snapshot — it never sees the raw {@code Fleet} — so triggers are
 * unit-testable without a running QSim (just construct a {@code GuidanceState}). The scheduler builds one snapshot per
 * step from the fleet and the operator registry.
 * <p>
 * The three idle-related counts follow the D19 "idle" taxonomy:
 * <ul>
 *     <li>{@code idleAtHub} — vehicles waiting out of service on a {@code WaitForShiftTask}: the <em>activation
 *         source</em> (only these can be brought in).</li>
 *     <li>{@code idleInService} — active supervised vehicles that are truly idle in service (a {@code DrtStayTask} that
 *         is the last task in the schedule, i.e. no committed future work): the ready <em>buffer</em>.</li>
 * </ul>
 *
 * @param activeCount         number of currently active (supervised) virtual shifts.
 * @param activationCapacity  the activation ceiling {@code Σκ} over operators within their planned window (D22) — the
 *                            hard upper bound on the active count.
 * @param idleAtHub           number of out-of-service vehicles waiting at a hub (activation source).
 * @param idleInService       number of active vehicles idle in service with no committed work (ready buffer).
 * @param recentRejectionRate the recent request-rejection rate {@code rejected / (rejected + scheduled)} over a trailing
 *                            window, fed by {@code RejectionRateTracker} when {@code rejectionActivation} is configured
 *                            (else 0.0). The demand-pressure signal read by {@code RejectionRateActivation}.
 *
 * @author nkuehnel / MOIA
 */
public record GuidanceState(int activeCount, int activationCapacity, int idleAtHub, int idleInService,
							double recentRejectionRate) {
}
