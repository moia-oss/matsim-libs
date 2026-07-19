/*
 * Copyright (C) 2026 MOIA GmbH - All Rights Reserved
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.guidance;

import org.matsim.api.core.v01.Id;
import org.matsim.contrib.drt.extension.operations.guidance.RemoteGuidanceOperators.Operator;
import org.matsim.contrib.drt.extension.operations.shifts.shift.DrtShift;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The mutable, per-run runtime state of the remote guidance operator pool — the runtime companion to the immutable
 * spec-level {@link RemoteGuidanceOperators}. It owns everything about operators that changes as a simulation unfolds
 * and is meaningless outside a single QSim run: which operators have been <em>released</em> (D22) and when (their
 * <em>effective</em> end time, deferred past the planned end whenever an early release would break coverage), and which
 * are currently <em>incident-busy</em>.
 * <p>
 * Keeping this state here rather than on the spec {@link Operator} objects means the spec registry stays immutable and
 * cross-iteration safe, while all resettable state lives on this dedicated object and is cleared per iteration via
 * {@link #reset()} (called from {@code RemoteGuidanceScheduler.initialSchedule()}, which already re-initialises the
 * scheduler's per-iteration state). This removes the former bug where the release flag, living on a cross-iteration
 * spec instance, was never reset and starved coverage from the second iteration on.
 * <p>
 * <b>Lifecycle taxonomy.</b> An operator is:
 * <ul>
 *     <li><em>on duty for coverage</em> from its planned start until it is released — this is the runtime supervision /
 *         incident-server window and may extend past the planned end (retention);</li>
 *     <li><em>pending release</em> once it has reached its planned end but has not been released yet (retained for
 *         coverage);</li>
 *     <li><em>released</em> once {@link #releaseElapsedOperators(double, int)} has freed it — its effective end time is
 *         then fixed and it no longer contributes to coverage or the incident server pool.</li>
 * </ul>
 * There is no handover (D16): an incident-busy operator is never released mid-incident.
 *
 * @author nkuehnel / MOIA
 */
public final class RemoteGuidanceOperatorState {

	private final RemoteGuidanceOperators registry;

	private final Map<Id<DrtShift>, Runtime> runtimeById = new LinkedHashMap<>();

	/** Per-operator mutable runtime lifecycle state. */
	private static final class Runtime {
		private boolean started = false;
		private boolean released = false;
		private boolean incidentBusy = false;
		private double effectiveEndTime = Double.NaN; // set when released; the deferred (or on-time) actual end
	}

	public RemoteGuidanceOperatorState(RemoteGuidanceOperators registry) {
		this.registry = registry;
		reset();
	}

	/** Re-initialises the runtime state for a fresh iteration: every operator un-started, un-released, not busy. */
	public void reset() {
		runtimeById.clear();
		for (Id<DrtShift> id : registry.getOperators().keySet()) {
			runtimeById.put(id, new Runtime());
		}
	}

	/**
	 * @return the coverage capacity {@code Σκ(t)} — the summed capacity of all operators on duty for coverage at
	 * {@code now} (including operators retained past their planned end). The active supervised fleet must never exceed
	 * this, and it is the {@link IncidentDispatcher}'s server pool.
	 */
	public int coverageCapacityAt(double now) {
		int capacity = 0;
		for (Operator operator : registry.getOperators().values()) {
			if (onDutyForCoverage(operator, now)) {
				capacity += operator.capacity();
			}
		}
		return capacity;
	}

	/** @return the number of operators on duty for coverage (runtime sense) at {@code now}. */
	public int onDutyForCoverageCount(double now) {
		int count = 0;
		for (Operator operator : registry.getOperators().values()) {
			if (onDutyForCoverage(operator, now)) {
				count++;
			}
		}
		return count;
	}

	/** On duty in the runtime sense (coverage + incident processing): started and not yet released. */
	public boolean onDutyForCoverage(Operator operator, double now) {
		Runtime runtime = runtimeById.get(operator.id());
		return operator.startTime() <= now && !runtime.released;
	}

	/**
	 * Marks the operators whose planned start has been reached this step as started, firing nothing itself (the caller
	 * emits the lifecycle event). Idempotent per operator.
	 *
	 * @return the operators that transitioned to started on this call, in registry order.
	 */
	public List<Operator> markStarted(double now) {
		List<Operator> started = new ArrayList<>();
		for (Operator operator : registry.getOperators().values()) {
			Runtime runtime = runtimeById.get(operator.id());
			if (!runtime.started && operator.startTime() <= now) {
				runtime.started = true;
				started.add(operator);
			}
		}
		return started;
	}

	/**
	 * Marks whether the operator with {@code operatorId} is currently processing an incident. Called by the
	 * {@link IncidentDispatcher} on assignment / resolution. A busy operator is never released.
	 */
	public void setIncidentBusy(Id<DrtShift> operatorId, boolean busy) {
		Runtime runtime = runtimeById.get(operatorId);
		if (runtime != null) {
			runtime.incidentBusy = busy;
		}
	}

	/**
	 * Releases operators that have reached their planned end, one at a time, but only while doing so keeps the coverage
	 * invariant intact: an operator is freed only if it holds no incident and the currently supervised fleet still fits
	 * under the coverage capacity that <em>remains after</em> removing it. Processed greedily and re-checked per operator
	 * so several operators ending at the same step release only as far as the shrinking active fleet allows. Called once
	 * per sim step (from {@code RemoteGuidanceScheduler.schedule}) with the current supervised-vehicle count. The
	 * released operators' effective end time is fixed to {@code now}.
	 *
	 * @return the operators released on this call, in registry order (so the caller can emit their ended events).
	 */
	public List<Operator> releaseElapsedOperators(double now, int activeSupervised) {
		List<Operator> released = new ArrayList<>();
		for (Operator operator : registry.getOperators().values()) {
			Runtime runtime = runtimeById.get(operator.id());
			if (isPendingRelease(operator, now) && !runtime.incidentBusy
					&& activeSupervised <= coverageCapacityAt(now) - operator.capacity()) {
				runtime.released = true;
				runtime.effectiveEndTime = now;
				released.add(operator);
			}
		}
		return released;
	}

	/** True once the operator has reached its planned end but has not been released yet (retained for coverage). */
	public boolean isPendingRelease(Operator operator, double now) {
		Runtime runtime = runtimeById.get(operator.id());
		return now >= operator.plannedEndTime() && !runtime.released;
	}

	public boolean isReleased(Id<DrtShift> operatorId) {
		return runtimeById.get(operatorId).released;
	}

	public boolean isIncidentBusy(Id<DrtShift> operatorId) {
		return runtimeById.get(operatorId).incidentBusy;
	}

	/** @return the operator's effective (actual, possibly deferred) end time once released, else {@code NaN}. */
	public double effectiveEndTime(Id<DrtShift> operatorId) {
		return runtimeById.get(operatorId).effectiveEndTime;
	}
}
