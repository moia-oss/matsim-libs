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

package org.matsim.contrib.drt.optimizer;

import java.util.NoSuchElementException;
import java.util.Optional;

import jakarta.annotation.Nullable;

import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.drt.passenger.DrtRequest;
import org.matsim.contrib.dvrp.load.DvrpLoad;
import org.matsim.contrib.dvrp.schedule.Task;
import org.matsim.core.utils.misc.OptionalTime;

import com.google.common.base.MoreObjects;

/**
 * @author Michal Maciejewski (michalm)
 */
public interface Waypoint {
	Link getLink();

	double getArrivalTime();

	double getDepartureTime();

	DvrpLoad getOutgoingOccupancy();

	class Start implements Waypoint {
		public final Optional<Task> task;// empty if schedule status is PLANNED
		public final Link link;
		public final double time;
		public final DvrpLoad occupancy;

		public Start(@Nullable Task task, Link link, double time, DvrpLoad occupancy) {
			this.task = Optional.ofNullable(task);
			this.link = link;
			this.time = time;
			this.occupancy = occupancy;
		}

		@Override
		public Link getLink() {
			return link;
		}

		@Override
		public double getArrivalTime() {
			throw new UnsupportedOperationException("No arrival time for start waypoint");
		}

		@Override
		public double getDepartureTime() {
			return time;
		}

		@Override
		public DvrpLoad getOutgoingOccupancy() {
			return occupancy;
		}

		@Override
		public String toString() {
			return MoreObjects.toStringHelper(this)
					.add("task", task)
					.add("link", link)
					.add("time", time)
					.add("occupancy", occupancy)
					.toString();
		}
	}

	class End implements Waypoint {
		public static final End OPEN_END = new End();

		public final Optional<Link> link;//null if open-end route
		public final OptionalTime arrivalTime;//undefined if open-end route

		private End() {
			link = Optional.empty();
			arrivalTime = OptionalTime.undefined();
		}

		public End(Link link, double arrivalTime) {
			this.link = Optional.of(link);
			this.arrivalTime = OptionalTime.defined(arrivalTime);
		}

		public boolean isOpenEnd() {
			return link.isEmpty();
		}

		@Override
		public Link getLink() {
			return link.orElseThrow(() -> new NoSuchElementException("Open-end route -- no end link provided"));
		}

		@Override
		public double getArrivalTime() {
			return arrivalTime.seconds();
		}

		@Override
		public double getDepartureTime() {
			throw new UnsupportedOperationException("No departure time for end waypoint");
		}

		@Override
		public DvrpLoad getOutgoingOccupancy() {
			throw new UnsupportedOperationException("End is the terminal waypoint");
		}

		@Override
		public String toString() {
			return MoreObjects.toStringHelper(this).add("link", link).add("arrivalTime", arrivalTime).toString();
		}
	}

	class Pickup implements Waypoint {
		public final DrtRequest request;
		private final Link accessLink;

		public Pickup(DrtRequest request, Link accessLink) {
			this.request = request;
			this.accessLink = accessLink;
		}

		@Override
		public Link getLink() {
			return accessLink;
		}

		@Override
		public double getArrivalTime() {
			throw new UnsupportedOperationException();
		}

		@Override
		public double getDepartureTime() {
			throw new UnsupportedOperationException();
		}

		@Override
		public DvrpLoad getOutgoingOccupancy() {
			throw new UnsupportedOperationException();
		}

		@Override
		public String toString() {
			return MoreObjects.toStringHelper(this).add("request", request).toString();
		}
	}

	class Dropoff implements Waypoint {
		public final DrtRequest request;
		private final Link egressLink;

		public Dropoff(DrtRequest request, Link egressLink) {
			this.request = request;
			this.egressLink = egressLink;
		}

		@Override
		public Link getLink() {
			return egressLink;
		}

		@Override
		public double getArrivalTime() {
			throw new UnsupportedOperationException();
		}

		@Override
		public double getDepartureTime() {
			throw new UnsupportedOperationException();
		}

		@Override
		public DvrpLoad getOutgoingOccupancy() {
			throw new UnsupportedOperationException();
		}

		@Override
		public String toString() {
			return MoreObjects.toStringHelper(this).add("request", request).toString();
		}
	}
}
