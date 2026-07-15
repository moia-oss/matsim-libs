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
import org.matsim.contrib.drt.extension.operations.shifts.dispatcher.DrtShiftDispatcher;
import org.matsim.contrib.drt.extension.operations.shifts.dispatcher.ShiftEndLogic;
import org.matsim.contrib.drt.extension.operations.shifts.fleet.ShiftDvrpVehicle;
import org.matsim.contrib.drt.extension.operations.shifts.schedule.ShiftSchedules;
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
 *         demand-slack signal → recall it.</li>
 * </ol>
 * When recalling for capacity, <em>idle-in-service vehicles are chosen first</em> (least passenger disruption), then
 * others; recall itself is attempt-and-defer (handled by the dispatcher — a vehicle that cannot be routed to a hub now
 * keeps running and is reconsidered next step).
 * <p>
 * The decision is memoised per simulation second: the dispatcher calls {@link #shiftEndsEarly} once per active shift
 * per step, but the victim set is a function of the whole active-virtual fleet, so it is computed once per {@code now}
 * from the fleet and then answered per entry.
 * <p>
 * <b>Interaction with eager shift-end materialisation (pre-D21):</b> {@code startShift} currently materialises a
 * changeover at {@code shift.getEndTime()} (= the sim horizon) for every virtual shift. Two consequences until the
 * lazy-materialisation refactor (Task #8) lands: (a) "already leaving" cannot be detected by the mere presence of a
 * changeover — it is detected by the changeover's <em>begin time</em> being pulled forward before the horizon (a
 * materialised recall anchors it at {@code now}); (b) the {@code idleTimeout} trigger is effectively dormant, because
 * an eagerly-built vehicle's last task is always the WaitForShift tail, never a last-task {@link DrtStayTask}, so
 * {@link #isIdleInService} never holds. The idle-timeout code is correct and forward-looking; it activates once D21
 * removes the eager tail. {@code capacityExceeded} works today.
 *
 * @author nkuehnel / MOIA
 */
public final class RemoteGuidanceShiftEndLogic implements ShiftEndLogic {

	private final Fleet fleet;
	private final RemoteGuidanceOperators operators;
	private final double idleTimeout;
	private final double recallLeadTime;

	private double lastComputedTime = Double.NaN;
	private Set<Id<DrtShift>> recallSet = new HashSet<>();

	public RemoteGuidanceShiftEndLogic(Fleet fleet, RemoteGuidanceOperators operators, double idleTimeout,
									   double recallLeadTime) {
		this.fleet = fleet;
		this.operators = operators;
		this.idleTimeout = idleTimeout;
		this.recallLeadTime = recallLeadTime;
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
	 * every vehicle idle in service beyond the timeout, plus — if the active count exceeds the ceiling — enough
	 * additional (idle-first) vehicles to bring the active count back down to {@code capacityAt(now)}.
	 */
	private Set<Id<DrtShift>> selectRecalls(double now) {
		List<ShiftDvrpVehicle> active = new ArrayList<>();
		for (DvrpVehicle vehicle : fleet.getVehicles().values()) {
			if (vehicle instanceof ShiftDvrpVehicle shiftVehicle) {
				DrtShift current = shiftVehicle.getShifts().peek();
				if (current != null && isVirtualShift(current)
						&& vehicle.getSchedule().getStatus() == Schedule.ScheduleStatus.STARTED
						// exclude vehicles already recalled (routing home): their changeover has been pulled forward
						// ahead of the shift's (horizon) end. The eager tail every virtual shift starts with sits AT
						// the horizon, so it does NOT count as "leaving"; only a materialised early recall does. A
						// recall that was deferred (attempt-and-defer) has not moved the changeover yet, so it stays
						// counted and is retried next step. (Post-D21 the eager tail is gone and any changeover means
						// leaving — this predicate then simplifies to "has a changeover".)
						&& !isAlreadyLeaving(shiftVehicle, current)) {
					active.add(shiftVehicle);
				}
			}
		}

		Set<Id<DrtShift>> recalled = new HashSet<>();

		// (2) idleTimeout: recall every vehicle idle in service beyond the timeout
		for (ShiftDvrpVehicle vehicle : active) {
			if (idleInServiceElapsed(vehicle, now) > idleTimeout) {
				recalled.add(vehicle.getShifts().peek().getId());
			}
		}

		// (1) capacityExceeded: if still over the ceiling, recall the excess, idle-in-service vehicles first. The ceiling
		// is the MINIMUM activation capacity over the look-ahead window [now, now+recallLeadTime], not just at now, so
		// vehicles start heading home before an operator's planned end and reach a hub in time (proactive recall, D22).
		int ceiling = operators.minActivationCapacity(now, now + recallLeadTime);
		int excess = active.size() - ceiling;
		if (excess > recalled.size()) {
			active.stream()
					.filter(v -> !recalled.contains(v.getShifts().peek().getId()))
					.sorted(Comparator.comparingDouble((ShiftDvrpVehicle v) -> isIdleInService(v) ? 0 : 1)
							.thenComparing(v -> v.getId().toString()))
					.limit(excess - recalled.size())
					.forEach(v -> recalled.add(v.getShifts().peek().getId()));
		}

		return recalled;
	}

	/**
	 * @return the elapsed idle-in-service time (D19 meaning 2): {@code now - beginTime} if the current task is a
	 * {@link DrtStayTask} that is the last task in the schedule (no committed future work), else {@code 0}.
	 */
	private double idleInServiceElapsed(ShiftDvrpVehicle vehicle, double now) {
		return isIdleInService(vehicle) ? now - vehicle.getSchedule().getCurrentTask().getBeginTime() : 0.0;
	}

	/**
	 * @return {@code true} if this vehicle has already been recalled, i.e. its changeover has been pulled forward
	 * ahead of the shift's (horizon) end time. Distinguishes a materialised early recall from the eager end-of-shift
	 * tail that sits exactly at the shift end.
	 */
	private boolean isAlreadyLeaving(ShiftDvrpVehicle vehicle, DrtShift shift) {
		return ShiftSchedules.getNextShiftChangeover(vehicle.getSchedule())
				.map(changeover -> changeover.getBeginTime() < shift.getEndTime())
				.orElse(false);
	}

	private boolean isIdleInService(ShiftDvrpVehicle vehicle) {
		Schedule schedule = vehicle.getSchedule();
		if (schedule.getStatus() != Schedule.ScheduleStatus.STARTED) {
			return false;
		}
		Task current = schedule.getCurrentTask();
		return current instanceof DrtStayTask && current.equals(Schedules.getLastTask(schedule));
	}

	private static boolean isVirtualShift(DrtShift shift) {
		return shift.getShiftType().map(RemoteGuidanceScheduler.VIRTUAL_SHIFT_TYPE::equals).orElse(false);
	}
}
