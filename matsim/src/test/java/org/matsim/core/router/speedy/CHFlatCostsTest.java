package org.matsim.core.router.speedy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.groups.TravelTimeCalculatorConfigGroup;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.LeastCostPathCalculator.Path;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import org.matsim.core.router.speedy.CHExactnessUnderJamsTest.JamTravelTime;
import org.matsim.core.router.speedy.CHExactnessUnderJamsTest.Scenario;

/** Reading the sampled per-bin costs must not change any route, cost or time compared with calling the live travel time. */
public class CHFlatCostsTest {

	private static final TravelTimeCalculatorConfigGroup BINS = new TravelTimeCalculatorConfigGroup();

	private record Routers(LeastCostPathCalculator withFlatCosts, LeastCostPathCalculator withLiveLookups, LeastCostPathCalculator oracle) {}

	private static Routers routers(Network network, TravelTime tt) {
		TravelDisutility td = new OnlyTimeDependentTravelDisutility(tt);
		SpeedyGraph graph = CCHPotentials.baseGraph(network);
		CHGraph cch = CCHPotentials.contraction(graph, td);
		double bin = BINS.getTraveltimeBinSize(), horizon = BINS.getMaxTime();
		return new Routers(
				new CHPotentialRouter(CCHPotentials.customize(graph, cch, tt, td, bin, horizon), tt, td),
				new CHPotentialRouter(CCHPotentials.customize(graph, cch, td, bin, horizon), tt, td),
				new SpeedyDijkstraFactory().createPathCalculator(network, td, tt));
	}

	private static List<Id<Link>> ids(Path path) {
		return path.links.stream().map(Link::getId).toList();
	}

	@Test
	void flatCostsGiveTheSameRoutesCostsAndTimesAsLiveLookups() {
		Network network = CHExactnessUnderJamsTest.createNetwork();
		List<Node> nodes = new ArrayList<>(network.getNodes().values());
		List<String> differences = new ArrayList<>();
		long queries = 0;
		for (Scenario scenario : CHExactnessUnderJamsTest.scenarios(network)) {
			JamTravelTime tt = new JamTravelTime();
			tt.peakOnly = scenario.peakOnly();
			scenario.jammed().forEach(l -> tt.delay.put(l.getId(), CHExactnessUnderJamsTest.JAM_DELAY));
			Routers r = routers(network, tt);
			for (double departure : CHExactnessUnderJamsTest.DEPARTURES) {
				for (Node from : nodes) {
					for (Node to : nodes) {
						if (from == to) continue;
						queries++;
						Path flat = r.withFlatCosts().calcLeastCostPath(from, to, departure, null, null);
						Path live = r.withLiveLookups().calcLeastCostPath(from, to, departure, null, null);
						if (!ids(flat).equals(ids(live)) || flat.travelCost != live.travelCost || flat.travelTime != live.travelTime) {
							differences.add(scenario.name() + " " + from.getId() + ">" + to.getId() + "@" + departure);
						}
					}
				}
			}
		}
		assertEquals(List.of(), differences.subList(0, Math.min(5, differences.size())), differences.size() + " of " + queries + " queries differ");
	}

	/** Like MSF's estimator, which caps a link's travel time by the vehicle's top speed when a vehicle is given. */
	private static class VehicleAwareTravelTime implements TravelTime {
		final TravelTime base;
		VehicleAwareTravelTime(TravelTime base) { this.base = base; }

		@Override
		public double getLinkTravelTime(Link link, double time, Person person, Vehicle vehicle) {
			return base.getLinkTravelTime(link, time, person, vehicle) + (vehicle == null ? 0 : 5);
		}
	}

	@Test
	void queriesWithAVehicleReadTheLiveTravelTimeAndMatchDijkstra() {
		Network network = CHExactnessUnderJamsTest.createNetwork();
		List<Node> nodes = new ArrayList<>(network.getNodes().values());
		Vehicle vehicle = VehicleUtils.createVehicle(Id.createVehicleId("v"), VehicleUtils.createVehicleType(Id.create("type", VehicleType.class)));
		Routers r = routers(network, new VehicleAwareTravelTime(new JamTravelTime()));
		for (Node from : nodes) {
			for (Node to : nodes) {
				if (from == to) continue;
				Path ch = r.withFlatCosts().calcLeastCostPath(from, to, 8 * CHExactnessUnderJamsTest.HOUR, null, vehicle);
				Path dijkstra = r.oracle().calcLeastCostPath(from, to, 8 * CHExactnessUnderJamsTest.HOUR, null, vehicle);
				assertEquals(dijkstra.travelCost, ch.travelCost, CHExactnessUnderJamsTest.TOLERANCE, from.getId() + ">" + to.getId());
			}
		}
	}
}
