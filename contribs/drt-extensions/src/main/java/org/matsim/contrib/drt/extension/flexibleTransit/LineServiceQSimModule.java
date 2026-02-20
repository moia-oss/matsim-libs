package org.matsim.contrib.drt.extension.flexibleTransit;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.drt.optimizer.*;
import org.matsim.contrib.drt.optimizer.depot.DepotFinder;
import org.matsim.contrib.drt.optimizer.insertion.CostCalculationStrategy;
import org.matsim.contrib.drt.optimizer.insertion.DefaultInsertionCostCalculator;
import org.matsim.contrib.drt.optimizer.insertion.InsertionCostCalculator;
import org.matsim.contrib.drt.optimizer.insertion.UnplannedRequestInserter;
import org.matsim.contrib.drt.optimizer.rebalancing.RebalancingStrategy;
import org.matsim.contrib.drt.prebooking.PrebookingActionCreator;
import org.matsim.contrib.drt.prebooking.PrebookingManager;
import org.matsim.contrib.drt.prebooking.abandon.AbandonVoter;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.schedule.DrtTaskFactory;
import org.matsim.contrib.drt.scheduler.EmptyVehicleRelocator;
import org.matsim.contrib.drt.stops.PassengerStopDurationProvider;
import org.matsim.contrib.drt.vrpagent.DrtActionCreator;
import org.matsim.contrib.dvrp.fleet.Fleet;
import org.matsim.contrib.dvrp.load.DvrpLoadType;
import org.matsim.contrib.dvrp.passenger.PassengerEngine;
import org.matsim.contrib.dvrp.passenger.PassengerHandler;
import org.matsim.contrib.dvrp.router.TimeAsTravelDisutility;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeQSimModule;
import org.matsim.contrib.dvrp.schedule.ScheduleInquiry;
import org.matsim.contrib.dvrp.schedule.ScheduleTimingUpdater;
import org.matsim.contrib.dvrp.vrpagent.VrpAgentLogic;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.mobsim.framework.MobsimTimer;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;

import java.net.URL;

public class LineServiceQSimModule extends AbstractDvrpModeQSimModule {

    private final DrtConfigGroup drtCfg;
    private final boolean allowOnDemand;
    private final boolean enforceMaxWait;
    private final boolean mixed;

    public LineServiceQSimModule(DrtConfigGroup drtCfg, boolean allowOnDemand, boolean enforceMaxWait, boolean mixed) {
        super(drtCfg.getMode());
        this.drtCfg = drtCfg;
        this.allowOnDemand = allowOnDemand;
        this.enforceMaxWait = enforceMaxWait;
        this.mixed = mixed;
    }

    @Override
    protected void configureQSim() {

        URL url = ConfigGroup.getInputFileURL(getConfig().getContext(), drtCfg.getTransitStopFile());
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new TransitScheduleReader(scenario).readURL(url);

        bindModal(LineServiceManager.class).toProvider(modalProvider(getter -> new LineServiceManager(scenario.getTransitSchedule(),
                getter.getModal(DrtTaskFactory.class), getter.getModal(Network.class),
                getter.getModal(TravelTime.class), new SpeedyALTFactory().createPathCalculator(getter.getModal(Network.class),
                new TimeAsTravelDisutility(getter.getModal(TravelTime.class)), getter.getModal(TravelTime.class)),
                getter.getModal(QsimScopeForkJoinPool.class).getPool(), getter.getModal(VehicleEntry.EntryFactory.class),
                getter.getModal(Fleet.class),
                getter.get(EventsManager.class)))
        ).asEagerSingleton();

        addModalComponent(DrtOptimizer.class, modalProvider(
                getter -> {
                    return new LineServiceDrtOptimizer(
                          //  new ShiftDrtOptimizer(
                            new DefaultDrtOptimizer(
                                    getter.getModal(QsimScopeForkJoinPool.class),
                                    drtCfg,
                                    getter.getModal(Fleet.class),
                                    getter.get(MobsimTimer.class),
                                    getter.getModal(DepotFinder.class),
                                    getter.getModal(RebalancingStrategy.class),
                                    getter.getModal(ScheduleInquiry.class),
                                    getter.getModal(ScheduleTimingUpdater.class),
                                    getter.getModal(EmptyVehicleRelocator.class),
                                    getter.getModal(UnplannedRequestInserter.class),
                                    getter.getModal(DrtRequestInsertionRetryQueue.class)),
                                  //  getter.getModal(DrtShiftDispatcher.class),
                           //         getter.getModal(ScheduleTimingUpdater.class)
                          //  ),
                            getter.getModal(LineServiceManager.class));
                }));

        bindModal(InsertionCostCalculator.class).toProvider(modalProvider(
                getter -> new LineServiceInsertionCostCalculator(getter.getModal(LineServiceManager.class),
                        new DefaultInsertionCostCalculator(getter.getModal(CostCalculationStrategy.class),
                                drtCfg.addOrGetDrtOptimizationConstraintsParams().addOrGetDefaultDrtOptimizationConstraintsSet()),
                        allowOnDemand, scenario.getTransitSchedule(), mixed)));


        bindModal(VrpAgentLogic.DynActionCreator.class).toProvider(modalProvider(getter -> {
            PassengerHandler passengerHandler = (PassengerEngine) getter.getModal(PassengerHandler.class);
            DrtActionCreator delegate = getter.getModal(DrtActionCreator.class);
            PassengerStopDurationProvider stopDurationProvider = getter.getModal(PassengerStopDurationProvider.class);
            PrebookingManager prebookingManager = getter.getModal(PrebookingManager.class);
            AbandonVoter abandonVoter = getter.getModal(AbandonVoter.class);
            DvrpLoadType loadType = getter.getModal(DvrpLoadType.class);

            PrebookingActionCreator prebookingActionCreator = new PrebookingActionCreator(passengerHandler, delegate, stopDurationProvider, prebookingManager,
                    abandonVoter, loadType);

            return new FixedStopActivityCreator(prebookingActionCreator, getter.get(EventsManager.class),
                    getter.get(MobsimTimer.class), getMode(), getter.getModal(LineServiceManager.class));

        }));

        bindModal(CostCalculationStrategy.class).toProvider(modalProvider(
                getter -> new LineServiceCostCalculationStrategy(getter.getModal(PassengerStopDurationProvider.class), enforceMaxWait)));




    }
}
