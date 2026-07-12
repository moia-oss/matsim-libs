package org.matsim.contrib.drt.extension.operations.guidance;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.contrib.common.zones.systems.grid.square.SquareGridZoneSystemParams;
import org.matsim.contrib.drt.extension.DrtWithExtensionsConfigGroup;
import org.matsim.contrib.drt.extension.operations.DrtOperationsControlerCreator;
import org.matsim.contrib.drt.extension.operations.DrtOperationsParams;
import org.matsim.contrib.drt.extension.operations.guidance.config.RemoteGuidanceParams;
import org.matsim.contrib.drt.extension.operations.guidance.events.VehicleAssignedToOperatorEvent;
import org.matsim.contrib.drt.extension.operations.guidance.events.VehicleReleasedFromOperatorEvent;
import org.matsim.contrib.drt.extension.operations.operationFacilities.OperationFacilitiesParams;
import org.matsim.contrib.drt.extension.operations.shifts.config.ShiftsParams;
import org.matsim.contrib.drt.extension.operations.shifts.shift.DrtShift;
import org.matsim.contrib.drt.optimizer.constraints.DrtOptimizationConstraintsSetImpl;
import org.matsim.contrib.drt.fare.DrtFareParams;
import org.matsim.contrib.drt.optimizer.insertion.extensive.ExtensiveInsertionSearchParams;
import org.matsim.contrib.drt.optimizer.rebalancing.RebalancingParams;
import org.matsim.contrib.drt.optimizer.rebalancing.mincostflow.MinCostFlowRebalancingStrategyParams;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.zone.skims.DvrpTravelTimeMatrixParams;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.examples.ExamplesUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for remote guidance: two operators with capacity 5 each may supervise at most 10 of the 20 fleet
 * vehicles simultaneously. Asserts that vehicles get activated under operators and that the concurrent number of
 * supervised vehicles never exceeds the combined operator capacity.
 *
 * @author nkuehnel / MOIA
 */
public class RunRemoteGuidanceDrtScenarioIT {

	private static final int OPERATOR_CAPACITY = 5;
	private static final int NUMBER_OF_OPERATORS = 2;

	@Test
	void test() {
		MultiModeDrtConfigGroup multiModeDrtConfigGroup = new MultiModeDrtConfigGroup(DrtWithExtensionsConfigGroup::new);

		String fleetFile = "holzkirchenFleet.xml";
		String plansFile = "holzkirchenPlans.xml.gz";
		String networkFile = "holzkirchenNetwork.xml.gz";
		String opFacilitiesFile = "holzkirchenOperationFacilities.xml";
		String shiftsFile = "holzkirchenRemoteGuidanceShifts.xml";

		DrtWithExtensionsConfigGroup drtCfg = (DrtWithExtensionsConfigGroup) multiModeDrtConfigGroup.createParameterSet("drt");

		drtCfg.setMode(TransportMode.drt);
		DrtOptimizationConstraintsSetImpl defaultConstraintsSet =
				drtCfg.addOrGetDrtOptimizationConstraintsParams().addOrGetDefaultDrtOptimizationConstraintsSet();
		drtCfg.setStopDuration(30.);
		defaultConstraintsSet.setMaxTravelTimeAlpha(1.5);
		defaultConstraintsSet.setMaxTravelTimeBeta(10. * 60.);
		defaultConstraintsSet.setMaxWaitTime(600.);
		defaultConstraintsSet.setRejectRequestIfMaxWaitOrTravelTimeViolated(true);
		defaultConstraintsSet.setMaxWalkDistance(1000.);
		drtCfg.setUseModeFilteredSubnetwork(false);
		drtCfg.setVehiclesFile(fleetFile);
		drtCfg.setOperationalScheme(DrtConfigGroup.OperationalScheme.door2door);
		drtCfg.setPlotDetailedCustomerStats(true);
		drtCfg.setIdleVehiclesReturnToDepots(false);

		drtCfg.addParameterSet(new ExtensiveInsertionSearchParams());

		ConfigGroup rebalancing = drtCfg.createParameterSet("rebalancing");
		drtCfg.addParameterSet(rebalancing);
		((RebalancingParams) rebalancing).setInterval(600);

		MinCostFlowRebalancingStrategyParams strategyParams = new MinCostFlowRebalancingStrategyParams();
		strategyParams.setTargetAlpha(0.3);
		strategyParams.setTargetBeta(0.3);

		RebalancingParams rebalancingParams = drtCfg.getRebalancingParams().get();
		rebalancingParams.addParameterSet(strategyParams);

		SquareGridZoneSystemParams zoneParams = (SquareGridZoneSystemParams) rebalancingParams.createParameterSet(SquareGridZoneSystemParams.SET_NAME);
		zoneParams.setCellSize(500.);
		rebalancingParams.addParameterSet(zoneParams);
		drtCfg.addParameterSet(zoneParams);
		rebalancingParams.setTargetLinkSelection(RebalancingParams.TargetLinkSelection.mostCentral);

		multiModeDrtConfigGroup.addParameterSet(drtCfg);

		DvrpConfigGroup dvrpConfigGroup = new DvrpConfigGroup();
		DvrpTravelTimeMatrixParams matrixParams = dvrpConfigGroup.getTravelTimeMatrixParams();
		matrixParams.addParameterSet(matrixParams.createParameterSet(SquareGridZoneSystemParams.SET_NAME));

		final Config config = ConfigUtils.createConfig(multiModeDrtConfigGroup, dvrpConfigGroup);
		config.setContext(ExamplesUtils.getTestScenarioURL("holzkirchen"));

		Set<String> modes = new HashSet<>();
		modes.add("drt");
		config.travelTimeCalculator().setAnalyzedModes(modes);

		config.scoring().addModeParams(new ScoringConfigGroup.ModeParams("drt"));
		config.scoring().addModeParams(new ScoringConfigGroup.ModeParams("walk"));

		config.plans().setInputFile(plansFile);
		config.network().setInputFile(networkFile);

		config.qsim().setSimStarttimeInterpretation(QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime);
		config.qsim().setSimEndtimeInterpretation(QSimConfigGroup.EndtimeInterpretation.minOfEndtimeAndMobsimFinished);

		final ScoringConfigGroup.ActivityParams home = new ScoringConfigGroup.ActivityParams("home");
		home.setTypicalDuration(8 * 3600);
		final ScoringConfigGroup.ActivityParams other = new ScoringConfigGroup.ActivityParams("other");
		other.setTypicalDuration(4 * 3600);
		final ScoringConfigGroup.ActivityParams education = new ScoringConfigGroup.ActivityParams("education");
		education.setTypicalDuration(6 * 3600);
		final ScoringConfigGroup.ActivityParams shopping = new ScoringConfigGroup.ActivityParams("shopping");
		shopping.setTypicalDuration(2 * 3600);
		final ScoringConfigGroup.ActivityParams work = new ScoringConfigGroup.ActivityParams("work");
		work.setTypicalDuration(2 * 3600);

		config.scoring().addActivityParams(home);
		config.scoring().addActivityParams(other);
		config.scoring().addActivityParams(education);
		config.scoring().addActivityParams(shopping);
		config.scoring().addActivityParams(work);

		final ReplanningConfigGroup.StrategySettings stratSets = new ReplanningConfigGroup.StrategySettings();
		stratSets.setWeight(1);
		stratSets.setStrategyName("ChangeExpBeta");
		config.replanning().addStrategySettings(stratSets);

		config.controller().setLastIteration(0);
		config.controller().setWriteEventsInterval(1);
		config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setOutputDirectory("test/output/RunRemoteGuidanceDrtScenarioIT");

		DrtOperationsParams operationsParams = (DrtOperationsParams) drtCfg.createParameterSet(DrtOperationsParams.SET_NAME);
		ShiftsParams shiftsParams = (ShiftsParams) operationsParams.createParameterSet(ShiftsParams.SET_NAME);
		OperationFacilitiesParams operationFacilitiesParams = (OperationFacilitiesParams) operationsParams.createParameterSet(OperationFacilitiesParams.SET_NAME);
		RemoteGuidanceParams remoteGuidanceParams = (RemoteGuidanceParams) operationsParams.createParameterSet(RemoteGuidanceParams.SET_NAME);
		operationsParams.addParameterSet(shiftsParams);
		operationsParams.addParameterSet(operationFacilitiesParams);
		operationsParams.addParameterSet(remoteGuidanceParams);

		operationFacilitiesParams.setOperationFacilityInputFile(opFacilitiesFile);
		shiftsParams.setShiftInputFile(shiftsFile);
		shiftsParams.setAllowInFieldChangeover(true);

		remoteGuidanceParams.setOperatorShiftType("remoteGuidance");
		remoteGuidanceParams.setDefaultOperatorCapacity(OPERATOR_CAPACITY);
		remoteGuidanceParams.setMinRemainingShiftTimeForActivation(0);

		drtCfg.addParameterSet(operationsParams);

		DrtFareParams drtFareParams = new DrtFareParams();
		drtFareParams.setBaseFare(1.);
		drtFareParams.setDistanceFare_m(1. / 1000);
		drtCfg.addParameterSet(drtFareParams);

		final Controler controler = DrtOperationsControlerCreator.createControler(config, false);

		ConcurrencyTracker tracker = new ConcurrencyTracker();
		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				addEventHandlerBinding().toInstance(tracker);
			}
		});

		controler.run();

		// at least some vehicles must have been activated under operators
		assertThat(tracker.totalAssignments).isPositive();
		// concurrent supervised vehicles must never exceed the combined operator capacity
		assertThat(tracker.maxConcurrent).isLessThanOrEqualTo(NUMBER_OF_OPERATORS * OPERATOR_CAPACITY);
	}

	/**
	 * Tracks per-operator load and global concurrency from the remote guidance events.
	 */
	private static final class ConcurrencyTracker implements BasicEventHandler {
		private final Map<Id<DvrpVehicle>, Id<DrtShift>> supervisedBy = new HashMap<>();
		private int maxConcurrent = 0;
		private int totalAssignments = 0;

		@Override
		public void handleEvent(org.matsim.api.core.v01.events.Event event) {
			if (event instanceof VehicleAssignedToOperatorEvent assigned) {
				supervisedBy.put(assigned.getVehicleId(), assigned.getOperatorId());
				totalAssignments++;
				maxConcurrent = Math.max(maxConcurrent, supervisedBy.size());
			} else if (event instanceof VehicleReleasedFromOperatorEvent released) {
				supervisedBy.remove(released.getVehicleId());
			}
		}

		@Override
		public void reset(int iteration) {
			supervisedBy.clear();
			maxConcurrent = 0;
			totalAssignments = 0;
		}
	}
}