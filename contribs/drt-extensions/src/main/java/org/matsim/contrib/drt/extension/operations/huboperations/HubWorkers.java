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
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.drt.extension.operations.huboperations.config.HubOperationsParams;
import org.matsim.contrib.drt.extension.operations.operationFacilities.OperationFacilities;
import org.matsim.contrib.drt.extension.operations.operationFacilities.OperationFacility;
import org.matsim.contrib.drt.extension.operations.operationFacilities.OperationFacilityType;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The roster of hub workers. It holds two pools that never stand in for each other: {@link Pool#cleaning}, the hub crew
 * {@code L_C} that cleans, plugs in and unplugs, and {@link Pool#maintenance}, the technicians {@code L_M} who maintain
 * and calibrate vehicles in the workshop. Each worker is stationed at one hub and is on duty in one time window. The roster is a plain specification, read once from the worker input file;
 * runtime state, which vehicle a worker is cleaning, lives in {@link HubWorkforce}.
 * <p>
 * Workers are counted per hub, so a vehicle can only be cleaned by a worker of the hub it went to.
 *
 * @author lpfeil / MOIA
 */
public final class HubWorkers {

	/** The labour pool of a worker. */
	public enum Pool {
		/** the hub crew L_C: interior and exterior cleaning, plugging in and out */
		cleaning,
		/** the technicians L_M: maintenance and sensor calibration in the workshop */
		maintenance
	}

	/**
	 * One hub worker.
	 *
	 * @param start start of the working window, seconds of the day
	 * @param end   planned end of the working window; a worker who is busy at that time finishes the vehicle first
	 */
	public record Worker(Id<HubWorker> id, Id<OperationFacility> hub, double start, double end, Pool pool) {

		public Worker {
			if (!(end > start)) {
				throw new IllegalArgumentException("worker " + id + " ends at " + end + ", not after its start " + start);
			}
			Objects.requireNonNull(pool, "pool");
		}

		/** A worker of the cleaning pool. */
		public Worker(Id<HubWorker> id, Id<OperationFacility> hub, double start, double end) {
			this(id, hub, start, end, Pool.cleaning);
		}

		/** Whether the worker may take a new vehicle at {@code now}, i.e. is within the planned window. */
		public boolean onDuty(double now) {
			return now >= start && now < end;
		}

		/** Planned working time in hours, the labour input of this worker. */
		public double plannedHours() {
			return (end - start) / 3600.;
		}
	}

	/** Marker type for worker ids. */
	public interface HubWorker {
	}

	private final Map<Id<HubWorker>, Worker> workers;
	private final Map<Id<OperationFacility>, List<Worker>> byHub;

	public HubWorkers(Map<Id<HubWorker>, Worker> workers) {
		this.workers = Collections.unmodifiableMap(new LinkedHashMap<>(workers));
		Map<Id<OperationFacility>, List<Worker>> grouped = new LinkedHashMap<>();
		for (Worker worker : this.workers.values()) {
			grouped.computeIfAbsent(worker.hub(), h -> new ArrayList<>()).add(worker);
		}
		grouped.replaceAll((hub, list) -> Collections.unmodifiableList(list));
		this.byHub = Collections.unmodifiableMap(grouped);
	}

	/**
	 * Reads the roster named in {@code params}, resolved against the config {@code context}.
	 *
	 * @return the roster, or {@code null} when no worker file is configured, which means unlimited hub capacity
	 */
	@Nullable
	public static HubWorkers read(HubOperationsParams params, URL context) {
		if (params.getHubWorkerInputFile() == null) {
			return null;
		}
		HubWorkersReader reader = new HubWorkersReader();
		reader.parse(params.getHubWorkerInputUrl(context));
		return reader.getHubWorkers();
	}

	/**
	 * Reads the roster like {@link #read} and checks that every worker is stationed at a hub operation facility,
	 * otherwise the vehicles sent there could never be served by them.
	 */
	@Nullable
	public static HubWorkers readValidated(HubOperationsParams params, URL context, OperationFacilities facilities) {
		HubWorkers workers = read(params, context);
		if (workers == null) {
			return null;
		}
		for (Worker worker : workers.getWorkers().values()) {
			OperationFacility hub = facilities.getFacilities().get(worker.hub());
			if (hub == null || hub.getType() != OperationFacilityType.hub) {
				throw new IllegalArgumentException("hub worker " + worker.id() + " is stationed at " + worker.hub()
						+ ", which is not a hub operation facility");
			}
		}
		return workers;
	}

	public Map<Id<HubWorker>, Worker> getWorkers() {
		return workers;
	}

	/** Workers stationed at {@code hub}, of both pools; empty if the hub has none. */
	public List<Worker> atHub(Id<OperationFacility> hub) {
		return byHub.getOrDefault(hub, List.of());
	}

	/** Workers of {@code pool} stationed at {@code hub}. */
	public List<Worker> atHub(Id<OperationFacility> hub, Pool pool) {
		return atHub(hub).stream().filter(w -> w.pool() == pool).toList();
	}

	/** Number of workers of {@code pool} over the whole roster. */
	public long count(Pool pool) {
		return workers.values().stream().filter(w -> w.pool() == pool).count();
	}

	/** Planned worker hours over the whole roster. */
	public double plannedHours() {
		return workers.values().stream().mapToDouble(Worker::plannedHours).sum();
	}

	/** Planned worker hours of {@code pool}. */
	public double plannedHours(Pool pool) {
		return workers.values().stream().filter(w -> w.pool() == pool).mapToDouble(Worker::plannedHours).sum();
	}
}
