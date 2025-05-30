package org.matsim.contrib.drt.optimizer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.contrib.common.zones.systems.grid.square.SquareGridZoneSystemParams;
import org.matsim.contrib.drt.optimizer.constraints.DrtOptimizationConstraintsSetImpl;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.DrtControlerCreator;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.zone.skims.DvrpTravelTimeMatrixParams;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.examples.ExamplesUtils;
import org.matsim.testcases.MatsimTestUtils;
import org.matsim.vis.otfvis.OTFVisConfigGroup;

import java.net.URL;

public class MaxDetourConstraintTest {
	@RegisterExtension
	public MatsimTestUtils utils = new MatsimTestUtils();

	@Test
	public void testMaxDetourConstraint() {
		URL configUrl = IOUtils.extendUrl(ExamplesUtils.getTestScenarioURL("mielec"), "mielec_drt_config.xml");

		DvrpConfigGroup dvrpConfig = new DvrpConfigGroup();
		DvrpTravelTimeMatrixParams matrixParams = dvrpConfig.getTravelTimeMatrixParams();
		matrixParams.addParameterSet(matrixParams.createParameterSet(SquareGridZoneSystemParams.SET_NAME));

		Config config = ConfigUtils.loadConfig(configUrl, new MultiModeDrtConfigGroup(), dvrpConfig,
			new OTFVisConfigGroup());
		DrtConfigGroup drtConfigGroup = DrtConfigGroup.getSingleModeDrtConfig(config);

		// Max wait time
		DrtOptimizationConstraintsSetImpl defaultConstraintsSet =
                drtConfigGroup.addOrGetDrtOptimizationConstraintsParams()
                        .addOrGetDefaultDrtOptimizationConstraintsSet();
		defaultConstraintsSet.setMaxWaitTime(300);

		// Turn on the max detour constraint
		defaultConstraintsSet.setMaxDetourAlpha(1.5);
		defaultConstraintsSet.setMaxDetourBeta(300);
		defaultConstraintsSet.setMaxAllowedPickupDelay(180);
		defaultConstraintsSet.setMaxAbsoluteDetour(1200);

		// Make the max total travel time constraints very loose (i.e., make it not active)
		defaultConstraintsSet.setMaxTravelTimeAlpha(10);
		defaultConstraintsSet.setMaxTravelTimeBeta(7200);

		config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setOutputDirectory(utils.getOutputDirectory());

		Controler controler = DrtControlerCreator.createControler(config, false);

		controler.run();
	}
}
