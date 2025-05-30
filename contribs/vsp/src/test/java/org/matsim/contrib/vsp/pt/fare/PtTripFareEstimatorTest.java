package org.matsim.contrib.vsp.pt.fare;

import com.google.inject.Inject;
import com.google.inject.Injector;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.application.MATSimApplication;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import org.matsim.modechoice.*;
import org.matsim.modechoice.estimators.MinMaxEstimate;
import org.matsim.modechoice.estimators.TripEstimator;
import org.matsim.testcases.MatsimTestUtils;
import playground.vsp.TestScenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

public class PtTripFareEstimatorTest {

	@RegisterExtension
	private MatsimTestUtils utils = new MatsimTestUtils();

	protected InformedModeChoiceConfigGroup group;
	protected Controler controler;
	protected Injector injector;
	@Inject
	private EstimateRouter router;
	@Inject
	private ScoringParametersForPerson params;
	@Inject
	private Map<String, TripEstimator> tripEstimator;
	private PtTripWithDistanceBasedFareEstimator estimator;

	@BeforeEach
	public void setUp() {

		Config config = TestScenario.loadConfig(utils);

		Map<String, ScoringConfigGroup.ModeParams> modes = config.scoring().getScoringParameters("person").getModes();
		group = ConfigUtils.addOrGetModule(config, InformedModeChoiceConfigGroup.class);

		PtFareConfigGroup fare = ConfigUtils.addOrGetModule(config, PtFareConfigGroup.class);
		DistanceBasedPtFareParams distanceFare = new DistanceBasedPtFareParams();

		fare.setApplyUpperBound(true);
		fare.setUpperBoundFactor(1.5);

		distanceFare.setMinFare(0.1);
		DistanceBasedPtFareParams.DistanceClassLinearFareFunctionParams distanceClass20kmFareParams =
			distanceFare.getOrCreateDistanceClassFareParams(20000.0);
		distanceClass20kmFareParams.setFareIntercept(0.5);
		distanceClass20kmFareParams.setFareSlope(0.1);

		DistanceBasedPtFareParams.DistanceClassLinearFareFunctionParams distanceClassLongFareParams =
			distanceFare.getOrCreateDistanceClassFareParams(Double.POSITIVE_INFINITY);
		distanceClassLongFareParams.setFareIntercept(1.0);
		distanceClassLongFareParams.setFareSlope(0.01);

		fare.addPtFareParameterSet(distanceFare);

		controler = MATSimApplication.prepare(TestScenario.class, config);
		injector = controler.getInjector();

		injector.injectMembers(this);

		estimator = (PtTripWithDistanceBasedFareEstimator) tripEstimator.get(TransportMode.pt);
	}

	private List<MinMaxEstimate> estimateAgent(Id<Person> personId) {

		Person person = controler.getScenario().getPopulation().getPersons().get(personId);
		Plan plan = person.getSelectedPlan();

		EstimatorContext context = new EstimatorContext(plan.getPerson(), params.getScoringParameters(plan.getPerson()));

		PlanModel model = PlanModel.newInstance(plan);

		router.routeModes(model, Set.of(TransportMode.pt, TransportMode.walk, TransportMode.bike, TransportMode.car), TripModeFilter.ACCEPT_ALL);

		List<MinMaxEstimate> ests = new ArrayList<>();

		for (int i = 0; i < model.trips(); i++) {

			List<Leg> trip = model.getLegs(TransportMode.pt, i);

			if (trip == null || !model.doesNotConsistOfOnlyWalksLegs(TransportMode.pt, i)) {
				continue;
			}

			MinMaxEstimate est = estimator.estimate(context, TransportMode.pt, model, trip, ModeAvailability.YES);

			ests.add(est);
		}

		return ests;
	}

	@Test
	void fare() {

		List<MinMaxEstimate> est = estimateAgent(TestScenario.Agents.get(1));
		System.out.println(est);

		assertThat(est)
			.allMatch(e -> e.getMin() < e.getMax(), "Min smaller max")
			.first().extracting(MinMaxEstimate::getMin, InstanceOfAssertFactories.DOUBLE)
			.isCloseTo(-379.3, Offset.offset(0.1));

	}

	@Test
	void all() {

		for (Id<Person> agent : TestScenario.Agents) {
			List<MinMaxEstimate> est = estimateAgent(agent);

			assertThat(est)
				.allMatch(e -> e.getMin() <= e.getMax(), "Min smaller max");

		}
	}

	@Test
	void planEstimate() {

		Person person = controler.getScenario().getPopulation().getPersons().get(TestScenario.Agents.get(2));
		Plan plan = person.getSelectedPlan();

		EstimatorContext context = new EstimatorContext(plan.getPerson(), params.getScoringParameters(plan.getPerson()));

		PlanModel model = PlanModel.newInstance(plan);

		router.routeModes(model, Set.of(TransportMode.pt, TransportMode.walk, TransportMode.bike, TransportMode.car), TripModeFilter.ACCEPT_ALL);

		List<MinMaxEstimate> singleTrips = estimateAgent(TestScenario.Agents.get(2));

		double maxSum = singleTrips.stream().mapToDouble(MinMaxEstimate::getMax).sum();
		double minSum = singleTrips.stream().mapToDouble(MinMaxEstimate::getMin).sum();

		System.out.println(singleTrips);

		// 2nd one does hat have a pt connection
		double estimate = estimator.estimatePlan(context, TransportMode.pt, new String[]{"pt", "car", "pt", "pt", "pt"}, model, ModeAvailability.YES);

		assertThat(estimate)
			.isLessThanOrEqualTo(maxSum)
			.isGreaterThanOrEqualTo(minSum)
			.isCloseTo(-2738.7, Offset.offset(0.1));


		estimate = estimator.estimatePlan(context, TransportMode.pt, new String[]{"pt", "car", "car", "car", "pt"}, model, ModeAvailability.YES);

		assertThat(estimate)
			.isLessThanOrEqualTo(maxSum)
			.isGreaterThanOrEqualTo(minSum)
			.isCloseTo( -1222.9, Offset.offset(0.1));

		// Essentially single trip
		estimate = estimator.estimatePlan(context, TransportMode.pt, new String[]{"pt", "car", "car", "car", "car"}, model, ModeAvailability.YES);
		assertThat(estimate)
			.isCloseTo(singleTrips.get(0).getMin(), Offset.offset(0.1));

	}
}
