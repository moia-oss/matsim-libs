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
import org.matsim.contrib.drt.extension.operations.shifts.shift.DrtShift;
import org.matsim.contrib.drt.extension.operations.shifts.shift.DrtShiftSpecification;
import org.matsim.contrib.drt.extension.operations.shifts.shift.DrtShiftsSpecification;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thin, authoritative runtime registry of remote guidance operators. An operator is nothing but a {@link DrtShift}
 * whose type equals the configured operator shift type (D15) — there is no wrapper object and no per-vehicle binding.
 * This registry is the single source of truth for the aggregate quantities everything else in the guidance layer needs.
 * <p>
 * <b>Two distinct capacities (D22).</b> The operator's scheduled end time (the persistent plan, from the shift spec) is
 * NOT the moment it stops mattering. Mirroring how a driver shift ends not at {@code shift.getEndTime()} but when its
 * changeover actually begins (later, if the vehicle arrives at the hub late), an operator has a <em>planned</em> end and
 * an <em>effective</em> (runtime) end that is deferred past the planned end whenever releasing it now would break the
 * core invariant "never more vehicles supervised than can be supervised simultaneously". This yields two capacities:
 * <ul>
 *     <li>{@link #activationCapacityAt(double)} — {@code Σκ} over operators still within their <em>planned</em> window.
 *         This is the ceiling for <em>activating new</em> vehicles: an operator winding down must not pull new vehicles
 *         in.</li>
 *     <li>{@link #capacityAt(double)} (coverage) — {@code Σκ} over operators still on duty in the <em>runtime</em> sense
 *         (planned window OR retained past it). This is what must never be undercut by the active fleet, and the server
 *         pool the {@link IncidentDispatcher} draws from: a retained operator still supervises its vehicles and can
 *         still finish a pending incident.</li>
 * </ul>
 * An operator past its planned end is <em>pending release</em>: it keeps contributing to coverage until
 * {@link #releaseElapsedOperators(double, int)} frees it (once the active fleet has shrunk enough and it holds no
 * incident). Before the planned end the two capacities coincide.
 * <p>
 * Capacity κ is uniform (from config) for now; per-operator heterogeneous capacity (R10) is an additive later step
 * (attach κ as a persistent {@link DrtShift} attribute), needing no structural change here.
 *
 * @author nkuehnel / MOIA
 */
public final class RemoteGuidanceOperators {

	/** One operator = one operator-type shift, with a planned window and a deferrable runtime lifecycle (D22). */
	public static final class Operator {
		private final Id<DrtShift> id;
		private final double startTime;
		private final double plannedEndTime;
		private final int capacity;

		// runtime state: an operator past its planned end is retained ("pending release") for coverage until releasing
		// it no longer breaks the invariant; incidentBusy marks that it is currently processing an incident (must not
		// be released mid-incident — there is no handover, D16).
		private boolean released = false;
		private boolean incidentBusy = false;

		private Operator(Id<DrtShift> id, double startTime, double plannedEndTime, int capacity) {
			this.id = id;
			this.startTime = startTime;
			this.plannedEndTime = plannedEndTime;
			this.capacity = capacity;
		}

		public Id<DrtShift> id() {
			return id;
		}

		public double startTime() {
			return startTime;
		}

		public double plannedEndTime() {
			return plannedEndTime;
		}

		public int capacity() {
			return capacity;
		}

		public boolean isReleased() {
			return released;
		}

		public boolean isIncidentBusy() {
			return incidentBusy;
		}

		/** On duty for activating new vehicles: within the planned window (a winding-down operator does not qualify). */
		public boolean onDutyForActivation(double now) {
			return startTime <= now && now < plannedEndTime;
		}

		/** On duty in the runtime sense (coverage + incident processing): started and not yet released. */
		public boolean onDutyForCoverage(double now) {
			return startTime <= now && !released;
		}

		/** True once the operator has reached its planned end but has not been released yet (retained for coverage). */
		public boolean isPendingRelease(double now) {
			return now >= plannedEndTime && !released;
		}
	}

	private final Map<Id<DrtShift>, Operator> operators = new LinkedHashMap<>();

	private RemoteGuidanceOperators(Map<Id<DrtShift>, Operator> operators) {
		this.operators.putAll(operators);
	}

	/**
	 * Builds the registry from the shift specification, taking every shift of {@code operatorShiftType} as an operator
	 * with the given uniform capacity.
	 */
	public static RemoteGuidanceOperators fromSpecification(DrtShiftsSpecification specification, String operatorShiftType,
															int capacity) {
		Map<Id<DrtShift>, Operator> operators = new LinkedHashMap<>();
		for (DrtShiftSpecification spec : specification.getShiftSpecifications().values()) {
			if (spec.getShiftType().map(operatorShiftType::equals).orElse(false)) {
				operators.put(spec.getId(), new Operator(spec.getId(), spec.getStartTime(), spec.getEndTime(), capacity));
			}
		}
		return new RemoteGuidanceOperators(operators);
	}

	/** @return the number of operators on duty for coverage at {@code now}. */
	public int onDutyCount(double now) {
		int count = 0;
		for (Operator operator : operators.values()) {
			if (operator.onDutyForCoverage(now)) {
				count++;
			}
		}
		return count;
	}

	/**
	 * @return the number of operators whose <em>planned</em> window covers {@code now} (i.e.
	 * {@code startTime <= now < plannedEndTime}). Unlike {@link #onDutyCount(double)} this depends only on the immutable
	 * shift schedule, not on the runtime {@code released} flag, so it is safe to query retroactively for a historical
	 * time (e.g. an end-of-iteration utilisation series). This is the incident server-pool size — an incident always
	 * occupies exactly one operator regardless of κ (D14) — so it is the correct utilisation denominator (a numerator
	 * that momentarily exceeds it reflects an operator retained past its planned end to finish a queued incident).
	 */
	public int plannedOnDutyCount(double now) {
		int count = 0;
		for (Operator operator : operators.values()) {
			if (operator.onDutyForActivation(now)) {
				count++;
			}
		}
		return count;
	}

	/**
	 * @return the coverage capacity {@code Σκ(t)} — the summed capacity of all operators on duty for coverage at
	 * {@code now} (including operators retained past their planned end). The active supervised fleet must never exceed
	 * this, and it is the {@link IncidentDispatcher}'s server pool.
	 */
	public int capacityAt(double now) {
		int capacity = 0;
		for (Operator operator : operators.values()) {
			if (operator.onDutyForCoverage(now)) {
				capacity += operator.capacity;
			}
		}
		return capacity;
	}

	/**
	 * @return the activation ceiling {@code Σκ(t)} — the summed capacity of operators still within their planned window
	 * at {@code now}. Caps how many vehicles may be <em>newly activated</em>; excludes winding-down (pending-release)
	 * operators.
	 */
	public int activationCapacityAt(double now) {
		int capacity = 0;
		for (Operator operator : operators.values()) {
			if (operator.onDutyForActivation(now)) {
				capacity += operator.capacity;
			}
		}
		return capacity;
	}

	/**
	 * @return the minimum activation capacity over the look-ahead window {@code [from, to]}. Since activation capacity
	 * is a step function that only changes at operator start / planned-end times, the minimum over the window is the
	 * minimum of its values at {@code from} and at every operator boundary that falls inside {@code (from, to]}. Used by
	 * the recall logic to start bringing vehicles home <em>before</em> an operator's planned end, so they reach a hub in
	 * time (a proactive recall lead).
	 */
	public int minActivationCapacity(double from, double to) {
		int min = activationCapacityAt(from);
		for (Operator operator : operators.values()) {
			if (operator.startTime > from && operator.startTime <= to) {
				min = Math.min(min, activationCapacityAt(operator.startTime));
			}
			if (operator.plannedEndTime > from && operator.plannedEndTime <= to) {
				min = Math.min(min, activationCapacityAt(operator.plannedEndTime));
			}
		}
		return min;
	}

	/**
	 * @return the maximum activation capacity {@code Σκ} reached at any point over the operator schedule — the largest
	 * number of vehicles that can ever be supervised simultaneously. Since activation capacity is a step function that
	 * only changes at operator start / planned-end times, the maximum is attained at one of the operator start times.
	 * Used only for a config sanity warning (a floor that exceeds this can never be met).
	 */
	public int maxActivationCapacity() {
		int max = 0;
		for (Operator operator : operators.values()) {
			max = Math.max(max, activationCapacityAt(operator.startTime));
		}
		return max;
	}

	/**
	 * Marks whether the operator with {@code operatorId} is currently processing an incident. Called by the
	 * {@link IncidentDispatcher} on assignment / resolution. A busy operator is never released.
	 */
	public void setIncidentBusy(Id<DrtShift> operatorId, boolean busy) {
		Operator operator = operators.get(operatorId);
		if (operator != null) {
			operator.incidentBusy = busy;
		}
	}

	/**
	 * Releases operators that have reached their planned end, one at a time, but only while doing so keeps the coverage
	 * invariant intact: an operator is freed only if it holds no incident and the currently supervised fleet still fits
	 * under the coverage capacity that <em>remains after</em> removing it. Processed greedily and re-checked per operator
	 * so several operators ending at the same step release only as far as the shrinking active fleet allows. Called once
	 * per sim step (from {@link RemoteGuidanceScheduler#schedule}) with the current supervised-vehicle count.
	 */
	public void releaseElapsedOperators(double now, int activeSupervised) {
		for (Operator operator : operators.values()) {
			if (operator.isPendingRelease(now) && !operator.incidentBusy
					&& activeSupervised <= capacityAt(now) - operator.capacity) {
				operator.released = true;
			}
		}
	}

	public Map<Id<DrtShift>, Operator> getOperators() {
		return operators;
	}

	public int size() {
		return operators.size();
	}
}
