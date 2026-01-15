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
import org.matsim.api.core.v01.population.Route;
import org.matsim.contrib.dvrp.optimizer.Request;

import java.util.Collections;
import java.util.List;
import java.util.SequencedSet;
import java.util.Set;

/**
 * @author michalm
 */
public interface PassengerRequestCreator {

	/**
	 * Creates a passenger request with multiple potential pickup and dropoff locations.
	 * This is the primary method that implementations should override to support multi-link optimization.
	 * <p>
	 * Thread safety: This method can be called concurrently from multiple QSim worker threads.
	 * Prefer stateless implementation, otherwise provide other ways to achieve thread-safety.
	 *
	 * @param id             	request ID
	 * @param passengerIds   	list of unique passenger IDs
	 * @param routes          	list of planned routes (the required route type depends on the optimizer)
	 * @param fromLinks    		ordered set of potential pickup locations, with most preferred first
	 * @param toLinks    		ordered set of potential dropoff locations, with most preferred first
	 * @param departureTime  	requested time of departure
	 * @param submissionTime 	time at which request was submitted
	 * @return the created passenger request
	 */
	PassengerRequest createRequest(Id<Request> id, List<Id<Person>> passengerIds, List<Route> routes,
								   List<Link> fromLinks, List<Link> toLinks,
								   double departureTime, double submissionTime);

	/**
	 * Convenience overload for creating single-link requests (e.g., taxi, door-to-door DRT).
	 * Default implementation wraps the links into singleton sets and delegates to the multi-link method.
	 * <p>
	 * Thread safety: This method can be called concurrently from multiple QSim worker threads.
	 * Prefer stateless implementation, otherwise provide other ways to achieve thread-safety.
	 *
	 * @param id             	request ID
	 * @param passengerIds   	list of unique passenger IDs
	 * @param routes          	list of planned routes (the required route type depends on the optimizer)
	 * @param fromLink    		single pickup location
	 * @param toLink    		single dropoff location
	 * @param departureTime  	requested time of departure
	 * @param submissionTime 	time at which request was submitted
	 * @return the created passenger request
	 * @deprecated Use the multi-link variant for better flexibility. This method is provided for backward compatibility.
	 */
	default PassengerRequest createRequest(Id<Request> id, List<Id<Person>> passengerIds, List<Route> routes,
										   Link fromLink, Link toLink,
										   double departureTime, double submissionTime) {
		return createRequest(id, passengerIds, routes, List.of(fromLink), List.of(toLink), departureTime, submissionTime);
	}
}
