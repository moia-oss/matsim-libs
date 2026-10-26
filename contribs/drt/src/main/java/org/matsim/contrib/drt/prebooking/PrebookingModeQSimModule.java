package org.matsim.contrib.drt.prebooking;

import com.google.inject.Inject;
import com.google.inject.TypeLiteral;
import com.google.inject.name.Named;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.drt.optimizer.VehicleEntry;
import org.matsim.contrib.drt.prebooking.abandon.AbandonVoter;
import org.matsim.contrib.drt.prebooking.abandon.MaximumDelayAbandonVoter;
import org.matsim.contrib.drt.prebooking.logic.helpers.PopulationIterator.PopulationIteratorFactory;
import org.matsim.contrib.drt.prebooking.logic.helpers.PrebookingQueue;
import org.matsim.contrib.drt.prebooking.unscheduler.ComplexRequestUnscheduler;
import org.matsim.contrib.drt.prebooking.unscheduler.RequestUnscheduler;
import org.matsim.contrib.drt.prebooking.unscheduler.SimpleRequestUnscheduler;
import org.matsim.contrib.drt.schedule.DrtTaskFactory;
import org.matsim.contrib.drt.stops.PassengerStopDurationProvider;
import org.matsim.contrib.drt.vrpagent.DrtActionCreator;
import org.matsim.contrib.dvrp.fleet.DvrpVehicleLookup;
import org.matsim.contrib.dvrp.load.DvrpLoadType;
import org.matsim.contrib.dvrp.optimizer.VrpOptimizer;
import org.matsim.contrib.dvrp.passenger.PassengerEngine;
import org.matsim.contrib.dvrp.passenger.PassengerGroupIdentifier;
import org.matsim.contrib.dvrp.passenger.PassengerHandler;
import org.matsim.contrib.dvrp.passenger.PassengerRequestCreator;
import org.matsim.contrib.dvrp.passenger.PassengerRequestValidator;
import org.matsim.contrib.dvrp.router.DvrpRoutingModuleProvider;
import org.matsim.contrib.dvrp.router.TimeAsTravelDisutility;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeQSimModule;
import org.matsim.contrib.dvrp.schedule.ScheduleTimingUpdater;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.mobsim.framework.MobsimTimer;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.router.RoutingModule;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelTime;

import com.google.inject.Singleton;
import org.matsim.facilities.ActivityFacilities;

import java.util.Map;
import java.util.Objects;

public class PrebookingModeQSimModule extends AbstractDvrpModeQSimModule {
	private final PrebookingParams prebookingParams;

	public PrebookingModeQSimModule(String mode, PrebookingParams prebookingParams) {
		super(mode);
		this.prebookingParams = prebookingParams;
	}

	@Override
	protected void configureQSim() {
		bindModal(PrebookingActionCreator.class).toProvider(modalProvider(getter -> {
			PassengerHandler passengerHandler = (PassengerEngine) getter.getModal(PassengerHandler.class);
			DrtActionCreator delegate = getter.getModal(DrtActionCreator.class);
			PassengerStopDurationProvider stopDurationProvider = getter.getModal(PassengerStopDurationProvider.class);
			PrebookingManager prebookingManager = getter.getModal(PrebookingManager.class);
			AbandonVoter abandonVoter = getter.getModal(AbandonVoter.class);
			DvrpLoadType loadType = getter.getModal(DvrpLoadType.class);

			return new PrebookingActionCreator(passengerHandler, delegate, stopDurationProvider, prebookingManager,
					abandonVoter, loadType);
		})).in(Singleton.class);

		bindModal(PrebookingManager.class).toProvider(modalProvider(getter -> {
			Network network = getter.getModal(Network.class);
			PassengerRequestCreator requestCreator = getter.getModal(PassengerRequestCreator.class);
			VrpOptimizer optimizer = getter.getModal(VrpOptimizer.class);
			PassengerRequestValidator requestValidator = getter.getModal(PassengerRequestValidator.class);
			EventsManager eventsManager = getter.get(EventsManager.class);
			RequestUnscheduler requestUnscheduler = getter.getModal(RequestUnscheduler.class);
			MobsimTimer mobsimTimer = getter.get(MobsimTimer.class);

			RoutingModule walkRouter = getter.getNamed(RoutingModule.class, TransportMode.walk);

			Map<DvrpRoutingModuleProvider.Stage, RoutingModule> stageRouters = getter.getModal(new TypeLiteral<Map<DvrpRoutingModuleProvider.Stage, RoutingModule>>() {});
			RoutingModule mainRouter = Objects.requireNonNull(stageRouters.get(DvrpRoutingModuleProvider.Stage.MAIN),
					"Main mode router must be explicitly bound");
			RoutingModule accessRouter = stageRouters.getOrDefault(DvrpRoutingModuleProvider.Stage.ACCESS, walkRouter);
			RoutingModule egressRouter = stageRouters.getOrDefault(DvrpRoutingModuleProvider.Stage.EGRESS, walkRouter);

			DynamicAccessEgressHandler dynamicAccessEgressHandler = new DynamicAccessEgressHandler(getMode(), getter.get(ActivityFacilities.class), getter.get(Population.class),
					accessRouter, egressRouter, network);

			return new PrebookingManager(getMode(), network, requestCreator, optimizer, mobsimTimer, requestValidator,
					eventsManager, requestUnscheduler, prebookingParams.isAbortRejectedPrebookings(), dynamicAccessEgressHandler);
		})).in(Singleton.class);
		addModalQSimComponentBinding().to(modalKey(PrebookingManager.class));

		bindModal(PrebookingQueue.class).toProvider(modalProvider(getter -> {
			return new PrebookingQueue(getter.getModal(PrebookingManager.class), getter.getModal(PassengerGroupIdentifier.class));
		})).in(Singleton.class);
		addModalQSimComponentBinding().to(modalKey(PrebookingQueue.class));

		bindModal(PopulationIteratorFactory.class).toProvider(modalProvider(getter -> {
			return new PopulationIteratorFactory(getter.get(Population.class), getter.get(QSim.class));
		}));

		bindModal(MaximumDelayAbandonVoter.class).toProvider(modalProvider(getter -> {
			double maximumDelay = prebookingParams.getMaximumPassengerDelay();
			return new MaximumDelayAbandonVoter(maximumDelay);
		})).in(Singleton.class);
		bindModal(AbandonVoter.class).to(modalKey(MaximumDelayAbandonVoter.class));

		bindModal(SimpleRequestUnscheduler.class).toProvider(modalProvider(getter -> {
			DvrpVehicleLookup vehicleLookup = getter.get(DvrpVehicleLookup.class);
			return new SimpleRequestUnscheduler(vehicleLookup);
		})).in(Singleton.class);

		bindModal(ComplexRequestUnscheduler.class).toProvider(modalProvider(getter -> {
			DvrpVehicleLookup vehicleLookup = getter.get(DvrpVehicleLookup.class);
			VehicleEntry.EntryFactory entryFactory = getter.getModal(VehicleEntry.EntryFactory.class);
			DrtTaskFactory taskFactory = getter.getModal(DrtTaskFactory.class);
			LeastCostPathCalculator router = getter.getModal(LeastCostPathCalculator.class);
			TravelTime travelTime = getter.getModal(TravelTime.class);
			ScheduleTimingUpdater timingUpdater = getter.getModal(ScheduleTimingUpdater.class);

			return new ComplexRequestUnscheduler(vehicleLookup, entryFactory, taskFactory, router, travelTime,
					timingUpdater, prebookingParams.isScheduleWaitBeforeDrive());
		})).in(Singleton.class);

		switch (prebookingParams.getUnschedulingMode()) {
		case StopBased:
			bindModal(RequestUnscheduler.class).to(modalKey(SimpleRequestUnscheduler.class));
			break;
		case Routing:
			bindModal(LeastCostPathCalculator.class).toProvider(modalProvider(getter ->
					new SpeedyALTFactory().createPathCalculator(getter.getModal(Network.class),
                    new TimeAsTravelDisutility(getter.getModal(TravelTime.class)), getter.getModal(TravelTime.class)
            )));
			bindModal(RequestUnscheduler.class).to(modalKey(ComplexRequestUnscheduler.class));
			break;
		default:
			throw new IllegalStateException("No binding for selected RequestUnscheduler");
		}
	}
}
