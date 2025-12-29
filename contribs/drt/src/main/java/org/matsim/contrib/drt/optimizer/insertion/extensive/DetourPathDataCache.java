/*
 * *********************************************************************** *
 * project: org.matsim.*
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2022 by the members listed in the COPYING,        *
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

package org.matsim.contrib.drt.optimizer.insertion.extensive;

import java.util.Map;

import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.drt.optimizer.Waypoint;
import org.matsim.contrib.drt.optimizer.insertion.InsertionGenerator.Insertion;
import org.matsim.contrib.drt.optimizer.insertion.InsertionWithDetourData.InsertionDetourData;
import org.matsim.contrib.dvrp.path.OneToManyPathSearch.PathData;

/**
 * Contains detour data for all potential insertions (i.e. pickup and dropoff indices).
 * Typically, all path data of a given type (i.e. to/from pickup/delivery) are precomputed in one go and then cached.
 *
 * With dynamic stop selection, multiple access/egress links are possible per request.
 * The nested map structure allows efficient lookup: outer key = pickup/dropoff link, inner key = destination link.
 * For the common single-link case, this adds only one additional map lookup (negligible overhead).
 */
public final class DetourPathDataCache {
	// Outer key: pickup link, Inner key: previous waypoint link
	private final Map<Link, Map<Link, PathData>> detourToPickup;
	// Outer key: pickup link, Inner key: next waypoint link
	private final Map<Link, Map<Link, PathData>> detourFromPickup;
	// Outer key: dropoff link, Inner key: previous waypoint link
	private final Map<Link, Map<Link, PathData>> detourToDropoff;
	// Outer key: dropoff link, Inner key: next waypoint link
	private final Map<Link, Map<Link, PathData>> detourFromDropoff;
	private final PathData zeroDetour;

	public DetourPathDataCache(Map<Link, Map<Link, PathData>> detourToPickup, Map<Link, Map<Link, PathData>> detourFromPickup,
			Map<Link, Map<Link, PathData>> detourToDropoff, Map<Link, Map<Link, PathData>> detourFromDropoff, PathData zeroDetour) {
		this.detourToPickup = detourToPickup;
		this.detourFromPickup = detourFromPickup;
		this.detourToDropoff = detourToDropoff;
		this.detourFromDropoff = detourFromDropoff;
		this.zeroDetour = zeroDetour;
	}

	public InsertionDetourData createInsertionDetourData(Insertion insertion) {
		Link pickupLink = insertion.pickup.newWaypoint.getLink();
		Link dropoffLink = insertion.dropoff.newWaypoint.getLink();

		PathData toPickup = detourToPickup.get(pickupLink).get(insertion.pickup.previousWaypoint.getLink());
		PathData fromPickup = detourFromPickup.get(pickupLink).get(insertion.pickup.nextWaypoint.getLink());
		PathData toDropoff = insertion.dropoff.previousWaypoint instanceof Waypoint.Pickup ?
				null :
				detourToDropoff.get(dropoffLink).get(insertion.dropoff.previousWaypoint.getLink());
		PathData fromDropoff = insertion.dropoff.nextWaypoint instanceof Waypoint.End ?
				zeroDetour :
				detourFromDropoff.get(dropoffLink).get(insertion.dropoff.nextWaypoint.getLink());
		return new InsertionDetourData(toPickup, fromPickup, toDropoff, fromDropoff);
	}
}
