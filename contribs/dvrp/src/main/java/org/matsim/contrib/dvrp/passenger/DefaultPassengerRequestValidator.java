/*
 * *********************************************************************** *
 * project: org.matsim.*
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2018 by the members listed in the COPYING,        *
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

import com.google.common.collect.Sets;
import org.matsim.api.core.v01.network.Link;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Accepts all DRT requests as long as the start and end link are different.
 *
 * @author jbischoff
 * @author michalm (Michal Maciejewski)
 * @author nkuehnel
 */
public class DefaultPassengerRequestValidator implements PassengerRequestValidator {
	public static final String EQUAL_FROM_LINK_AND_TO_LINK_CAUSE = "equal_fromLink_and_toLink";
	public static final String EMPTY_ACCESS_LINKS = "empty_access_links";
	public static final String EMPTY_EGRESS_LINKS = "empty_egress_links";
	public static final String EQUAL_ACCESS_EGRESS_LINKS = "equal_access_egress_link";

	@Override
	public Set<String> validateRequest(PassengerRequest request) {
		Set<String> errors = null;

		// Traditional validation: fromLink and toLink must be different
		if (request.getFromLink() == request.getToLink()) {
			errors = new HashSet<>();
			errors.add(EQUAL_FROM_LINK_AND_TO_LINK_CAUSE);
		}

		Set<Link> accessLinks = request.getAccessLinkCandidates();
		Set<Link> egressLinks = request.getEgressLinkCandidates();

		// Defensive validation: access/egress candidates should not be empty
		if(accessLinks.isEmpty()) {
			errors = errors == null ? new HashSet<>() : errors;
			errors.add(EMPTY_ACCESS_LINKS);
		}
		if(egressLinks.isEmpty()) {
			errors = errors == null ? new HashSet<>() : errors;
			errors.add(EMPTY_EGRESS_LINKS);
		}

		// If both sets have exactly one element and they're the same, request is invalid
		// (would generate zero valid insertions)
		if(accessLinks.size() == 1 && egressLinks.size() == 1) {
			if(accessLinks.equals(egressLinks)) {
				errors = errors == null ? new HashSet<>() : errors;
				errors.add(EQUAL_ACCESS_EGRESS_LINKS);
			}
		}

		return errors == null ? Collections.emptySet() : errors;
	}
}
