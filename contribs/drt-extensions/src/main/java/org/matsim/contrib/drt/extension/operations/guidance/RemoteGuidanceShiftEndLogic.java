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
import org.matsim.contrib.drt.extension.operations.guidance.activation.ActivationReconciler;
import org.matsim.contrib.drt.extension.operations.guidance.activation.GuidanceState;
import org.matsim.contrib.drt.extension.operations.shifts.dispatcher.DrtShiftDispatcher;
import org.matsim.contrib.drt.extension.operations.shifts.dispatcher.ShiftEndLogic;
import org.matsim.contrib.drt.extension.operations.shifts.fleet.ShiftDvrpVehicle;
import org.matsim.contrib.drt.extension.operations.shifts.schedule.ShiftSchedules;
import org.matsim.contrib.drt.extension.operations.shifts.schedule.WaitForShiftTask;
import org.matsim.contrib.drt.extension.operations.shifts.shift.DrtShift;
import org.matsim.contrib.drt.schedule.DrtStayTask;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.fleet.Fleet;
import org.matsim.contrib.dvrp.schedule.Schedule;
import org.matsim.contrib.dvrp.schedule.Schedules;
import org.matsim.contrib.dvrp.schedule.Task;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Remote guidance deactivation policy (D16/D17). Virtual driver shifts (emitted by {@link RemoteGuidanceScheduler})
 * carry no scheduled end of their own — they run until a trigger recalls the vehicle. This {@link ShiftEndLogic}
 * implements the two deactivation triggers, both gated on the aggregate activation ceiling {@code Σκ(t)} from the
 * {@link RemoteGuidanceOperators} registry (D14/D15 — no operator&harr;vehicle binding):
 * <ol>
 *     <li><b>capacityExceeded</b>: an operator goes off duty → {@code capacityAt(t)} drops → if more vehicles are
 *         supervised than the new ceiling allows, recall the excess. This is the (only, implicit) handover.</li>
 *     <li><b>idleTimeout</b>: a supervised vehicle that has been <em>truly idle in service</em> (D19: on a
 *         {@link DrtStayTask} that is the last task in its schedule) for longer than the configured timeout is a
 *         demand-slack signal → recall it, but only down to the shared fleet-sizing target from the
 *         {@link ActivationReconciler}. This side builds the same {@code GuidanceState} snapshot and reads the same
 *         reconciler as the activation side ({@link RemoteGuidanceScheduler}), so it recalls DOWN to exactly the target
 *         the activation side ramps UP to. Whatever set that target — the regulatory floor, the responsiveness buffer,
 *         or a future demand-driven trigger — is honoured identically on both margins, so a demand lull settles at the
 *         target with no churn, without a cooldown.</li>
 * </ol>
 * When recalling for capacity, <em>idle-in-service vehicles are chosen first</em> (least passenger disruption), then
 * others; recall itself is attempt-and-defer (handled by the dispatcher — a vehicle that cannot be routed to a hub now
 * keeps running and is reconsidered next step).
 * <p>
 * The decision is memoised per simulation second: the dispatcher calls {@link #shiftEndsEarly} once per active shift
 * per step, but the victim set is a function of the whole active-virtual fleet, so it is computed once per {@code now}
 * from the fleet and then answered per entry.
 * <p>
 * <b>Since D21 (lazy shift-end materialisation):</b> a virtual shift carries no eagerly-built changeover/wait tail —
 * an active virtual vehicle sits on a last-task {@link DrtStayTask} until recalled. Consequences: (a) the
 * {@code idleTimeout} trigger is now live (a vehicle with no committed work satisfies {@link #isIdleInService}
 * immediately, and is recalled once it has been idle longer than the timeout); (b) a virtual vehicle has a changeover
 * in its schedule <em>only</em> after a recall has materialised one (anchored ahead of the horizon end), so
 * "already leaving" is simply "has a next changeover" ({@link #isAlreadyLeaving}). {@code capacityExceeded} is
 * unchanged.
 *
 * @author nkuehnel / MOIA
 */
public final class RemoteGuidanceShiftEndLogic implements ShiftEndLogic {

	private final Fleet fleet;
	private final RemoteGuidanceOperators operators;
	private final double idleTimeout;
	private final double recallLeadTime;
	private final ActivationReconciler reconciler;
	// nullable: shared demand-pressure source (same instance the scheduler reads), so both margins see the same
	// recentRejectionRate and the shared target stays consistent. null when no rejection-activation config is present.
	private final RejectionRateTracker rejectionRateTracker;

	private double lastComputedTime = Double.NaN;
	private Set<Id<DrtShift>> recallSet = new HashSet<>();

	public RemoteGuidanceShiftEndLogic(Fleet fleet, RemoteGuidanceOperators operators, double idleTimeout,
									   double recallLeadTime, ActivationReconciler reconciler,
									   RejectionRateTracker rejectionRateTracker) {
		this.fleet = fleet;
		this.operators = operators;
		this.idleTimeout = idleTimeout;
		this.recallLeadTime = recallLeadTime;
		this.reconciler = reconciler;
		this.rejectionRateTracker = rejectionRateTracker;
	}

	@Override
	public boolean shiftEndsEarly(DrtShiftDispatcher.ShiftEntry activeShift, double now) {
		if (!isVirtualShift(activeShift.shift())) {
			// regular (driver) shifts run to their scheduled end — never recalled here
			return false;
		}
		if (now != lastComputedTime) {
			recallSet = selectRecalls(now);
			lastComputedTime = now;
		}
		return recallSet.contains(activeShift.shift().getId());
	}

	/**
	 * Determines, across the whole currently-supervised virtual fleet, which shifts should be recalled this step:
	 * idle-in-service vehicles beyond the timeout are recalled only down to the shared fleet-sizing target
	 * {@link ActivationReconciler#desired} (so the floor + responsiveness buffer + any future trigger are all honoured
	 * through one value the activation side agrees on — no churn), plus — if the active count exceeds the look-ahead
	 * ceiling — enough additional (idle-first) vehicles to bring the active count back down to capacity.
	 */
	private Set<Id<DrtShift>> selectRecalls(double now) {
		List<ShiftDvrpVehicle> active = new ArrayList<>();
		int idleAtHub = 0;
		for (DvrpVehicle vehicle : fleet.getVehicles().values()) {
			if (!(vehicle instanceof ShiftDvrpVehicle shiftVehicle)) {
				continue;
			}
			// key on the STARTED virtual shift, not the queue head: the shift queue is start-time-ordered and holds
			// assigned-but-unstarted future shifts, so peek() may return a shift that has not started yet.
			boolean runningVirtual = RemoteGuidanceScheduler.startedVirtualShift(shiftVehicle).isPresent();
			if (runningVirtual
					&& vehicle.getSchedule().getStatus() == Schedule.ScheduleStatus.STARTED
					// exclude vehicles already recalled (routing home). Since D21 a virtual shift has no eager tail,
					// so a changeover in the schedule can only have been materialised by a recall → its mere
					// presence means "leaving". A recall that was deferred (attempt-and-defer) has not materialised
					// a changeover yet, so it stays counted and is retried next step.
					&& !isAlreadyLeaving(shiftVehicle)) {
				active.add(shiftVehicle);
			} else if (shiftVehicle.getShifts().isEmpty()
					&& vehicle.getSchedule().getStatus() == Schedule.ScheduleStatus.STARTED
					&& vehicle.getSchedule().getCurrentTask() instanceof WaitForShiftTask) {
				// out of service, waiting at a hub → an activation source (needed to build the GuidanceState the
				// reconciler reads; see desiredActiveCount).
				idleAtHub++;
			}
		}

		Set<Id<DrtShift>> recalled = new HashSet<>();

		// (2) idleTimeout: recall idle-in-service vehicles that are beyond the timeout, but only the surplus ABOVE the
		// shared target. keepIdle is exactly the number of idle-in-service vehicles the target wants held (target minus
		// the busy vehicles it is already covered by), clamped to what is actually idle. Because the activation side
		// pulls the fleet UP to the same target and this side only recalls DOWN to it, a demand lull settles at the
		// target with no churn — whatever set the target (regulatory floor, responsiveness buffer, a future
		// demand-driven trigger) is honoured identically on both margins. The freshest idle vehicles are kept; the
		// longest-idle ones (the strongest demand-slack signal) are recalled first.
		List<ShiftDvrpVehicle> idleInService = active.stream()
				.filter(this::isIdleInService)
				.sorted(Comparator.comparingDouble((ShiftDvrpVehicle v) -> idleInServiceElapsed(v, now))
						.thenComparing(v -> v.getId().toString()))
				.toList();
		int desired = desiredActiveCount(active.size(), idleInService.size(), idleAtHub, now);
		int keepIdle = idleToKeep(active.size(), idleInService.size(), desired);
		for (int i = keepIdle; i < idleInService.size(); i++) {
			ShiftDvrpVehicle vehicle = idleInService.get(i);
			if (idleInServiceElapsed(vehicle, now) > idleTimeout) {
				recalled.add(startedVirtualShiftId(vehicle));
			}
		}

		// (1) capacityExceeded: if still over the ceiling, recall the excess, idle-in-service vehicles first. The ceiling
		// is the MINIMUM activation capacity over the look-ahead window [now, now+recallLeadTime], not just at now, so
		// vehicles start heading home before an operator's planned end and reach a hub in time (proactive recall, D22).
		int ceiling = operators.minActivationCapacity(now, now + recallLeadTime);
		int excess = active.size() - ceiling;
		if (excess > recalled.size()) {
			active.stream()
					.filter(v -> !recalled.contains(startedVirtualShiftId(v)))
					.sorted(Comparator.comparingDouble((ShiftDvrpVehicle v) -> isIdleInService(v) ? 0 : 1)
							.thenComparing(v -> v.getId().toString()))
					.limit(excess - recalled.size())
					.forEach(v -> recalled.add(startedVirtualShiftId(v)));
		}

		return recalled;
	}

	/**
	 * How many of the idle-in-service vehicles to <em>keep</em> (spare from the idle-timeout recall) so the active count
	 * settles at the shared {@code desired} target: the target minus the {@code busy = activeCount − idleInService}
	 * vehicles it is already covered by, clamped to {@code [0, idleInService]}. This is the churn-guard: because the
	 * activation side ramps UP to {@code desired} and this keeps enough idle to hold the fleet at {@code desired}, a
	 * demand lull settles there rather than oscillating — whatever set the target (floor, responsiveness buffer, a future
	 * trigger). Pure arithmetic, package-private for unit testing.
	 */
	static int idleToKeep(int activeCount, int idleInService, int desired) {
		int busy = activeCount - idleInService;
		return Math.min(Math.max(0, desired - busy), idleInService);
	}

	/**
	 * The shared fleet-sizing target this step: builds a {@link GuidanceState} snapshot and asks the same
	 * {@link ActivationReconciler#desired} policy the activation side uses, so activation (ramp up to the target) and
	 * this side (recall down to it) apply one policy and cannot disagree on where the fleet should settle. The two
	 * snapshots are not byte-identical — this side deliberately excludes vehicles already routing home
	 * ({@link #isAlreadyLeaving}) from {@code activeCount}, whereas the scheduler counts every live virtual shift — but
	 * the default policy's target ({@code busy + buffer}, floor) does not depend on that difference. A future trigger
	 * whose target reads {@code activeCount} directly would need to account for this transient. Uses the PLANNED-window
	 * ceiling {@code Σκ} at {@code now} (like the scheduler); the separate look-ahead capacity reduction is the
	 * {@code capacityExceeded} pass.
	 */
	private int desiredActiveCount(int activeCount, int idleInService, int idleAtHub, double now) {
		double rejectionRate = rejectionRateTracker == null ? 0.0 : rejectionRateTracker.rejectionRate(now);
		GuidanceState state = new GuidanceState(activeCount, operators.activationCapacityAt(now), idleAtHub,
				idleInService, rejectionRate);
		return reconciler.desired(state, now);
	}

	/**
	 * @return the elapsed idle-in-service time (D19 meaning 2): {@code now - beginTime} if the current task is a
	 * {@link DrtStayTask} that is the last task in the schedule (no committed future work), else {@code 0}.
	 */
	private double idleInServiceElapsed(ShiftDvrpVehicle vehicle, double now) {
		return isIdleInService(vehicle) ? now - vehicle.getSchedule().getCurrentTask().getBeginTime() : 0.0;
	}

	/**
	 * @return {@code true} if this vehicle has already been recalled, i.e. a recall has materialised a changeover in
	 * its schedule (since D21 a virtual shift has no eager tail, so any next changeover is a recall's).
	 */
	private boolean isAlreadyLeaving(ShiftDvrpVehicle vehicle) {
		return ShiftSchedules.getNextShiftChangeover(vehicle.getSchedule()).isPresent();
	}

	private boolean isIdleInService(ShiftDvrpVehicle vehicle) {
		Schedule schedule = vehicle.getSchedule();
		if (schedule.getStatus() != Schedule.ScheduleStatus.STARTED) {
			return false;
		}
		Task current = schedule.getCurrentTask();
		return current instanceof DrtStayTask && current.equals(Schedules.getLastTask(schedule));
	}

	/**
	 * The id of the vehicle's running virtual shift. Only called for vehicles already confirmed to have one (members of
	 * {@code active}), so the started shift is guaranteed present; keyed on the started shift rather than the queue head,
	 * which may be a not-yet-started future shift.
	 */
	private static Id<DrtShift> startedVirtualShiftId(ShiftDvrpVehicle vehicle) {
		return RemoteGuidanceScheduler.startedVirtualShift(vehicle).orElseThrow().getId();
	}

	private static boolean isVirtualShift(DrtShift shift) {
		return shift.getShiftType().map(RemoteGuidanceScheduler.VIRTUAL_SHIFT_TYPE::equals).orElse(false);
	}
}
