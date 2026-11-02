/*
 * *********************************************************************** *
 * project: org.matsim.*
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2026 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** *
 */

package org.matsim.contrib.drt.extension.companions;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.handler.PersonArrivalEventHandler;
import org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler;
import org.matsim.api.core.v01.events.handler.PersonStuckEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.contrib.common.zones.systems.grid.square.SquareGridZoneSystemParams;
import org.matsim.contrib.drt.extension.DrtWithExtensionsConfigGroup;
import org.matsim.contrib.drt.optimizer.constraints.DrtOptimizationConstraintsSetImpl;
import org.matsim.contrib.drt.optimizer.insertion.DetourTimeEstimator;
import org.matsim.contrib.drt.optimizer.insertion.selective.SelectiveInsertionSearchParams;
import org.matsim.contrib.drt.passenger.events.DrtRequestSubmittedEvent;
import org.matsim.contrib.drt.passenger.events.DrtRequestSubmittedEventHandler;
import org.matsim.contrib.drt.prebooking.PrebookingParams;
import org.matsim.contrib.drt.prebooking.logic.AttributeBasedPrebookingLogic;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.DrtConfigs;
import org.matsim.contrib.drt.run.DrtControlerCreator;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtModule;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.fleet.FleetSpecification;
import org.matsim.contrib.dvrp.fleet.FleetSpecificationImpl;
import org.matsim.contrib.dvrp.fleet.ImmutableDvrpVehicleSpecification;
import org.matsim.contrib.dvrp.load.IntegerLoadType;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeModule;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeQSimModule;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpModule;
import org.matsim.contrib.dvrp.run.DvrpQSimComponents;
import org.matsim.contrib.zone.skims.DvrpTravelTimeMatrixParams;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.QSimConfigGroup.EndtimeInterpretation;
import org.matsim.core.config.groups.RoutingConfigGroup;
import org.matsim.core.config.groups.QSimConfigGroup.StarttimeInterpretation;
import org.matsim.core.config.groups.ScoringConfigGroup.ActivityParams;
import org.matsim.core.config.groups.ScoringConfigGroup.ModeParams;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelTime;
import org.matsim.pt.transitSchedule.TransitScheduleFactoryImpl;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.testcases.MatsimTestUtils;

/**
 * Dynamic stop selection with DRT companions: the companions (which have no own walks) follow the person they travel
 * with to the stop chosen by the insertion search, and the group is picked up and dropped off together.
 * <p>
 * Same geometry as {@code PrebookingStopSelectionTest} in the drt contrib: the person lives next to stop A, stop B is
 * 200 m away, and a slow vehicle coming from the north reaches B before A; the request is booked 100 s before departure.
 *
 * @author nkuehnel / MOIA
 */
public class CompanionStopSelectionTest {
	@RegisterExtension
	private MatsimTestUtils utils = new MatsimTestUtils();

	private static final double EDGE_LENGTH = 200.0;
	private static final double SPEED = 2.0; // 100 s per link
	private static final double DEPARTURE_TIME = 8 * 3600;
	private static final double SUBMISSION_TIME = DEPARTURE_TIME - 100;

	private static final Id<Link> STOP_A = linkId(3, 5);
	private static final Id<Link> STOP_B = linkId(3, 6);
	private static final Id<Link> STOP_E = linkId(3, 9);

	@Test
	void testCompanionFollowsToTheClosestStopWithOneCandidate() {
		Result result = run(1);

		assertThat(result.drtDepartures).hasSize(2).allSatisfy(e -> assertThat(e.getLinkId()).isEqualTo(STOP_A));
		assertThat(result.requests).isEqualTo(1);
		assertThat(result.stuck).isEmpty();
	}

	@Test
	void testCompanionFollowsToTheChosenStop() {
		Result result = run(2);

		// person and companion depart together at B, as one request
		assertThat(result.drtDepartures).hasSize(2).allSatisfy(e -> assertThat(e.getLinkId()).isEqualTo(STOP_B));
		assertThat(result.drtDepartures.get(0).getTime()).isEqualTo(result.drtDepartures.get(1).getTime());
		assertThat(result.drtDepartures).extracting(e -> e.getPersonId().toString())
				.anyMatch(id -> id.equals("person"))
				.anyMatch(id -> id.startsWith(DrtCompanionUtils.DRT_COMPANION_AGENT_PREFIX));
		assertThat(result.requests).isEqualTo(1);
		assertThat(result.drtArrivalLinks).hasSize(2).containsOnly(STOP_E);
		assertThat(result.stuck).isEmpty();
	}

	private record Result(List<PersonDepartureEvent> drtDepartures, List<Id<Link>> drtArrivalLinks, int requests,
						  List<PersonStuckEvent> stuck) {
	}

	private Result run(int maxStopCandidates) {
		Config config = ConfigUtils.createConfig();
		config.controller().setOutputDirectory(utils.getOutputDirectory());
		config.controller().setOverwriteFileSetting(OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setLastIteration(0);
		config.qsim().setStartTime(0.0);
		config.qsim().setSimStarttimeInterpretation(StarttimeInterpretation.onlyUseStarttime);
		config.qsim().setEndTime(12 * 3600);
		config.qsim().setSimEndtimeInterpretation(EndtimeInterpretation.onlyUseEndtime);
		// companions travel without access/egress walks; as in RunDrtWithCompanionExampleIT, their single-leg trips must
		// not be re-routed (that would give them own walks and drop their group)
		config.routing().setAccessEgressConsistencyCheck(RoutingConfigGroup.AccessEgressConsistencyCheck.disable);
		config.scoring().addModeParams(new ModeParams("drt"));
		ActivityParams generic = new ActivityParams("generic");
		generic.setScoringThisActivityAtAll(false);
		config.scoring().addActivityParams(generic);

		DvrpConfigGroup dvrpConfig = new DvrpConfigGroup();
		DvrpTravelTimeMatrixParams matrixParams = dvrpConfig.getTravelTimeMatrixParams();
		matrixParams.addParameterSet(matrixParams.createParameterSet(SquareGridZoneSystemParams.SET_NAME));
		config.addModule(dvrpConfig);

		MultiModeDrtConfigGroup multiModeConfig = new MultiModeDrtConfigGroup(DrtWithExtensionsConfigGroup::new);
		config.addModule(multiModeConfig);
		DrtWithExtensionsConfigGroup drtConfig = new DrtWithExtensionsConfigGroup();
		multiModeConfig.addParameterSet(drtConfig);
		drtConfig.setMode("drt");
		DrtOptimizationConstraintsSetImpl constraints = drtConfig.addOrGetDrtOptimizationConstraintsParams()
				.addOrGetDefaultDrtOptimizationConstraintsSet();
		constraints.setMaxWaitTime(600);
		constraints.setMaxTravelTimeAlpha(1.3);
		constraints.setMaxTravelTimeBeta(600);
		constraints.setMaxWalkDistance(500);
		constraints.setMaxStopCandidates(maxStopCandidates);
		drtConfig.setStopDuration(60);
		drtConfig.setIdleVehiclesReturnToDepots(false);
		drtConfig.setVehiclesFile(null);
		drtConfig.setOperationalScheme(DrtConfigGroup.OperationalScheme.stopbased);
		drtConfig.setTransitStopFile(writeStops(List.of(STOP_A, STOP_B, STOP_E)));
		drtConfig.addDrtInsertionSearchParams(new SelectiveInsertionSearchParams());
		drtConfig.addParameterSet(new PrebookingParams());
		DrtCompanionParams companionParams = new DrtCompanionParams();
		companionParams.setDrtCompanionSamplingWeights(List.of(0.0, 1.0)); // exactly one companion
		drtConfig.addParameterSet(companionParams);
		DrtConfigs.adjustMultiModeDrtConfig(multiModeConfig, config.scoring(), config.routing());

		Scenario scenario = DrtControlerCreator.createScenarioWithDrtRouteFactory(config);
		buildNetwork(scenario.getNetwork());
		buildPopulation(scenario);

		Controler controller = new Controler(scenario);
		controller.addOverridingModule(new DvrpModule());
		controller.addOverridingModule(new MultiModeDrtModule());
		controller.addOverridingModule(new MultiModeDrtCompanionModule());
		controller.configureQSimComponents(DvrpQSimComponents.activateAllModes(multiModeConfig));
		AttributeBasedPrebookingLogic.install(controller, drtConfig);

		FleetSpecification fleet = new FleetSpecificationImpl();
		fleet.addVehicleSpecification(ImmutableDvrpVehicleSpecification.newBuilder()
				.id(Id.create("vehicle", DvrpVehicle.class))
				.capacity(new IntegerLoadType("passengers").fromInt(4))
				.serviceBeginTime(0)
				.serviceEndTime(12 * 3600)
				.startLinkId(linkId(3, 8))
				.build());
		controller.addOverridingModule(new AbstractDvrpModeModule("drt") {
			@Override
			public void install() {
				bindModal(FleetSpecification.class).toInstance(fleet);
			}
		});
		controller.addOverridingQSimModule(new AbstractDvrpModeQSimModule("drt") {
			@Override
			protected void configureQSim() {
				bindModal(DetourTimeEstimator.class).toProvider(modalProvider(getter -> {
					Network network = getter.getModal(Network.class);
					TravelTime travelTime = getter.getModal(TravelTime.class);
					LeastCostPathCalculator router = new SpeedyALTFactory().createPathCalculator(network,
							new OnlyTimeDependentTravelDisutility(travelTime), travelTime);
					return DetourTimeEstimator.createExactEstimator(router, travelTime);
				}));
			}
		});

		Handler handler = new Handler();
		controller.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				addEventHandlerBinding().toInstance(handler);
			}
		});
		controller.run();
		return new Result(handler.drtDepartures, handler.drtArrivalLinks, handler.requests, handler.stuck);
	}

	private static final class Handler implements PersonDepartureEventHandler, PersonArrivalEventHandler,
			PersonStuckEventHandler, DrtRequestSubmittedEventHandler {
		final List<PersonDepartureEvent> drtDepartures = new ArrayList<>();
		final List<Id<Link>> drtArrivalLinks = new ArrayList<>();
		final List<PersonStuckEvent> stuck = new ArrayList<>();
		int requests = 0;

		@Override
		public void handleEvent(PersonDepartureEvent event) {
			if (event.getLegMode().equals("drt")) {
				drtDepartures.add(event);
			}
		}

		@Override
		public void handleEvent(PersonArrivalEvent event) {
			if (event.getLegMode().equals("drt")) {
				drtArrivalLinks.add(event.getLinkId());
			}
		}

		@Override
		public void handleEvent(PersonStuckEvent event) {
			stuck.add(event);
		}

		@Override
		public void handleEvent(DrtRequestSubmittedEvent event) {
			requests++;
		}
	}

	private static Id<Link> linkId(int x, int y) {
		return Id.createLinkId(x + ":" + y + "-" + (x + 1) + ":" + y);
	}

	private static Coord linkCentre(Id<Link> linkId) {
		String[] from = linkId.toString().split("-")[0].split(":");
		return new Coord((Integer.parseInt(from[0]) + 0.5) * EDGE_LENGTH, Integer.parseInt(from[1]) * EDGE_LENGTH);
	}

	private static String writeStops(List<Id<Link>> stopLinks) {
		TransitScheduleFactory factory = new TransitScheduleFactoryImpl();
		TransitSchedule schedule = factory.createTransitSchedule();
		for (Id<Link> linkId : stopLinks) {
			TransitStopFacility stop = factory.createTransitStopFacility(
					Id.create("stop_" + linkId, TransitStopFacility.class), linkCentre(linkId), false);
			stop.setLinkId(linkId);
			schedule.addStopFacility(stop);
		}
		try {
			File file = File.createTempFile("drt_stops", ".xml");
			file.deleteOnExit();
			new TransitScheduleWriter(schedule).writeFile(file.getAbsolutePath());
			return file.getAbsolutePath();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void buildNetwork(Network network) {
		NetworkFactory factory = network.getFactory();
		int size = 10;
		Node[][] nodes = new Node[size][size];
		for (int i = 0; i < size; i++) {
			for (int j = 0; j < size; j++) {
				nodes[i][j] = factory.createNode(Id.createNodeId(i + ":" + j), new Coord(i * EDGE_LENGTH, j * EDGE_LENGTH));
				network.addNode(nodes[i][j]);
			}
		}
		for (int i = 0; i < size; i++) {
			for (int j = 0; j < size; j++) {
				if (i + 1 < size) {
					addLinkPair(network, nodes[i][j], nodes[i + 1][j]);
				}
				if (j + 1 < size) {
					addLinkPair(network, nodes[i][j], nodes[i][j + 1]);
				}
			}
		}
	}

	private static void addLinkPair(Network network, Node a, Node b) {
		for (Node[] pair : new Node[][] {{a, b}, {b, a}}) {
			Link link = network.getFactory().createLink(
					Id.createLinkId(pair[0].getId() + "-" + pair[1].getId()), pair[0], pair[1]);
			link.setFreespeed(SPEED);
			link.setCapacity(1e9);
			link.setLength(EDGE_LENGTH);
			link.setNumberOfLanes(1);
			network.addLink(link);
		}
	}

	private static void buildPopulation(Scenario scenario) {
		PopulationFactory factory = scenario.getPopulation().getFactory();
		Person person = factory.createPerson(Id.createPersonId("person"));
		Plan plan = factory.createPlan();
		Activity origin = factory.createActivityFromLinkId("generic", STOP_A);
		origin.setCoord(linkCentre(STOP_A));
		origin.setEndTime(DEPARTURE_TIME);
		AttributeBasedPrebookingLogic.setSubmissionTime("drt", origin, SUBMISSION_TIME);
		plan.addActivity(origin);
		plan.addLeg(factory.createLeg("drt"));
		Activity destination = factory.createActivityFromLinkId("generic", STOP_E);
		destination.setCoord(linkCentre(STOP_E));
		plan.addActivity(destination);
		person.addPlan(plan);
		scenario.getPopulation().addPerson(person);
	}
}
