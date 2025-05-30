/* *********************************************************************** *
 * project: org.matsim.*
 * ChangeLegModeTest.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2008 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package org.matsim.core.replanning.modules;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ChangeModeConfigGroup;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.PopulationUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * @author mrieser
 */
public class ChangeLegModeTest {

	@Test
	void testDefaultModes() {
		Config config = ConfigUtils.createConfig();
		config.global().setNumberOfThreads(0);

		final ChangeLegMode module = new ChangeLegMode(config.global(), config.changeMode());
		final String[] modes = new String[] {TransportMode.car, TransportMode.pt};
		runTest(module, modes);
	}

	@Test
	void testWithConfig() {
		Config config = ConfigUtils.createConfig();
		config.global().setNumberOfThreads(0);
		config.changeMode().addParam(ChangeModeConfigGroup.CONFIG_PARAM_MODES, " car,pt ,bike,walk ");

		final ChangeLegMode module = new ChangeLegMode(config.global(), config.changeMode());
		final String[] modes = new String[] {TransportMode.car, TransportMode.pt, TransportMode.bike, TransportMode.walk};
		runTest(module, modes);
	}

	@Test
	void test_behavior_allowSwitchFromListedModesOnly() {
		Config config = ConfigUtils.createConfig();
		config.global().setNumberOfThreads(0);
		config.changeMode().addParam(ChangeModeConfigGroup.CONFIG_PARAM_MODES, "pt,bike,walk"); // do not include car
		config.changeMode().addParam(ChangeModeConfigGroup.MODE_SWITCH_BEHAVIOR, "fromSpecifiedModesToSpecifiedModes");

		final ChangeLegMode module = new ChangeLegMode(config.global(), config.changeMode());
		final String[] modes = new String[] {TransportMode.car};
		runTest(module, modes);
	}

	@Test
	void test_behavior_fromAllModesToSpecifiedModes() {
		Config config = ConfigUtils.createConfig();
		config.global().setNumberOfThreads(0);
		config.changeMode().addParam(ChangeModeConfigGroup.CONFIG_PARAM_MODES, "pt,bike,walk"); // do not include car
		config.changeMode().addParam(ChangeModeConfigGroup.MODE_SWITCH_BEHAVIOR, "fromAllModesToSpecifiedModes");

		final ChangeLegMode module = new ChangeLegMode(config.global(), config.changeMode());
		final String[] modes = new String[] {TransportMode.pt, TransportMode.bike, TransportMode.walk};
		runTest(module, modes);
	}

	@Test
	void testWithConstructor() {
		final ChangeLegMode module = new ChangeLegMode(0, new String[] {"car", "pt", "bike", "walk"}, true, false);
		final String[] modes = new String[] {TransportMode.car, TransportMode.pt, TransportMode.bike, TransportMode.walk};
		runTest(module, modes);
	}

	@Test
	void testWithConfig_withoutIgnoreCarAvailability() {
		Config config = ConfigUtils.createConfig();
		config.global().setNumberOfThreads(0);
		config.changeMode().addParam(ChangeModeConfigGroup.CONFIG_PARAM_MODES, "car,pt,walk");
		config.changeMode().addParam(ChangeModeConfigGroup.CONFIG_PARAM_IGNORECARAVAILABILITY, "false");

		final ChangeLegMode module = new ChangeLegMode(config.global(), config.changeMode());
		final String[] modes = new String[] {TransportMode.car, TransportMode.pt, TransportMode.walk};

		module.prepareReplanning(null);
		Person person = PopulationUtils.getFactory().createPerson(Id.create(1, Person.class));
		PersonUtils.setCarAvail(person, "never");
		Plan plan = PopulationUtils.createPlan(person);
		PopulationUtils.createAndAddActivityFromCoord(plan, "home", new Coord((double) 0, (double) 0));
		Leg leg = PopulationUtils.createAndAddLeg( plan, TransportMode.pt );
		PopulationUtils.createAndAddActivityFromCoord(plan, "work", new Coord((double) 0, (double) 0));

		HashMap<String, Integer> counter = new HashMap<>();
		for (String mode : modes) {
			counter.put(mode, Integer.valueOf(0));
		}

		for (int i = 0; i < 100; i++) {
			module.handlePlan(plan);
			Integer count = counter.get(leg.getMode());
			counter.put(leg.getMode(), Integer.valueOf(count.intValue() + 1));
		}
		Assertions.assertEquals(0, counter.get("car").intValue());
	}

	private void runTest(final ChangeLegMode module, final String[] possibleModes) {
		module.prepareReplanning(null);

		Plan plan = PopulationUtils.createPlan(null);
		PopulationUtils.createAndAddActivityFromCoord(plan, "home", new Coord((double) 0, (double) 0));
		Leg leg = PopulationUtils.createAndAddLeg( plan, TransportMode.car );
		PopulationUtils.createAndAddActivityFromCoord(plan, "work", new Coord((double) 0, (double) 0));

		HashMap<String, Integer> counter = new HashMap<>();
		for (String mode : possibleModes) {
			counter.put(mode, Integer.valueOf(0));
		}

		for (int i = 0; i < 50; i++) {
			module.handlePlan(plan);
			Integer count = counter.get(leg.getMode());
			Assertions.assertNotNull(count, "unexpected mode: " + leg.getMode());
			counter.put(leg.getMode(), Integer.valueOf(count.intValue() + 1));
		}

		for (Map.Entry<String, Integer> entry : counter.entrySet()) {
			int count = entry.getValue().intValue();
			Assertions.assertTrue(count > 0, "mode " + entry.getKey() + " was never chosen.");
		}
	}
}
