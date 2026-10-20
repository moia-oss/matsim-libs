/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.huboperations.charging;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.drt.extension.operations.huboperations.config.HubChargingSlackParams;
import org.matsim.contrib.drt.extension.operations.huboperations.config.HubOperationsParams;
import com.google.common.collect.ImmutableMap;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.fleet.Fleet;
import org.matsim.contrib.dvrp.schedule.Schedule;
import org.matsim.contrib.dvrp.schedule.ScheduleInquiry;

import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two charging policies and the invariants between the charging parameters, after the Lucerne
 * {@code FleetSlackParamsTest} and the MoiaConfig SOC limit checks.
 *
 * @author lpfeil / MOIA
 */
public class HubChargingPolicyTest {

	@Test
	void thresholdPolicyIsDueAtTheThresholdAndBundlesFromTheShare() {
		// due at 0.3, i.e. 70 % used; bundled from 0.8 * 70 = 56 % used, i.e. SOC 0.44
		HubChargingPolicy policy = HubChargingPolicy.threshold(0.3, 0.8);
		assertThat(policy.isDue(0.31)).isFalse();
		assertThat(policy.isDue(0.30)).isTrue();
		assertThat(policy.isBundled(0.45)).isFalse();
		assertThat(policy.isBundled(0.44)).isTrue();
		assertThat(policy.allowsEarlyRelease()).isFalse();
	}

	@Test
	void zeroThresholdSwitchesChargingOff() {
		HubChargingPolicy policy = HubChargingPolicy.threshold(0., 0.);
		assertThat(policy.isDue(0.)).isFalse();
		assertThat(policy.isBundled(0.)).isFalse();
	}

	@Test
	void fleetSlackPolicyChargesOpportunisticallyOnlyWhileRelaxed() {
		HubChargingSlackParams params = new HubChargingSlackParams();
		params.setMinDwellSeconds(0);
		params.setSmoothingSeconds(1);
		boolean[] busy = {false};
		FleetSlackMonitor monitor = new FleetSlackMonitor(oneVehicleFleet(), inquiry(busy), params, v -> true);
		HubChargingPolicy policy = HubChargingPolicy.fleetSlack(params, monitor);

		policy.update(0);
		assertThat(monitor.isRelaxed()).isTrue();
		assertThat(policy.isDue(0.55)).as("opportunistic while relaxed").isTrue();
		assertThat(policy.isDue(0.65)).as("above socOpportunity never").isFalse();

		busy[0] = true; // the only vehicle is busy: free share 0, the fleet becomes tight
		policy.update(60);
		assertThat(monitor.isRelaxed()).isFalse();
		assertThat(policy.isDue(0.55)).as("not while tight").isFalse();
		assertThat(policy.isDue(0.45)).as("below socCritical always").isTrue();
		assertThat(policy.isBundled(0.55)).as("bundled below socOpportunity whatever the fleet").isTrue();
		assertThat(policy.isBundled(0.65)).isFalse();
		assertThat(policy.allowsEarlyRelease()).isTrue();
	}

	@Test
	void slackInvariantsAreChecked() {
		HubChargingSlackParams params = new HubChargingSlackParams();
		params.validate(0.9);
		params.setSocOpportunity(0.88);
		assertThatThrownBy(() -> params.validate(0.9)).hasMessageContaining("socOpportunity");
		params.setSocOpportunity(0.6);
		params.setSocCritical(0.65);
		assertThatThrownBy(() -> params.validate(0.9)).hasMessageContaining("socCritical");
		params.setSocCritical(0.5);
		params.setSocRelease(0.55);
		assertThatThrownBy(() -> params.validate(0.9)).hasMessageContaining("socRelease");
		params.setSocRelease(0.7);
		params.setSlackLow(0.3);
		assertThatThrownBy(() -> params.validate(0.9)).hasMessageContaining("slackLow");
	}

	@Test
	void hubParamsRejectATriggerAtTheTargetAndAGateAtTheTrigger() {
		HubOperationsParams hub = new HubOperationsParams();
		hub.setChargeSocThreshold(0.9);
		hub.setChargingTargetSoc(0.9);
		assertThatThrownBy(() -> hub.validate()).hasMessageContaining("chargingTargetSoc");
		hub.setChargeSocThreshold(0.3);
		hub.setMinSocForDispatch(0.3);
		assertThatThrownBy(() -> hub.validate()).hasMessageContaining("minSocForDispatch");
		hub.setMinSocForDispatch(0.1);
		hub.validate();
		assertThat(hub.getChargingTrigger()).isEqualTo(0.3);
		hub.addParameterSet(new HubChargingSlackParams());
		assertThat(hub.getChargingTrigger()).as("socCritical with the slack policy").isEqualTo(0.5);
	}

	private static ScheduleInquiry inquiry(boolean[] busy) {
		return (ScheduleInquiry) Proxy.newProxyInstance(HubChargingPolicyTest.class.getClassLoader(),
				new Class<?>[]{ScheduleInquiry.class}, (proxy, method, args) -> {
					if (method.getName().equals("isIdle")) {
						return !busy[0];
					}
					throw new UnsupportedOperationException(method.getName());
				});
	}

	/** A fleet of one vehicle in service all day, whose idle state the schedule inquiry stub decides. */
	private static Fleet oneVehicleFleet() {
		Id<DvrpVehicle> id = Id.create("v", DvrpVehicle.class);
		Schedule schedule = (Schedule) Proxy.newProxyInstance(HubChargingPolicyTest.class.getClassLoader(),
				new Class<?>[]{Schedule.class}, (proxy, method, args) -> {
					if (method.getName().equals("getStatus")) {
						return Schedule.ScheduleStatus.PLANNED;
					}
					throw new UnsupportedOperationException(method.getName());
				});
		DvrpVehicle vehicle = (DvrpVehicle) Proxy.newProxyInstance(HubChargingPolicyTest.class.getClassLoader(),
				new Class<?>[]{DvrpVehicle.class}, (proxy, method, args) -> switch (method.getName()) {
					case "getId" -> id;
					case "getServiceBeginTime" -> 0.;
					case "getServiceEndTime" -> 108000.;
					case "getSchedule" -> schedule;
					case "hashCode" -> id.hashCode();
					case "equals" -> proxy == args[0];
					default -> throw new UnsupportedOperationException(method.getName());
				});
		return (Fleet) Proxy.newProxyInstance(HubChargingPolicyTest.class.getClassLoader(), new Class<?>[]{Fleet.class},
				(proxy, method, args) -> {
					if (method.getName().equals("getVehicles")) {
						return ImmutableMap.of(id, vehicle);
					}
					throw new UnsupportedOperationException(method.getName());
				});
	}
}
