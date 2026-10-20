/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.huboperations.analysis;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.contrib.drt.extension.operations.huboperations.CleaningType;
import org.matsim.contrib.drt.extension.operations.huboperations.HubWorkers;
import org.matsim.contrib.drt.extension.operations.huboperations.events.AbstractHubOperationsEvent;
import org.matsim.contrib.drt.extension.operations.huboperations.events.CleaningAbandonedEvent;
import org.matsim.contrib.drt.extension.operations.huboperations.events.CleaningDueEvent;
import org.matsim.contrib.drt.extension.operations.huboperations.events.CleaningEndedEvent;
import org.matsim.contrib.drt.extension.operations.huboperations.events.CleaningQueuedEvent;
import org.matsim.contrib.drt.extension.operations.huboperations.events.CleaningStartedEvent;
import org.matsim.contrib.drt.extension.operations.huboperations.events.HubChargingPluggedEvent;
import org.matsim.contrib.drt.extension.operations.huboperations.events.HubChargingUnpluggedEvent;
import org.matsim.contrib.ev.EvUnits;
import org.matsim.contrib.ev.infrastructure.Charger;
import org.matsim.contrib.drt.extension.operations.operationFacilities.OperationFacility;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.core.events.handler.BasicEventHandler;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rebuilds the hub visit of every vehicle, cleaning and charging, from the hub operations events, the counterpart of
 * {@code RemoteGuidanceAnalysisTracker}. A vehicle has at most one open cleaning at a time, so the events are matched
 * on the vehicle id: due, then queued (only with hub workers), then started, then ended or abandoned.
 * <p>
 * A maintenance visit is a lifecycle of its own: a type that became due before or during it, but is not maintenance,
 * is carried over into the next lifecycle, the cleaning visit that follows; and maintenance that becomes due during a
 * cleaning visit is carried over the same way.
 * <p>
 * Each closed lifecycle becomes one {@link CleaningRecord}. Cleanings still open at the end of the iteration are kept
 * as records with the missing times set to {@code NaN}, so a due vehicle that never reached the hub is not lost.
 * Besides the records the tracker keeps the chronological step changes of the hub queue length and of the busy workers
 * per hub, behind the time series of where hub labour binds.
 *
 * @author lpfeil / MOIA
 */
public final class HubCleaningAnalysisTracker implements BasicEventHandler {

	/** Outcome of one cleaning lifecycle. */
	public enum Outcome {
		/** cleaned at the hub */
		cleaned,
		/** gave up waiting because no worker of the hub came on duty again */
		abandoned,
		/** still finishing riders, driving to the hub, queued or being cleaned when the iteration ended */
		open
	}

	/**
	 * One cleaning lifecycle of one vehicle. Times are seconds of the simulation, {@code NaN} where the step did not
	 * happen. {@code workerId} is {@code null} without hub workers or when no worker took the vehicle.
	 */
	public record CleaningRecord(Id<DvrpVehicle> vehicleId, Id<OperationFacility> hub, CleaningType trigger,
								 Set<CleaningType> cleaned, double passengerKm, double vehicleKm,
								 double dueTime, double arrivalTime, double startTime, double endTime,
								 Id<HubWorkers.HubWorker> workerId, double queueDelay, double workerTime,
								 Charging charging, Outcome outcome) {

		/** Charging part of a visit; all fields NaN or null when the vehicle was not plugged in. */
		public record Charging(Id<Charger> chargerId, double plugTime, double socAtPlug, double unplugTime,
							   double socAtUnplug, Id<HubWorkers.HubWorker> unplugWorker, double waitForUnplug,
							   double energyKWh, boolean early) {
			static final Charging NONE = new Charging(null, Double.NaN, Double.NaN, Double.NaN, Double.NaN, null,
					Double.NaN, Double.NaN, false);

			public boolean plugged() {
				return chargerId != null;
			}

			/** Time on the charger, from plug-in to unplug. */
			public double pluggedTime() {
				return unplugTime - plugTime;
			}
		}

		/** Whether a type that was not due was cleaned along with the trigger. */
		public boolean bundled() {
			return cleaned.size() > 1;
		}

		/** Whether this was a maintenance visit of the maintenance pool L_M. */
		public boolean maintenance() {
			return cleaned.contains(CleaningType.maintenance) || trigger == CleaningType.maintenance;
		}

		/** Time out of service: from becoming due until the cleaning ended or was abandoned. */
		public double outOfServiceTime() {
			double close = outcome == Outcome.cleaned ? endTime : outcome == Outcome.abandoned ? arrivalTime + queueDelay
					: Double.NaN;
			return close - dueTime;
		}

		/** Time from becoming due until arriving at the hub, i.e. finishing riders and the empty drive. */
		public double approachTime() {
			return arrivalTime - dueTime;
		}

		/** Worker time spent cleaning, without the time the vehicle only charged. */
		public double cleaningDuration() {
			return Double.isNaN(workerTime) ? endTime - startTime : workerTime;
		}

		/** Time at the hub from arrival to departure. */
		public double dwellTime() {
			return endTime - arrivalTime;
		}
	}

	/**
	 * Step change of the vehicles waiting at a hub, the workers busy there or the vehicles plugged in; {@code maintenance}
	 * says whether the queue and the workers are those of the maintenance pool.
	 */
	public record HubChange(double time, Id<OperationFacility> hub, int queueDelta, int busyDelta, int pluggedDelta,
							boolean maintenance) {
		HubChange(double time, Id<OperationFacility> hub, int queueDelta, int busyDelta, boolean maintenance) {
			this(time, hub, queueDelta, busyDelta, 0, maintenance);
		}
	}

	/** The due event of one type. */
	private record Due(double time, double passengerKm, double vehicleKm) {
	}

	private static final class Open {
		private Id<OperationFacility> hub;
		// due types in the order they crossed; the trigger is picked from them when the record is written
		private final Map<CleaningType, Due> dues = new LinkedHashMap<>();
		private boolean maintenanceVisit;
		private Set<CleaningType> cleaned = EnumSet.noneOf(CleaningType.class);
		private double arrivalTime = Double.NaN;
		private double startTime = Double.NaN;
		private Id<HubWorkers.HubWorker> workerId;
		private double queueDelay = Double.NaN;
		private double workerTime = Double.NaN;
		private boolean queued;
		private CleaningRecord.Charging charging = CleaningRecord.Charging.NONE;

		/** The type that brought the vehicle in: maintenance on a maintenance visit, else the first other due type. */
		private CleaningType trigger() {
			if (maintenanceVisit) {
				return CleaningType.maintenance;
			}
			return dues.keySet().stream().filter(t -> t != CleaningType.maintenance).findFirst()
					.orElse(dues.keySet().stream().findFirst().orElse(null));
		}

		private Due triggerDue() {
			CleaningType trigger = trigger();
			return trigger == null || !dues.containsKey(trigger) ? new Due(Double.NaN, Double.NaN, Double.NaN)
					: dues.get(trigger);
		}
	}

	private final String mode;
	private Map<Id<DvrpVehicle>, Open> open = new HashMap<>();
	private List<CleaningRecord> records = new ArrayList<>();
	private List<HubChange> hubChanges = new ArrayList<>();

	public HubCleaningAnalysisTracker(String mode) {
		this.mode = mode;
	}

	@Override
	public void handleEvent(Event event) {
		if (!(event instanceof AbstractHubOperationsEvent hubEvent) || !mode.equals(hubEvent.getMode())) {
			return;
		}
		Id<DvrpVehicle> vehicleId = hubEvent.getVehicleId();
		if (event instanceof CleaningDueEvent due) {
			// the first due type opens the lifecycle; a second type crossing on the way is part of the same visit
			open.computeIfAbsent(vehicleId, v -> new Open()).dues.putIfAbsent(due.getCleaningType(),
					new Due(due.getTime(), due.getPassengerKm(), due.getVehicleKm()));
			return;
		}
		Open cleaning = open.computeIfAbsent(vehicleId, v -> new Open());
		if (event instanceof CleaningQueuedEvent queued) {
			cleaning.maintenanceVisit = queued.isMaintenance();
			cleaning.hub = queued.getFacilityId();
			cleaning.arrivalTime = queued.getTime();
			cleaning.queued = true;
			hubChanges.add(new HubChange(queued.getTime(), cleaning.hub, +1, 0, cleaning.maintenanceVisit));
		} else if (event instanceof CleaningStartedEvent started) {
			cleaning.hub = started.getFacilityId();
			cleaning.cleaned = EnumSet.copyOf(started.getCleaningTypes());
			cleaning.maintenanceVisit |= cleaning.cleaned.contains(CleaningType.maintenance);
			cleaning.startTime = started.getTime();
			cleaning.workerId = started.getWorkerId();
			cleaning.queueDelay = started.getQueueDelay();
			cleaning.workerTime = started.getWorkerTime();
			if (Double.isNaN(cleaning.arrivalTime)) {
				cleaning.arrivalTime = started.getTime() - started.getQueueDelay();
			}
			hubChanges.add(new HubChange(started.getTime(), cleaning.hub, cleaning.queued ? -1 : 0, 0,
					cleaning.maintenanceVisit));
			if (started.getWorkerId() != null) {
				// the worker is busy for the cleaning time only; plugging in and out take none
				double workerTime = Double.isNaN(started.getWorkerTime()) ? 0. : started.getWorkerTime();
				if (workerTime > 0) {
					hubChanges.add(new HubChange(started.getTime(), cleaning.hub, 0, +1, cleaning.maintenanceVisit));
					hubChanges.add(new HubChange(started.getTime() + workerTime, cleaning.hub, 0, -1,
							cleaning.maintenanceVisit));
				}
			}
		} else if (event instanceof HubChargingPluggedEvent plugged) {
			cleaning.charging = new CleaningRecord.Charging(plugged.getChargerId(), plugged.getTime(), plugged.getSoc(), Double.NaN,
					Double.NaN, null, Double.NaN, Double.NaN, false);
			hubChanges.add(new HubChange(plugged.getTime(), plugged.getFacilityId(), 0, 0, +1, false));
		} else if (event instanceof HubChargingUnpluggedEvent unplugged) {
			CleaningRecord.Charging c = cleaning.charging;
			cleaning.charging = new CleaningRecord.Charging(unplugged.getChargerId(), c.plugTime(), c.socAtPlug(), unplugged.getTime(),
					unplugged.getSoc(), unplugged.getWorkerId(), unplugged.getWaitForUnplug(),
					EvUnits.J_to_kWh(unplugged.getEnergy()), unplugged.isEarly());
			hubChanges.add(new HubChange(unplugged.getTime(), unplugged.getFacilityId(), 0, 0, -1, false));
		} else if (event instanceof CleaningEndedEvent ended) {
			cleaning.cleaned = EnumSet.copyOf(ended.getCleaningTypes());
			records.add(close(vehicleId, cleaning, ended.getTime(), Outcome.cleaned));
			carryOver(vehicleId, cleaning);
		} else if (event instanceof CleaningAbandonedEvent abandoned) {
			cleaning.hub = abandoned.getFacilityId();
			cleaning.queueDelay = abandoned.getQueueDelay();
			hubChanges.add(new HubChange(abandoned.getTime(), cleaning.hub, -1, 0, cleaning.maintenanceVisit));
			records.add(close(vehicleId, cleaning, Double.NaN, Outcome.abandoned));
		}
	}

	private CleaningRecord close(Id<DvrpVehicle> vehicleId, Open cleaning, double endTime, Outcome outcome) {
		open.remove(vehicleId);
		return toRecord(vehicleId, cleaning, endTime, outcome);
	}

	/**
	 * Opens the next lifecycle with the due types the closed visit left for another pool: after a maintenance visit
	 * everything still due, after a cleaning visit a maintenance that became due on the way.
	 */
	private void carryOver(Id<DvrpVehicle> vehicleId, Open closed) {
		Open next = new Open();
		closed.dues.forEach((type, due) -> {
			if (!closed.cleaned.contains(type) && (closed.maintenanceVisit || type == CleaningType.maintenance)) {
				next.dues.put(type, due);
			}
		});
		if (!next.dues.isEmpty()) {
			open.put(vehicleId, next);
		}
	}

	private static CleaningRecord toRecord(Id<DvrpVehicle> vehicleId, Open cleaning, double endTime, Outcome outcome) {
		Due due = cleaning.triggerDue();
		return new CleaningRecord(vehicleId, cleaning.hub, cleaning.trigger(), cleaning.cleaned, due.passengerKm(),
				due.vehicleKm(), due.time(), cleaning.arrivalTime, cleaning.startTime, endTime,
				cleaning.workerId, cleaning.queueDelay, cleaning.workerTime, cleaning.charging, outcome);
	}

	/** Closed cleanings in the order they closed, followed by the ones still open. */
	public List<CleaningRecord> getRecords() {
		List<CleaningRecord> all = new ArrayList<>(records);
		open.forEach((vehicleId, cleaning) -> all.add(toRecord(vehicleId, cleaning, Double.NaN, Outcome.open)));
		return all;
	}

	public List<HubChange> getHubChanges() {
		return hubChanges;
	}

	@Override
	public void reset(int iteration) {
		open = new HashMap<>();
		records = new ArrayList<>();
		hubChanges = new ArrayList<>();
	}
}
