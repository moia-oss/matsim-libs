/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2013 by the members listed in the COPYING,        *
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
 * *********************************************************************** */

package org.matsim.contrib.dvrp.passenger;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.dvrp.load.DvrpLoad;
import org.matsim.contrib.dvrp.optimizer.Request;

import java.util.List;
import java.util.SequencedSet;
import java.util.Set;

public interface PassengerRequest extends Request {
	/**
	 * @return beginning of the time window (inclusive) - earliest time when the passenger can be picked up
	 */
	double getEarliestStartTime();

	/**
	 * @return end of the time window (exclusive) - time by which the passenger should be picked up
	 */
	default double getLatestStartTime() {
		return Double.MAX_VALUE;
	}

	List<Id<Person>> getPassengerIds();

	String getMode();

	DvrpLoad getLoad();

	/**
	 * Returns ordered set of potential pickup links, with most preferred first.
	 * For single-link requests (e.g., taxi, door-to-door DRT), this returns a singleton set.
	 * For multi-link requests (e.g., stop-based DRT), returns multiple candidates ordered by preference.
	 *
	 * @return sequenced set of pickup link candidates (never empty)
	 */
	List<Link> getFromLinks();

	/**
	 * Returns ordered set of potential dropoff links, with most preferred first.
	 * For single-link requests (e.g., taxi, door-to-door DRT), this returns a singleton set.
	 * For multi-link requests (e.g., stop-based DRT), returns multiple candidates ordered by preference.
	 *
	 * @return sequenced set of dropoff link candidates (never empty)
	 */
	List<Link> getToLinks();

	/**
	 * Convenience method returning the primary/preferred pickup link.
	 * Equivalent to {@code getFromLinks().getFirst()}.
	 * <p>
	 * For new code, prefer {@link #getFromLinks()} to support multi-link optimization.
	 *
	 * @return the primary pickup link
	 * @deprecated Use {@link #getFromLinks()} for multi-link support
	 */
	@Deprecated
	default Link getFromLink() {
		return getFromLinks().getFirst();
	}

	/**
	 * Convenience method returning the primary/preferred dropoff link.
	 * Equivalent to {@code getToLinks().getFirst()}.
	 * <p>
	 * For new code, prefer {@link #getToLinks()} to support multi-link optimization.
	 *
	 * @return the primary dropoff link
	 * @deprecated Use {@link #getToLinks()} for multi-link support
	 */
	@Deprecated
	default Link getToLink() {
		return getToLinks().getFirst();
	}

}
