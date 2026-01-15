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

package org.matsim.contrib.dvrp.passenger;

import java.util.*;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.GenericEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.optimizer.Request;

import static org.matsim.api.core.v01.events.HasPersonId.ATTRIBUTE_PERSON;

/**
 * @author michalm
 * @author nkuehnel
 */
public class PassengerRequestScheduledEvent extends AbstractPassengerRequestEvent {
	public static final String EVENT_TYPE = "PassengerRequest scheduled";

	public static final String ATTRIBUTE_VEHICLE = "vehicle";
	public static final String ATTRIBUTE_PICKUP_TIME = "pickupTime";
	public static final String ATTRIBUTE_DROPOFF_TIME = "dropoffTime";
	public static final String ATTRIBUTE_PICKUP_LINK = "pickupLink";
	public static final String ATTRIBUTE_DROPOFF_LINK = "dropoffLink";

	private final Id<DvrpVehicle> vehicleId;
	private final double pickupTime;
	private final double dropoffTime;
	private final Id<Link> pickupLink;
	private final Id<Link> dropoffLink;

	/**
	 * An event processed upon request scheduling.
	 */
	public PassengerRequestScheduledEvent(double time, String mode, Id<Request> requestId, List<Id<Person>> personIds,
			Id<DvrpVehicle> vehicleId, double pickupTime, double dropoffTime, Id<Link> pickupLink, Id<Link> dropoffLink) {
		super(time, mode, requestId, personIds);
		this.vehicleId = vehicleId;
		this.pickupTime = pickupTime;
		this.dropoffTime = dropoffTime;
		this.pickupLink = pickupLink;
		this.dropoffLink = dropoffLink;
	}

	@Override
	public String getEventType() {
		return EVENT_TYPE;
	}

	/**
	 * @return id of the vehicle assigned to the request
	 */
	public final Id<DvrpVehicle> getVehicleId() {
		return vehicleId;
	}

	/**
	 * @return <b>estimated</b> pickup time
	 */
	public final double getPickupTime() {
		return pickupTime;
	}

	/**
	 * @return <b>estimated</b> dropoff time
	 */
	public final double getDropoffTime() {
		return dropoffTime;
	}

	@Override
	public Map<String, String> getAttributes() {
		Map<String, String> attr = super.getAttributes();
		attr.put(ATTRIBUTE_VEHICLE, vehicleId + "");
		attr.put(ATTRIBUTE_PICKUP_TIME, pickupTime + "");
		attr.put(ATTRIBUTE_DROPOFF_TIME, dropoffTime + "");
		attr.put(ATTRIBUTE_PICKUP_LINK, pickupLink + "");
		attr.put(ATTRIBUTE_DROPOFF_LINK, dropoffLink + "");
		return attr;
	}

	public static PassengerRequestScheduledEvent convert(GenericEvent event) {
		Map<String, String> attributes = event.getAttributes();
		double time = Double.parseDouble(attributes.get(ATTRIBUTE_TIME));
		String mode = Objects.requireNonNull(attributes.get(ATTRIBUTE_MODE));
		Id<Request> requestId = Id.create(attributes.get(ATTRIBUTE_REQUEST), Request.class);
		String[] personIdsAttribute = attributes.get(ATTRIBUTE_PERSON).split(",");
		List<Id<Person>> personIds = new ArrayList<>();
		for (String person : personIdsAttribute) {
			personIds.add(Id.create(person, Person.class));
		}
		Id<DvrpVehicle> vehicleId = Id.create(attributes.get(ATTRIBUTE_VEHICLE), DvrpVehicle.class);
		double pickupTime = Double.parseDouble(attributes.get(ATTRIBUTE_PICKUP_TIME));
		double dropoffTime = Double.parseDouble(attributes.get(ATTRIBUTE_DROPOFF_TIME));
		Id<Link> pickupLink = Id.createLinkId(attributes.get(ATTRIBUTE_PICKUP_LINK));
		Id<Link> dropoffLink = Id.createLinkId(attributes.get(ATTRIBUTE_DROPOFF_LINK));
		return new PassengerRequestScheduledEvent(time, mode, requestId, personIds, vehicleId, pickupTime, dropoffTime, pickupLink, dropoffLink);
	}
}
