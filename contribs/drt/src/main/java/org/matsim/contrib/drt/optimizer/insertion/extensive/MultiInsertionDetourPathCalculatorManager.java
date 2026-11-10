package org.matsim.contrib.drt.optimizer.insertion.extensive;

import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.core.config.groups.TravelTimeCalculatorConfigGroup;
import org.matsim.core.mobsim.framework.events.MobsimBeforeCleanupEvent;
import org.matsim.core.mobsim.framework.listeners.MobsimBeforeCleanupListener;
import org.matsim.core.router.speedy.CCHPotentials;
import org.matsim.core.router.speedy.SpeedyGraph;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;

import java.util.ArrayList;
import java.util.List;

public class MultiInsertionDetourPathCalculatorManager implements MobsimBeforeCleanupListener {

	private final Network network;
	private final TravelTime travelTime;
	private final TravelDisutility travelDisutility;
	private final DrtConfigGroup drtCfg;
	private final boolean useCH;
	private final double binSize;
	private final double horizon;
	private final List<MultiInsertionDetourPathCalculator> multiInsertionDetourPathCalculatorList;

	/** Lower bounds for this manager's travel times, computed on first use and shared by its calculators. */
	private CCHPotentials potentials;

	/**
	 * Creates a new manager.
	 *
	 * @param useCH if {@code true}, uses the exact goal-directed one-to-many search (multi-target CH-Potentials);
	 *              derive this from
	 *              {@code config.controller().getRoutingAlgorithmType() == ControllerConfigGroup.RoutingAlgorithmType.CHRouter}.
	 * @param travelTimeConfig bin size and horizon of the travel times, for the CH-Potentials' lower bounds
	 */
	MultiInsertionDetourPathCalculatorManager(Network network, TravelTime travelTime, TravelDisutility travelDisutility,
											  DrtConfigGroup drtCfg, boolean useCH, TravelTimeCalculatorConfigGroup travelTimeConfig) {
		this.network = network;
		this.travelTime = travelTime;
		this.travelDisutility = travelDisutility;
		this.drtCfg = drtCfg;
		this.useCH = useCH;
		this.binSize = travelTimeConfig.getTraveltimeBinSize();
		this.horizon = travelTimeConfig.getMaxTime();
		this.multiInsertionDetourPathCalculatorList = new ArrayList<>();
	}

	/** As above, with the default travel time configuration. */
	MultiInsertionDetourPathCalculatorManager(Network network, TravelTime travelTime, TravelDisutility travelDisutility,
											  DrtConfigGroup drtCfg, boolean useCH) {
		this(network, travelTime, travelDisutility, drtCfg, useCH, new TravelTimeCalculatorConfigGroup());
	}

	@Override
	public void notifyMobsimBeforeCleanup(MobsimBeforeCleanupEvent e) {
		multiInsertionDetourPathCalculatorList.forEach(i -> i.notifyMobsimBeforeCleanup(e));
	}

	MultiInsertionDetourPathCalculator create() {
		MultiInsertionDetourPathCalculator instance;
		if (useCH) {
			instance = new MultiInsertionDetourPathCalculator(potentials(), travelTime, travelDisutility, drtCfg);
		} else {
			instance = new MultiInsertionDetourPathCalculator(network, travelTime, travelDisutility, drtCfg);
		}
		this.multiInsertionDetourPathCalculatorList.add(instance);
		return instance;
	}

	/**
	 * The contraction is shared per network (it does not depend on costs). The lower bounds are computed once per
	 * manager: the manager lives in the QSim scope, so once per iteration, and the DVRP travel time estimates it
	 * reads only change after the mobsim.
	 */
	private synchronized CCHPotentials potentials() {
		if (potentials == null) {
			SpeedyGraph graph = CCHPotentials.baseGraph(network);
			potentials = CCHPotentials.customize(graph, CCHPotentials.contraction(graph, travelDisutility), travelTime, travelDisutility, binSize, horizon);
		}
		return potentials;
	}
}
