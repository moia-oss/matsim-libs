package org.matsim.contrib.drt.extension.flexibleTransit;

import org.matsim.contrib.drt.optimizer.StopWaypointFactory;
import org.matsim.contrib.drt.optimizer.StopWaypointFactoryImpl;
import org.matsim.contrib.drt.prebooking.PrebookingParams;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.stops.PassengerStopDurationProvider;
import org.matsim.contrib.drt.stops.PrebookingStopTimeCalculator;
import org.matsim.contrib.drt.stops.StopTimeCalculator;
import org.matsim.contrib.dvrp.load.DvrpLoadType;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeModule;

public class LineServiceModule extends AbstractDvrpModeModule {
    private final DrtConfigGroup drtCfg;

    public LineServiceModule(DrtConfigGroup drtCfg) {
        super(drtCfg.getMode());
        this.drtCfg = drtCfg;
    }

    @Override
    public void install() {
        boolean scheduleWaitBeforeDrive = drtCfg.getPrebookingParams().map(PrebookingParams::isScheduleWaitBeforeDrive).orElse(false);
        bindModal(StopWaypointFactory.class).toProvider(modalProvider(getter ->
        {
            StopTimeCalculator stopTimeCalculator = getter.getModal(StopTimeCalculator.class);
            StopWaypointFactoryImpl stopWaypointFactory = new StopWaypointFactoryImpl(getter.getModal(DvrpLoadType.class), scheduleWaitBeforeDrive);
            return new FixedStopWaypointFactory(stopWaypointFactory, getter.getModal(DvrpLoadType.class), stopTimeCalculator);
        }));

        bindModal(StopTimeCalculator.class).toProvider(modalProvider(getter -> {
            PassengerStopDurationProvider provider = getter.getModal(PassengerStopDurationProvider.class);
            return new PrebookingStopTimeCalculator(provider);
        }));
    }
}
