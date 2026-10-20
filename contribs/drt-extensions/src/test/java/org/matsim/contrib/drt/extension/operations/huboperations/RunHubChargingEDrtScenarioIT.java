/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.huboperations;

import com.google.inject.Key;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.Event;
import org.matsim.contrib.common.zones.systems.grid.square.SquareGridZoneSystemParams;
import org.matsim.contrib.drt.extension.DrtWithExtensionsConfigGroup;
import org.matsim.contrib.drt.extension.operations.DrtOperationsParams;
import org.matsim.contrib.drt.extension.operations.EDrtOperationsControlerCreator;
import org.matsim.contrib.drt.extension.operations.huboperations.config.HubChargingSlackParams;
import org.matsim.contrib.drt.extension.operations.huboperations.config.HubOperationsParams;
import org.matsim.contrib.drt.extension.operations.huboperations.events.CleaningDueEvent;
import org.matsim.contrib.drt.extension.operations.huboperations.events.CleaningEndedEvent;
import org.matsim.contrib.drt.extension.operations.huboperations.events.CleaningQueuedEvent;
import org.matsim.contrib.drt.extension.operations.huboperations.events.CleaningStartedEvent;
import org.matsim.contrib.drt.extension.operations.huboperations.events.CleaningWithdrawnEvent;
import org.matsim.contrib.drt.extension.operations.huboperations.events.HubChargingPluggedEvent;
import org.matsim.contrib.drt.extension.operations.huboperations.events.HubChargingUnpluggedEvent;
import org.matsim.contrib.drt.extension.operations.operationFacilities.OperationFacilitiesParams;
import org.matsim.contrib.drt.extension.operations.shifts.config.ShiftsParams;
import org.matsim.contrib.drt.extension.operations.shifts.events.DrtShiftAssignedEvent;
import org.matsim.contrib.drt.optimizer.constraints.DrtOptimizationConstraintsSetImpl;
import org.matsim.contrib.drt.optimizer.insertion.extensive.ExtensiveInsertionSearchParams;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.passenger.PassengerRequestScheduledEvent;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.dvrp.vrpagent.TaskStartedEvent;
import org.matsim.contrib.dvrp.run.DvrpModes;
import org.matsim.contrib.ev.EvConfigGroup;
import org.matsim.contrib.ev.charging.ChargeUpToMaxSocStrategy;
import org.matsim.contrib.ev.charging.ChargingEndEvent;
import org.matsim.contrib.ev.charging.ChargingLogic;
import org.matsim.contrib.ev.charging.ChargingPower;
import org.matsim.contrib.ev.charging.ChargingStartEvent;
import org.matsim.contrib.ev.charging.ChargingStrategy;
import org.matsim.contrib.ev.charging.ChargingWithQueueingAndAssignmentLogic;
import org.matsim.contrib.ev.charging.FastThenSlowCharging;
import org.matsim.contrib.ev.temperature.TemperatureService;
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
import org.matsim.core.mobsim.qsim.AbstractQSimModule;
import org.matsim.examples.ExamplesUtils;
import org.matsim.vehicles.Vehicle;

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
 * Integration test for hub charging by hub workers on the electric Holzkirchen fleet (30 and 77 kWh batteries, one hub
 * with 5 fast and 20 slow plugs), with interior and exterior cleaning bundled in.
 * <p>
 * The test checks the rules the module promises. The ev module only starts charging a vehicle a worker has plugged in.
 * The vehicle stays on the plug after it has reached its target until a worker unplugs it, and it leaves the hub only
 * after it is unplugged and cleaned. A worker is never busy with two cleanings at once. No rider is assigned to a vehicle
 * on a hub visit, and no shift is assigned to one either.
 *
 * @author lpfeil / MOIA
 */
public class RunHubChargingEDrtScenarioIT {

	private static final double MAX_RELATIVE_SOC = 0.9;
	private static final double TEMPERATURE = 20;
	private static final double CHARGE_SOC_THRESHOLD = 0.8;
	private static final double WORKER_START = 4 * 3600.;
	private static final double WORKER_END = 22 * 3600.;

	@Test
	void workersPlugAndUnplugAndVehiclesLeaveOnlyWhenDone() {
		String outputDirectory = "test/output/RunHubChargingEDrtScenarioIT";
		Path roster = writeRoster(Path.of(outputDirectory + "_input"));
		VisitTracker tracker = runScenario(outputDirectory, roster.toAbsolutePath().toString(),
				"holzkirchenElectricFleet.xml");

		assertThat(tracker.chargingDue).as("vehicles due for charging").isPositive();
		assertThat(tracker.plugged).as("vehicles plugged in by a worker").isPositive();
		assertThat(tracker.unpluggedByWorker).as("vehicles unplugged by a worker").isPositive();
		assertThat(tracker.chargingStartedWithoutPlug).as("ev charging without a hub plug-in").isZero();
		assertThat(tracker.chargingEndedBeforeUnplug).as("charge ended other than by an unplug").isZero();
		assertThat(tracker.minWaitForUnplug).as("charged vehicles wait for their unplug").isGreaterThanOrEqualTo(0);
		assertThat(tracker.endedWhilePlugged).as("visits that ended with the vehicle still plugged in").isZero();
		assertThat(tracker.endedBeforeCleaning).as("visits that ended before their cleaning time").isZero();
		assertThat(tracker.workerDoubleBooked).as("worker cleaning two vehicles at once").isZero();
		assertThat(tracker.ridersAssignedOnVisit).as("riders assigned to a vehicle on a hub visit").isZero();
		assertThat(tracker.shiftsAssignedOnVisit).as("shifts assigned to a vehicle on a hub visit").isZero();
		assertThat(tracker.bundledWithCharging).as("visits that charge and clean").isPositive();
		assertThat(tracker.energyKWh).as("energy charged").isPositive();
	}

	/**
	 * The fleet starts with SOC 0.5, below the charging threshold, so most vehicles are due while they still wait at
	 * the hub for their first shift. Their visit runs on the wait task, and no shift may be assigned to them until they
	 * are charged and unplugged.
	 */
	@Test
	void parkedVehiclesAreChargedBeforeTheyGetAShift() {
		String outputDirectory = "test/output/RunHubChargingEDrtScenarioIT_parked";
		Path input = Path.of(outputDirectory + "_input");
		Path roster = writeRoster(input);
		Path fleet = writeLowSocFleet(input, 0.5);
		VisitTracker tracker = runScenario(outputDirectory, roster.toAbsolutePath().toString(),
				fleet.toUri().toString());

		assertThat(tracker.parkedVisits).as("visits of vehicles parked at the hub").isPositive();
		assertThat(tracker.plugged).isPositive();
		assertThat(tracker.unpluggedByWorker).isPositive();
		assertThat(tracker.chargingStartedWithoutPlug).isZero();
		assertThat(tracker.chargingEndedBeforeUnplug).isZero();
		assertThat(tracker.endedWhilePlugged).isZero();
		assertThat(tracker.workerDoubleBooked).isZero();
		assertThat(tracker.ridersAssignedOnVisit).isZero();
		assertThat(tracker.shiftsAssignedOnVisit).as("shifts assigned to a vehicle on a hub visit").isZero();
	}

	/**
	 * The fleet-slack aware policy: the fleet starts at SOC 0.65, just above socOpportunity, and opportunistic charges
	 * are only due while the fleet has slack. With tight slack thresholds the morning peak makes the fleet tight, so
	 * charging vehicles are unplugged early, each by a worker. The early unplugs never go below socRelease and never
	 * before the minimum charging time.
	 */
	@Test
	void fleetSlackPolicyChargesWithSlackAndReleasesWhenTight() {
		String outputDirectory = "test/output/RunHubChargingEDrtScenarioIT_slack";
		Path input = Path.of(outputDirectory + "_input");
		Path roster = writeRoster(input);
		Path fleet = writeLowSocFleet(input, 0.65);
		HubChargingSlackParams slack = new HubChargingSlackParams();
		slack.setSocCritical(0.3);
		slack.setSocOpportunity(0.6);
		slack.setSocRelease(0.62);
		slack.setMinChargeSecondsBeforeRelease(300);
		slack.setSlackLow(0.4);
		slack.setSlackHigh(0.6);
		slack.setMinDwellSeconds(300);
		VisitTracker tracker = runScenario(outputDirectory, roster.toAbsolutePath().toString(),
				fleet.toUri().toString(), slack);

		assertThat(tracker.chargingDue).as("vehicles due for charging").isPositive();
		assertThat(tracker.plugged).isPositive();
		assertThat(tracker.earlyUnplugs).as("vehicles unplugged early while the fleet was tight").isPositive();
		assertThat(tracker.earlyUnplugsWithoutWorker).as("early unplugs without a worker").isZero();
		assertThat(tracker.minSocAtEarlyUnplug).as("early unplug only from socRelease on")
				.isGreaterThanOrEqualTo(slack.getSocRelease());
		assertThat(tracker.minPluggedBeforeEarlyUnplug).as("early unplug only after the minimum charging time")
				.isGreaterThanOrEqualTo(slack.getMinChargeSecondsBeforeRelease());
		assertThat(tracker.chargingStartedWithoutPlug).isZero();
		assertThat(tracker.chargingEndedBeforeUnplug).isZero();
		assertThat(tracker.endedWhilePlugged).isZero();
		assertThat(tracker.workerDoubleBooked).isZero();
		assertThat(tracker.ridersAssignedOnVisit).isZero();
		assertThat(tracker.shiftsAssignedOnVisit).isZero();
	}

	/** Copies the example electric fleet with every initial SOC set to {@code soc}. */
	private static Path writeLowSocFleet(Path directory, double soc) {
		try (var in = org.matsim.core.utils.io.IOUtils.extendUrl(ExamplesUtils.getTestScenarioURL("holzkirchen"),
				"holzkirchenElectricFleet.xml").openStream()) {
			String fleet = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
			fleet = fleet.replace("initialSoc\" class=\"java.lang.Double\">1.0<",
					"initialSoc\" class=\"java.lang.Double\">" + soc + "<");
			Files.createDirectories(directory);
			Path file = directory.resolve("lowSocElectricFleet.xml");
			Files.writeString(file, fleet);
			return file;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static Path writeRoster(Path directory) {
		try {
			Files.createDirectories(directory);
			Path file = directory.resolve("hubWorkers.xml");
			Files.writeString(file, """
					<?xml version="1.0" encoding="UTF-8"?>
					<hubWorkers>
						<worker id="w0" hub="1" start="%s" end="%s"/>
						<worker id="w1" hub="1" start="%s" end="%s"/>
					</hubWorkers>
					""".formatted(WORKER_START, WORKER_END, WORKER_START, WORKER_END));
			return file;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private VisitTracker runScenario(String outputDirectory, String rosterFile, String electricFleetFile) {
		return runScenario(outputDirectory, rosterFile, electricFleetFile, null);
	}

	private VisitTracker runScenario(String outputDirectory, String rosterFile, String electricFleetFile,
									 HubChargingSlackParams slack) {
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
		shiftsParams.setShiftInputFile("holzkirchenShifts.xml");
		shiftsParams.setAllowInFieldChangeover(true);
		// hub workers own all charging; the shift stack must not charge on its own
		shiftsParams.setChargeAtHubThreshold(0.);
		shiftsParams.setChargeDuringBreakThreshold(0.);
		shiftsParams.setShiftAssignmentBatteryThreshold(0.);
		operationsParams.addParameterSet(shiftsParams);

		HubOperationsParams hubParams = (HubOperationsParams) operationsParams
				.createParameterSet(HubOperationsParams.SET_NAME);
		hubParams.setSoilingThresholdPkm(20.);
		hubParams.setExteriorSoilingThresholdVkm(30.);
		hubParams.setChargeSocThreshold(CHARGE_SOC_THRESHOLD);
		hubParams.setChargerType("fast");
		hubParams.setBundlingShare(0.8);
		if (slack != null) {
			hubParams.addParameterSet(slack);
		}
		hubParams.setHubWorkerInputFile(rosterFile);
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

		EvConfigGroup evConfigGroup = new EvConfigGroup();
		evConfigGroup.setChargersFile("holzkirchenChargers.xml");
		evConfigGroup.setMinimumChargeTime(0);
		config.addModule(evConfigGroup);
		config.vehicles().setVehiclesFile(electricFleetFile);

		Controler controler = EDrtOperationsControlerCreator.createControler(config, false);
		controler.addOverridingQSimModule(new AbstractQSimModule() {
			@Override
			protected void configureQSim() {
				bind(ChargingLogic.Factory.class).to(ChargingWithQueueingAndAssignmentLogic.Factory.class);
			}
		});
		VisitTracker tracker = new VisitTracker(hubParams);
		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				bind(Key.get(ChargingStrategy.Factory.class, DvrpModes.mode(TransportMode.drt)))
						.toInstance(new ChargeUpToMaxSocStrategy.Factory(MAX_RELATIVE_SOC));
				bind(ChargingPower.Factory.class).toInstance(ev -> new FastThenSlowCharging(ev.getBattery()));
				bind(TemperatureService.class).toInstance(linkId -> TEMPERATURE);
				addEventHandlerBinding().toInstance(tracker);
			}
		});
		controler.run();
		return tracker;
	}

	/**
	 * Rebuilds plug-ins, unplugs, worker occupation and the visit windows from the events. The ev charging events carry
	 * the mobsim vehicle id, whose string equals the fleet vehicle id.
	 */
	private static final class VisitTracker implements BasicEventHandler {
		private final HubOperationsParams params;

		private final Set<String> pluggedVehicles = new HashSet<>();
		private final Set<String> evCharging = new HashSet<>();
		private final Set<String> onVisit = new HashSet<>();
		private final Map<String, Double> visitStart = new HashMap<>();
		private final Map<String, Double> visitWorkerTime = new HashMap<>();
		private final Map<String, Double> workerBusyUntil = new HashMap<>();

		private int chargingDue = 0;
		private int plugged = 0;
		private int unpluggedByWorker = 0;
		private int chargingStartedWithoutPlug = 0;
		private int chargingEndedBeforeUnplug = 0;
		private double minWaitForUnplug = Double.POSITIVE_INFINITY;
		private int endedWhilePlugged = 0;
		private int endedBeforeCleaning = 0;
		private int workerDoubleBooked = 0;
		private int ridersAssignedOnVisit = 0;
		private int shiftsAssignedOnVisit = 0;
		private int bundledWithCharging = 0;
		private int parkedVisits = 0;
		private int earlyUnplugs = 0;
		private int withdrawnCount = 0;
		private final Map<String, Set<CleaningType>> dueTypes = new HashMap<>();
		private int earlyUnplugsWithoutWorker = 0;
		private double minSocAtEarlyUnplug = Double.POSITIVE_INFINITY;
		private double minPluggedBeforeEarlyUnplug = Double.POSITIVE_INFINITY;
		private final Map<String, Double> pluggedAt = new HashMap<>();
		private final Map<String, String> currentTask = new HashMap<>();
		private double energyKWh = 0;

		private VisitTracker(HubOperationsParams params) {
			this.params = params;
		}

		@Override
		public void handleEvent(Event event) {
			if (event instanceof TaskStartedEvent taskStarted) {
				currentTask.put(taskStarted.getDvrpVehicleId().toString(), taskStarted.getTaskType().name());
			} else if (event instanceof CleaningQueuedEvent queued) {
				if ("WAIT_FOR_SHIFT".equals(currentTask.get(queued.getVehicleId().toString()))) {
					parkedVisits++;
				}
			} else if (event instanceof CleaningDueEvent due) {
				if (due.getCleaningType() == CleaningType.charging) {
					chargingDue++;
				}
				onVisit.add(due.getVehicleId().toString());
				dueTypes.computeIfAbsent(due.getVehicleId().toString(), k -> java.util.EnumSet.noneOf(CleaningType.class))
						.add(due.getCleaningType());
			} else if (event instanceof CleaningStartedEvent started) {
				String v = started.getVehicleId().toString();
				visitStart.put(v, event.getTime());
				visitWorkerTime.put(v, started.getWorkerTime());
				if (started.getWorkerId() != null && started.getWorkerTime() > 0) {
					String w = started.getWorkerId().toString();
					if (workerBusyUntil.getOrDefault(w, Double.NEGATIVE_INFINITY) > event.getTime()) {
						workerDoubleBooked++;
					}
					workerBusyUntil.put(w, event.getTime() + started.getWorkerTime());
				}
				if (started.getCleaningTypes().contains(CleaningType.charging) && started.getCleaningTypes().size() > 1) {
					bundledWithCharging++;
				}
			} else if (event instanceof HubChargingPluggedEvent plug) {
				plugged++;
				pluggedVehicles.add(plug.getVehicleId().toString());
			} else if (event instanceof HubChargingUnpluggedEvent unplug && unplug.isEarly()) {
				earlyUnplugs++;
				if (unplug.getWorkerId() == null) {
					earlyUnplugsWithoutWorker++;
				}
				minSocAtEarlyUnplug = Math.min(minSocAtEarlyUnplug, unplug.getSoc());
				Double at = pluggedAt.get(unplug.getVehicleId().toString());
				if (at != null) {
					minPluggedBeforeEarlyUnplug = Math.min(minPluggedBeforeEarlyUnplug, event.getTime() - at);
				}
				pluggedVehicles.remove(unplug.getVehicleId().toString());
			} else if (event instanceof HubChargingUnpluggedEvent unplug) {
				String v = unplug.getVehicleId().toString();
				pluggedVehicles.remove(v);
				evCharging.remove(v);
				if (unplug.getWorkerId() != null) {
					unpluggedByWorker++;
					if (!Double.isNaN(unplug.getWaitForUnplug())) {
						minWaitForUnplug = Math.min(minWaitForUnplug, unplug.getWaitForUnplug());
					}
				}
				energyKWh += org.matsim.contrib.ev.EvUnits.J_to_kWh(unplug.getEnergy());
			} else if (event instanceof ChargingStartEvent start) {
				String v = start.getVehicleId().toString();
				pluggedAt.put(v, event.getTime());
				if (!pluggedVehicles.contains(v)) {
					chargingStartedWithoutPlug++;
				}
				evCharging.add(v);
			} else if (event instanceof ChargingEndEvent end) {
				// the ev logic fires its end event inside removeVehicle, i.e. just before our unplug event
				String v = end.getVehicleId().toString();
				if (!pluggedVehicles.contains(v)) {
					chargingEndedBeforeUnplug++;
				}
			} else if (event instanceof CleaningEndedEvent ended) {
				String v = ended.getVehicleId().toString();
				if (pluggedVehicles.contains(v)) {
					endedWhilePlugged++;
				}
				Double start = visitStart.remove(v);
				Double workerTime = visitWorkerTime.remove(v);
				if (start != null && workerTime != null && !Double.isNaN(workerTime)
						&& event.getTime() < start + workerTime) {
					endedBeforeCleaning++;
				}
				onVisit.remove(v);
				dueTypes.remove(v);
			} else if (event instanceof CleaningWithdrawnEvent withdrawn) {
				// an opportunistic charge withdrawn before the visit: if nothing else is due, the vehicle is back in
				// service and may take riders again
				String v = withdrawn.getVehicleId().toString();
				Set<CleaningType> types = dueTypes.get(v);
				if (types != null) {
					types.remove(withdrawn.getCleaningType());
					if (types.isEmpty()) {
						onVisit.remove(v);
					}
				}
				withdrawnCount++;
			} else if (event instanceof PassengerRequestScheduledEvent scheduled) {
				if (onVisit.contains(scheduled.getVehicleId().toString())) {
					ridersAssignedOnVisit++;
				}
			} else if (event instanceof DrtShiftAssignedEvent assigned) {
				if (onVisit.contains(assigned.getAttributes().get(DrtShiftAssignedEvent.ATTRIBUTE_VEHICLE_ID))) {
					shiftsAssignedOnVisit++;
				}
			}
		}

		@Override
		public void reset(int iteration) {
			pluggedVehicles.clear();
			evCharging.clear();
			onVisit.clear();
		}
	}
}
