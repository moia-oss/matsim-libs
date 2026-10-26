package org.matsim.contrib.drt.extension.flexibleTransit;

import com.google.common.collect.ImmutableMap;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.drt.optimizer.StopWaypointFactory;
import org.matsim.contrib.drt.optimizer.StopWaypointFactoryImpl;
import org.matsim.contrib.drt.prebooking.PrebookingParams;
import org.matsim.contrib.drt.routing.DrtStopFacility;
import org.matsim.contrib.drt.routing.DrtStopFacilityImpl;
import org.matsim.contrib.drt.routing.DrtStopNetwork;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.stops.PassengerStopDurationProvider;
import org.matsim.contrib.drt.stops.PrebookingStopTimeCalculator;
import org.matsim.contrib.drt.stops.StopTimeCalculator;
import org.matsim.contrib.dvrp.load.DvrpLoadType;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeModule;
import org.matsim.contrib.dvrp.run.DvrpMode;
import org.matsim.contrib.dvrp.run.DvrpModes;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.modal.ModalProviders;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.matsim.utils.gis.shp2matsim.ShpGeometryUtils;

import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class LineServiceModule extends AbstractDvrpModeModule {
    private final DrtConfigGroup drtCfg;

    public LineServiceModule(DrtConfigGroup drtCfg) {
        super(drtCfg.getMode());
        this.drtCfg = drtCfg;
    }

    private static DrtStopNetwork createDrtStopNetworkFromServiceAreaAndAddLineStops(Config config, DrtConfigGroup drtCfg,
                                                                                     Network drtNetwork) {
        final List<PreparedGeometry> preparedGeometries = ShpGeometryUtils.loadPreparedGeometries(
                ConfigGroup.getInputFileURL(config.getContext(), drtCfg.getDrtServiceAreaShapeFile()));

        //read stops form area file
        Map<Id<DrtStopFacility>, DrtStopFacility> drtStops = drtNetwork.getLinks()
                .values()
                .stream()
                .filter(link -> ShpGeometryUtils.isCoordInPreparedGeometries(link.getToNode().getCoord(),
                        preparedGeometries))
                .map(DrtStopFacilityImpl::createFromLink)
                .collect(Collectors.toMap(DrtStopFacility::getId, f -> f));

        //add all stops from schedule file (line-operation)
        drtStops.putAll(parseDrtStopNetworkFromTransitSchedule(config, drtCfg));

        return () -> ImmutableMap.copyOf(drtStops);
    }

    private static DrtStopNetwork createDrtStopNetworkFromTransitSchedule(Config config, DrtConfigGroup drtCfg) {
        return () -> ImmutableMap.copyOf(parseDrtStopNetworkFromTransitSchedule(config,drtCfg));
    }

    private static Map<Id<DrtStopFacility>, DrtStopFacility> parseDrtStopNetworkFromTransitSchedule(Config config, DrtConfigGroup drtCfg) {
        URL url = ConfigGroup.getInputFileURL(config.getContext(), drtCfg.getTransitStopFile());
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new TransitScheduleReader(scenario).readURL(url);
        return scenario.getTransitSchedule()
                .getFacilities()
                .values()
                .stream()
                .map(DrtStopFacilityImpl::createFromFacility)
                .collect(Collectors.toMap(DrtStopFacility::getId, f -> f));
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

        bindModal(DrtStopNetwork.class).toProvider(new FlexibleTransitStopNetworkProvider(getConfig(), drtCfg)).asEagerSingleton();

        if (drtCfg.getPrebookingParams().isPresent()) {
            bindModal(StopTimeCalculator.class).toProvider(modalProvider(getter -> {
                PassengerStopDurationProvider provider = getter.getModal(PassengerStopDurationProvider.class);
                return new PrebookingStopTimeCalculator(provider);
            }));
        }
    }

    //TODO this is just a copy of the standard DrtStopNetworkProvider
    // which combines stop networks from the service area file and transit stops file
    // in case operationalSCheme == serviceAreaBased.
    // This is to make sure that agents can choose the line stops.
    // Ideally, one would delegate to the standard provider and then read in the additional stops for line operation
    // (from a separate FlexibleTransitConfigGroup!?!)
    private static class FlexibleTransitStopNetworkProvider extends ModalProviders.AbstractProvider<DvrpMode, DrtStopNetwork> {

        private final DrtConfigGroup drtCfg;
        private final Config config;

        private FlexibleTransitStopNetworkProvider(Config config, DrtConfigGroup drtCfg) {
            super(drtCfg.getMode(), DvrpModes::mode);
            this.drtCfg = drtCfg;
            this.config = config;
        }

        @Override
        public DrtStopNetwork get() {
            switch (drtCfg.getOperationalScheme()) {
                case door2door:
                    return ImmutableMap::of;
                case stopbased:
                    return createDrtStopNetworkFromTransitSchedule(config, drtCfg);
                case serviceAreaBased:
                    return createDrtStopNetworkFromServiceAreaAndAddLineStops(config, drtCfg, getModalInstance(Network.class));
                default:
                    throw new RuntimeException("Unsupported operational scheme: " + drtCfg.getOperationalScheme());
            }
        }
    }
}
