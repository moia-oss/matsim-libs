/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.huboperations;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.Event;
import org.matsim.contrib.common.zones.systems.grid.square.SquareGridZoneSystemParams;
import org.matsim.contrib.drt.extension.DrtWithExtensionsConfigGroup;
import org.matsim.contrib.drt.extension.operations.DrtOperationsControlerCreator;
import org.matsim.contrib.drt.extension.operations.DrtOperationsParams;
import org.matsim.contrib.drt.extension.operations.huboperations.config.HubOperationsParams;
import org.matsim.contrib.drt.extension.operations.huboperations.events.CleaningAbandonedEvent;
import org.matsim.contrib.drt.extension.operations.huboperations.events.CleaningDueEvent;
import org.matsim.contrib.drt.extension.operations.huboperations.events.CleaningEndedEvent;
import org.matsim.contrib.drt.extension.operations.huboperations.events.CleaningQueuedEvent;
import org.matsim.contrib.drt.extension.operations.huboperations.events.CleaningStartedEvent;
import org.matsim.contrib.drt.extension.operations.operationFacilities.OperationFacilitiesParams;
import org.matsim.contrib.drt.extension.operations.remoteoperations.config.RemoteGuidanceParams;
import org.matsim.contrib.drt.extension.operations.shifts.config.ShiftsParams;
import org.matsim.contrib.drt.optimizer.constraints.DrtOptimizationConstraintsSetImpl;
import org.matsim.contrib.drt.optimizer.insertion.extensive.ExtensiveInsertionSearchParams;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.passenger.PassengerDroppedOffEvent;
import org.matsim.contrib.dvrp.passenger.PassengerPickedUpEvent;
import org.matsim.contrib.dvrp.passenger.PassengerRequestScheduledEvent;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.zone.skims.DvrpTravelTimeMatrixParams;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.examples.ExamplesUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for interior cleaning on top of remote guidance. The Holzkirchen fleet runs under four remote
 * guidance operators with a low soiling threshold, so most vehicles go through several cleaning cycles in one day.
 * <p>
 * The test checks the rules the module promises. A vehicle becomes due only once it has carried the threshold in
 * passenger kilometres. It is never cleaned with a rider on board. Each due event is followed by exactly one cleaning,
 * which lasts the configured duration. No rider boards a vehicle between its due event and the end of its cleaning.
 * <p>
 * Both cleaning types are on: interior every 20 pkm, sensors and exterior every 30 vkm, bundled from 80 percent of a
 * threshold. Each hub visit lasts the sum of the durations of the types it cleans.
 * <p>
 * A second run adds a roster with a single hub worker on a limited window. Then cleanings queue at the hub, never more
 * than one runs at a time, none starts outside the worker's window, and each still lasts the configured duration.
 *
 * @author lpfeil / MOIA
 */
public class RunHubCleaningDrtScenarioIT {

	private static final double THRESHOLD_PKM = 20.;
	private static final double CLEANING_DURATION = 1200.;
	private static final double EXTERIOR_THRESHOLD_VKM = 30.;
	private static final double EXTERIOR_DURATION = 600.;
	private static final double BUNDLING_SHARE = 0.8;

	private static final double WORKER_START = 6 * 3600.;
	private static final double WORKER_END = 18 * 3600.;

	@Test
	void cleaningCycleRespectsTheRules() {
		CleaningTracker tracker = runScenario("test/output/RunHubCleaningDrtScenarioIT", null);
		assertThat(tracker.queued).as("no queue without hub workers").isZero();
		assertThat(tracker.abandoned).isZero();

		assertThat(tracker.due).as("vehicles became due for cleaning").isPositive();
		assertThat(tracker.started).as("every cleaning that started also ended").isEqualTo(tracker.ended);
		assertThat(tracker.ended).as("cleanings completed").isPositive();
		// a due vehicle is cleaned once, or is still finishing its riders or driving to the hub at the end of the run
		assertThat(tracker.started).isLessThanOrEqualTo(tracker.due);
		assertThat(tracker.due - tracker.started).as("due vehicles left uncleaned at the end").isLessThanOrEqualTo(20);

		assertThat(tracker.minDuePassengerKm).as("due only after the threshold").isGreaterThanOrEqualTo(THRESHOLD_PKM);
		assertThat(tracker.minDueVehicleKm).as("due only after the threshold")
				.isGreaterThanOrEqualTo(EXTERIOR_THRESHOLD_VKM);
		assertThat(tracker.cleanedWithRiderOnBoard).as("cleaning with a rider on board").isZero();
		assertThat(tracker.boardingWhileDue).as("riders boarding a due vehicle").isZero();
		assertThat(tracker.durationMismatches).as("visits whose duration is not the sum of their types").isZero();
		assertThat(tracker.interiorOnly).as("interior only visits").isPositive();
		assertThat(tracker.exteriorOnly).as("exterior only visits").isPositive();
		assertThat(tracker.bundled).as("visits that clean both").isPositive();
	}

	@Test
	void scarceWorkersQueueVehicles() {
		String outputDirectory = "test/output/RunHubCleaningDrtScenarioIT_workers";
		Path rosterFile = writeRoster(Path.of(outputDirectory + "_input"));
		CleaningTracker tracker = runScenario(outputDirectory, rosterFile.toAbsolutePath().toString());

		assertThat(tracker.queued).as("vehicles queued for the worker").isPositive();
		assertThat(tracker.started).as("cleanings done by the worker").isPositive();
		assertThat(tracker.started + tracker.abandoned).as("every queued vehicle is cleaned or gives up")
				.isLessThanOrEqualTo(tracker.queued);
		assertThat(tracker.maxConcurrent).as("cleanings at the same time").isEqualTo(1);
		assertThat(tracker.maxQueueDelay).as("some vehicle waited for the worker").isPositive();
		assertThat(tracker.minStartTime).isGreaterThanOrEqualTo(WORKER_START);
		assertThat(tracker.maxStartTime).isLessThan(WORKER_END);
		assertThat(tracker.durationMismatches).isZero();
		assertThat(tracker.cleanedWithRiderOnBoard).isZero();
		assertThat(tracker.boardingWhileDue).isZero();
	}

	private static final double MAINTENANCE_THRESHOLD_VKM = 40.;
	private static final double MAINTENANCE_DURATION = 1800.;

	@Test
	void maintenanceIsDoneByTechniciansOnSeparateVisits() {
		String outputDirectory = "test/output/RunHubCleaningDrtScenarioIT_maintenance";
		Path rosterFile = writeRoster(Path.of(outputDirectory + "_input"),
				"<worker id=\"m0\" hub=\"1\" start=\"%s\" end=\"%s\" pool=\"maintenance\"/>"
						.formatted(WORKER_START, WORKER_END));
		MaintenanceTracker maintenance = new MaintenanceTracker();
		CleaningTracker tracker = runScenario(outputDirectory, rosterFile.toAbsolutePath().toString(),
				MAINTENANCE_THRESHOLD_VKM, maintenance);

		assertThat(maintenance.due).as("vehicles due for maintenance").isPositive();
		assertThat(maintenance.minDueVehicleKm).isGreaterThanOrEqualTo(MAINTENANCE_THRESHOLD_VKM);
		assertThat(maintenance.started).as("maintenance visits").isPositive();
		assertThat(maintenance.mixedVisits).as("visits mixing maintenance with another type").isZero();
		assertThat(maintenance.wrongWorker).as("visits served by the other pool").isZero();
		assertThat(maintenance.durationMismatches).isZero();
		assertThat(maintenance.maxConcurrent).as("one technician").isEqualTo(1);
		assertThat(maintenance.cleaningsStarted).as("the crew still cleans").isPositive();
		assertThat(tracker.cleanedWithRiderOnBoard).isZero();
		assertThat(tracker.boardingWhileDue).isZero();
	}

	private static Path writeRoster(Path directory) {
		return writeRoster(directory, "");
	}

	private static Path writeRoster(Path directory, String extraWorkers) {
		try {
			Files.createDirectories(directory);
			Path file = directory.resolve("hubWorkers.xml");
			Files.writeString(file, """
					<?xml version="1.0" encoding="UTF-8"?>
					<hubWorkers>
						<worker id="w0" hub="1" start="%s" end="%s"/>
						%s
					</hubWorkers>
					""".formatted(WORKER_START, WORKER_END, extraWorkers));
			return file;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private CleaningTracker runScenario(String outputDirectory, String hubWorkerFile) {
		return runScenario(outputDirectory, hubWorkerFile, Double.POSITIVE_INFINITY, null);
	}

	private CleaningTracker runScenario(String outputDirectory, String hubWorkerFile, double maintenanceThresholdVkm,
										BasicEventHandler extraHandler) {
		MultiModeDrtConfigGroup multiModeDrtConfigGroup = new MultiModeDrtConfigGroup(DrtWithExtensionsConfigGroup::new);
		DrtWithExtensionsConfigGroup drtCfg =
				(DrtWithExtensionsConfigGroup) multiModeDrtConfigGroup.createParameterSet("drt");

		drtCfg.setMode(TransportMode.drt);
		drtCfg.setStopDuration(30.);
		drtCfg.setUseModeFilteredSubnetwork(false);
		drtCfg.setVehiclesFile("holzkirchenFleet.xml");
		drtCfg.setOperationalScheme(DrtConfigGroup.OperationalScheme.door2door);
		drtCfg.setIdleVehiclesReturnToDepots(false);
		DrtOptimizationConstraintsSetImpl constraints =
				drtCfg.addOrGetDrtOptimizationConstraintsParams().addOrGetDefaultDrtOptimizationConstraintsSet();
		constraints.setMaxTravelTimeAlpha(1.5);
		constraints.setMaxTravelTimeBeta(10. * 60.);
		constraints.setMaxWaitTime(600.);
		constraints.setRejectRequestIfMaxWaitOrTravelTimeViolated(true);
		constraints.setMaxWalkDistance(1000.);
		drtCfg.addParameterSet(new ExtensiveInsertionSearchParams());

		DrtOperationsParams operationsParams =
				(DrtOperationsParams) drtCfg.createParameterSet(DrtOperationsParams.SET_NAME);
		OperationFacilitiesParams facilitiesParams = (OperationFacilitiesParams) operationsParams
				.createParameterSet(OperationFacilitiesParams.SET_NAME);
		facilitiesParams.setOperationFacilityInputFile("holzkirchenOperationFacilities.xml");
		operationsParams.addParameterSet(facilitiesParams);
		ShiftsParams shiftsParams = (ShiftsParams) operationsParams.createParameterSet(ShiftsParams.SET_NAME);
		shiftsParams.setShiftInputFile("holzkirchenRemoteGuidanceShifts.xml");
		shiftsParams.setAllowInFieldChangeover(true);
		operationsParams.addParameterSet(shiftsParams);
		RemoteGuidanceParams remoteGuidanceParams = (RemoteGuidanceParams) operationsParams
				.createParameterSet(RemoteGuidanceParams.SET_NAME);
		remoteGuidanceParams.setOperatorShiftType("remoteGuidance");
		remoteGuidanceParams.setDefaultOperatorCapacity(5);
		remoteGuidanceParams.setMinRemainingShiftTimeForActivation(0);
		operationsParams.addParameterSet(remoteGuidanceParams);

		HubOperationsParams hubParams = (HubOperationsParams) operationsParams
				.createParameterSet(HubOperationsParams.SET_NAME);
		hubParams.setSoilingThresholdPkm(THRESHOLD_PKM);
		hubParams.setCleaningDuration(CLEANING_DURATION);
		hubParams.setExteriorSoilingThresholdVkm(EXTERIOR_THRESHOLD_VKM);
		hubParams.setExteriorCleaningDuration(EXTERIOR_DURATION);
		hubParams.setBundlingShare(BUNDLING_SHARE);
		hubParams.setHubWorkerInputFile(hubWorkerFile);
		hubParams.setMaintenanceThresholdVkm(maintenanceThresholdVkm);
		hubParams.setMaintenanceDuration(MAINTENANCE_DURATION);
		operationsParams.addParameterSet(hubParams);
		drtCfg.addParameterSet(operationsParams);

		multiModeDrtConfigGroup.addParameterSet(drtCfg);

		DvrpConfigGroup dvrpConfigGroup = new DvrpConfigGroup();
		DvrpTravelTimeMatrixParams matrixParams = dvrpConfigGroup.getTravelTimeMatrixParams();
		matrixParams.addParameterSet(matrixParams.createParameterSet(SquareGridZoneSystemParams.SET_NAME));

		Config config = ConfigUtils.createConfig(multiModeDrtConfigGroup, dvrpConfigGroup);
		config.setContext(ExamplesUtils.getTestScenarioURL("holzkirchen"));
		config.plans().setInputFile("holzkirchenPlans.xml.gz");
		config.network().setInputFile("holzkirchenNetwork.xml.gz");
		config.travelTimeCalculator().setAnalyzedModes(Set.of(TransportMode.drt));
		config.scoring().addModeParams(new ScoringConfigGroup.ModeParams(TransportMode.drt));
		config.scoring().addModeParams(new ScoringConfigGroup.ModeParams(TransportMode.walk));
		for (String type : new String[]{"home", "other", "education", "shopping", "work"}) {
			ScoringConfigGroup.ActivityParams activity = new ScoringConfigGroup.ActivityParams(type);
			activity.setTypicalDuration(4 * 3600);
			config.scoring().addActivityParams(activity);
		}
		config.qsim().setSimStarttimeInterpretation(QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime);
		config.qsim().setSimEndtimeInterpretation(QSimConfigGroup.EndtimeInterpretation.minOfEndtimeAndMobsimFinished);
		ReplanningConfigGroup.StrategySettings strategy = new ReplanningConfigGroup.StrategySettings();
		strategy.setWeight(1);
		strategy.setStrategyName("ChangeExpBeta");
		config.replanning().addStrategySettings(strategy);
		config.controller().setLastIteration(0);
		config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setOutputDirectory(outputDirectory);

		Controler controler = DrtOperationsControlerCreator.createControler(config, false);
		CleaningTracker tracker = new CleaningTracker();
		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				addEventHandlerBinding().toInstance(tracker);
				if (extraHandler != null) {
					addEventHandlerBinding().toInstance(extraHandler);
				}
			}
		});
		controler.run();
		return tracker;
	}

	/**
	 * Rebuilds the cleaning lifecycle and the riders on board from the event stream. A request scheduled to a vehicle
	 * between its due event and the end of its cleaning breaks the rule that a due vehicle takes no new riders. Riders
	 * scheduled before the due event may still be picked up, because the vehicle finishes them first.
	 */
	private static final class CleaningTracker implements BasicEventHandler {
		private final Map<Id<DvrpVehicle>, Integer> onBoard = new HashMap<>();
		private final Set<Id<DvrpVehicle>> dueVehicles = new HashSet<>();
		private final Map<Id<DvrpVehicle>, Double> cleaningStart = new HashMap<>();
		private final Set<Id<DvrpVehicle>> maintenanceVisits = new HashSet<>();

		private int queued = 0;
		private int abandoned = 0;
		private int running = 0;
		private int maxConcurrent = 0;
		private double maxQueueDelay = 0;
		private double minStartTime = Double.POSITIVE_INFINITY;
		private double maxStartTime = Double.NEGATIVE_INFINITY;
		private int due = 0;
		private int started = 0;
		private int ended = 0;
		private int cleanedWithRiderOnBoard = 0;
		private int boardingWhileDue = 0;
		private double minDuePassengerKm = Double.POSITIVE_INFINITY;
		private double minDueVehicleKm = Double.POSITIVE_INFINITY;
		private int durationMismatches = 0;
		private int interiorOnly = 0;
		private int exteriorOnly = 0;
		private int bundled = 0;

		@Override
		public void handleEvent(Event event) {
			if (event instanceof PassengerRequestScheduledEvent scheduled) {
				if (dueVehicles.contains(scheduled.getVehicleId())) {
					boardingWhileDue++;
				}
			} else if (event instanceof PassengerPickedUpEvent pickup && pickup.getVehicleId() != null) {
				onBoard.merge(pickup.getVehicleId(), 1, Integer::sum);
			} else if (event instanceof PassengerDroppedOffEvent dropoff && dropoff.getVehicleId() != null) {
				onBoard.merge(dropoff.getVehicleId(), -1, Integer::sum);
			} else if (event instanceof CleaningDueEvent dueEvent && dueEvent.getCleaningType() == CleaningType.maintenance) {
				// counted by MaintenanceTracker
			} else if (event instanceof CleaningDueEvent dueEvent) {
				// one hub visit per due vehicle, even when both types cross before it is cleaned
				if (dueVehicles.add(dueEvent.getVehicleId())) {
					due++;
				}
				if (dueEvent.getCleaningType() == CleaningType.interior) {
					minDuePassengerKm = Math.min(minDuePassengerKm, dueEvent.getPassengerKm());
				} else {
					minDueVehicleKm = Math.min(minDueVehicleKm, dueEvent.getVehicleKm());
				}
			} else if (event instanceof CleaningQueuedEvent) {
				queued++;
			} else if (event instanceof CleaningAbandonedEvent) {
				abandoned++;
			} else if (event instanceof CleaningStartedEvent startedEvent
					&& startedEvent.getCleaningTypes().contains(CleaningType.maintenance)) {
				maintenanceVisits.add(startedEvent.getVehicleId());
			} else if (event instanceof CleaningEndedEvent endedEvent && maintenanceVisits.remove(endedEvent.getVehicleId())) {
				// a maintenance visit, checked by MaintenanceTracker
			} else if (event instanceof CleaningStartedEvent startedEvent) {
				started++;
				running++;
				maxConcurrent = Math.max(maxConcurrent, running);
				maxQueueDelay = Math.max(maxQueueDelay, startedEvent.getQueueDelay());
				minStartTime = Math.min(minStartTime, event.getTime());
				maxStartTime = Math.max(maxStartTime, event.getTime());
				cleaningStart.put(startedEvent.getVehicleId(), event.getTime());
				if (onBoard.getOrDefault(startedEvent.getVehicleId(), 0) > 0) {
					cleanedWithRiderOnBoard++;
				}
			} else if (event instanceof CleaningEndedEvent endedEvent) {
				ended++;
				running--;
				double duration = event.getTime() - cleaningStart.remove(endedEvent.getVehicleId());
				Set<CleaningType> types = endedEvent.getCleaningTypes();
				double expected = (types.contains(CleaningType.interior) ? CLEANING_DURATION : 0)
						+ (types.contains(CleaningType.exterior) ? EXTERIOR_DURATION : 0);
				if (duration != expected) {
					durationMismatches++;
				}
				if (types.size() == 2) {
					bundled++;
				} else if (types.contains(CleaningType.interior)) {
					interiorOnly++;
				} else {
					exteriorOnly++;
				}
				dueVehicles.remove(endedEvent.getVehicleId());
			}
		}

		@Override
		public void reset(int iteration) {
			onBoard.clear();
			dueVehicles.clear();
			cleaningStart.clear();
			maintenanceVisits.clear();
		}
	}

	/** Checks the maintenance visits: maintenance only, by a technician, one at a time, for the configured duration. */
	private static final class MaintenanceTracker implements BasicEventHandler {
		private final Map<Id<DvrpVehicle>, Double> start = new HashMap<>();
		private int due = 0;
		private double minDueVehicleKm = Double.POSITIVE_INFINITY;
		private int started = 0;
		private int cleaningsStarted = 0;
		private int mixedVisits = 0;
		private int wrongWorker = 0;
		private int durationMismatches = 0;
		private int running = 0;
		private int maxConcurrent = 0;

		@Override
		public void handleEvent(Event event) {
			if (event instanceof CleaningDueEvent dueEvent && dueEvent.getCleaningType() == CleaningType.maintenance) {
				due++;
				minDueVehicleKm = Math.min(minDueVehicleKm, dueEvent.getVehicleKm());
			} else if (event instanceof CleaningStartedEvent startedEvent) {
				Set<CleaningType> types = startedEvent.getCleaningTypes();
				boolean technician = startedEvent.getWorkerId() != null
						&& startedEvent.getWorkerId().toString().startsWith("m");
				if (!types.contains(CleaningType.maintenance)) {
					cleaningsStarted++;
					if (technician) {
						wrongWorker++;
					}
					return;
				}
				started++;
				running++;
				maxConcurrent = Math.max(maxConcurrent, running);
				if (types.size() > 1) {
					mixedVisits++;
				}
				if (!technician) {
					wrongWorker++;
				}
				start.put(startedEvent.getVehicleId(), event.getTime());
			} else if (event instanceof CleaningEndedEvent endedEvent && start.containsKey(endedEvent.getVehicleId())) {
				running--;
				if (event.getTime() - start.remove(endedEvent.getVehicleId()) != MAINTENANCE_DURATION) {
					durationMismatches++;
				}
			}
		}
	}
}
