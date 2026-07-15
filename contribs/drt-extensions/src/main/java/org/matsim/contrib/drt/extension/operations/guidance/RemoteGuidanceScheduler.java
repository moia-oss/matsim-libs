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
import org.matsim.contrib.drt.extension.operations.guidance.events.VehicleActivatedForRemoteGuidanceEvent;
import org.matsim.contrib.drt.extension.operations.guidance.events.VehicleDeactivatedForRemoteGuidanceEvent;
import org.matsim.contrib.drt.extension.operations.guidance.events.VehicleDeactivatedForRemoteGuidanceEvent.DeactivationReason;
import org.matsim.contrib.drt.extension.operations.shifts.dispatcher.DefaultShiftScheduler;
import org.matsim.contrib.drt.extension.operations.shifts.dispatcher.ShiftScheduler;
import org.matsim.contrib.drt.extension.operations.shifts.fleet.ShiftDvrpVehicle;
import org.matsim.contrib.drt.extension.operations.shifts.schedule.WaitForShiftTask;
import org.matsim.contrib.drt.extension.operations.shifts.shift.DrtShift;
import org.matsim.contrib.drt.extension.operations.shifts.shift.DrtShiftImpl;
import org.matsim.contrib.drt.extension.operations.shifts.shift.DrtShiftSpecificationImpl;
import org.matsim.contrib.drt.extension.operations.shifts.shift.DrtShiftsSpecification;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.fleet.Fleet;
import org.matsim.contrib.dvrp.schedule.Schedule;
import org.matsim.contrib.dvrp.schedule.Task;
import org.matsim.core.api.experimental.events.EventsManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A {@link ShiftScheduler} that implements remote guidance: operator shifts (shifts of the configured operator type)
 * are not assigned to vehicles directly. Instead they define the aggregate supervision capacity {@code Σκ(t)} of the
 * operator pool, tracked by the authoritative {@link RemoteGuidanceOperators} registry (D15 — there is no longer a
 * {@code RemoteGuidanceOperator} wrapper nor any per-vehicle&harr;operator binding). Every step, this scheduler emits
 * "virtual" driver shifts for idle-at-hub vehicles as long as the active count is below the current ceiling. These
 * virtual shifts are processed by the regular {@link org.matsim.contrib.drt.extension.operations.shifts.dispatcher.DrtShiftDispatcher}
 * exactly like normal shifts: the dispatcher assigns each to an idle vehicle and starts it. A virtual shift has
 * <b>no scheduled end of its own</b> — it is created with {@code end = the simulation horizon} (D16) and is ended
 * early on demand by {@link RemoteGuidanceShiftEndLogic} (capacity exceeded / idle timeout), which drives the
 * dispatcher's early-end mechanism.
 * <p>
 * Non-operator shifts (regular driver shifts) are passed through unchanged, so a fleet may combine driver shifts and
 * remote guidance.
 * <p>
 * This scheduler <em>observes</em> the extensive margin and fires the two vehicle events: it detects which virtual
 * shifts became live (→ {@link VehicleActivatedForRemoteGuidanceEvent}) and which ended (→
 * {@link VehicleDeactivatedForRemoteGuidanceEvent}) by scanning the fleet's shift queues each step, without any
 * id&harr;operator bookkeeping. The deactivation reason is inferred from the aggregate signal: if the number of
 * still-supervised vehicles remains at or above {@code capacityAt(now)}, the release was forced by a capacity drop
 * ({@link DeactivationReason#capacityExceeded}); otherwise a below-ceiling release is a demand-slack recall
 * ({@link DeactivationReason#idleTimeout}). This mirrors the two triggers in {@link RemoteGuidanceShiftEndLogic}.
 *
 * @author nkuehnel / MOIA
 */
public final class RemoteGuidanceScheduler implements ShiftScheduler {

	private static final Logger logger = LogManager.getLogger(RemoteGuidanceScheduler.class);

	static final String VIRTUAL_SHIFT_TYPE = "remoteGuidanceVirtual";

	private final ShiftScheduler delegate;
	private final RemoteGuidanceOperators operators;
	private final RemoteGuidanceParams params;
	private final EventsManager eventsManager;
	private final String mode;
	private final double changeoverDuration;

	// runtime state, (re)initialized on each initialSchedule() (i.e. per iteration)
	private Map<Id<DrtShift>, Id<DvrpVehicle>> liveVirtualShifts;
	// virtual shifts that became live (and thus were registered in the shift specification so that they can be
	// analysed like regular shifts); kept across iterations only to purge them at the start of the next one.
	private final Set<Id<DrtShift>> registeredVirtualSpecs = new HashSet<>();
	private long virtualShiftCounter;
	// the "simulation horizon" end assigned to every virtual shift (D16). Lazily derived from the fleet on the first
	// schedule() call as (minimum service end time − changeover duration). A virtual shift has a discretionary end
	// (D21): startShift materialises NO changeover tail for it, so this end time is never physically reached as a
	// changeover — it only serves as the far-future bound that the dispatcher's lifecycle keys on and against which
	// the recall look-ahead is evaluated. The − changeoverDuration is no longer load-bearing (post-D21 nothing is
	// materialised at this time); it is kept only as a small margin below service end so that the shift end sorts
	// strictly before the vehicle's service end in the dispatcher's end-time-keyed lifecycle. A recall materialises
	// its own changeover with a fresh [arrival, serviceEnd] reservation and does not depend on this offset.
	private double virtualShiftEndTime = Double.NaN;

	public RemoteGuidanceScheduler(ShiftScheduler delegate, RemoteGuidanceOperators operators,
								   RemoteGuidanceParams params, EventsManager eventsManager, String mode,
								   double changeoverDuration) {
		this.delegate = delegate;
		this.operators = operators;
		this.params = params;
		this.eventsManager = eventsManager;
		this.mode = mode;
		this.changeoverDuration = changeoverDuration;
	}

	@Override
	public ImmutableMap<Id<DrtShift>, DrtShift> initialSchedule() {
		// purge any virtual shift specifications registered during the previous iteration, so that they are neither
		// reloaded as initial shifts by the delegate nor accumulated across iterations.
		for (Id<DrtShift> virtualSpecId : registeredVirtualSpecs) {
			delegate.get().removeShiftSpecification(virtualSpecId);
		}
		registeredVirtualSpecs.clear();

		// (re)init runtime state for this iteration
		liveVirtualShifts = new HashMap<>();
		virtualShiftCounter = 0;

		// operator shifts define capacity only (via the registry) and are NOT handed to the dispatcher for assignment
		ImmutableMap.Builder<Id<DrtShift>, DrtShift> driverShifts = ImmutableMap.builder();
		for (DrtShift shift : delegate.initialSchedule().values()) {
			if (!isOperatorShift(shift)) {
				driverShifts.put(shift.getId(), shift);
			}
		}
		logger.info("Initialized remote guidance with {} operator shifts (capacity {} each).", operators.size(),
				params.getDefaultOperatorCapacity());
		return driverShifts.build();
	}

	@Override
	public List<DrtShift> schedule(double now, Fleet fleet) {
		if (Double.isNaN(virtualShiftEndTime)) {
			double minServiceEnd = fleet.getVehicles().values().stream()
					.mapToDouble(DvrpVehicle::getServiceEndTime)
					.min()
					.orElse(now);
			virtualShiftEndTime = minServiceEnd - changeoverDuration;
		}

		List<DrtShift> emitted = new ArrayList<>(delegate.schedule(now, fleet));

		reconcileSupervisions(now, fleet);

		// D22: release operators that have passed their planned end, but only as far as the (now reconciled) active
		// supervised fleet allows without breaking coverage — a retained operator keeps supervising its vehicles and
		// finishing any pending incident until its vehicles have gone home. Runs before emission so the freshly-released
		// operators no longer count toward the activation ceiling below.
		operators.releaseElapsedOperators(now, liveVirtualShifts.size());

		// activation (D17 baseline trigger): bring idle-at-hub vehicles in while below the activation ceiling. This uses
		// the PLANNED-window capacity (not coverage): a winding-down operator retained past its planned end must not pull
		// new vehicles in.
		int capacity = operators.activationCapacityAt(now);
		int freeCapacity = capacity - liveVirtualShifts.size();
		if (freeCapacity <= 0) {
			return emitted;
		}
		int idleAtHub = countIdleAtHubVehicles(fleet);
		int toEmit = Math.min(freeCapacity, idleAtHub);
		for (int i = 0; i < toEmit; i++) {
			emitted.add(createVirtualShift(now));
		}
		return emitted;
	}

	/**
	 * Recomputes, from the current fleet state, which virtual shifts are live (present in a vehicle's shift queue).
	 * Fires activation/deactivation events on transitions. Emission itself no longer needs an id&harr;operator map:
	 * a virtual shift is recognised purely by its shift type.
	 */
	private void reconcileSupervisions(double now, Fleet fleet) {
		Map<Id<DrtShift>, Id<DvrpVehicle>> currentlyLive = new HashMap<>();
		for (DvrpVehicle vehicle : fleet.getVehicles().values()) {
			if (vehicle instanceof ShiftDvrpVehicle shiftVehicle) {
				for (DrtShift shift : shiftVehicle.getShifts()) {
					if (isVirtualShift(shift)) {
						currentlyLive.put(shift.getId(), vehicle.getId());
					}
				}
			}
		}

		// newly activated: live now but not tracked before
		for (Map.Entry<Id<DrtShift>, Id<DvrpVehicle>> entry : currentlyLive.entrySet()) {
			if (!liveVirtualShifts.containsKey(entry.getKey())) {
				registerVirtualShiftSpec(entry.getKey());
				eventsManager.processEvent(new VehicleActivatedForRemoteGuidanceEvent(now, mode, entry.getValue()));
			}
		}

		// deactivated: tracked before but no longer live (virtual shift ended)
		Set<Id<DrtShift>> ended = new HashSet<>(liveVirtualShifts.keySet());
		ended.removeAll(currentlyLive.keySet());
		int capacity = operators.capacityAt(now);
		int stillLive = currentlyLive.size();
		for (Id<DrtShift> endedShiftId : ended) {
			Id<DvrpVehicle> vehicleId = liveVirtualShifts.get(endedShiftId);
			// aggregate inference of the trigger (mirrors RemoteGuidanceShiftEndLogic): still at/over the ceiling → the
			// release was forced by a capacity drop; below the ceiling → a demand-slack (idle-timeout) recall.
			DeactivationReason reason = stillLive >= capacity
					? DeactivationReason.capacityExceeded
					: DeactivationReason.idleTimeout;
			eventsManager.processEvent(new VehicleDeactivatedForRemoteGuidanceEvent(now, mode, vehicleId, reason));
		}

		liveVirtualShifts = currentlyLive;
	}

	private DrtShift createVirtualShift(double now) {
		Id<DrtShift> id = Id.create("rg_" + (virtualShiftCounter++) + "_" + (long) now, DrtShift.class);
		// no fixed facility: the vehicle is activated from / returns to any hub (hub-based handover);
		// no designated vehicle: the dispatcher matches an idle vehicle;
		// end = simulation horizon (D16): the shift runs until a deactivation trigger recalls it early;
		// committedEnd = false (D21): the horizon end is discretionary, so startShift materialises NO changeover/wait
		// tail — the vehicle stays in service on a plain stay until a recall lazily materialises the end.
		return new DrtShiftImpl(id, now, virtualShiftEndTime, null, null, null, VIRTUAL_SHIFT_TYPE, false);
	}

	/**
	 * Registers a live virtual shift in the shared shift specification so that downstream analyses (shift duration,
	 * efficiency, dumps) can resolve it like any regular shift. The spec is kept until the next iteration, where it is
	 * purged in {@link #initialSchedule()}.
	 */
	private void registerVirtualShiftSpec(Id<DrtShift> shiftId) {
		if (registeredVirtualSpecs.add(shiftId)) {
			delegate.get().addShiftSpecification(DrtShiftSpecificationImpl.newBuilder()
					.id(shiftId)
					.start(0)
					.end(virtualShiftEndTime)
					.type(VIRTUAL_SHIFT_TYPE)
					.build());
		}
	}

	private int countIdleAtHubVehicles(Fleet fleet) {
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

	private static boolean isVirtualShift(DrtShift shift) {
		return shift.getShiftType().map(VIRTUAL_SHIFT_TYPE::equals).orElse(false);
	}

	@Override
	public DrtShiftsSpecification get() {
		return delegate.get();
	}

	/**
	 * Convenience factory wrapping a {@link DefaultShiftScheduler} over the given specification.
	 */
	public static RemoteGuidanceScheduler create(DrtShiftsSpecification specification, RemoteGuidanceOperators operators,
												 RemoteGuidanceParams params, EventsManager eventsManager, String mode,
												 double changeoverDuration) {
		return new RemoteGuidanceScheduler(new DefaultShiftScheduler(specification), operators, params, eventsManager,
				mode, changeoverDuration);
	}
}
