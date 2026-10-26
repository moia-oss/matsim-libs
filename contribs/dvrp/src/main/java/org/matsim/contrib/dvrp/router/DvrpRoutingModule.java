/*
 * *********************************************************************** *
 * project: org.matsim.*
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2019 by the members listed in the COPYING,        *
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

package org.matsim.contrib.dvrp.router;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.google.common.base.Verify;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contrib.dvrp.router.accessegress.AccessEgressFacilities;
import org.matsim.contrib.dvrp.router.accessegress.AccessEgressFacilityFinder;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.DefaultRoutingRequest;
import org.matsim.core.router.RoutingModule;
import org.matsim.core.router.RoutingRequest;
import org.matsim.core.utils.timing.TimeInterpretation;
import org.matsim.facilities.Facility;

/**
 * @author jbischoff
 * @author michalm (Michal Maciejewski)
 */
public class DvrpRoutingModule implements RoutingModule {
	private static final Logger logger = LogManager.getLogger(DvrpRoutingModule.class);

	private final AccessEgressFacilityFinder stopFinder;
	private final String mode;
	private final RoutingModule mainRouter;
	private final RoutingModule accessRouter;
	private final RoutingModule egressRouter;
	private final TimeInterpretation timeInterpretation;
	private final boolean useDynamicStopSelection = true;

	public DvrpRoutingModule(RoutingModule mainRouter, RoutingModule accessRouter, RoutingModule egressRouter,
			AccessEgressFacilityFinder stopFinder, String mode, TimeInterpretation timeInterpretation) {
		this.mainRouter = mainRouter;
		this.stopFinder = stopFinder;
		this.mode = mode;
		this.accessRouter = accessRouter;
		this.egressRouter = egressRouter;
		this.timeInterpretation = timeInterpretation;
	}

	@Override
	public List<? extends PlanElement> calcRoute(RoutingRequest request) {
		final Facility fromFacility = request.getFromFacility();
		final Facility toFacility = request.getToFacility();
		final double departureTime = request.getDepartureTime();
		final Person person = request.getPerson();

		Optional<AccessEgressFacilities> stops = stopFinder.findFacilities(
				Objects.requireNonNull(fromFacility, "fromFacility is null"),
				Objects.requireNonNull(toFacility, "toFacility is null"), request.getAttributes());
		if (stops.isEmpty()) {
			logger.debug("No access/egress stops found, agent will use fallback mode as leg mode (usually "
					+ TransportMode.walk
					+ ") and routing mode "
					+ mode
					+ ". Agent Id:\t"
					+ person.getId());
			return null;
		}

		List<Facility> accessStops = stops.get().access();
		List<Facility> egressStops = stops.get().egress();
		if(!useDynamicStopSelection ) {
			Verify.verify(accessStops.size() == 1 && egressStops.size() == 1,
					"Only one access and one egress facility allowed without dynamic stop selection.");

		}

		Facility accessFacility = accessStops.getFirst();
		Facility egressFacility = egressStops.getFirst();

		if (accessFacility.getLinkId().equals(egressFacility.getLinkId()) && accessStops.size() == 1 && egressStops.size() == 1) {
			logger.debug("Start and end stop are the same, agent will use fallback mode as leg mode (usually "
					+ TransportMode.walk
					+ ") and routing mode "
					+ mode
					+ ". Agent Id:\t"
					+ person.getId());
			return null;
		}

		List<PlanElement> trip = new ArrayList<>();

		double now = departureTime;

		if (!useDynamicStopSelection) {
			// access (sub-)trip:
			List<? extends PlanElement> accessTrip = accessRouter.calcRoute(
					DefaultRoutingRequest.of(fromFacility, accessFacility, now, person, request.getAttributes()));
			if (!accessTrip.isEmpty()) {
				trip.addAll(accessTrip);
				now = timeInterpretation.decideOnElementsEndTime(accessTrip, now).seconds();

				// interaction activity:
				trip.add(createDrtStageActivity(accessFacility, now));
			}
		}

		// dvrp proper leg:
		List<? extends PlanElement> drtLeg = mainRouter.calcRoute(
				DvrpRoutingRequest.of(accessStops, egressStops, now, person, request.getAttributes()));
		trip.addAll(drtLeg);
		now = timeInterpretation.decideOnElementsEndTime(drtLeg, now).seconds();

		if(!useDynamicStopSelection) {
			// egress (sub-)trip:
			List<? extends PlanElement> egressTrip = egressRouter.calcRoute(
					DefaultRoutingRequest.of(egressFacility, toFacility, now, person, request.getAttributes()));
			if (!egressTrip.isEmpty()) {
				// interaction activity:
				trip.add(createDrtStageActivity(egressFacility, now));

				trip.addAll(egressTrip);
			}
		}

		return trip;
	}

	private Activity createDrtStageActivity(Facility stopFacility, double now) {
		return PopulationUtils.createStageActivityFromCoordLinkIdAndModePrefix(stopFacility.getCoord(),
				stopFacility.getLinkId(), mode);
	}
}
