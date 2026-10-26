/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.remoteoperations.activation;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.drt.extension.operations.remoteoperations.RemoteGuidanceOperators;
import org.matsim.contrib.drt.extension.operations.remoteoperations.config.ActivationPolicy;
import org.matsim.contrib.drt.extension.operations.shifts.shift.DrtShift;
import org.matsim.contrib.drt.extension.operations.shifts.shift.DrtShiftSpecificationImpl;
import org.matsim.contrib.drt.extension.operations.shifts.shift.DrtShiftsSpecificationImpl;

import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The starting fleet goes on the road as the operators come on duty after a gap and is held for its duration only.
 *
 * @author lpfeil / MOIA
 */
public class StartupActivationTest {

	private static final double H = 3600;

	/** Two back-to-back operators 05:30-13:30-21:30 on day 0, one 05:30-13:30 on day 1, nothing in between. */
	private static RemoteGuidanceOperators operators() {
		DrtShiftsSpecificationImpl spec = new DrtShiftsSpecificationImpl();
		double[][] windows = {{5.5 * H, 13.5 * H}, {13.5 * H, 21.5 * H}, {29.5 * H, 37.5 * H}};
		for (int i = 0; i < windows.length; i++) {
			spec.addShiftSpecification(DrtShiftSpecificationImpl.newBuilder().id(Id.create("op" + i, DrtShift.class))
					.start(windows[i][0]).end(windows[i][1]).type("operator").build());
		}
		return RemoteGuidanceOperators.fromSpecification(spec, "operator", Integer.MAX_VALUE);
	}

	@Test
	void coverageAgeRunsFromTheStartOfTheStretch() {
		RemoteGuidanceOperators operators = operators();
		assertThat(operators.timeSinceCoverageStart(5 * H)).isNaN();
		assertThat(operators.timeSinceCoverageStart(6 * H)).isEqualTo(0.5 * H);
		// the second operator continues the morning stretch, so the stretch is still dated from 05:30
		assertThat(operators.timeSinceCoverageStart(14 * H)).isEqualTo(8.5 * H);
		assertThat(operators.timeSinceCoverageStart(25 * H)).isNaN();
		// the next morning after the closed night starts a new stretch
		assertThat(operators.timeSinceCoverageStart(30 * H)).isEqualTo(0.5 * H);
	}

	@Test
	void theStartingFleetIsHeldOnlyAfterTheOperatorsComeOnDuty() {
		RemoteGuidanceOperators operators = operators();
		StartupActivation startup = new StartupActivation(80, H, operators::timeSinceCoverageStart);
		GuidanceState state = new GuidanceState(0, Integer.MAX_VALUE, 200, 0, 0.);
		assertThat(startup.desiredActive(state, 5 * H)).as("before the operators").isZero();
		assertThat(startup.desiredActive(state, 5.5 * H)).isEqualTo(80);
		assertThat(startup.desiredActive(state, 6.4 * H)).isEqualTo(80);
		assertThat(startup.desiredActive(state, 6.6 * H)).as("after the startup hour").isZero();
		assertThat(startup.desiredActive(state, 13.6 * H)).as("an operator change is no new start").isZero();
		assertThat(startup.desiredActive(state, 29.7 * H)).as("next morning").isEqualTo(80);
	}

	@Test
	void theReconcilerEmitsTheStartingFleetAtOnce() {
		RemoteGuidanceOperators operators = operators();
		ActivationReconciler reconciler = ActivationReconciler.create(ActivationPolicy.buffered, 10, 2,
				OptionalDouble.empty(), new StartupActivation(80, H, operators::timeSinceCoverageStart));
		GuidanceState opening = new GuidanceState(0, Integer.MAX_VALUE, 200, 0, 0.);
		assertThat(reconciler.toEmit(opening, 5.5 * H)).isEqualTo(80);
		GuidanceState later = new GuidanceState(0, Integer.MAX_VALUE, 200, 0, 0.);
		assertThat(reconciler.toEmit(later, 8 * H)).as("only the floor and buffer later on").isEqualTo(10);
	}

	@Test
	void theStartingFleetIsKeptByTheDeactivationTargetToo() {
		// the run's operator windows: 05:30-13:30, 13:30-21:30, 17:00-01:00 on day 0, then 05:30 on day 1
		DrtShiftsSpecificationImpl spec = new DrtShiftsSpecificationImpl();
		double[][] windows = {{5.5 * H, 13.5 * H}, {13.5 * H, 21.5 * H}, {17 * H, 25 * H}, {29.5 * H, 37.5 * H}};
		for (int i = 0; i < windows.length; i++) {
			spec.addShiftSpecification(DrtShiftSpecificationImpl.newBuilder().id(Id.create("op" + i, DrtShift.class))
					.start(windows[i][0]).end(windows[i][1]).type("operator").build());
		}
		RemoteGuidanceOperators operators = RemoteGuidanceOperators.fromSpecification(spec, "operator", 1_000_000);
		ActivationReconciler reconciler = ActivationReconciler.create(ActivationPolicy.buffered, 10, 2,
				OptionalDouble.empty(), new StartupActivation(60, H, operators::timeSinceCoverageStart));
		// Tuesday 05:45: 60 vehicles active, all idle in service, nothing busy
		double t = 29.75 * H;
		GuidanceState state = new GuidanceState(60, operators.activationCapacityAt(t), 190, 60, 0, 0.);
		assertThat(operators.timeSinceCoverageStart(t)).isEqualTo(0.25 * H);
		assertThat(reconciler.desired(state, t)).isEqualTo(60);
	}
}
