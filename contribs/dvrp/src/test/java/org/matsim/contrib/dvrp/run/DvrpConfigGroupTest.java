package org.matsim.contrib.dvrp.run;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.ControllerConfigGroup.RoutingAlgorithmType;

public class DvrpConfigGroupTest {

	private static Config config(double beta, RoutingAlgorithmType routing) {
		DvrpConfigGroup dvrp = new DvrpConfigGroup();
		dvrp.setTravelTimeEstimationBeta(beta);
		Config config = ConfigUtils.createConfig(dvrp);
		config.controller().setRoutingAlgorithmType(routing);
		config.qsim().setSimStarttimeInterpretation(QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime); // required by DVRP
		return config;
	}

	private static void check(Config config) {
		DvrpConfigGroup.get(config).checkConsistency(config);
	}

	@Test
	void onlineEstimationIsRejectedWithChRouter() {
		RuntimeException e = assertThrows(RuntimeException.class, () -> check(config(600, RoutingAlgorithmType.CHRouter)));
		assertTrue(e.getMessage().contains("CHRouter"), e.getMessage());
	}

	@Test
	void onlineEstimationIsAcceptedWithSpeedyAlt() {
		assertDoesNotThrow(() -> check(config(600, RoutingAlgorithmType.SpeedyALT)));
	}

	@Test
	void offlineEstimationIsAcceptedWithChRouter() {
		assertDoesNotThrow(() -> check(config(0, RoutingAlgorithmType.CHRouter)));
	}
}
