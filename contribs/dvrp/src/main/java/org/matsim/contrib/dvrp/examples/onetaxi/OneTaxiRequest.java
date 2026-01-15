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

package org.matsim.contrib.dvrp.examples.onetaxi;

import com.google.common.base.Verify;
import com.google.common.collect.ImmutableSet;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Route;
import org.matsim.contrib.dvrp.load.DvrpLoad;
import org.matsim.contrib.dvrp.load.IntegerLoad;
import org.matsim.contrib.dvrp.optimizer.Request;
import org.matsim.contrib.dvrp.passenger.PassengerRequest;
import org.matsim.contrib.dvrp.passenger.PassengerRequestCreator;

import java.util.*;

/**
 * @author michalm
 */
public final class OneTaxiRequest implements PassengerRequest {
	private final Id<Request> id;
	private final double submissionTime;
	private final double earliestStartTime;

	private final List<Id<Person>> passengerIds = new ArrayList<>();
	private final String mode;

	private final List<Link> fromLinks;
	private final List<Link> toLinks;
	private final DvrpLoad load;

	public OneTaxiRequest(Id<Request> id, Collection<Id<Person>> passengerIds, String mode,
						  List<Link> fromLinks, List<Link> toLinks,
						  double departureTime, double submissionTime) {
		this.id = id;
		this.submissionTime = submissionTime;
		this.earliestStartTime = departureTime;
		this.passengerIds.addAll(passengerIds);
		this.mode = mode;
		this.fromLinks = List.copyOf(fromLinks);
		this.toLinks = List.copyOf(toLinks);
		this.load = IntegerLoad.fromValue(passengerIds.size());
	}

	@Override
	public Id<Request> getId() {
		return id;
	}

	@Override
	public double getSubmissionTime() {
		return submissionTime;
	}

	@Override
	public double getEarliestStartTime() {
		return earliestStartTime;
	}

	@Override
	public List<Id<Person>> getPassengerIds() {
		return List.copyOf(passengerIds);
	}

	@Override
	public String getMode() {
		return mode;
	}

	@Override
	public DvrpLoad getLoad() {
		return this.load;
	}

	@Override
	public List<Link> getFromLinks() {
		return fromLinks;
	}

	@Override
	public List<Link> getToLinks() {
		return toLinks;
	}

	public static final class OneTaxiRequestCreator implements PassengerRequestCreator {
		@Override
		public OneTaxiRequest createRequest(Id<Request> id, List<Id<Person>> passengerIds, List<Route> routes,
											List<Link> fromLinks, List<Link> toLinks,
											double departureTime, double submissionTime) {
			Verify.verify(fromLinks.size() == 1, "OneTaxi only supports single pickup link");
			Verify.verify(toLinks.size() == 1, "OneTaxi only supports single dropoff link");
			return new OneTaxiRequest(id, passengerIds, TransportMode.taxi, fromLinks, toLinks, departureTime,
					submissionTime);
		}
	}
}
