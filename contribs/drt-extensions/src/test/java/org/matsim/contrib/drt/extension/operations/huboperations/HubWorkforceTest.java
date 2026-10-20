/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.huboperations;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.drt.extension.operations.operationFacilities.OperationFacility;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.matsim.contrib.drt.extension.operations.huboperations.HubWorkforce.Job.service;
import static org.matsim.contrib.drt.extension.operations.huboperations.HubWorkforce.Job.unplug;

/**
 * Queueing rules of {@link HubWorkforce} and parsing of the worker roster by {@link HubWorkersReader}.
 *
 * @author lpfeil / MOIA
 */
public class HubWorkforceTest {

	private static final Id<OperationFacility> HUB_A = Id.create("A", OperationFacility.class);
	private static final Id<OperationFacility> HUB_B = Id.create("B", OperationFacility.class);
	private static final double DURATION = 1200.;

	private static HubWorkers roster(HubWorkers.Worker... workers) {
		Map<Id<HubWorkers.HubWorker>, HubWorkers.Worker> map = new LinkedHashMap<>();
		for (HubWorkers.Worker worker : workers) {
			map.put(worker.id(), worker);
		}
		return new HubWorkers(map);
	}

	private static HubWorkers.Worker worker(String id, Id<OperationFacility> hub, double start, double end) {
		return new HubWorkers.Worker(Id.create(id, HubWorkers.HubWorker.class), hub, start, end);
	}

	private static Id<DvrpVehicle> vehicle(String id) {
		return Id.create(id, DvrpVehicle.class);
	}

	private static List<HubWorkforce.Assignment> assign(HubWorkforce workforce, double now) {
		return workforce.assign(now, v -> DURATION);
	}

	@Test
	void oneWorkerServesVehiclesFirstComeFirstServed() {
		HubWorkforce workforce = new HubWorkforce(roster(worker("w", HUB_A, 0, 86400)));
		workforce.enqueue(HUB_A, vehicle("v1"), service, 100);
		workforce.enqueue(HUB_A, vehicle("v2"), service, 110);

		List<HubWorkforce.Assignment> first = assign(workforce, 110);
		assertThat(first).singleElement().satisfies(a -> {
			assertThat(a.vehicleId()).isEqualTo(vehicle("v1"));
			assertThat(a.queueDelay()).isEqualTo(10);
			assertThat(a.endTime()).isEqualTo(110 + DURATION);
		});
		assertThat(workforce.waitingAt(HUB_A, service)).containsExactly(vehicle("v2"));

		// busy until the first cleaning ends, free again in the step it ends
		assertThat(assign(workforce, 500)).isEmpty();
		assertThat(assign(workforce, 110 + DURATION)).singleElement().satisfies(a -> {
			assertThat(a.vehicleId()).isEqualTo(vehicle("v2"));
			assertThat(a.queueDelay()).isEqualTo(DURATION);
		});
	}

	@Test
	void busyWorkersNeverExceedTheRoster() {
		HubWorkforce workforce = new HubWorkforce(roster(worker("w1", HUB_A, 0, 86400),
				worker("w2", HUB_A, 0, 86400)));
		for (int i = 0; i < 5; i++) {
			workforce.enqueue(HUB_A, vehicle("v" + i), service, 0);
		}
		assertThat(assign(workforce, 0)).hasSize(2);
		assertThat(workforce.busyCount(0)).isEqualTo(2);
		assertThat(workforce.queueLength()).isEqualTo(3);
	}

	@Test
	void unplugGoesBeforeServiceAndTakesNoWorkerTime() {
		HubWorkforce workforce = new HubWorkforce(roster(worker("w", HUB_A, 0, 86400)));
		workforce.enqueue(HUB_A, vehicle("toClean"), service, 0);
		workforce.enqueue(HUB_A, vehicle("charged1"), unplug, 10);
		workforce.enqueue(HUB_A, vehicle("charged2"), unplug, 20);

		// one worker unplugs both charged vehicles and then starts the cleaning, all in the same step
		List<HubWorkforce.Assignment> started = assign(workforce, 30);
		assertThat(started).extracting(HubWorkforce.Assignment::vehicleId)
				.containsExactly(vehicle("charged1"), vehicle("charged2"), vehicle("toClean"));
		assertThat(started.get(0).endTime()).isEqualTo(30);
		assertThat(workforce.queueLength()).isZero();
	}

	@Test
	void zeroTimeServiceFreesTheWorkerAtOnce() {
		// a charging-only visit: the worker plugs in and is free again in the same step
		HubWorkforce workforce = new HubWorkforce(roster(worker("w", HUB_A, 0, 86400)));
		workforce.enqueue(HUB_A, vehicle("v1"), service, 0);
		workforce.enqueue(HUB_A, vehicle("v2"), service, 0);
		assertThat(workforce.assign(0, v -> 0.)).hasSize(2);
	}

	@Test
	void workersOnlyServeTheirOwnHub() {
		HubWorkforce workforce = new HubWorkforce(roster(worker("w", HUB_A, 0, 86400)));
		workforce.enqueue(HUB_B, vehicle("v"), service, 0);
		assertThat(assign(workforce, 0)).isEmpty();
		assertThat(workforce.isWaiting(vehicle("v"))).isTrue();
	}

	@Test
	void workerTakesNoJobOutsideTheWindowButFinishesAStartedOne() {
		HubWorkforce workforce = new HubWorkforce(roster(worker("w", HUB_A, 1000, 2000)));
		workforce.enqueue(HUB_A, vehicle("early"), service, 0);
		assertThat(assign(workforce, 999)).isEmpty();

		// taken just before the window closes, the cleaning runs past the planned end
		assertThat(assign(workforce, 1900)).singleElement()
				.satisfies(a -> assertThat(a.endTime()).isEqualTo(1900 + DURATION));

		workforce.enqueue(HUB_A, vehicle("after"), service, 1950);
		assertThat(assign(workforce, 2500)).as("still busy").isEmpty();
		assertThat(assign(workforce, 1900 + DURATION)).as("off duty after the window").isEmpty();
	}

	@Test
	void queueIsDroppedOnceNoWorkerComesOnDutyAgain() {
		HubWorkforce workforce = new HubWorkforce(roster(worker("w", HUB_A, 1000, 2000)));
		workforce.enqueue(HUB_A, vehicle("v"), service, 500);
		workforce.enqueue(HUB_B, vehicle("noWorkers"), unplug, 500);
		assertThat(workforce.dropUnservable(500)).extracting(HubWorkforce.Waiting::vehicleId)
				.containsExactly(vehicle("noWorkers"));
		assertThat(workforce.dropUnservable(1999)).isEmpty();
		assertThat(workforce.dropUnservable(2000)).extracting(HubWorkforce.Waiting::vehicleId)
				.containsExactly(vehicle("v"));
		assertThat(workforce.queueLength()).isZero();
	}

	@Test
	void removeTakesAVehicleOutOfAllQueues() {
		HubWorkforce workforce = new HubWorkforce(roster(worker("w", HUB_A, 0, 86400)));
		workforce.enqueue(HUB_A, vehicle("v"), service, 0);
		workforce.enqueue(HUB_A, vehicle("v"), unplug, 0);
		workforce.remove(vehicle("v"));
		assertThat(workforce.isWaiting(vehicle("v"))).isFalse();
	}

	@Test
	void readerParsesTheRoster() {
		String xml = """
				<?xml version="1.0" encoding="UTF-8"?>
				<hubWorkers>
					<worker id="w0" hub="A" start="14400" end="43200"/>
					<worker id="w1" hub="A" start="43200" end="79200"/>
					<worker id="w2" hub="B" start="0" end="86400"/>
				</hubWorkers>
				""";
		HubWorkersReader reader = new HubWorkersReader();
		reader.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
		HubWorkers workers = reader.getHubWorkers();
		assertThat(workers.getWorkers()).hasSize(3);
		assertThat(workers.atHub(HUB_A)).hasSize(2);
		assertThat(workers.atHub(HUB_B)).hasSize(1);
		assertThat(workers.plannedHours()).isEqualTo(8 + 10 + 24);
	}

	@Test
	void workerMustEndAfterItStarts() {
		assertThatThrownBy(() -> worker("w", HUB_A, 100, 100)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void poolsNeverStandInForEachOther() {
		HubWorkers.Worker technician = new HubWorkers.Worker(Id.create("m", HubWorkers.HubWorker.class), HUB_A, 0,
				86400, HubWorkers.Pool.maintenance);
		HubWorkforce workforce = new HubWorkforce(roster(worker("w", HUB_A, 0, 86400), technician));
		workforce.enqueue(HUB_A, vehicle("clean1"), service, 0);
		workforce.enqueue(HUB_A, vehicle("clean2"), service, 0);
		workforce.enqueue(HUB_A, vehicle("maint1"), HubWorkforce.Job.maintenance, 0);
		workforce.enqueue(HUB_A, vehicle("maint2"), HubWorkforce.Job.maintenance, 0);

		List<HubWorkforce.Assignment> started = assign(workforce, 0);
		assertThat(started).extracting(HubWorkforce.Assignment::vehicleId)
				.containsExactly(vehicle("clean1"), vehicle("maint1"));
		assertThat(started).filteredOn(a -> a.job() == HubWorkforce.Job.maintenance)
				.singleElement().satisfies(a -> assertThat(a.workerId()).isEqualTo(technician.id()));
		assertThat(workforce.waitingAt(HUB_A, service)).containsExactly(vehicle("clean2"));
		assertThat(workforce.waitingAt(HUB_A, HubWorkforce.Job.maintenance)).containsExactly(vehicle("maint2"));
	}

	@Test
	void maintenanceWithoutTechniciansIsUnservable() {
		HubWorkforce workforce = new HubWorkforce(roster(worker("w", HUB_A, 0, 86400)));
		workforce.enqueue(HUB_A, vehicle("clean"), service, 0);
		workforce.enqueue(HUB_A, vehicle("maint"), HubWorkforce.Job.maintenance, 0);
		assertThat(workforce.dropUnservable(0)).extracting(HubWorkforce.Waiting::vehicleId)
				.containsExactly(vehicle("maint"));
		assertThat(workforce.waitingAt(HUB_A, service)).containsExactly(vehicle("clean"));
	}
}
