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
import org.matsim.contrib.drt.extension.operations.guidance.activation.ActivationReconciler;
import org.matsim.contrib.drt.extension.operations.guidance.activation.ActivationTrigger;
import org.matsim.contrib.drt.extension.operations.guidance.activation.GuidanceState;
import org.matsim.contrib.drt.extension.operations.guidance.activation.IdleBufferActivation;
import org.matsim.contrib.drt.extension.operations.guidance.config.RemoteGuidanceParams;
import org.matsim.contrib.drt.extension.operations.guidance.events.RemoteGuidanceOperatorEndedEvent;
import org.matsim.contrib.drt.extension.operations.guidance.events.RemoteGuidanceOperatorStartedEvent;
import org.matsim.contrib.drt.extension.operations.guidance.events.VehicleActivatedForRemoteGuidanceEvent;
import org.matsim.contrib.drt.extension.operations.guidance.events.VehicleDeactivatedForRemoteGuidanceEvent;
import org.matsim.contrib.drt.extension.operations.guidance.events.VehicleDeactivatedForRemoteGuidanceEvent.DeactivationReason;
import org.matsim.contrib.drt.extension.operations.shifts.dispatcher.DefaultShiftScheduler;
import org.matsim.contrib.drt.extension.operations.shifts.dispatcher.ShiftScheduler;
import org.matsim.contrib.drt.extension.operations.shifts.fleet.ShiftDvrpVehicle;
import org.matsim.contrib.drt.extension.operations.shifts.schedule.ShiftSchedules;
import org.matsim.contrib.drt.extension.operations.shifts.schedule.WaitForShiftTask;
import org.matsim.contrib.drt.extension.operations.shifts.shift.DrtShift;
import org.matsim.contrib.drt.extension.operations.shifts.shift.DrtShiftImpl;
import org.matsim.contrib.drt.extension.operations.shifts.shift.DrtShiftsSpecification;
import org.matsim.contrib.drt.schedule.DrtStayTask;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.fleet.Fleet;
import org.matsim.contrib.dvrp.schedule.Schedule;
import org.matsim.contrib.dvrp.schedule.Schedules;
import org.matsim.contrib.dvrp.schedule.Task;
import org.matsim.core.api.experimental.events.EventsManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
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
 * <b>How many to emit is decided by pluggable activation triggers (RF1 / D17), not greedily.</b> Each step the
 * scheduler builds a narrow {@link GuidanceState} snapshot and hands it to an {@link ActivationReconciler}, which
 * OR-combines the configured {@link ActivationTrigger}s into one fleet-sizing target (max of their desired active
 * counts, clamped to the activation ceiling {@code Σκ}) and returns how many new virtual shifts to emit to reach it.
 * The default policy is the regulatory floor plus a single {@link IdleBufferActivation} responsiveness buffer. The very
 * same reconciler (built from the same config) is read by {@link RemoteGuidanceShiftEndLogic} on the deactivation side,
 * so both margins share one target — which is what damps the low-demand activate&harr;idle-timeout&harr;recall sawtooth
 * the old greedy emit produced.
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
	private final RemoteGuidanceOperatorState operatorState;
	private final RemoteGuidanceParams params;
	private final EventsManager eventsManager;
	private final String mode;
	private final double changeoverDuration;
	private final ActivationReconciler activationReconciler;
	// nullable: the demand-pressure source for the RejectionRateActivation trigger; null when no rejection-activation
	// config is present (then recentRejectionRate stays 0.0 and the trigger, if somehow present, never fires).
	private final RejectionRateTracker rejectionRateTracker;
	// the trailing-window busy-count smoother shared with the deactivation side. A window of 0 makes it a pass-through
	// (smoothedBusy == instantaneous busy), so behaviour is unchanged when the busy window is disabled.
	private final BusyWindowTracker busyWindowTracker;

	// runtime state, (re)initialized on each initialSchedule() (i.e. per iteration)
	private Map<Id<DrtShift>, Id<DvrpVehicle>> liveVirtualShifts;
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
								   RemoteGuidanceOperatorState operatorState, RemoteGuidanceParams params,
								   EventsManager eventsManager, String mode, double changeoverDuration,
								   ActivationReconciler activationReconciler, RejectionRateTracker rejectionRateTracker,
								   BusyWindowTracker busyWindowTracker) {
		this.delegate = delegate;
		this.operators = operators;
		this.operatorState = operatorState;
		this.params = params;
		this.eventsManager = eventsManager;
		this.mode = mode;
		this.changeoverDuration = changeoverDuration;
		this.activationReconciler = activationReconciler;
		this.rejectionRateTracker = rejectionRateTracker;
		this.busyWindowTracker = busyWindowTracker;
	}

	@Override
	public ImmutableMap<Id<DrtShift>, DrtShift> initialSchedule() {
		// (re)init runtime state for this iteration. Virtual shifts are purely transient (they exist only in the QSim
		// lifecycle via liveVirtualShifts + the activation/deactivation events); they are deliberately NOT written into
		// the persistent shift specification, so there is nothing to purge across iterations and no id can collide.
		liveVirtualShifts = new HashMap<>();
		virtualShiftCounter = 0;
		// clear the operators' per-iteration runtime lifecycle (released / incident-busy / effective end): the state
		// object is a single cross-iteration instance, so without this reset a previous iteration's releases would
		// persist and starve coverage. The immutable spec registry needs no reset.
		operatorState.reset();

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
			warnIfMinActiveFleetUnreachable(fleet);
		}

		List<DrtShift> emitted = new ArrayList<>(delegate.schedule(now, fleet));

		// operator lifecycle: fire a started event for every operator whose planned start has been reached (today the
		// actual start == planned start; the emission point exists for a future delayed start). Idempotent per operator.
		for (RemoteGuidanceOperators.Operator started : operatorState.markStarted(now)) {
			eventsManager.processEvent(new RemoteGuidanceOperatorStartedEvent(now, mode, started.id(),
					started.capacity()));
		}

		// single fleet scan: reconciles which virtual shifts are live (firing activation/deactivation events) AND
		// collects the two D19 idle counts the activation triggers need — folded together to avoid a second pass over
		// the whole fleet each step (matters at large fleet sizes).
		IdleCounts idleCounts = reconcileSupervisions(now, fleet);

		// D22: release operators that have passed their planned end, but only as far as the (now reconciled) active
		// supervised fleet allows without breaking coverage — a retained operator keeps supervising its vehicles and
		// finishing any pending incident until its vehicles have gone home. Runs before emission so the freshly-released
		// operators no longer count toward the activation ceiling below. Each release fires an ended event carrying the
		// planned end, so the retention (effective − planned) is observable.
		for (RemoteGuidanceOperators.Operator released : operatorState.releaseElapsedOperators(now, liveVirtualShifts.size())) {
			eventsManager.processEvent(new RemoteGuidanceOperatorEndedEvent(now, mode, released.id(),
					released.plannedEndTime()));
		}

		// activation (RF1 / D17): pluggable triggers decide how many vehicles should be active; the reconciler combines
		// them and clamps to the hard floor + the activation ceiling Σκ. The ceiling uses the PLANNED-window capacity
		// (not coverage): a winding-down operator retained past its planned end must not pull new vehicles in.
		double rejectionRate = rejectionRateTracker == null ? 0.0 : rejectionRateTracker.rejectionRate(now);
		// busy counts only STARTED virtual vehicles doing passenger work — NOT the queue-membership activeCount below.
		// A replacement shift assigned to a still-recalling vehicle at end of day is live (so activeCount does not
		// re-emit it) but not yet started; feeding it into busy would re-inflate the target and re-run the runaway.
		int busy = busy(idleCounts.startedVirtual(), idleCounts.idleInService(), idleCounts.leaving());
		int smoothedBusy = busyWindowTracker.sample(now, busy);
		GuidanceState state = new GuidanceState(liveVirtualShifts.size(), operators.activationCapacityAt(now),
				idleCounts.idleAtHub(), idleCounts.idleInService(), smoothedBusy, rejectionRate);
		int toEmit = activationReconciler.toEmit(state, now);
		for (int i = 0; i < toEmit; i++) {
			emitted.add(createVirtualShift(now));
		}
		return emitted;
	}

	/**
	 * Counts collected during the single fleet scan: idle-at-hub (activation source), idle-in-service (ready buffer),
	 * leaving (live virtual vehicles already recalled and routing home — a materialised changeover tail), and
	 * startedVirtual (vehicles with an actually-started virtual shift). {@code leaving} is excluded from busy so a
	 * winding-down vehicle does not trigger a replacement activation. {@code startedVirtual} is the base for busy
	 * (passenger-work capacity), deliberately narrower than the queue-membership {@code activeCount}: an end-of-day
	 * replacement shift assigned to a still-recalling vehicle is live (counted in activeCount so it is not re-emitted)
	 * but not yet started, so it must not inflate busy.
	 */
	private record IdleCounts(int idleAtHub, int idleInService, int leaving, int startedVirtual) {}

	/**
	 * The busy count fed into the shared {@link BusyWindowTracker}: STARTED virtual vehicles doing passenger work, i.e.
	 * neither idle-in-service (ready buffer) nor {@code leaving} (already recalled, routing home on a materialised
	 * changeover tail).
	 * <p>
	 * Two exclusions, both load-bearing against the end-of-day recall/re-activate runaway:
	 * <ul>
	 *     <li>{@code startedVirtual} (not the queue-membership activeCount) is the base: a replacement shift assigned to
	 *         a still-recalling vehicle is live (counted in activeCount so it is not re-emitted) but its vehicle is not
	 *         doing passenger work, so it must not count as busy.</li>
	 *     <li>{@code leaving} is subtracted: a recalled vehicle is not idle-in-service (its last task is no longer a stay
	 *         task) yet it is winding down, not busy — counting it as busy would re-activate a replacement for a vehicle
	 *         that is going home.</li>
	 * </ul>
	 * {@link RemoteGuidanceShiftEndLogic} computes busy over an active set of started, non-leaving vehicles too; both
	 * margins feed the same tracker, so they must agree on this signal. Pure arithmetic, package-private for unit testing.
	 */
	static int busy(int startedVirtual, int idleInService, int leaving) {
		return startedVirtual - idleInService - leaving;
	}

	/**
	 * Warns once (at the first schedule call) if the configured {@code minActiveFleet} floor can never be met, because
	 * it exceeds either the maximum activation capacity {@code Σκ} the operator schedule ever reaches, or the number of
	 * shift-capable vehicles in the fleet. Both are hard upper bounds on the active count; the reconciler clamps the
	 * floor to them gracefully, so this is a diagnostic only (no exception): a silently-truncated floor would otherwise
	 * read as "the floor is in effect" when it is not.
	 */
	private void warnIfMinActiveFleetUnreachable(Fleet fleet) {
		int minActiveFleet = params.getMinActiveFleet();
		if (minActiveFleet <= 0) {
			return;
		}
		int maxCapacity = operators.maxActivationCapacity();
		if (minActiveFleet > maxCapacity) {
			logger.warn("minActiveFleet ({}) exceeds the maximum operator activation capacity Σκ ({}); the floor is "
					+ "capped by capacity and can never be fully met.", minActiveFleet, maxCapacity);
		}
		long shiftVehicles = fleet.getVehicles().values().stream()
				.filter(vehicle -> vehicle instanceof ShiftDvrpVehicle)
				.count();
		if (minActiveFleet > shiftVehicles) {
			logger.warn("minActiveFleet ({}) exceeds the number of shift-capable vehicles in the fleet ({}); the floor "
					+ "is capped by the fleet size and can never be fully met.", minActiveFleet, shiftVehicles);
		}
	}

	/**
	 * Single pass over the fleet that (1) recomputes which virtual shifts are live (present in a vehicle's shift queue)
	 * and fires activation/deactivation events on transitions, and (2) tallies the two D19 idle counts for the
	 * activation triggers. Emission no longer needs an id&harr;operator map: a virtual shift is recognised purely by
	 * its shift type.
	 */
	private IdleCounts reconcileSupervisions(double now, Fleet fleet) {
		Map<Id<DrtShift>, Id<DvrpVehicle>> currentlyLive = new HashMap<>();
		int idleAtHub = 0;
		int idleInService = 0;
		int leaving = 0;
		int startedVirtual = 0;
		for (DvrpVehicle vehicle : fleet.getVehicles().values()) {
			if (!(vehicle instanceof ShiftDvrpVehicle shiftVehicle)) {
				continue;
			}
			// every virtual shift in the queue counts as live for activeCount purposes: a shift emitted this iteration is
			// assigned and started within the same dispatcher step (scheduleShifts→assignShifts→startShifts), so counting
			// queue membership (not isStarted) keeps activeCount from briefly undercounting and re-emitting a duplicate.
			// This full-queue scan was never exposed to the peek() head-vs-started hazard the idle/recall paths were.
			for (DrtShift shift : shiftVehicle.getShifts()) {
				if (isVirtualShift(shift)) {
					currentlyLive.put(shift.getId(), vehicle.getId());
				}
			}
			// idle counts (D19), only meaningful for a started schedule
			Schedule schedule = vehicle.getSchedule();
			if (schedule.getStatus() != Schedule.ScheduleStatus.STARTED) {
				continue;
			}
			Task currentTask = schedule.getCurrentTask();
			if (shiftVehicle.getShifts().isEmpty()) {
				// no shift assigned at all: out of service, waiting at a hub → activation source. (Any queued shift —
				// started or not, virtual or a driver shift — means the vehicle is not a free activation source, so the
				// empty-queue check is deliberately kept here rather than "no started virtual shift".)
				if (currentTask instanceof WaitForShiftTask) {
					idleAtHub++;
				}
			} else if (startedVirtualShift(shiftVehicle).isPresent()) {
				// a virtual shift that has actually STARTED (vehicle is supervised and doing/able to do passenger work).
				// This is deliberately narrower than currentlyLive (queue membership): at end of day a recalled vehicle
				// still mid-changeover can have a fresh replacement shift ASSIGNED but not yet started — that shift is in
				// the queue (→ counted live, so activeCount does not re-emit it) but its vehicle is NOT doing passenger
				// work, so it must not feed busy. busy is computed over startedVirtual, activeCount over currentlyLive.
				startedVirtual++;
				if (ShiftSchedules.getNextShiftChangeover(schedule).isPresent()) {
					// already recalled and routing home: since D21 a virtual shift has no eager tail, so a materialised
					// changeover can only be a recall's. Such a vehicle is winding down, NOT doing passenger work — it
					// must not inflate busy (= active − idleInService), or the freed slot is immediately re-activated and
					// the recall/re-activate loop runs away at end of day. This mirrors RemoteGuidanceShiftEndLogic's
					// isAlreadyLeaving exclusion so both margins feed the shared BusyWindowTracker the same busy signal.
					leaving++;
				} else if (currentTask instanceof DrtStayTask && currentTask.equals(Schedules.getLastTask(schedule))) {
					// active virtual vehicle truly idle in service (D19: stay task that is the last task) → ready buffer.
					// keyed on the STARTED virtual shift, not the queue head, which may be a not-yet-started future shift.
					idleInService++;
				}
			}
		}

		// newly activated: live now but not tracked before
		for (Map.Entry<Id<DrtShift>, Id<DvrpVehicle>> entry : currentlyLive.entrySet()) {
			if (!liveVirtualShifts.containsKey(entry.getKey())) {
				eventsManager.processEvent(new VehicleActivatedForRemoteGuidanceEvent(now, mode, entry.getValue()));
			}
		}

		// deactivated: tracked before but no longer live (virtual shift ended)
		Set<Id<DrtShift>> ended = new HashSet<>(liveVirtualShifts.keySet());
		ended.removeAll(currentlyLive.keySet());
		int capacity = operatorState.coverageCapacityAt(now);
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
		return new IdleCounts(idleAtHub, idleInService, leaving, startedVirtual);
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

	private boolean isOperatorShift(DrtShift shift) {
		return shift.getShiftType().map(params.getOperatorShiftType()::equals).orElse(false);
	}

	static boolean isVirtualShift(DrtShift shift) {
		return shift.getShiftType().map(VIRTUAL_SHIFT_TYPE::equals).orElse(false);
	}

	/**
	 * The vehicle's currently <em>running</em> virtual shift, if any. A {@link ShiftDvrpVehicle}'s shift queue is a
	 * {@link java.util.PriorityQueue} ordered by start time and holds shifts from assignment (not from start), so its
	 * head ({@code peek()}) may be a not-yet-started future shift while a different one is actually running. Idle/recall
	 * decisions must key on the shift that has {@link DrtShift#isStarted() started} and not {@link DrtShift#isEnded()
	 * ended}, never blindly on the queue head. At most one such shift exists (a vehicle runs one shift at a time).
	 */
	static Optional<DrtShift> startedVirtualShift(ShiftDvrpVehicle vehicle) {
		for (DrtShift shift : vehicle.getShifts()) {
			if (shift.isStarted() && !shift.isEnded() && isVirtualShift(shift)) {
				return Optional.of(shift);
			}
		}
		return Optional.empty();
	}

	@Override
	public DrtShiftsSpecification get() {
		return delegate.get();
	}

	/**
	 * Convenience factory wrapping a {@link DefaultShiftScheduler} over the given specification, with the default
	 * activation policy from {@link ActivationReconciler#createDefault} (RF1 / D17): the regulatory {@code minActiveFleet}
	 * floor, an {@link IdleBufferActivation} responsiveness buffer, and — iff {@code rejectionActivation} is configured —
	 * a demand-driven {@code RejectionRateActivation} fed by {@code rejectionRateTracker}. The deactivation side ({@link
	 * RemoteGuidanceShiftEndLogic}) builds an identical reconciler from the same params and reads the SAME tracker, so
	 * both margins share one fleet-sizing target. The greedy baseline ({@code GreedyIdleActivation}) is deliberately NOT
	 * in the default set — it reproduces the low-demand sawtooth.
	 *
	 * @param rejectionRateTracker shared demand-pressure source; {@code null} when no rejection-activation config is
	 *                             present (then no {@code RejectionRateActivation} is wired).
	 * @param busyWindowTracker    shared trailing-window busy smoother read by both margins; a window of 0 makes it a
	 *                             pass-through, so behaviour is unchanged when the busy window is disabled.
	 */
	public static RemoteGuidanceScheduler create(DrtShiftsSpecification specification, RemoteGuidanceOperators operators,
												 RemoteGuidanceOperatorState operatorState, RemoteGuidanceParams params,
												 EventsManager eventsManager, String mode, double changeoverDuration,
												 RejectionRateTracker rejectionRateTracker, BusyWindowTracker busyWindowTracker) {
		ActivationReconciler reconciler = ActivationReconciler.create(params.getActivationPolicy(),
				params.getMinActiveFleet(), params.getReadyBufferSize(), rejectionThreshold(params));
		return new RemoteGuidanceScheduler(new DefaultShiftScheduler(specification), operators, operatorState, params,
				eventsManager, mode, changeoverDuration, reconciler, rejectionRateTracker, busyWindowTracker);
	}

	/**
	 * The configured rejection-rate threshold for the demand-driven trigger, or empty when no rejection-activation
	 * config is present. Shared by both margins so they build the identical trigger set.
	 */
	public static OptionalDouble rejectionThreshold(RemoteGuidanceParams params) {
		return params.getRejectionActivationParams()
				.map(p -> OptionalDouble.of(p.getRejectionRateThreshold()))
				.orElseGet(OptionalDouble::empty);
	}
}
