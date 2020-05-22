/*
 * *********************************************************************** *
 * project: org.matsim.*
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2020 by the members listed in the COPYING,        *
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

package org.matsim.contrib.drt.optimizer.insertion;

import java.util.function.Function;

import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.drt.optimizer.VehicleData;
import org.matsim.contrib.drt.optimizer.insertion.InsertionGenerator.Insertion;
import org.matsim.contrib.drt.passenger.DrtRequest;

/**
 * @author Michal Maciejewski (michalm)
 */
public interface DetourDataProvider<D> {
	DetourData<D> getDetourData(DrtRequest drtRequest, VehicleData.Entry vEntry);

	/**
	 * Contains detour data for all potential insertions (i.e. pickup and dropoff indices)
	 * <p>
	 * Having them collected in one set allows the typical use case where all paths are precomputed in one go
	 * and then provided via InsertionWithPathData for a specific Insertion.
	 * <p>
	 * The current implementation assumes the DetourData functions are time independent. This may be changed in the future (esp.
	 * for pre-booking or to enhance simple beeline TT estimation) to BiFunctions: (Link, time) -> data.
	 * <p>
	 * On the other hand, detour data (D) could itself provide time-dependent information.
	 */
	class DetourData<D> {
		private final Function<Link, D> detourToPickup;
		private final Function<Link, D> detourFromPickup;
		private final Function<Link, D> detourToDropoff;
		private final Function<Link, D> detourFromDropoff;

		DetourData(Function<Link, D> detourToPickup, Function<Link, D> detourFromPickup,
				Function<Link, D> detourToDropoff, Function<Link, D> detourFromDropoff) {
			this.detourToPickup = detourToPickup;
			this.detourFromPickup = detourFromPickup;
			this.detourToDropoff = detourToDropoff;
			this.detourFromDropoff = detourFromDropoff;
		}

		public InsertionWithDetourData<D> createInsertionWithDetourData(Insertion insertion, DrtRequest request,
				VehicleData.Entry vEntry) {
			int i = insertion.pickupIdx;
			int j = insertion.dropoffIdx;

			Link pickupDetourStartLink = i == 0 ? vEntry.start.link : vEntry.stops.get(i - 1).task.getLink();
			Link pickupDetourEndLink = i == j ? request.getToLink() : vEntry.stops.get(i).task.getLink();

			Link dropoffDetourStartLink = i == j ? null : vEntry.stops.get(j - 1).task.getLink();
			Link dropoffDetourEndLink = j == vEntry.stops.size() ? null : vEntry.stops.get(j).task.getLink();

			D toPickup = detourToPickup.apply(pickupDetourStartLink);
			D fromPickup = detourFromPickup.apply(pickupDetourEndLink);
			D toDropoff = dropoffDetourStartLink == null ? null : detourToDropoff.apply(dropoffDetourStartLink);
			D fromDropoff = dropoffDetourEndLink == null ? null : detourFromDropoff.apply(dropoffDetourEndLink);

			// TODO switch to the new approach
			//			D fromPickup = i == detourFromPickup.length //
			//					? detourFromPickup[0] // pickup inserted at the end
			//					: detourFromPickup[i + 1]; // pickup -> i+1
			//			D toDropoff = i == j ? detourFromPickup[0] // pickup followed by dropoff
			//					: detourToDropoff[j]; // j -> dropoff
			//			D fromDropoff = j == detourFromDropoff.length //
			//					? detourFromDropoff[0] // dropoff inserted at the end
			//					: detourFromDropoff[j + 1];

			return new InsertionWithDetourData<>(i, j, toPickup, fromPickup, toDropoff, fromDropoff);
		}
	}
}