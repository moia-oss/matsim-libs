/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.huboperations;

import org.matsim.api.core.v01.Id;
import org.matsim.contrib.drt.extension.operations.operationFacilities.OperationFacility;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToDoubleFunction;

/**
 * Runtime state of the hub service workers. Each hub runs its own queue of jobs, and a job needs one free worker of
 * that hub who is on duty. There are two kinds of job:
 * <ul>
 *     <li>{@link Job#service}: the first visit of a worker to a vehicle. The worker plugs it in if it charges and then
 *         cleans its types one after the other. It occupies the worker for the cleaning time; plugging in takes no
 *         time.</li>
 *     <li>{@link Job#unplug}: unplugging a charged vehicle. It takes no time but needs a free worker. Unplug jobs go
 *         before service jobs, because a charged vehicle blocks a plug that the next vehicle may be waiting for.</li>
 *     <li>{@link Job#maintenance}: maintenance and sensor calibration in the workshop. Only a worker of the maintenance
 *         pool L_M takes it, and such a worker takes nothing else; the two pools never stand in for each other.</li>
 * </ul>
 * Within a kind the queue is first come, first served. Different vehicles are served in parallel by different workers.
 * A worker is busy until the end of the job it took, so a worker who finishes a job at time t can take the next one in
 * the same step, and one worker can do several zero-time jobs in one step.
 * <p>
 * The class holds state only and knows nothing about schedules or chargers, so its queueing rules can be tested on
 * their own. The {@link CleaningDispatcher} calls it once per sim step and turns its decisions into schedule changes.
 * <p>
 * A worker never leaves a job unfinished. One who is cleaning at the end of their window finishes the vehicle and then
 * goes off duty, so the planned window bounds when a worker may <em>start</em> a job, not when it ends.
 *
 * @author lpfeil / MOIA
 */
public final class HubWorkforce {

	/** Kind of job a worker does on a vehicle. */
	public enum Job {
		service, unplug, maintenance;

		/** The pool whose workers do this job. */
		public HubWorkers.Pool pool() {
			return this == maintenance ? HubWorkers.Pool.maintenance : HubWorkers.Pool.cleaning;
		}
	}

	/** A started job: which worker serves which vehicle, and when it was queued, started and ends. */
	public record Assignment(Id<HubWorkers.HubWorker> workerId, Id<DvrpVehicle> vehicleId, Id<OperationFacility> hub,
							 Job job, double queuedAt, double startTime, double endTime) {

		public double queueDelay() {
			return startTime - queuedAt;
		}
	}

	/** A job waiting for a worker. */
	public record Waiting(Id<DvrpVehicle> vehicleId, Id<OperationFacility> hub, Job job, double queuedAt) {
	}

	private record Queues(Deque<Waiting> unplug, Deque<Waiting> service, Deque<Waiting> maintenance) {
		private Queues() {
			this(new ArrayDeque<>(), new ArrayDeque<>(), new ArrayDeque<>());
		}

		private Deque<Waiting> of(Job job) {
			return switch (job) {
				case unplug -> unplug;
				case service -> service;
				case maintenance -> maintenance;
			};
		}

		private int size() {
			return unplug.size() + service.size() + maintenance.size();
		}
	}

	private final HubWorkers roster;
	private final Map<Id<OperationFacility>, Queues> queues = new LinkedHashMap<>();
	private final Map<Id<HubWorkers.HubWorker>, Double> busyUntil = new LinkedHashMap<>();

	public HubWorkforce(HubWorkers roster) {
		this.roster = roster;
	}

	/** Puts a job at the back of its queue at {@code hub}. */
	public void enqueue(Id<OperationFacility> hub, Id<DvrpVehicle> vehicleId, Job job, double now) {
		queues.computeIfAbsent(hub, h -> new Queues()).of(job).addLast(new Waiting(vehicleId, hub, job, now));
	}

	/**
	 * Gives waiting jobs, unplug jobs first and then service jobs, each front of the queue first, to free cleaning
	 * workers of their hub who are on duty at {@code now}, and maintenance jobs to free maintenance workers. A queue
	 * whose hub has no such worker stays as it is.
	 *
	 * @param serviceDuration worker time of a service or maintenance job, asked once when the job is assigned
	 * @return the jobs started this step
	 */
	public List<Assignment> assign(double now, ToDoubleFunction<Id<DvrpVehicle>> serviceDuration) {
		List<Assignment> started = new ArrayList<>();
		for (var entry : queues.entrySet()) {
			Id<OperationFacility> hub = entry.getKey();
			for (Job job : List.of(Job.unplug, Job.service, Job.maintenance)) {
				Deque<Waiting> queue = entry.getValue().of(job);
				while (!queue.isEmpty()) {
					Optional<HubWorkers.Worker> free = freeWorker(hub, job.pool(), now);
					if (free.isEmpty()) {
						break;
					}
					Waiting next = queue.pollFirst();
					double duration = job == Job.unplug ? 0. : serviceDuration.applyAsDouble(next.vehicleId());
					busyUntil.put(free.get().id(), now + duration);
					started.add(new Assignment(free.get().id(), next.vehicleId(), hub, job, next.queuedAt(), now,
							now + duration));
				}
			}
		}
		return started;
	}

	private Optional<HubWorkers.Worker> freeWorker(Id<OperationFacility> hub, HubWorkers.Pool pool, double now) {
		for (HubWorkers.Worker worker : roster.atHub(hub)) {
			if (worker.pool() == pool && worker.onDuty(now) && busyUntil.getOrDefault(worker.id(), Double.NEGATIVE_INFINITY) <= now) {
				return Optional.of(worker);
			}
		}
		return Optional.empty();
	}

	/**
	 * Removes and returns the jobs waiting at hubs where no worker of their pool will be on duty again after
	 * {@code now}. Without this a backlog left at the end of the last worker's window would wait for ever.
	 */
	public List<Waiting> dropUnservable(double now) {
		List<Waiting> dropped = new ArrayList<>();
		for (var entry : queues.entrySet()) {
			for (Job job : Job.values()) {
				if (!hasFutureCapacity(entry.getKey(), job.pool(), now)) {
					dropped.addAll(entry.getValue().of(job));
					entry.getValue().of(job).clear();
				}
			}
		}
		return dropped;
	}

	/** Removes every waiting job of {@code vehicleId}, e.g. when its visit is aborted. */
	public void remove(Id<DvrpVehicle> vehicleId) {
		for (Queues q : queues.values()) {
			for (Job job : Job.values()) {
				q.of(job).removeIf(w -> w.vehicleId().equals(vehicleId));
			}
		}
	}

	/** Whether some worker of {@code pool} at {@code hub} is on duty now or later. */
	public boolean hasFutureCapacity(Id<OperationFacility> hub, HubWorkers.Pool pool, double now) {
		return roster.atHub(hub).stream().anyMatch(worker -> worker.pool() == pool && worker.end() > now);
	}

	/** Vehicles waiting at {@code hub} for {@code job}, front of the queue first. */
	public List<Id<DvrpVehicle>> waitingAt(Id<OperationFacility> hub, Job job) {
		Queues q = queues.get(hub);
		return q == null ? List.of() : q.of(job).stream().map(Waiting::vehicleId).toList();
	}

	public boolean isWaiting(Id<DvrpVehicle> vehicleId) {
		return queues.values().stream().anyMatch(q -> Arrays.stream(Job.values())
				.anyMatch(job -> q.of(job).stream().anyMatch(w -> w.vehicleId().equals(vehicleId))));
	}

	/** Workers busy with a job at {@code now}. */
	public int busyCount(double now) {
		return (int) busyUntil.values().stream().filter(end -> end > now).count();
	}

	public int queueLength() {
		return queues.values().stream().mapToInt(Queues::size).sum();
	}

	public HubWorkers getRoster() {
		return roster;
	}

	public void clear() {
		queues.clear();
		busyUntil.clear();
	}
}
