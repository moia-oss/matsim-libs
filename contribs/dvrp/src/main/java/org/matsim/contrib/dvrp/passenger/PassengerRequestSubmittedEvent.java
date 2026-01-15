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
import java.util.stream.Collectors;

import com.google.common.base.Verify;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.GenericEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.dvrp.load.DvrpLoad;
import org.matsim.contrib.dvrp.optimizer.Request;

/**
 * @author michalm
 */
public class PassengerRequestSubmittedEvent extends AbstractPassengerRequestEvent {
	public static final String EVENT_TYPE = "PassengerRequest submitted";

	public static final String ATTRIBUTE_FROM_LINK = "fromLink";
	public static final String ATTRIBUTE_TO_LINK = "toLink";
	public static final String ATTRIBUTE_FROM_LINKS = "fromLinks";
	public static final String ATTRIBUTE_TO_LINKS = "toLinks";
	public static final String ATTRIBUTE_LOAD = "load";
	public static final String DELIMITER = ",";

	private final DvrpLoad load;
	private final String serializedLoad;

	private final List<Id<Link>> fromLinkIds;
	private final List<Id<Link>> toLinkIds;

	public PassengerRequestSubmittedEvent(double time, String mode, Id<Request> requestId, List<Id<Person>> personIds,
										  List<Id<Link>> fromLinkIds, List<Id<Link>> toLinkIds,
										  DvrpLoad load, String serializedDvrpLoad) {
		super(time, mode, requestId, personIds);
		Verify.verifyNotNull(fromLinkIds);
		Verify.verifyNotNull(toLinkIds);
		this.fromLinkIds = fromLinkIds;
		this.toLinkIds = toLinkIds;
		this.load = load;
		this.serializedLoad = serializedDvrpLoad;
	}


	@Override
	public String getEventType() {
		return EVENT_TYPE;
	}

	/**
	 * @return ordered set of origin link IDs, with most preferred first
	 */
	public List<Id<Link>> getFromLinkIds() {
		return fromLinkIds;
	}

	/**
	 * @return ordered set of destination link IDs, with most preferred first
	 */
	public List<Id<Link>> getToLinkIds() {
		return toLinkIds;
	}

	/**
	 * Convenience method returning the primary/preferred origin link ID.
	 * Equivalent to {@code getFromLinkIds().getFirst()}.
	 *
	 * @return the primary origin link ID
	 * @deprecated Use {@link #getFromLinkIds()} for multi-link support
	 */
	@Deprecated
	public Id<Link> getFromLinkId() {
		return fromLinkIds.getFirst();
	}

	/**
	 * Convenience method returning the primary/preferred destination link ID.
	 * Equivalent to {@code getToLinkIds().getFirst()}.
	 *
	 * @return the primary destination link ID
	 * @deprecated Use {@link #getToLinkIds()} for multi-link support
	 */
	@Deprecated
	public Id<Link> getToLinkId() {
		return toLinkIds.getFirst();
	}

	@Override
	public Map<String, String> getAttributes() {
		Map<String, String> attr = super.getAttributes();
		attr.put(ATTRIBUTE_FROM_LINKS, fromLinkIds.stream().map(Object::toString).collect(Collectors.joining(",")));
		attr.put(ATTRIBUTE_TO_LINKS, toLinkIds.stream().map(Object::toString).collect(Collectors.joining(",")));
		attr.put(ATTRIBUTE_LOAD, serializedLoad);
		return attr;
	}

	public static PassengerRequestSubmittedEvent convert(GenericEvent event) {
		Map<String, String> attributes = event.getAttributes();
		double time = Double.parseDouble(attributes.get(ATTRIBUTE_TIME));
		String mode = Objects.requireNonNull(attributes.get(ATTRIBUTE_MODE));
		Id<Request> requestId = Id.create(attributes.get(ATTRIBUTE_REQUEST), Request.class);
		String[] personIdsAttribute = attributes.get(ATTRIBUTE_PERSON).split(DELIMITER);

		List<Id<Person>> personIds = new ArrayList<>();
		for (String person : personIdsAttribute) {
			personIds.add(Id.create(person, Person.class));
		}

		LinkIds linkIds = parseLinkIds(attributes);

		String serializedLoad  = attributes.get(ATTRIBUTE_LOAD);
		return new PassengerRequestSubmittedEvent(time, mode, requestId, personIds, linkIds.fromLinkIds(), linkIds.toLinkIds(), null, serializedLoad);
	}

	protected static LinkIds parseLinkIds(Map<String, String> attributes) {
		String fromLinkAttr = attributes.get(ATTRIBUTE_FROM_LINK);
		String toLinkAttr = attributes.get(ATTRIBUTE_TO_LINK);

		String fromLinksAttr = attributes.get(ATTRIBUTE_FROM_LINKS);
		String toLinksAttr = attributes.get(ATTRIBUTE_TO_LINKS);

		Verify.verify((fromLinkAttr != null && toLinkAttr != null) ^ (fromLinksAttr != null && toLinksAttr != null),
				"Submission event must have either fromLink and toLink or fromLinks and toLinks set");

		List<Id<Link>> fromLinkIds = new ArrayList<>();
		List<Id<Link>> toLinkIds = new ArrayList<>();

		if(fromLinkAttr != null && toLinkAttr != null) {
			Id<Link> fromLinkId = Id.createLinkId(fromLinkAttr);
			Id<Link> toLinkId = Id.createLinkId(toLinkAttr);
			fromLinkIds.add(fromLinkId);
			toLinkIds.add(toLinkId);
		} else {
			String[] fromLinkIdsAttribute = fromLinksAttr.split(DELIMITER);
			String[] toLinkIdsAttribute = toLinksAttr.split(DELIMITER);

			for (String fromLinkId : fromLinkIdsAttribute) {
				fromLinkIds.add(Id.createLinkId(fromLinkId));
			}
			for (String toLinkId : toLinkIdsAttribute) {
				toLinkIds.add(Id.createLinkId(toLinkId));
			}
		}
		return new LinkIds(Collections.unmodifiableList(fromLinkIds), Collections.unmodifiableList(toLinkIds));
	}

	protected record LinkIds(List<Id<Link>> fromLinkIds, List<Id<Link>> toLinkIds) {}

	public DvrpLoad getLoad() {
		return load;
	}

	public String getSerializedLoad() {
		return serializedLoad;
	}
}
