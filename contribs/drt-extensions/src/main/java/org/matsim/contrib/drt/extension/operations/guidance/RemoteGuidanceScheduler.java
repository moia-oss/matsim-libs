/*
 * Copyright (C) 2026 MOIA GmbH - All Rights Reserved
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.guidance;

import com.google.common.collect.ImmutableMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.drt.extension.operations.guidance.config.RemoteGuidanceParams;
import org.matsim.contrib.drt.extension.operations.guidance.events.VehicleAssignedToOperatorEvent;
import org.matsim.contrib.drt.extension.operations.guidance.events.VehicleReleasedFromOperatorEvent;
import org.matsim.contrib.drt.extension.operations.shifts.dispatcher.DefaultShiftScheduler;
import org.matsim.contrib.drt.extension.operations.shifts.dispatcher.ShiftScheduler;
import org.matsim.contrib.drt.extension.operations.shifts.fleet.ShiftDvrpVehicle;
import org.matsim.contrib.drt.extension.operations.shifts.schedule.WaitForShiftTask;
import org.matsim.contrib.drt.extension.operations.shifts.shift.DrtShift;
import org.matsim.contrib.drt.extension.operations.shifts.shift.DrtShiftImpl;
import org.matsim.contrib.drt.extension.operations.shifts.shift.DrtShiftSpecification;
import org.matsim.contrib.drt.extension.operations.shifts.shift.DrtShiftSpecificationImpl;
import org.matsim.contrib.drt.extension.operations.shifts.shift.DrtShiftsSpecification;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.fleet.Fleet;
import org.matsim.contrib.dvrp.schedule.Schedule;
import org.matsim.contrib.dvrp.schedule.Task;
import org.matsim.core.api.experimental.events.EventsManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A {@link ShiftScheduler} that implements remote guidance: operator shifts (shifts of the configured operator type)
 * are not assigned to vehicles directly. Instead, each is turned into a {@link RemoteGuidanceOperator} that can
 * supervise up to a configurable number of vehicles at once. Every time step, this scheduler emits "virtual" driver
 * shifts for idle vehicles as long as some operator still has free capacity. These virtual shifts are processed by the
 * regular {@link org.matsim.contrib.drt.extension.operations.shifts.dispatcher.DrtShiftDispatcher} exactly like normal
 * shifts: the dispatcher assigns each to an idle vehicle, starts it, and on operator-shift end sends the vehicle back
 * to a hub (where it becomes available for re-activation under another operator — i.e. a hub-based handover).
 * <p>
 * Non-operator shifts (regular driver shifts) are passed through unchanged, so a fleet may combine driver shifts and
 * remote guidance.
 * <p>
 * Operator capacity is reconciled from the fleet state at the start of every {@link #schedule(double, Fleet)} call.
 * This is exact because the dispatcher always invokes the scheduler before assigning shifts within the same step, so by
 * then every previously emitted virtual shift is either live in a vehicle's shift queue or has been discarded.
 *
 * @author nkuehnel / MOIA
 */
public final class RemoteGuidanceScheduler implements ShiftScheduler {

	private static final Logger logger = LogManager.getLogger(RemoteGuidanceScheduler.class);

	static final String VIRTUAL_SHIFT_TYPE = "remoteGuidanceVirtual";

	private final ShiftScheduler delegate;
	private final RemoteGuidanceParams params;
	private final EventsManager eventsManager;
	private final String mode;

	// runtime state, (re)initialized on each initialSchedule() (i.e. per iteration)
	private Map<Id<DrtShift>, RemoteGuidanceOperator> operators;
	private Map<Id<DrtShift>, Id<DrtShift>> virtualShiftToOperator;
	private Map<Id<DrtShift>, LiveSupervision> liveSupervisions;
	// virtual shifts that became live (and thus were registered in the shift specification so that they can be
	// analysed like regular shifts); kept across iterations only to purge them at the start of the next one.
	private final Set<Id<DrtShift>> registeredVirtualSpecs = new HashSet<>();
	private long virtualShiftCounter;

	private record LiveSupervision(Id<DrtShift> operatorId, Id<DvrpVehicle> vehicleId, DrtShift shift) {}

	public RemoteGuidanceScheduler(ShiftScheduler delegate, RemoteGuidanceParams params, EventsManager eventsManager,
								   String mode) {
		this.delegate = delegate;
		this.params = params;
		this.eventsManager = eventsManager;
		this.mode = mode;
	}

	@Override
	public ImmutableMap<Id<DrtShift>, DrtShift> initialSchedule() {
		// purge any virtual shift specifications registered during the previous iteration, so that they are neither
		// reloaded as initial shifts by the delegate nor accumulated across iterations.
		for (Id<DrtShift> virtualSpecId : registeredVirtualSpecs) {
			delegate.get().removeShiftSpecification(virtualSpecId);
		}
		registeredVirtualSpecs.clear();

		// (re)build operators and reset state for this iteration
		operators = new LinkedHashMap<>();
		virtualShiftToOperator = new HashMap<>();
		liveSupervisions = new HashMap<>();
		virtualShiftCounter = 0;

		ImmutableMap.Builder<Id<DrtShift>, DrtShift> driverShifts = ImmutableMap.builder();
		for (DrtShift shift : delegate.initialSchedule().values()) {
			if (isOperatorShift(shift)) {
				operators.put(shift.getId(), new RemoteGuidanceOperator(shift, params.getDefaultOperatorCapacity()));
			} else {
				driverShifts.put(shift.getId(), shift);
			}
		}
		logger.info("Initialized remote guidance with {} operator shifts (capacity {} each).", operators.size(),
				params.getDefaultOperatorCapacity());
		return driverShifts.build();
	}

	@Override
	public List<DrtShift> schedule(double now, Fleet fleet) {
		List<DrtShift> emitted = new ArrayList<>(delegate.schedule(now, fleet));

		reconcileSupervisions(now, fleet);

		int idleVehicles = countIdleVehicles(fleet);
		if (idleVehicles == 0) {
			return emitted;
		}

		// operators that can still take vehicles, preferring those with the most remaining shift time to reduce churn
		List<RemoteGuidanceOperator> available = operators.values().stream()
				.filter(op -> canActivateUnder(op, now))
				.filter(RemoteGuidanceOperator::hasFreeCapacity)
				.sorted(Comparator.comparingDouble(RemoteGuidanceOperator::getEndTime).reversed())
				.toList();

		for (RemoteGuidanceOperator operator : available) {
			while (idleVehicles > 0 && operator.hasFreeCapacity()) {
				DrtShift virtualShift = createVirtualShift(operator, now);
				operator.reserveSlot(virtualShift.getId());
				virtualShiftToOperator.put(virtualShift.getId(), operator.getId());
				emitted.add(virtualShift);
				idleVehicles--;
			}
			if (idleVehicles == 0) {
				break;
			}
		}
		return emitted;
	}

	/**
	 * Recomputes, from the current fleet state, which virtual shifts are live (present in a vehicle's shift queue) and
	 * under which operator. Fires assignment/release events on transitions and keeps operator capacity in sync.
	 */
	private void reconcileSupervisions(double now, Fleet fleet) {
		// map of currently live virtual shifts -> (supervising vehicle, shift)
		Map<Id<DrtShift>, LiveSupervision> currentlyLive = new HashMap<>();
		for (DvrpVehicle vehicle : fleet.getVehicles().values()) {
			if (vehicle instanceof ShiftDvrpVehicle shiftVehicle) {
				for (DrtShift shift : shiftVehicle.getShifts()) {
					Id<DrtShift> operatorId = virtualShiftToOperator.get(shift.getId());
					if (operatorId != null) {
						currentlyLive.put(shift.getId(), new LiveSupervision(operatorId, vehicle.getId(), shift));
					}
				}
			}
		}

		// newly assigned: live now but not tracked before
		for (Map.Entry<Id<DrtShift>, LiveSupervision> entry : currentlyLive.entrySet()) {
			Id<DrtShift> virtualShiftId = entry.getKey();
			if (!liveSupervisions.containsKey(virtualShiftId)) {
				LiveSupervision supervision = entry.getValue();
				liveSupervisions.put(virtualShiftId, supervision);
				// register the virtual shift in the shared specification so analyses can resolve it like a real shift
				registerVirtualShiftSpec(supervision.shift());
				eventsManager.processEvent(
						new VehicleAssignedToOperatorEvent(now, mode, supervision.operatorId(), supervision.vehicleId()));
			}
		}

		// released: tracked before but no longer live (virtual shift ended)
		Set<Id<DrtShift>> ended = new HashSet<>(liveSupervisions.keySet());
		ended.removeAll(currentlyLive.keySet());
		for (Id<DrtShift> virtualShiftId : ended) {
			LiveSupervision supervision = liveSupervisions.remove(virtualShiftId);
			RemoteGuidanceOperator operator = operators.get(supervision.operatorId());
			VehicleReleasedFromOperatorEvent.ReleaseReason reason = operator != null && now >= operator.getEndTime()
					? VehicleReleasedFromOperatorEvent.ReleaseReason.operatorShiftEnded
					: VehicleReleasedFromOperatorEvent.ReleaseReason.vehicleReturned;
			eventsManager.processEvent(new VehicleReleasedFromOperatorEvent(now, mode, supervision.operatorId(),
					supervision.vehicleId(), reason));
		}

		// re-synchronize operator capacity with the live (assigned, not yet ended) virtual shifts
		for (RemoteGuidanceOperator operator : operators.values()) {
			operator.clearReservations();
		}
		for (Map.Entry<Id<DrtShift>, LiveSupervision> entry : liveSupervisions.entrySet()) {
			RemoteGuidanceOperator operator = operators.get(entry.getValue().operatorId());
			if (operator != null) {
				operator.reserveSlot(entry.getKey());
			}
		}

		// prune mappings of virtual shifts that were emitted but never became live (discarded by the dispatcher)
		virtualShiftToOperator.keySet().removeIf(id -> !currentlyLive.containsKey(id) && !liveSupervisions.containsKey(id));
	}

	private boolean canActivateUnder(RemoteGuidanceOperator operator, double now) {
		return operator.getStartTime() <= now
				&& now + params.getMinRemainingShiftTimeForActivation() <= operator.getEndTime();
	}

	private DrtShift createVirtualShift(RemoteGuidanceOperator operator, double now) {
		Id<DrtShift> id = Id.create("rg_" + operator.getId() + "_" + (virtualShiftCounter++), DrtShift.class);
		// no fixed facility: the vehicle is activated from / returns to any hub (hub-based handover);
		// no designated vehicle: the dispatcher matches an idle vehicle.
		return new DrtShiftImpl(id, now, operator.getEndTime(), null, null, null, VIRTUAL_SHIFT_TYPE);
	}

	/**
	 * Registers a live virtual shift in the shared shift specification so that downstream analyses (shift duration,
	 * efficiency, dumps) can resolve it like any regular shift. The spec is kept until the next iteration, where it is
	 * purged in {@link #initialSchedule()}.
	 */
	private void registerVirtualShiftSpec(DrtShift shift) {
		if (registeredVirtualSpecs.add(shift.getId())) {
			delegate.get().addShiftSpecification(DrtShiftSpecificationImpl.newBuilder()
					.id(shift.getId())
					.start(shift.getStartTime())
					.end(shift.getEndTime())
					.type(shift.getShiftType().orElse(VIRTUAL_SHIFT_TYPE))
					.build());
		}
	}

	private int countIdleVehicles(Fleet fleet) {
		int count = 0;
		for (DvrpVehicle vehicle : fleet.getVehicles().values()) {
			if (vehicle instanceof ShiftDvrpVehicle shiftVehicle && shiftVehicle.getShifts().isEmpty()) {
				Schedule schedule = vehicle.getSchedule();
				if (schedule.getStatus() == Schedule.ScheduleStatus.STARTED) {
					Task currentTask = schedule.getCurrentTask();
					if (currentTask instanceof WaitForShiftTask) {
						count++;
					}
				}
			}
		}
		return count;
	}

	private boolean isOperatorShift(DrtShift shift) {
		return shift.getShiftType().map(params.getOperatorShiftType()::equals).orElse(false);
	}

	@Override
	public DrtShiftsSpecification get() {
		return delegate.get();
	}

	/**
	 * Convenience factory wrapping a {@link DefaultShiftScheduler} over the given specification.
	 */
	public static RemoteGuidanceScheduler create(DrtShiftsSpecification specification, RemoteGuidanceParams params,
												 EventsManager eventsManager, String mode) {
		return new RemoteGuidanceScheduler(new DefaultShiftScheduler(specification), params, eventsManager, mode);
	}
}