/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.huboperations;

import jakarta.annotation.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.LinkLeaveEvent;
import org.matsim.api.core.v01.events.handler.LinkLeaveEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.drt.extension.operations.huboperations.config.HubOperationsParams;
import org.matsim.contrib.drt.extension.operations.huboperations.events.CleaningAbandonedEvent;
import org.matsim.contrib.drt.extension.operations.huboperations.events.CleaningDueEvent;
import org.matsim.contrib.drt.extension.operations.huboperations.events.CleaningEndedEvent;
import org.matsim.contrib.drt.extension.operations.huboperations.events.CleaningQueuedEvent;
import org.matsim.contrib.drt.extension.operations.huboperations.events.CleaningStartedEvent;
import org.matsim.contrib.drt.extension.operations.huboperations.schedule.CleaningTask;
import org.matsim.contrib.drt.extension.operations.huboperations.charging.FleetSlackMonitor;
import org.matsim.contrib.drt.extension.operations.huboperations.charging.WorkerReleasedChargingStrategy;
import org.matsim.contrib.drt.extension.operations.huboperations.config.HubChargingSlackParams;
import org.matsim.contrib.drt.extension.operations.huboperations.events.CleaningWithdrawnEvent;
import org.matsim.contrib.drt.extension.operations.shifts.shift.DrtShift;
import org.matsim.contrib.drt.extension.operations.huboperations.events.HubChargingPluggedEvent;
import org.matsim.contrib.drt.extension.operations.huboperations.events.HubChargingTargetReachedEvent;
import org.matsim.contrib.drt.extension.operations.huboperations.events.HubChargingUnpluggedEvent;
import org.matsim.contrib.drt.extension.operations.operationFacilities.OperationFacilities;
import org.matsim.contrib.drt.extension.operations.shifts.fleet.ShiftDvrpVehicle;
import org.matsim.contrib.drt.extension.operations.shifts.schedule.WaitForShiftTask;
import org.matsim.contrib.ev.charging.ChargingEstimations;
import org.matsim.contrib.ev.charging.ChargingListener;
import org.matsim.contrib.ev.charging.ChargingLogic;
import org.matsim.contrib.ev.charging.ChargingStrategy;
import org.matsim.contrib.ev.fleet.ElectricVehicle;
import org.matsim.contrib.ev.infrastructure.Charger;
import org.matsim.contrib.ev.infrastructure.ChargingInfrastructure;
import org.matsim.contrib.evrp.EvDvrpVehicle;
import org.matsim.contrib.drt.extension.operations.operationFacilities.OperationFacility;
import org.matsim.contrib.drt.extension.operations.operationFacilities.OperationFacilityFinder;
import org.matsim.contrib.drt.extension.operations.operationFacilities.OperationFacilityType;
import org.matsim.contrib.drt.extension.operations.shifts.schedule.OperationalStop;
import org.matsim.contrib.drt.schedule.DrtStayTask;
import org.matsim.contrib.drt.schedule.DrtStopTask;
import org.matsim.contrib.drt.schedule.DrtTaskFactory;
import org.matsim.contrib.drt.schedule.DrtTaskType;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.fleet.Fleet;
import org.matsim.contrib.dvrp.passenger.PassengerDroppedOffEvent;
import org.matsim.contrib.dvrp.passenger.PassengerDroppedOffEventHandler;
import org.matsim.contrib.dvrp.passenger.PassengerPickedUpEvent;
import org.matsim.contrib.dvrp.passenger.PassengerPickedUpEventHandler;
import org.matsim.contrib.dvrp.path.VrpPathWithTravelData;
import org.matsim.contrib.dvrp.path.VrpPaths;
import org.matsim.contrib.dvrp.router.TimeAsTravelDisutility;
import org.matsim.contrib.dvrp.schedule.DriveTask;
import org.matsim.contrib.dvrp.schedule.Schedule;
import org.matsim.contrib.dvrp.schedule.ScheduleTimingUpdater;
import org.matsim.contrib.dvrp.schedule.StayTask;
import org.matsim.contrib.dvrp.schedule.Task;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;
import org.matsim.core.mobsim.framework.listeners.MobsimBeforeSimStepListener;
import org.matsim.core.mobsim.framework.MobsimTimer;
import org.matsim.core.events.MobsimScopeEventHandler;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelTime;
import org.matsim.vehicles.Vehicle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static org.matsim.contrib.drt.schedule.DrtTaskBaseType.DRIVE;
import static org.matsim.contrib.drt.schedule.DrtTaskBaseType.STAY;

/**
 * Sends vehicles to a hub for cleaning once a soiling counter crosses its threshold: interior per passenger kilometre,
 * sensors and exterior per vehicle kilometre. It is the runtime
 * counterpart of {@link HubOperationsParams} and runs once per sim step, mirroring how the remote-guidance
 * {@code IncidentDispatcher} plugs into the shift stack.
 * <p>
 * <b>Accounting</b> is event driven. Pickup and dropoff events keep the on-board count of each fleet vehicle, and each
 * {@link LinkLeaveEvent} adds the link length to the exterior counter and the link length times that count to the
 * interior counter in {@link SoilingTracker}. Each crossed threshold fires a {@link CleaningDueEvent}.
 * <p>
 * <b>Bundling.</b> When the cleaning starts, its types are fixed to every due type plus every type that has reached the
 * bundling share of its threshold, see {@link SoilingTracker#typesToClean}. One worker does them one after the other,
 * so the stop lasts the sum of their durations, and only the cleaned counters are reset.
 * <p>
 * <b>Finishing the passengers.</b> A due vehicle is not stopped. {@link HubAwareVehicleDataEntryFactory} takes it out
 * of request insertion, so no new rider is assigned, and the riders already assigned to it are picked up and dropped
 * off as planned. Only once its schedule holds no passenger stop any more does this dispatcher act.
 * <p>
 * <b>Sending it to the hub.</b> At that point the vehicle is idle, its current task is a stay. The dispatcher finds the
 * nearest hub, ends the stay now, and splices a drive to the hub followed by a {@link CleaningTask} into the schedule.
 * Whatever trailed the stay, such as the changeover of a committed shift end, is kept and shifted behind the cleaning,
 * with a fresh drive back to the place it was planned at. When the cleaning ends the counter is reset and the vehicle
 * is back in service, at the hub.
 * <p>
 * <b>Hub labour.</b> Without a worker roster every cleaning starts on arrival and takes the configured duration. With a
 * roster ({@link HubWorkers}) the cleaning stop begins with the arrival, but the cleaning itself only starts once a free
 * service worker of that hub is on duty. Until then the vehicle waits in the hub queue of {@link HubWorkforce}, and its
 * stop end is pushed forward step by step, as the incident dispatcher does for queued holds. When a worker takes the
 * vehicle the end is fixed to that moment plus the cleaning duration. A vehicle still waiting when no worker of its hub
 * will be on duty again gives up: its stop ends, it stays at the hub uncleaned and, being still due, out of service.
 * <p>
 * <b>Maintenance.</b> Maintenance and sensor calibration have their own vehicle-kilometre counter and their own worker
 * pool, the technicians L_M of {@link HubWorkers.Pool#maintenance}. A vehicle due for maintenance gets a maintenance
 * visit, planned and entered like a cleaning visit, but it does maintenance only: nothing is bundled with it, it is not
 * plugged in, and it waits in its own queue for a technician. Maintenance goes first; any other due type is done on a
 * separate visit afterwards. The two pools never stand in for each other.
 *
 * @author lpfeil / MOIA
 */
public final class CleaningDispatcher implements MobsimBeforeSimStepListener, LinkLeaveEventHandler,
		PassengerPickedUpEventHandler, PassengerDroppedOffEventHandler, MobsimScopeEventHandler {

	private static final Logger logger = LogManager.getLogger(CleaningDispatcher.class);

	/** Task type of the drive that brings a due vehicle to the hub. */
	public static final DrtTaskType DRIVE_TO_CLEANING_TYPE = new DrtTaskType("DRIVE_TO_CLEANING", DRIVE);

	/** Task type of the drive back from the hub to a task that was planned elsewhere, such as a changeover. */
	public static final DrtTaskType DRIVE_FROM_CLEANING_TYPE = new DrtTaskType("DRIVE_FROM_CLEANING", DRIVE);

	// Placeholder [s] by which a cleaning stop whose end is still open (waiting for a worker, charging, waiting to be
	// unplugged) is pushed forward once its end catches up with now. It has no physical meaning: the visit is moved on
	// every step and the stop is ended exactly when the visit is done, so the step size never delays a vehicle. It only
	// limits how often the schedule tail is re-timed.
	private static final double HOLD_STEP = 60.0;

	private final String mode;
	private final HubOperationsParams params;
	private final SoilingTracker tracker;
	private final Fleet fleet;
	private final Network network;
	private final OperationFacilityFinder facilityFinder;
	private final DrtTaskFactory taskFactory;
	private final ScheduleTimingUpdater scheduleTimingUpdater;
	private final EventsManager eventsManager;
	private final MobsimTimer timer;
	private final TravelTime travelTime;
	private final LeastCostPathCalculator router;
	// null when hub labour is not modelled, then hubs have unlimited capacity
	@Nullable
	private final HubWorkforce workforce;

	// mobsim vehicle id -> fleet vehicle id; its key set filters link events of other modes cheaply
	private final Map<Id<Vehicle>, Id<DvrpVehicle>> fleetVehicleIds = new LinkedHashMap<>();
	private boolean initialized = false;

	// open hub visits, so a due vehicle is dispatched once only
	private final Map<Id<DvrpVehicle>, HubVisit> visits = new LinkedHashMap<>();
	// due vehicles that gave up waiting for a worker; they are not sent to the hub again until their next threshold
	private final Set<Id<DvrpVehicle>> abandoned = new HashSet<>();
	// vehicles taken off a charger before its charging step had plugged or queued them: the ev logic still holds them
	// and ends their cancelled charge in a later step, so they must not be added to a charger again until it has
	private final Set<Id<Vehicle>> leavingCharger = new HashSet<>();

	// charging, null for fleets without batteries or with hub charging switched off
	@Nullable
	private final ChargingInfrastructure chargingInfrastructure;
	@Nullable
	private final ChargingStrategy.Factory chargingStrategyFactory;
	@Nullable
	private final OperationFacilities facilities;
	private final HubVisitRegistry registry;
	// fleet-slack aware charging, null with the plain threshold policy
	@Nullable
	private FleetSlackMonitor slackMonitor;
	@Nullable
	private HubChargingSlackParams slackParams;
	private double lastReleaseCheck = Double.NEGATIVE_INFINITY;

	public CleaningDispatcher(String mode, HubOperationsParams params, SoilingTracker tracker, Fleet fleet,
							  Network network, OperationFacilityFinder facilityFinder, DrtTaskFactory taskFactory,
							  ScheduleTimingUpdater scheduleTimingUpdater, EventsManager eventsManager,
							  MobsimTimer timer, TravelTime travelTime, @Nullable HubWorkers workers) {
		this(mode, params, tracker, fleet, network, facilityFinder, taskFactory, scheduleTimingUpdater, eventsManager,
				timer, travelTime, workers, new HubVisitRegistry(), null, null, null);
	}

	public CleaningDispatcher(String mode, HubOperationsParams params, SoilingTracker tracker, Fleet fleet,
							  Network network, OperationFacilityFinder facilityFinder, DrtTaskFactory taskFactory,
							  ScheduleTimingUpdater scheduleTimingUpdater, EventsManager eventsManager,
							  MobsimTimer timer, TravelTime travelTime, @Nullable HubWorkers workers,
							  HubVisitRegistry registry, @Nullable OperationFacilities facilities,
							  @Nullable ChargingInfrastructure chargingInfrastructure,
							  @Nullable ChargingStrategy.Factory chargingStrategyFactory) {
		this.mode = mode;
		this.params = params;
		this.tracker = tracker;
		this.fleet = fleet;
		this.network = network;
		this.facilityFinder = facilityFinder;
		this.taskFactory = taskFactory;
		this.scheduleTimingUpdater = scheduleTimingUpdater;
		this.eventsManager = eventsManager;
		this.timer = timer;
		this.travelTime = travelTime;
		this.router = new SpeedyALTFactory().createPathCalculator(network, new TimeAsTravelDisutility(travelTime),
				travelTime);
		this.workforce = workers == null ? null : new HubWorkforce(workers);
		this.registry = registry;
		this.facilities = facilities;
		boolean charging = chargingInfrastructure != null && chargingStrategyFactory != null && facilities != null
				&& params.getChargeSocThreshold() > 0;
		this.chargingInfrastructure = charging ? chargingInfrastructure : null;
		this.chargingStrategyFactory = charging ? chargingStrategyFactory : null;
	}

	/**
	 * Enables early unplugs while the fleet is tight. The monitor must be the one the tracker's fleet-slack charging
	 * policy reads.
	 */
	public CleaningDispatcher withEarlyRelease(FleetSlackMonitor monitor, HubChargingSlackParams params) {
		this.slackMonitor = monitor;
		this.slackParams = params;
		return this;
	}

	/**
	 * Which vehicles count as fleet capacity for the slack monitor in the shift stack: those with a running shift, and
	 * those on a hub visit from service. A vehicle parked at the hub out of shift is not capacity.
	 */
	public static Predicate<DvrpVehicle> inServiceForSlack(HubVisitRegistry registry) {
		return vehicle -> {
			if (!(vehicle instanceof ShiftDvrpVehicle shiftVehicle)) {
				return true;
			}
			if (registry.isOnVisit(vehicle.getId()) && vehicle.getSchedule().getStatus() == Schedule.ScheduleStatus.STARTED
					&& !(vehicle.getSchedule().getCurrentTask() instanceof WaitForShiftTask)) {
				return true;
			}
			DrtShift shift = shiftVehicle.getShifts().peek();
			return shift != null && shift.isStarted() && !shift.isEnded();
		};
	}

	// ------------------------------------------------------------------------------------------------- accounting

	@Override
	public void handleEvent(PassengerPickedUpEvent event) {
		if (mode.equals(event.getMode())) {
			tracker.pickUp(event.getVehicleId());
		}
	}

	@Override
	public void handleEvent(PassengerDroppedOffEvent event) {
		if (mode.equals(event.getMode())) {
			tracker.dropOff(event.getVehicleId());
		}
	}

	@Override
	public void handleEvent(LinkLeaveEvent event) {
		ensureInitialized();
		Id<DvrpVehicle> vehicleId = fleetVehicleIds.get(event.getVehicleId());
		if (vehicleId == null) {
			return;
		}
		double length = network.getLinks().get(event.getLinkId()).getLength();
		for (CleaningType type : tracker.addLink(vehicleId, length)) {
			abandoned.remove(vehicleId);
			// the vehicle km of the crossed counter: maintenance has its own
			double vehicleKm = type == CleaningType.maintenance ? tracker.getMaintenanceKm(vehicleId)
					: tracker.getVehicleKm(vehicleId);
			eventsManager.processEvent(new CleaningDueEvent(event.getTime(), mode, vehicleId, type,
					tracker.getPassengerKm(vehicleId), vehicleKm, soc(vehicleId)));
		}
	}

	// ------------------------------------------------------------------------------------------------- dispatching

	@Override
	public void notifyMobsimBeforeSimStep(@SuppressWarnings("rawtypes") MobsimBeforeSimStepEvent e) {
		dispatch(e.getSimulationTime());
	}

	void dispatch(double now) {
		ensureInitialized();
		updateBatteries(now);
		processVisits(now);
		releaseEarly(now);
		if (workforce != null) {
			assignWorkers(now);
			abandonUnservable(now);
		}
		holdOpenVisits(now);
		for (DvrpVehicle vehicle : fleet.getVehicles().values()) {
			if (tracker.isDue(vehicle.getId()) && !visits.containsKey(vehicle.getId())
					&& !abandoned.contains(vehicle.getId())) {
				if (!trySendToHub(vehicle, now)) {
					tryStartParkedVisit(vehicle, now);
				}
			}
		}
	}

	/** Reads the battery of every electric vehicle into the tracker and fires the due event of a crossing. */
	private void updateBatteries(double now) {
		if (chargingInfrastructure == null) {
			return;
		}
		tracker.getChargingPolicy().update(now);
		for (DvrpVehicle vehicle : fleet.getVehicles().values()) {
			if (vehicle instanceof EvDvrpVehicle evVehicle) {
				double soc = evVehicle.getElectricVehicle().getBattery().getSoc();
				if (!visits.containsKey(vehicle.getId()) && tracker.withdrawCharging(vehicle.getId(), soc)) {
					// an opportunistic charge the fleet can no longer afford: the vehicle stays in service
					eventsManager.processEvent(new CleaningWithdrawnEvent(now, mode, vehicle.getId(),
							CleaningType.charging, soc));
				}
				if (tracker.updateBattery(vehicle.getId(), soc)) {
					abandoned.remove(vehicle.getId());
					eventsManager.processEvent(new CleaningDueEvent(now, mode, vehicle.getId(), CleaningType.charging,
							tracker.getPassengerKm(vehicle.getId()), tracker.getVehicleKm(vehicle.getId()), soc));
				}
			}
		}
	}

	/** Moves each visit through its phases as its host stop starts, its cleaning ends and its charge completes. */
	private void processVisits(double now) {
		var iterator = visits.values().iterator();
		while (iterator.hasNext()) {
			HubVisit visit = iterator.next();
			switch (visit.phase) {
				case approaching -> {
					if (visit.host.getStatus() == Task.TaskStatus.STARTED) {
						arrive(visit, visit.host.getBeginTime(), now);
					}
				}
				case inService -> {
					if (visit.charges() && visit.charger == null) {
						// the plug was deferred, the vehicle was still leaving a charger: retry
						plugIn(visit, visit.serviceWorker, now);
					}
					markTargetReached(visit, now);
					if (now >= visit.cleaningEnd) {
						afterCleaning(visit, now);
					}
				}
				case charging -> {
					markTargetReached(visit, now);
					if (!Double.isNaN(visit.targetReachedTime)) {
						visit.phase = HubVisit.Phase.waitingForUnplug;
						requestUnplug(visit, now);
					}
				}
				default -> {
				}
			}
			if (visit.parked && visit.host.getStatus() == Task.TaskStatus.PERFORMED) {
				// the wait task ended under the visit, e.g. at the end of the vehicle's service time
				abort(visit, now);
				iterator.remove();
			} else if (visit.phase == HubVisit.Phase.done) {
				finish(visit, now);
				iterator.remove();
			}
		}
	}

	/**
	 * The host stop has started at {@code arrival}, seen by the dispatcher at {@code now}: queue for a worker, or without
	 * workers start at once. Events carry {@code now}, because the events manager only accepts them in time order; the
	 * queue delay counts from the arrival.
	 */
	private void arrive(HubVisit visit, double arrival, double now) {
		visit.arrivalTime = arrival;
		if (workforce == null) {
			startService(visit, null, now, now - arrival);
			return;
		}
		visit.phase = HubVisit.Phase.waitingForWorker;
		workforce.enqueue(visit.hub, visit.vehicleId, visit.serviceJob(), arrival);
		eventsManager.processEvent(new CleaningQueuedEvent(now, mode, visit.vehicleId, visit.hub, visit.maintenance));
	}

	/** Hands waiting jobs to free workers. */
	private void assignWorkers(double now) {
		for (HubWorkforce.Assignment assignment : workforce.assign(now, this::serviceDuration)) {
			HubVisit visit = visits.get(assignment.vehicleId());
			if (assignment.job() != HubWorkforce.Job.unplug) {
				startService(visit, assignment.workerId(), now, assignment.queueDelay());
			} else {
				unplug(visit, assignment.workerId(), now);
			}
		}
	}

	/**
	 * Worker time of the service job of a vehicle that a worker takes now, fixing its types first: every due type plus
	 * every type that has reached the bundling share by now. They are decided at the start, not when the visit was
	 * planned, because the counters keep running while the vehicle finishes its riders and drives to the hub.
	 */
	private double serviceDuration(Id<DvrpVehicle> vehicleId) {
		HubVisit visit = visits.get(vehicleId);
		visit.types = typesFor(vehicleId, visit.hub, visit.maintenance);
		return params.getDuration(visit.types);
	}

	/**
	 * Types to do on a visit at {@code hub}: maintenance only on a maintenance visit, otherwise the cleaning types and
	 * charging, charging only if the vehicle has a battery and the hub a charger.
	 */
	private Set<CleaningType> typesFor(Id<DvrpVehicle> vehicleId, Id<OperationFacility> hub, boolean maintenance) {
		if (maintenance) {
			return tracker.typesToMaintain(vehicleId);
		}
		Set<CleaningType> types = tracker.typesToClean(vehicleId);
		if (types.contains(CleaningType.charging) && findCharger(hub).isEmpty()) {
			types.remove(CleaningType.charging);
		}
		return types;
	}

	/**
	 * Starts the service job: plug in if the visit charges, then clean the cleaning types one after the other. A
	 * {@code null} worker means hub workers are not modelled.
	 */
	private void startService(HubVisit visit, @Nullable Id<HubWorkers.HubWorker> worker, double now, double queueDelay) {
		if (worker == null) {
			visit.types = typesFor(visit.vehicleId, visit.hub, visit.maintenance);
		}
		visit.serviceWorker = worker;
		visit.serviceStart = now;
		visit.queueDelay = queueDelay;
		visit.cleaningEnd = now + params.getDuration(visit.types);
		visit.phase = HubVisit.Phase.inService;
		if (visit.charges()) {
			plugIn(visit, worker, now);
		}
		if (cleaningTypes(visit).isEmpty() && !visit.charges()) {
			// nothing left to do, e.g. a charging visit at a hub without a charger of the vehicle's kind
			visit.phase = HubVisit.Phase.done;
		}
		if (visit.host instanceof CleaningTask task) {
			task.setTypes(visit.types);
		}
		eventsManager.processEvent(new CleaningStartedEvent(now, mode, visit.vehicleId, visit.hub, visit.types,
				worker, queueDelay).withWorkerTime(visit.cleaningEnd - now));
	}

	/** Records the first step in which a plugged vehicle has reached its charging target. */
	private void markTargetReached(HubVisit visit, double now) {
		if (visit.isPlugged() && Double.isNaN(visit.targetReachedTime) && visit.strategy.isTargetReached()) {
			visit.targetReachedTime = now;
			eventsManager.processEvent(new HubChargingTargetReachedEvent(now, mode, visit.vehicleId,
					visit.ev.getBattery().getSoc()));
		}
	}

	/** Cleaning is over: the visit is done unless the vehicle is still on the charger. */
	private void afterCleaning(HubVisit visit, double now) {
		if (visit.charges() && visit.charger == null) {
			// the plug is still deferred, the vehicle is leaving a charger: wait in service and retry next step
			return;
		}
		if (!visit.charges() || !visit.isPlugged()) {
			visit.phase = HubVisit.Phase.done;
		} else if (!Double.isNaN(visit.targetReachedTime)) {
			// charged before the cleaning was done; it has stood on the plug since then
			visit.phase = HubVisit.Phase.waitingForUnplug;
			requestUnplug(visit, now);
		} else {
			visit.phase = HubVisit.Phase.charging;
		}
	}

	private void requestUnplug(HubVisit visit, double now) {
		if (workforce == null) {
			unplug(visit, null, now);
		} else {
			workforce.enqueue(visit.hub, visit.vehicleId, HubWorkforce.Job.unplug, now);
		}
	}

	// ------------------------------------------------------------------------------------------------- charging

	/**
	 * While the fleet is tight, requests the early unplug of as many charging vehicles as the fleet is short of the
	 * lower slack threshold: those already cleaned, actually on a plug for at least the minimum charging time and above
	 * the release SOC, fullest first. Each unplug is a worker job like a regular one, so a scarce hub crew delays the
	 * vehicle's return. Demand driven as in the Lucerne controller, at the configured interval.
	 */
	private void releaseEarly(double now) {
		if (slackMonitor == null || slackParams == null || !tracker.getChargingPolicy().allowsEarlyRelease()
				|| now < lastReleaseCheck + slackParams.getReleaseIntervalSeconds() || slackMonitor.isRelaxed()) {
			return;
		}
		lastReleaseCheck = now;
		int deficit = Math.max(1, (int) Math.ceil((slackParams.getSlackLow() - slackMonitor.smoothedFreeRatio())
				* slackMonitor.vehiclesInService()));
		List<HubVisit> candidates = new ArrayList<>();
		for (HubVisit visit : visits.values()) {
			if (visit.phase == HubVisit.Phase.charging && !visit.parked && isOnPlug(visit)
					&& visit.ev.getBattery().getSoc() >= slackParams.getSocRelease()
					&& now - visit.serviceStart >= slackParams.getMinChargeSecondsBeforeRelease()) {
				candidates.add(visit);
			}
		}
		candidates.sort(Comparator.comparingDouble((HubVisit v) -> v.ev.getBattery().getSoc()).reversed());
		for (HubVisit visit : candidates.subList(0, Math.min(deficit, candidates.size()))) {
			visit.earlyRelease = true;
			visit.phase = HubVisit.Phase.waitingForUnplug;
			requestUnplug(visit, now);
		}
	}

	/** Whether the ev logic has actually plugged the vehicle in, as opposed to queueing it for a plug. */
	private static boolean isOnPlug(HubVisit visit) {
		return visit.isPlugged() && visit.charger.getLogic().getPluggedVehicles().stream()
				.anyMatch(cv -> cv.ev() == visit.ev);
	}

	private void plugIn(HubVisit visit, @Nullable Id<HubWorkers.HubWorker> worker, double now) {
		Optional<Charger> charger = findCharger(visit.hub);
		DvrpVehicle vehicle = fleet.getVehicles().get(visit.vehicleId);
		if (charger.isEmpty() || !(vehicle instanceof EvDvrpVehicle evVehicle)) {
			visit.types.remove(CleaningType.charging);
			return;
		}
		ElectricVehicle ev = evVehicle.getElectricVehicle();
		if (leavingCharger.contains(ev.getId())) {
			// still held by a charger from a cancelled charge; adding it again would plug it in twice. The visit stays in
			// service and retries in the next step
			return;
		}
		visit.ev = ev;
		visit.charger = charger.get();
		visit.strategy = new WorkerReleasedChargingStrategy(
				chargingStrategyFactory.createStrategy(charger.get().getSpecification(), ev));
		visit.socAtPlug = ev.getBattery().getSoc();
		visit.chargeAtPlug = ev.getBattery().getCharge();
		// the ev logic plugs it in its next charging step, or queues it if all plugs are taken
		charger.get().getLogic().addVehicle(ev, visit.strategy, releaseListener, now);
		eventsManager.processEvent(new HubChargingPluggedEvent(now, mode, visit.vehicleId, visit.hub,
				charger.get().getId(), visit.socAtPlug, worker));
	}

	/** Clears a vehicle from {@link #leavingCharger} once the ev logic has let it go. */
	private final ChargingListener releaseListener = new ChargingListener() {
		@Override
		public void notifyChargingEnded(ElectricVehicle ev, double now) {
			leavingCharger.remove(ev.getId());
		}

		@Override
		public void notifyVehicleQuitChargerQueue(ElectricVehicle ev, double now) {
			leavingCharger.remove(ev.getId());
		}
	};

	/** Takes the vehicle off the charger, plugged or still queued for a plug. */
	private void unplug(HubVisit visit, @Nullable Id<HubWorkers.HubWorker> worker, double now) {
		if (!visit.isPlugged()) {
			return;
		}
		ChargingLogic logic = visit.charger.getLogic();
		boolean onCharger = logic.getPluggedVehicles().stream().anyMatch(cv -> cv.ev() == visit.ev)
				|| logic.getQueuedVehicles().stream().anyMatch(cv -> cv.ev() == visit.ev);
		if (onCharger) {
			logic.removeVehicle(visit.ev, now);
		} else {
			// added this charging step but not yet plugged or queued by the ev logic: let it end the charge itself, and
			// keep the vehicle off every charger until it has
			visit.strategy.cancel();
			leavingCharger.add(visit.ev.getId());
		}
		visit.unplugTime = now;
		visit.unplugWorker = worker;
		double energy = visit.ev.getBattery().getCharge() - visit.chargeAtPlug;
		eventsManager.processEvent(new HubChargingUnpluggedEvent(now, mode, visit.vehicleId, visit.hub,
				visit.charger.getId(), visit.ev.getBattery().getSoc(), worker, now - visit.targetReachedTime,
				energy).withEarly(visit.earlyRelease));
		if (visit.phase == HubVisit.Phase.waitingForUnplug) {
			visit.phase = HubVisit.Phase.done;
		}
	}

	/**
	 * The charger for a visit at {@code hub}: among the hub's chargers of the configured type the one with the shortest
	 * expected wait, otherwise among all its chargers.
	 */
	private Optional<Charger> findCharger(Id<OperationFacility> hub) {
		if (chargingInfrastructure == null) {
			return Optional.empty();
		}
		OperationFacility facility = facilities.getFacilities().get(hub);
		if (facility == null || facility.getChargers().isEmpty()) {
			return Optional.empty();
		}
		List<Charger> chargers = facility.getChargers().stream()
				.map(id -> chargingInfrastructure.getChargers().get(id))
				.filter(Objects::nonNull)
				.toList();
		List<Charger> preferred = chargers.stream()
				.filter(c -> params.getChargerType().equals(c.getChargerType()))
				.toList();
		return (preferred.isEmpty() ? chargers : preferred).stream()
				.min(Comparator.comparingDouble(ChargingEstimations::estimateMaxWaitTimeForNextVehicle));
	}

	// ------------------------------------------------------------------------------------------------- closing

	/** The visit is over: reset the counters of what was done, fire the end event and let the host stop end. */
	private void finish(HubVisit visit, double now) {
		Set<CleaningType> done = EnumSet.copyOf(cleaningTypes(visit));
		if (visit.charges() && !Double.isNaN(visit.unplugTime)) {
			done.add(CleaningType.charging);
		}
		tracker.reset(visit.vehicleId, done.isEmpty() ? EnumSet.noneOf(CleaningType.class) : done);
		// a charging visit that could not charge must not leave the vehicle due for ever; a maintenance visit does not
		// charge by design, so it leaves the charging flag for the next visit
		if (!visit.charges() && !visit.maintenance) {
			tracker.reset(visit.vehicleId, EnumSet.of(CleaningType.charging));
		}
		registry.end(visit.vehicleId);
		eventsManager.processEvent(new CleaningEndedEvent(now, mode, visit.vehicleId, visit.hub,
				done.isEmpty() ? visit.types : done));
		releaseHost(visit, now);
	}

	/** Ends the host stop now; for a parked vehicle the wait task simply goes on, so nothing is to be done. */
	private void releaseHost(HubVisit visit, double now) {
		if (visit.host instanceof CleaningTask task && task.getStatus() == Task.TaskStatus.STARTED) {
			setHostEnd(visit, Math.max(now, task.getBeginTime()));
		}
	}

	/** Ends a visit that cannot go on: takes the vehicle off the charger and out of the queues, nothing is reset. */
	private void abort(HubVisit visit, double now) {
		unplug(visit, null, now);
		if (workforce != null) {
			workforce.remove(visit.vehicleId);
		}
		registry.end(visit.vehicleId);
		eventsManager.processEvent(new CleaningAbandonedEvent(now, mode, visit.vehicleId, visit.hub,
				Double.isNaN(visit.arrivalTime) ? 0. : now - visit.arrivalTime));
	}

	/** Ends every waiting visit at a hub where no worker will be on duty again; a plugged vehicle is unplugged. */
	private void abandonUnservable(double now) {
		for (HubWorkforce.Waiting waiting : workforce.dropUnservable(now)) {
			HubVisit visit = visits.get(waiting.vehicleId());
			if (waiting.job() == HubWorkforce.Job.unplug) {
				// charged, nobody to unplug it: take it off without a worker so the plug is not blocked for ever
				unplug(visit, null, now);
				continue;
			}
			visits.remove(waiting.vehicleId());
			abandoned.add(waiting.vehicleId());
			registry.end(waiting.vehicleId());
			eventsManager.processEvent(new CleaningAbandonedEvent(now, mode, waiting.vehicleId(), visit.hub,
					now - visit.arrivalTime));
			releaseHost(visit, now);
		}
	}

	/**
	 * Keeps the cleaning stop of every open visit from running out while it waits for a worker, is cleaned, charges or
	 * waits to be unplugged. The stop is ended by {@link #finish} in the step the visit is done.
	 */
	private void holdOpenVisits(double now) {
		for (HubVisit visit : visits.values()) {
			if (visit.host instanceof CleaningTask task && task.getStatus() == Task.TaskStatus.STARTED
					&& task.getEndTime() <= now) {
				setHostEnd(visit, now + HOLD_STEP);
			}
		}
	}

	/** Sets the end of a started cleaning stop and re-times everything behind it. */
	private void setHostEnd(HubVisit visit, double endTime) {
		visit.host.setEndTime(endTime);
		scheduleTimingUpdater.updateTimingsStartingFromTaskIdx(fleet.getVehicles().get(visit.vehicleId),
				visit.host.getTaskIdx() + 1, endTime);
	}

	private static Set<CleaningType> cleaningTypes(HubVisit visit) {
		Set<CleaningType> types = EnumSet.noneOf(CleaningType.class);
		visit.types.stream().filter(CleaningType::isCleaning).forEach(types::add);
		return types;
	}

	// ------------------------------------------------------------------------------------------------- entering

	/**
	 * Sends a due vehicle to the nearest hub once it has finished its assigned passengers. Returns {@code false} without
	 * change while passengers are still on board or assigned, or while the vehicle is not idle in service; it is retried
	 * on the next sim step.
	 */
	private boolean trySendToHub(DvrpVehicle vehicle, double now) {
		Schedule schedule = vehicle.getSchedule();
		if (schedule.getStatus() != Schedule.ScheduleStatus.STARTED) {
			return false;
		}
		if (tracker.getOnBoard(vehicle.getId()) > 0 || hasPendingPassengerStop(schedule)) {
			return false; // still finishing the passengers assigned before it became due
		}
		Task current = schedule.getCurrentTask();
		if (!STAY.isBaseTypeOf(current) || !(current instanceof DrtStayTask stay)) {
			return false; // parked at a hub, driving to one for another reason, or at a break, changeover or hold
		}
		if (stay.getEndTime() <= now) {
			return false; // the stay is ending this step; let the schedule move on and retry
		}

		Optional<OperationFacilityFinder.FacilityWithPath> maybeHub = facilityFinder.findFacility(stay.getLink(), vehicle,
				Set.of(OperationFacilityType.hub));
		if (maybeHub.isEmpty()) {
			logger.warn("vehicle {} is due at a hub but none is available, retrying", vehicle.getId());
			return false;
		}
		boolean maintenance = tracker.isMaintenanceDue(vehicle.getId());
		CleaningTask cleaning = insertCleaning(vehicle, stay, maybeHub.get().operationFacility(),
				maybeHub.get().path(), maintenance, now);
		visits.put(vehicle.getId(), new HubVisit(vehicle.getId(), cleaning.getFacilityId(), cleaning, false,
				maintenance));
		registry.start(vehicle.getId());
		return true;
	}

	/**
	 * Starts a visit for a vehicle parked out of service at a hub, on its {@link WaitForShiftTask}. The vehicle does not
	 * move; it stays on its wait task, and shift assignment and remote-guidance activation leave it alone until the
	 * visit is over, see {@link HubVisitRegistry}.
	 */
	private void tryStartParkedVisit(DvrpVehicle vehicle, double now) {
		if (!(vehicle instanceof ShiftDvrpVehicle shiftVehicle) || !shiftVehicle.getShifts().isEmpty()) {
			return;
		}
		Schedule schedule = vehicle.getSchedule();
		if (schedule.getStatus() != Schedule.ScheduleStatus.STARTED
				|| !(schedule.getCurrentTask() instanceof WaitForShiftTask wait)
				|| wait.getChargingTask().isPresent()) {
			return;
		}
		HubVisit visit = new HubVisit(vehicle.getId(), wait.getFacilityId(), wait, true,
				tracker.isMaintenanceDue(vehicle.getId()));
		visits.put(vehicle.getId(), visit);
		registry.start(vehicle.getId());
		arrive(visit, now, now);
	}

	/**
	 * Splices {@code [drive to hub, cleaning]} into the schedule right after the current stay, which is ended now.
	 * Tasks that trailed the stay are kept. If the next one is planned elsewhere than the hub, a drive back to its link
	 * is inserted before it, so a committed changeover still happens where it was reserved.
	 */
	private CleaningTask insertCleaning(DvrpVehicle vehicle, DrtStayTask stay, OperationFacility hub,
										VrpPathWithTravelData toHub, boolean maintenance, double now) {
		Schedule schedule = vehicle.getSchedule();
		List<Task> tail = new ArrayList<>(schedule.getTasks().subList(stay.getTaskIdx() + 1,
				schedule.getTasks().size()));
		for (Task task : tail) {
			schedule.removeTask(task);
		}

		stay.setEndTime(now);
		// the finder routes from the current time, which is now in a before-sim-step listener; recompute only if not
		VrpPathWithTravelData path = toHub.getDepartureTime() == now ? toHub
				: VrpPaths.calcAndCreatePath(stay.getLink(), toHub.getToLink(), now, router, travelTime);
		schedule.addTask(taskFactory.createDriveTask(vehicle, path, DRIVE_TO_CLEANING_TYPE));

		// planned with the types known now; the types and the end are settled when the service starts
		Set<CleaningType> types = typesFor(vehicle.getId(), hub.getId(), maintenance);
		// at least one second, so the stop cannot start and end between two dispatcher steps unseen; with workers or
		// charging the end is open anyway and is held until the visit is done
		double duration = Math.max(1., params.getDuration(types));
		if (workforce != null || types.contains(CleaningType.charging)) {
			duration = Math.max(duration, HOLD_STEP);
		}
		double cleaningEnd = path.getArrivalTime() + duration;
		CleaningTask cleaning = new CleaningTask(path.getArrivalTime(), cleaningEnd, path.getToLink(), hub.getId(),
				types);
		schedule.addTask(cleaning);

		reattachTail(vehicle, tail, path.getToLink(), cleaningEnd);
		scheduleTimingUpdater.updateTimings(vehicle);
		return cleaning;
	}

	/**
	 * Re-appends the tasks that trailed the interrupted stay behind the cleaning. An open stay tail is replaced by a
	 * stay at the hub until the vehicle's service end, which keeps the vehicle available in service at the hub. Any
	 * other tail starts with an operational stop or a stay at its own link, so a drive back to that link precedes it.
	 */
	private void reattachTail(DvrpVehicle vehicle, List<Task> tail, Link hubLink, double cleaningEnd) {
		Schedule schedule = vehicle.getSchedule();
		if (tail.isEmpty()) {
			if (cleaningEnd < vehicle.getServiceEndTime()) {
				schedule.addTask(taskFactory.createStayTask(vehicle, cleaningEnd, vehicle.getServiceEndTime(), hubLink));
			}
			return;
		}
		if (tail.get(0) instanceof DriveTask drive) {
			// a planned drive after the stay (e.g. to a changeover elsewhere) started at the stay; replace it by a drive
			// from the hub to the same destination, so the schedule stays connected
			Link destination = drive.getPath().getToLink();
			tail.remove(0);
			if (!destination.equals(hubLink)) {
				VrpPathWithTravelData back = VrpPaths.calcAndCreatePath(hubLink, destination, cleaningEnd, router,
						travelTime);
				schedule.addTask(taskFactory.createDriveTask(vehicle, back, (DrtTaskType) drive.getTaskType()));
				cleaningEnd = back.getArrivalTime();
			}
			if (tail.isEmpty()) {
				if (cleaningEnd < vehicle.getServiceEndTime()) {
					schedule.addTask(taskFactory.createStayTask(vehicle, cleaningEnd, vehicle.getServiceEndTime(),
							destination));
				}
				return;
			}
			hubLink = destination;
		}
		Task first = tail.get(0);
		Link firstLink = taskLink(first);
		double resume = cleaningEnd;
		if (firstLink != null && !firstLink.equals(hubLink)) {
			VrpPathWithTravelData back = VrpPaths.calcAndCreatePath(hubLink, firstLink, cleaningEnd, router,
					travelTime);
			schedule.addTask(taskFactory.createDriveTask(vehicle, back, DRIVE_FROM_CLEANING_TYPE));
			resume = back.getArrivalTime();
		}
		Link at = firstLink != null ? firstLink : hubLink;
		for (Task task : tail) {
			double begin = Math.max(resume, task.getBeginTime());
			if (begin > resume) {
				// the task is planned later than the vehicle can be there, e.g. a changeover at its shift end: wait for it
				schedule.addTask(taskFactory.createStayTask(vehicle, resume, begin, at));
			}
			task.setBeginTime(begin);
			task.setEndTime(Math.max(begin, task.getEndTime()));
			schedule.addTask(task);
			resume = task.getEndTime();
			Link end = endLink(task);
			if (end != null) {
				at = end;
			}
		}
	}

	private static Link endLink(Task task) {
		if (task instanceof DriveTask drive) {
			return drive.getPath().getToLink();
		}
		return taskLink(task);
	}

	private static Link taskLink(Task task) {
		if (task instanceof DrtStayTask stay) {
			return stay.getLink();
		}
		if (task instanceof DrtStopTask stop) {
			return stop.getLink();
		}
		if (task instanceof StayTask stay) {
			return stay.getLink();
		}
		return null;
	}

	/** True while the schedule still holds a passenger pickup or dropoff that has not been performed. */
	private static boolean hasPendingPassengerStop(Schedule schedule) {
		List<? extends Task> tasks = schedule.getTasks();
		for (Task task : tasks.subList(schedule.getCurrentTask().getTaskIdx(), tasks.size())) {
			if (task instanceof DrtStopTask stop && !(task instanceof OperationalStop)
					&& (!stop.getPickupRequests().isEmpty() || !stop.getDropoffRequests().isEmpty())) {
				return true;
			}
		}
		return false;
	}

	private double soc(Id<DvrpVehicle> vehicleId) {
		double used = tracker.getBatteryUsed(vehicleId);
		return Double.isNaN(used) ? Double.NaN : 1. - used;
	}

	private void ensureInitialized() {
		if (initialized) {
			return;
		}
		for (Id<DvrpVehicle> dvrpVehicleId : fleet.getVehicles().keySet()) {
			fleetVehicleIds.put(Id.create(dvrpVehicleId, Vehicle.class), dvrpVehicleId);
		}
		if (params.isRandomizeInitialSoiling()) {
			// a fleet that has been in service before: counters spread over [0, threshold), sorted ids for determinism
			tracker.randomizeInitialCounters(fleet.getVehicles().keySet().stream().sorted().toList(),
					MatsimRandom.getLocalInstance());
			logger.info("Initial soiling randomized for {} vehicles.", fleetVehicleIds.size());
		}
		logger.info("Initialized hub operations for {} vehicles: interior every {} pkm for {} s, exterior every {} vkm for"
						+ " {} s, maintenance every {} vkm for {} s, {}, bundling from {} of a threshold, {}.",
				fleetVehicleIds.size(), params.getSoilingThresholdPkm(), params.getCleaningDuration(),
				params.getExteriorSoilingThresholdVkm(), params.getExteriorCleaningDuration(),
				params.getMaintenanceThresholdVkm(), params.getMaintenanceDuration(),
				chargingInfrastructure == null ? "no hub charging"
						: "charging at SOC " + params.getChargeSocThreshold() + " on " + params.getChargerType()
						+ " chargers",
				params.getBundlingShare(),
				workforce == null ? "unlimited hub capacity" : workforce.getRoster().count(HubWorkers.Pool.cleaning)
						+ " hub workers and " + workforce.getRoster().count(HubWorkers.Pool.maintenance)
						+ " technicians, " + workforce.getRoster().plannedHours() + " planned worker hours");
		if (workforce != null && Double.isFinite(params.getMaintenanceThresholdVkm())
				&& workforce.getRoster().count(HubWorkers.Pool.maintenance) == 0) {
			logger.warn("Maintenance is due every {} vkm but the roster has no maintenance technicians: every"
					+ " maintenance visit will be abandoned and its vehicle stay at the hub.", params.getMaintenanceThresholdVkm());
		}
		initialized = true;
	}

	@Override
	public void reset(int iteration) {
		tracker.clear();
		visits.clear();
		abandoned.clear();
		registry.clear();
		lastReleaseCheck = Double.NEGATIVE_INFINITY;
		if (slackMonitor != null) {
			slackMonitor.reset();
		}
		if (workforce != null) {
			workforce.clear();
		}
	}
}
