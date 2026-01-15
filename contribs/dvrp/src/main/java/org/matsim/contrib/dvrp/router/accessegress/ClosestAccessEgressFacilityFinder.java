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

package org.matsim.contrib.dvrp.router.accessegress;

import java.util.*;
import java.util.stream.Collectors;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.utils.collections.QuadTree;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.facilities.Facility;
import org.matsim.utils.objectattributes.attributable.Attributes;

import com.google.common.base.Verify;

/**
 * @author michalm
 */
public class ClosestAccessEgressFacilityFinder implements AccessEgressFacilityFinder {
	private final Network network;
	private final QuadTree<? extends Facility> facilityQuadTree;
	private final double maxDistance;
	private final int numberOfAccessEgressCandidates;

	public ClosestAccessEgressFacilityFinder(int maxAccessEgressCandidateStops, double maxDistance, Network network,
			QuadTree<? extends Facility> facilityQuadTree) {
		this.network = network;
		this.facilityQuadTree = facilityQuadTree;
		this.maxDistance = maxDistance;
		this.numberOfAccessEgressCandidates = maxAccessEgressCandidateStops;
	}

	@Override
	public Optional<AccessEgressFacilities> findFacilities(Facility fromFacility, Facility toFacility, Attributes tripAttributes) {
		List<Facility> accessFacilities = findClosestStops(fromFacility);
		if (accessFacilities.isEmpty()) {
			return Optional.empty();
		}

		List<Facility> egressFacility = findClosestStops(toFacility);
		return egressFacility.isEmpty() ?
				Optional.empty() :
				Optional.of(new AccessEgressFacilities(accessFacilities, egressFacility));
	}

	private List<Facility> findClosestStops(Facility facility) {
		Coord coord = getFacilityCoord(facility, network);

		if(numberOfAccessEgressCandidates == 1) {
			Facility closestStop = facilityQuadTree.getClosest(coord.getX(), coord.getY());
			double closestStopDistance = CoordUtils.calcEuclideanDistance(coord, closestStop.getCoord());
			return closestStopDistance > maxDistance ? List.of() : List.of(closestStop);
		} else {
			Collection<Facility> closestStops = (Collection<Facility>) facilityQuadTree.getDisk(coord.getX(), coord.getY(), maxDistance);

			List<Facility> nearestStopsSorted = closestStops.stream()
					.sorted(Comparator.comparingDouble(o -> CoordUtils.calcEuclideanDistance(coord, o.getCoord())))
					.limit(numberOfAccessEgressCandidates)
					.toList();
			return nearestStopsSorted;
		}
	}

	static Coord getFacilityCoord(Facility facility, Network network) {
		Coord coord = facility.getCoord();
		if (coord == null) {
			coord = network.getLinks().get(facility.getLinkId()).getCoord();
			Verify.verify(coord != null, "From facility has neither coordinates nor link Id. Should not happen.");
		}
		return coord;
	}
}
