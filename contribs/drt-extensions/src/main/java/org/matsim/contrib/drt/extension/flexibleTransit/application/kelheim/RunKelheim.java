package org.matsim.contrib.drt.extension.flexibleTransit.application.kelheim;

import org.geotools.api.referencing.FactoryException;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.contrib.common.zones.systems.grid.h3.H3GridZoneSystemParams;
import org.matsim.contrib.drt.extension.DrtWithExtensionsConfigGroup;
import org.matsim.contrib.drt.extension.flexibleTransit.LineServiceModule;
import org.matsim.contrib.drt.extension.flexibleTransit.LineServiceQSimModule;
import org.matsim.contrib.drt.extension.flexibleTransit.MoiaDvrpLoadType;
import org.matsim.contrib.drt.optimizer.constraints.DrtOptimizationConstraintsParams;
import org.matsim.contrib.drt.optimizer.constraints.DrtOptimizationConstraintsSetImpl;
import org.matsim.contrib.drt.optimizer.insertion.extensive.ExtensiveInsertionSearchParams;
import org.matsim.contrib.drt.optimizer.rebalancing.RebalancingParams;
import org.matsim.contrib.drt.optimizer.rebalancing.mincostflow.MinCostFlowRebalancingStrategyParams;
import org.matsim.contrib.drt.prebooking.PrebookingParams;
import org.matsim.contrib.drt.prebooking.logic.AdaptivePrebookingLogic;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.DrtControlerCreator;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.load.DvrpLoadType;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeModule;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.zone.skims.DvrpTravelTimeMatrixParams;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.config.groups.RoutingConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.replanning.strategies.DefaultPlanStrategiesModule;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.matsim.contrib.drt.optimizer.rebalancing.mincostflow.MinCostFlowRebalancingStrategyParams.RebalancingTargetCalculatorType.EstimatedRelativeDemand;

public class RunKelheim {


    private final static boolean FLEX_TRANSIT = true;
    private final static boolean ALLOW_ON_DEMAND = true;
    private final static boolean ENFORCE_MAX_WAIT = true;
    private final static boolean PREBOOKING = true;
    private final static boolean MIXED = false;

    public static final String SCHEDULE_FILE = "";
    public static final String PLANS_FILE =    "";
    public static final String FLEET_FILE =    "";
    public static final String NETWORK_FILE =  "";
    public static final String OUTPUT_PATH =   "";

    public static void main(String[] args) throws CommandLine.ConfigurationException, FactoryException {

        CommandLine cmd = new CommandLine.Builder(args).allowOptions(
                "data-path",
                "static-path",
                "output-path",
                "use-moia-cost-function",
                "use-moia-constraints",
                "use-network-change-events",
                "use-dvrp-tt-for-change-events",
                "fleet-filename",
                "use-dvrp-tt-for-change-events",
                "shifts-prefix", // prefix to the default shifts filename. Allows reading a modified shifts file (e.g. scaled up/down shifts)
                "plans-prefix",
                "stops-prefix",
                "fleet-prefix",
                "opfas-prefix",
                "max-walk-distance",
                "h3resolution",
                "max-abs-detour",
                "use-max-abs-detour-constraint",
                "max-rel-detour",
                "use-max-rel-detour-constraint",
                "min-abs-detour",
                "use-min-abs-detour-constraint",
                "detour-scaling-factor",
                "wait-time-factor",
                //repeated insertion stuff
                "use-repeated-selective-insertion",
                "insertion-retry-count"
        ).build();

        // Check if input path were supplied
        double maxWalkDistance = Double.parseDouble(cmd.getOption("max-walk-distance").orElse("1000")); // in meters
        double rebalancingAlpha = Double.parseDouble(cmd.getOption("rebalancing-alpha").orElse("0.3"));
        double rebalancingBeta = Double.parseDouble(cmd.getOption("rebalancing-beta").orElse("0.3"));
        String rebalancingType = cmd.getOption("rebalancing-type").orElse("EstimatedDemand");
        double beelineDistanceScalingFactor = Double.parseDouble(cmd.getOption("beeline-distance-scaling-factor").orElse("1.0"));

        MultiModeDrtConfigGroup multiModeDrtConfigGroup = new MultiModeDrtConfigGroup(DrtWithExtensionsConfigGroup::new);

        DrtWithExtensionsConfigGroup drtConfigGroup = new DrtWithExtensionsConfigGroup();
        drtConfigGroup.setMode(TransportMode.drt);
        drtConfigGroup.setStopDuration(60.);
        drtConfigGroup.setUseModeFilteredSubnetwork(false);
        drtConfigGroup.setOperationalScheme(DrtConfigGroup.OperationalScheme.stopbased);
        drtConfigGroup.setPlotDetailedCustomerStats(true);
        drtConfigGroup.setIdleVehiclesReturnToDepots(false);
        drtConfigGroup.setNumberOfThreads(Math.min(20, Runtime.getRuntime().availableProcessors()));
        drtConfigGroup.addParameterSet(new ExtensiveInsertionSearchParams());

        H3GridZoneSystemParams zonesParams = new H3GridZoneSystemParams();
        zonesParams.setH3Resolution(7);
        drtConfigGroup.addParameterSet(zonesParams);

        // #####################
        // prebooking

        PrebookingParams prebookingParams = new PrebookingParams();
        prebookingParams.setMaximumPassengerDelay(600);
        prebookingParams.setUnschedulingMode(PrebookingParams.UnschedulingMode.Routing);
        prebookingParams.setScheduleWaitBeforeDrive(true);
        drtConfigGroup.addParameterSet(prebookingParams);

        // #####################

        // #####################
        // rebalancing
        ConfigGroup rebalancing = drtConfigGroup.createParameterSet("rebalancing");
        drtConfigGroup.addParameterSet(rebalancing);
        ((RebalancingParams) rebalancing).setInterval(900);
        ((RebalancingParams) rebalancing).setMaxTimeBeforeIdle(300);
        ((RebalancingParams) rebalancing).setTargetLinkSelection(RebalancingParams.TargetLinkSelection.mostCentral);
        rebalancing.addParameterSet(zonesParams);


        MinCostFlowRebalancingStrategyParams strategyParams = new MinCostFlowRebalancingStrategyParams();
        strategyParams.setTargetAlpha(0.3);
        strategyParams.setTargetBeta(0.3);
        strategyParams.setRebalancingTargetCalculatorType(EstimatedRelativeDemand);
        rebalancing.addParameterSet(strategyParams);

        // #####################

        DrtOptimizationConstraintsParams params = drtConfigGroup.addOrGetDrtOptimizationConstraintsParams();
        DrtOptimizationConstraintsSetImpl constraintsSet = params.addOrGetDefaultDrtOptimizationConstraintsSet();
        //constraintsSet.setMaxWaitTime(FLEX_TRANSIT ? 3600 : 900);
        constraintsSet.setMaxWaitTime(60 * 60);
        constraintsSet.setMaxTravelTimeBeta(FLEX_TRANSIT ? 9999999 : 10 * 60);
        constraintsSet.setMaxDetourBeta(FLEX_TRANSIT ? 9999999 : 10 * 60);
        constraintsSet.setMaxDetourAlpha(FLEX_TRANSIT ? 9999999 : 1.5);
        constraintsSet.setMaxTravelTimeAlpha(FLEX_TRANSIT ? 9999999 : 1.5);
        constraintsSet.setMaxAbsoluteDetour(FLEX_TRANSIT ? 9999999 : 30 * 60);
        constraintsSet.setMinimumAllowedDetour(10 * 60);
        constraintsSet.setMaxWalkDistance(maxWalkDistance);
        constraintsSet.setMaxAllowedPickupDelay(5 * 60);
        //constraintsSet.setRejectRequestIfMaxWaitOrTravelTimeViolated(!FLEX_TRANSIT);
        constraintsSet.setRejectRequestIfMaxWaitOrTravelTimeViolated(true);

        //constraintsSet.setRejectRequestIfMaxWaitOrTravelTimeViolated(tr);


        drtConfigGroup.setTransitStopFile(SCHEDULE_FILE);

        RebalancingParams rebalancingParams = drtConfigGroup.getRebalancingParams().orElseThrow();
        rebalancingParams.setRebalancingMinIdleGap(1800);

        // adjustment of the rebalancing strategy
        MinCostFlowRebalancingStrategyParams rebalancingStrategyParams =
                (MinCostFlowRebalancingStrategyParams) rebalancingParams.getRebalancingStrategyParams();
        rebalancingStrategyParams.setTargetAlpha(rebalancingAlpha);
        rebalancingStrategyParams.setTargetBeta(rebalancingBeta);
        switch (rebalancingType) {
            case "EstimatedDemand":
                rebalancingStrategyParams.setRebalancingTargetCalculatorType(MinCostFlowRebalancingStrategyParams.RebalancingTargetCalculatorType.EstimatedDemand);
                break;
            case "EstimatedRelativeDemand":
                rebalancingStrategyParams.setRebalancingTargetCalculatorType(MinCostFlowRebalancingStrategyParams.RebalancingTargetCalculatorType.EstimatedRelativeDemand);
                break;
            case "EqualVehicleDensity":
                rebalancingStrategyParams.setRebalancingTargetCalculatorType(MinCostFlowRebalancingStrategyParams.RebalancingTargetCalculatorType.EqualVehicleDensity);
                break;
            case "EqualRebalancableVehicleDistribution":
                rebalancingStrategyParams.setRebalancingTargetCalculatorType(MinCostFlowRebalancingStrategyParams.RebalancingTargetCalculatorType.EqualRebalancableVehicleDistribution);
                break;
            case "EqualVehiclesToPopulationRatio":
                rebalancingStrategyParams.setRebalancingTargetCalculatorType(MinCostFlowRebalancingStrategyParams.RebalancingTargetCalculatorType.EqualVehiclesToPopulationRatio);
                break;
            default:
                throw new IllegalArgumentException("Unknown rebalancing type: " + rebalancingType);
        }

        multiModeDrtConfigGroup.addParameterSet(drtConfigGroup);

        DvrpConfigGroup dvrpConfigGroup = new DvrpConfigGroup();
        DvrpTravelTimeMatrixParams matrixParams = dvrpConfigGroup.getTravelTimeMatrixParams();
        H3GridZoneSystemParams h3Params = (H3GridZoneSystemParams) matrixParams.createParameterSet(H3GridZoneSystemParams.SET_NAME);
        h3Params.setH3Resolution(9);
        matrixParams.addParameterSet(h3Params);
        matrixParams.setMaxNeighborDistance(1000);


        final Config config = ConfigUtils.createConfig(multiModeDrtConfigGroup,
                dvrpConfigGroup);

        config.global().setNumberOfThreads(10);
        config.qsim().setNumberOfThreads(10);
        config.eventsManager().setNumberOfThreads(10);
        config.global().setDefaultDelimiter(",");
        config.global().setCoordinateSystem("EPSG:25832");

        Set<String> modes = new HashSet<>();
        modes.add("drt");
        config.travelTimeCalculator().setAnalyzedModes(modes);

        // scale the walking distance estimation
        Map<String, RoutingConfigGroup.TeleportedModeParams> teleportParams = config.routing().getTeleportedModeParams();
        double beelineDistanceFactor = teleportParams.get("walk").getBeelineDistanceFactor();
        teleportParams.get("walk").setBeelineDistanceFactor(beelineDistanceScalingFactor * beelineDistanceFactor);


        ScoringConfigGroup.ModeParams scoreParams = new ScoringConfigGroup.ModeParams("drt");
        config.scoring().addModeParams(scoreParams);
        ScoringConfigGroup.ModeParams scoreParams2 = new ScoringConfigGroup.ModeParams("walk");
        config.scoring().addModeParams(scoreParams2);

        config.plans().setInputFile(PLANS_FILE);
        config.network().setInputFile(NETWORK_FILE);

        config.qsim().setSimStarttimeInterpretation(QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime);
        config.qsim().setSimEndtimeInterpretation(QSimConfigGroup.EndtimeInterpretation.onlyUseEndtime);
        // 7 days à 24 hrs à 60 min à 60 s plus some overhead of 2 hrs
        config.qsim().setEndTime(26 * 60 * 60);

        final ScoringConfigGroup.ActivityParams origin = new ScoringConfigGroup.ActivityParams("origin");
        origin.setScoringThisActivityAtAll(false);
        config.scoring().addActivityParams(origin);

        final ScoringConfigGroup.ActivityParams destination = new ScoringConfigGroup.ActivityParams("destination");
        destination.setScoringThisActivityAtAll(false);
        config.scoring().addActivityParams(destination);

        final ReplanningConfigGroup.StrategySettings stratSets = new ReplanningConfigGroup.StrategySettings();
        stratSets.setWeight(1);
        stratSets.setStrategyName(DefaultPlanStrategiesModule.DefaultSelector.KeepLastSelected);
        config.replanning().addStrategySettings(stratSets);

        config.controller().setLastIteration(FLEX_TRANSIT ? 0: 1);
        config.controller().setWriteEventsInterval(1);

        config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        config.controller().setOutputDirectory(OUTPUT_PATH);

        prebookingParams.setUnschedulingMode(FLEX_TRANSIT ? PrebookingParams.UnschedulingMode.Routing: PrebookingParams.UnschedulingMode.StopBased);
        prebookingParams.setAbortRejectedPrebookings(true);
        prebookingParams.setMaximumPassengerDelay(60);

        config.vehicles().setVehiclesFile(FLEET_FILE);

        //drtConfigGroup.setUpdateRoutes(true);


        final Controler controller = DrtControlerCreator.createControler(config, false);

        controller.addOverridingModule(new AbstractDvrpModeModule(drtConfigGroup.getMode()) {
            @Override
            public void install() {
                bindModal(DvrpLoadType.class).toInstance(new MoiaDvrpLoadType());
            }
        });

        if (FLEX_TRANSIT) {
            if(PREBOOKING) {
            //    ProbabilityBasedPrebookingLogic.install(controller, drtConfigGroup, 1, 1800);
                AdaptivePrebookingLogic.install(controller, drtConfigGroup, 1800);
            }
            controller.addOverridingQSimModule(new LineServiceQSimModule(drtConfigGroup, ALLOW_ON_DEMAND, ENFORCE_MAX_WAIT, MIXED));
            controller.addOverridingModule(new LineServiceModule(drtConfigGroup));
        }

        controller.run();
    }
}
