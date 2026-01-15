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

package org.matsim.contrib.drt.passenger;

import com.google.common.base.MoreObjects;
import com.google.common.base.Verify;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.drt.optimizer.constraints.DrtRouteConstraints;
import org.matsim.contrib.dvrp.load.DvrpLoad;
import org.matsim.contrib.dvrp.optimizer.Request;
import org.matsim.contrib.dvrp.passenger.PassengerRequest;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @author michalm
 *
 * @author nkuehnel / MOIA
 * @author mfrawley / MOIA
 */
public class DrtRequest implements PassengerRequest {
	private final Id<Request> id;
	private final double submissionTime;

	private final List<Id<Person>> passengerIds;
	private final String mode;

	private final List<Link> fromLinks;
	private final List<Link> toLinks;

	private final DvrpLoad load;

	private final DrtRouteConstraints constraints;

	private final double earliestStartTime;
	private final double latestStartTime;
	private final double latestArrivalTime;

	private DrtRequest(Builder builder) {
		id = builder.id;
		submissionTime = builder.submissionTime;
		earliestStartTime = builder.earliestDepartureTime;
		latestStartTime = earliestStartTime + builder.constraints.maxWaitDuration();
		latestArrivalTime = earliestStartTime + builder.constraints.maxTravelDuration();
		constraints = builder.constraints;
		mode = builder.mode;
		passengerIds = List.copyOf(builder.passengerIds);
		fromLinks = List.copyOf(builder.fromLinks);
		toLinks = List.copyOf(builder.toLinks);
		this.load = builder.load;
	}

	public static Builder newBuilder() {
		return new Builder();
	}

	public static Builder newBuilder(DrtRequest copy) {
		Builder builder = new Builder();
		builder.id = copy.getId();
		builder.submissionTime = copy.getSubmissionTime();
		builder.passengerIds = List.copyOf(copy.getPassengerIds());
		builder.mode = copy.getMode();
		builder.earliestDepartureTime = copy.earliestStartTime;
		builder.constraints = copy.constraints;
		builder.load = copy.load;
		builder.fromLinks = List.copyOf(copy.fromLinks);
		builder.toLinks = List.copyOf(copy.toLinks);
		return builder;
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
	public double getLatestStartTime() {
		return latestStartTime;
	}

	public double getLatestArrivalTime() {
		return latestArrivalTime;
	}

	public DrtRouteConstraints getConstraints() {
		return constraints;
	}

	@Override
	public List<Link> getFromLinks() {
		return fromLinks;
	}

	@Override
	public List<Link> getToLinks() {
		return toLinks;
	}

	@Override
	public List<Id<Person>> getPassengerIds() {
		return passengerIds;
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
	public String toString() {
		return MoreObjects.toStringHelper(this)
				.add("id", id)
				.add("submissionTime", submissionTime)
				.add("earliestStartTime", earliestStartTime)
				.add("latestStartTime", latestStartTime)
				.add("latestArrivalTime", latestArrivalTime)
				.add("maxRideDuration", constraints.maxRideDuration())
				.add("passengerIds", passengerIds.stream().map(Object::toString).collect(Collectors.joining(",")))
				.add("mode", mode)
				.add("fromLink", fromLinks)
				.add("toLink", toLinks)
				.toString();
	}

	public static final class Builder {
		private Id<Request> id;
		private double submissionTime;
		private double earliestDepartureTime;

		private DrtRouteConstraints constraints = DrtRouteConstraints.UNDEFINED;

		private List<Id<Person>> passengerIds = new ArrayList<>();

		private List<Link> fromLinks;
		private List<Link> toLinks;

		private String mode;
		private DvrpLoad load;

		private Builder() {}

		public Builder id(Id<Request> val) {
			id = val;
			return this;
		}

		public Builder submissionTime(double val) {
			submissionTime = val;
			return this;
		}

		public Builder constraints(DrtRouteConstraints val) {
			constraints = val;
			return this;
		}

		public Builder earliestDepartureTime(double val) {
			earliestDepartureTime = val;
			return this;
		}

		public Builder passengerIds(List<Id<Person>> val) {
			passengerIds = new ArrayList<>(val);
			return this;
		}

		public Builder mode(String val) {
			mode = val;
			return this;
		}

		public Builder load(DvrpLoad load) {
			this.load = load;
			return this;
		}

		/**
		 * Set ordered set of potential pickup links, with most preferred first.
		 * For stop-based DRT with multiple candidates.
		 *
		 * @param fromLinkCandidates ordered set of pickup link candidates
		 * @return this builder
		 */
		public Builder fromLinks(List<Link> fromLinkCandidates) {
			Verify.verifyNotNull(fromLinkCandidates, "fromLinkCandidates may not be null.");
			this.fromLinks = List.copyOf(fromLinkCandidates);
			return this;
		}

		/**
		 * Set ordered set of potential dropoff links, with most preferred first.
		 * For stop-based DRT with multiple candidates.
		 *
		 * @param toLinkCandidates ordered set of dropoff link candidates
		 * @return this builder
		 */
		public Builder toLinks(List<Link> toLinkCandidates) {
			Verify.verifyNotNull(toLinkCandidates, "toLinkCandidates may not be null.");
			this.toLinks = List.copyOf(toLinkCandidates);
			return this;
		}

		/**
		 * Convenience method for single pickup link (e.g., taxi, door-to-door DRT).
		 * Wraps the link into a singleton set.
		 *
		 * @param fromLink single pickup link
		 * @return this builder
		 * @deprecated Use {@link #fromLinks(List)} for consistency
		 */
		@Deprecated
		public Builder fromLink(Link fromLink) {
			Verify.verifyNotNull(fromLink, "fromLink may not be null.");
			this.fromLinks = List.of(fromLink);
			return this;
		}

		/**
		 * Convenience method for single dropoff link (e.g., taxi, door-to-door DRT).
		 * Wraps the link into a singleton set.
		 *
		 * @param toLink single dropoff link
		 * @return this builder
		 * @deprecated Use {@link #toLinks(List)} for consistency
		 */
		@Deprecated
		public Builder toLink(Link toLink) {
			Verify.verifyNotNull(toLink, "toLink may not be null.");
			this.toLinks = List.of(toLink);
			return this;
		}

		public DrtRequest build() {
			return new DrtRequest(this);
		}
	}
}
