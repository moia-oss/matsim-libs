/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.huboperations.charging;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.drt.extension.operations.huboperations.config.HubChargingSlackParams;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.fleet.Fleet;
import org.matsim.contrib.dvrp.schedule.Schedule;
import org.matsim.contrib.dvrp.schedule.ScheduleInquiry;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Hysteresis of the fleet slack state, ported from the Lucerne {@code FleetSlackMonitorTest}: a switch needs both a
 * crossed threshold and the minimum dwell time, nothing switches inside the dead band, a repeated update in one step is
 * ignored and the smoothing dampens a single sample. Plus the shift adaptation: only vehicles in service count.
 * Fleet, vehicles and the schedule inquiry are dynamic proxies, so no QSim is needed.
 *
 * @author nkuehnel / MOIA (original), lpfeil / MOIA
 */
public class FleetSlackMonitorTest {

	private static final int FLEET_SIZE = 10;

	private final Set<Id<DvrpVehicle>> idle = new HashSet<>();

	private static HubChargingSlackParams params(double smoothingSeconds) {
		HubChargingSlackParams p = new HubChargingSlackParams();
		p.setSlackHigh(0.30);
		p.setSlackLow(0.15);
		p.setMinDwellSeconds(900);
		p.setSmoothingSeconds(smoothingSeconds);
		return p;
	}

	private DvrpVehicle vehicle(Id<DvrpVehicle> id) {
		return (DvrpVehicle) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DvrpVehicle.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "getId" -> id;
					case "getServiceBeginTime" -> 0.;
					case "getServiceEndTime" -> 108000.;
					case "getSchedule" -> notStartedSchedule();
					case "hashCode" -> id.hashCode();
					case "equals" -> proxy == args[0];
					case "toString" -> "vehicle " + id;
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}

	/** A schedule that has not started, so a busy vehicle is not mistaken for a relocating one. */
	private Schedule notStartedSchedule() {
		return (Schedule) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Schedule.class},
				(proxy, method, args) -> {
					if (method.getName().equals("getStatus")) {
						return Schedule.ScheduleStatus.PLANNED;
					}
					throw new UnsupportedOperationException(method.getName());
				});
	}

	private FleetSlackMonitor monitor(HubChargingSlackParams params, Predicate<DvrpVehicle> inService) {
		ImmutableMap.Builder<Id<DvrpVehicle>, DvrpVehicle> vehicles = ImmutableMap.builder();
		for (int i = 0; i < FLEET_SIZE; i++) {
			Id<DvrpVehicle> id = Id.create("v" + i, DvrpVehicle.class);
			vehicles.put(id, vehicle(id));
		}
		ImmutableMap<Id<DvrpVehicle>, DvrpVehicle> map = vehicles.build();
		Fleet fleet = (Fleet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Fleet.class},
				(proxy, method, args) -> {
					if (method.getName().equals("getVehicles")) {
						return map;
					}
					throw new UnsupportedOperationException(method.getName());
				});
		ScheduleInquiry inquiry = (ScheduleInquiry) Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[]{ScheduleInquiry.class}, (proxy, method, args) -> {
					if (method.getName().equals("isIdle")) {
						return idle.contains(((DvrpVehicle) args[0]).getId());
					}
					throw new UnsupportedOperationException(method.getName());
				});
		return new FleetSlackMonitor(fleet, inquiry, params, inService);
	}

	private FleetSlackMonitor monitor(HubChargingSlackParams params) {
		return monitor(params, v -> true);
	}

	private void setIdleCount(int n) {
		idle.clear();
		for (int i = 0; i < n; i++) {
			idle.add(Id.create("v" + i, DvrpVehicle.class));
		}
	}

	@Test
	void startsRelaxedWithAFreeFleet() {
		FleetSlackMonitor monitor = monitor(params(1));
		setIdleCount(FLEET_SIZE);
		monitor.update(0);
		assertThat(monitor.isRelaxed()).isTrue();
		assertThat(monitor.smoothedFreeRatio()).isEqualTo(1.0);
		assertThat(monitor.vehiclesInService()).isEqualTo(FLEET_SIZE);
	}

	@Test
	void switchesToTightOnlyAfterTheDwellTime() {
		FleetSlackMonitor monitor = monitor(params(1));
		setIdleCount(FLEET_SIZE);
		monitor.update(0);
		setIdleCount(1); // 10 % free, below slackLow
		monitor.update(60);
		assertThat(monitor.isRelaxed()).as("dwell time not yet satisfied").isTrue();
		monitor.update(900);
		assertThat(monitor.getState()).isEqualTo(FleetSlackMonitor.SlackState.TIGHT);
		assertThat(monitor.stateChanges()).isEqualTo(1);
	}

	@Test
	void staysTightInsideTheDeadBandAndRelaxesAboveSlackHigh() {
		FleetSlackMonitor monitor = monitor(params(1));
		setIdleCount(FLEET_SIZE);
		monitor.update(0);
		setIdleCount(1);
		monitor.update(900);
		setIdleCount(2); // 20 %: above slackLow, below slackHigh
		monitor.update(1800);
		monitor.update(2700);
		assertThat(monitor.getState()).as("inside the dead band nothing switches")
				.isEqualTo(FleetSlackMonitor.SlackState.TIGHT);
		setIdleCount(4); // 40 %, above slackHigh
		monitor.update(3600);
		assertThat(monitor.isRelaxed()).isTrue();
		assertThat(monitor.stateChanges()).isEqualTo(2);
	}

	@Test
	void repeatedUpdateInOneStepIsIgnored() {
		FleetSlackMonitor monitor = monitor(params(300));
		setIdleCount(FLEET_SIZE);
		monitor.update(0);
		setIdleCount(0);
		monitor.update(10);
		double once = monitor.smoothedFreeRatio();
		monitor.update(10);
		assertThat(monitor.smoothedFreeRatio()).isEqualTo(once);
	}

	@Test
	void smoothingDampensASingleSample() {
		FleetSlackMonitor monitor = monitor(params(300));
		setIdleCount(FLEET_SIZE);
		monitor.update(0);
		setIdleCount(0);
		monitor.update(1);
		assertThat(monitor.smoothedFreeRatio()).isGreaterThan(0.99);
	}

	@Test
	void onlyVehiclesInServiceCount() {
		// half the fleet parked out of shift: the free share is over the five in service only
		Set<String> parked = Set.of("v5", "v6", "v7", "v8", "v9");
		FleetSlackMonitor monitor = monitor(params(1), v -> !parked.contains(v.getId().toString()));
		setIdleCount(1);
		monitor.update(0);
		assertThat(monitor.vehiclesInService()).isEqualTo(5);
		assertThat(monitor.smoothedFreeRatio()).isCloseTo(0.2, within(1e-9));
	}

	@Test
	void resetStartsRelaxedAgain() {
		FleetSlackMonitor monitor = monitor(params(1));
		setIdleCount(FLEET_SIZE);
		monitor.update(0);
		setIdleCount(0);
		monitor.update(900);
		assertThat(monitor.isRelaxed()).isFalse();
		monitor.reset();
		assertThat(monitor.isRelaxed()).isTrue();
		assertThat(monitor.stateChanges()).isZero();
	}
}
