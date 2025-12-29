/*
 * *********************************************************************** *
 * project: org.matsim.*
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2025 by the members listed in the COPYING,        *
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

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.testcases.fakes.FakeLink;

import java.util.Collections;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for DefaultPassengerRequestValidator
 *
 * @author nkuehnel
 */
public class DefaultPassengerRequestValidatorTest {

	private final DefaultPassengerRequestValidator validator = new DefaultPassengerRequestValidator();

	@Test
	void testValidRequest_singleLinks() {
		// Traditional case: single access and egress link, different from each other
		PassengerRequest request = createRequest(
			link("from"),
			link("to"),
			Set.of(link("from")),
			Set.of(link("to"))
		);

		Set<String> errors = validator.validateRequest(request);
		assertThat(errors).isEmpty();
	}

	@Test
	void testValidRequest_multipleLinks() {
		// Dynamic stop selection: multiple access and egress candidates
		Link from = link("from");
		Link to = link("to");

		PassengerRequest request = createRequest(
			from,
			to,
			Set.of(link("access1"), link("access2"), link("access3")),
			Set.of(link("egress1"), link("egress2"), link("egress3"))
		);

		Set<String> errors = validator.validateRequest(request);
		assertThat(errors).isEmpty();
	}

	@Test
	void testInvalidRequest_sameFromToLink() {
		// Traditional validation: fromLink equals toLink
		Link sameLink = link("same");
		PassengerRequest request = createRequest(
			sameLink,
			sameLink,
			Set.of(link("access1"), link("access2")),
			Set.of(link("egress1"), link("egress2"))
		);

		Set<String> errors = validator.validateRequest(request);
		assertThat(errors).containsExactly(DefaultPassengerRequestValidator.EQUAL_FROM_LINK_AND_TO_LINK_CAUSE);
	}

	@Test
	void testInvalidRequest_emptyAccessLinks() {
		PassengerRequest request = createRequest(
			link("from"),
			link("to"),
			Collections.emptySet(), // Empty access links
			Set.of(link("egress1"))
		);

		Set<String> errors = validator.validateRequest(request);
		assertThat(errors).contains(DefaultPassengerRequestValidator.EMPTY_ACCESS_LINKS);
	}

	@Test
	void testInvalidRequest_emptyEgressLinks() {
		PassengerRequest request = createRequest(
			link("from"),
			link("to"),
			Set.of(link("access1")),
			Collections.emptySet() // Empty egress links
		);

		Set<String> errors = validator.validateRequest(request);
		assertThat(errors).contains(DefaultPassengerRequestValidator.EMPTY_EGRESS_LINKS);
	}

	@Test
	void testInvalidRequest_emptyBoth() {
		PassengerRequest request = createRequest(
			link("from"),
			link("to"),
			Collections.emptySet(),
			Collections.emptySet()
		);

		Set<String> errors = validator.validateRequest(request);
		assertThat(errors).containsExactlyInAnyOrder(
			DefaultPassengerRequestValidator.EMPTY_ACCESS_LINKS,
			DefaultPassengerRequestValidator.EMPTY_EGRESS_LINKS
		);
	}

	@Test
	void testInvalidRequest_singleEqualAccessEgressLink() {
		// Special case: both sets contain exactly one element and they're the same
		// This would generate zero valid insertions (same-link filtering)
		Link sameLink = link("same");
		PassengerRequest request = createRequest(
			link("from"),
			link("to"),
			Set.of(sameLink),
			Set.of(sameLink)
		);

		Set<String> errors = validator.validateRequest(request);
		assertThat(errors).contains(DefaultPassengerRequestValidator.EQUAL_ACCESS_EGRESS_LINKS);
	}

	@Test
	void testValidRequest_overlappingMultipleLinks() {
		// Valid: Multiple links with some overlap (but not single equal link)
		Link shared = link("shared");
		PassengerRequest request = createRequest(
			link("from"),
			link("to"),
			Set.of(shared, link("access1")),
			Set.of(shared, link("egress1"))
		);

		Set<String> errors = validator.validateRequest(request);
		// Valid because there are multiple candidates - some combinations will work
		assertThat(errors).isEmpty();
	}

	@Test
	void testInvalidRequest_multipleErrors() {
		// Combination of errors: same fromLink/toLink AND empty access/egress
		Link sameLink = link("same");
		PassengerRequest request = createRequest(
			sameLink,
			sameLink,
			Collections.emptySet(),
			Collections.emptySet()
		);

		Set<String> errors = validator.validateRequest(request);
		assertThat(errors).containsExactlyInAnyOrder(
			DefaultPassengerRequestValidator.EQUAL_FROM_LINK_AND_TO_LINK_CAUSE,
			DefaultPassengerRequestValidator.EMPTY_ACCESS_LINKS,
			DefaultPassengerRequestValidator.EMPTY_EGRESS_LINKS
		);
	}

	@Test
	void testValidRequest_singleAccessMultipleEgress() {
		// Edge case: single access, multiple egress - should be valid
		PassengerRequest request = createRequest(
			link("from"),
			link("to"),
			Set.of(link("access1")),
			Set.of(link("egress1"), link("egress2"), link("egress3"))
		);

		Set<String> errors = validator.validateRequest(request);
		assertThat(errors).isEmpty();
	}

	@Test
	void testValidRequest_multipleAccessSingleEgress() {
		// Edge case: multiple access, single egress - should be valid
		PassengerRequest request = createRequest(
			link("from"),
			link("to"),
			Set.of(link("access1"), link("access2"), link("access3")),
			Set.of(link("egress1"))
		);

		Set<String> errors = validator.validateRequest(request);
		assertThat(errors).isEmpty();
	}

	// Helper methods

	private PassengerRequest createRequest(Link fromLink, Link toLink, Set<Link> accessLinks, Set<Link> egressLinks) {
		PassengerRequest request = mock(PassengerRequest.class);
		when(request.getFromLink()).thenReturn(fromLink);
		when(request.getToLink()).thenReturn(toLink);
		when(request.getAccessLinkCandidates()).thenReturn(accessLinks);
		when(request.getEgressLinkCandidates()).thenReturn(egressLinks);
		return request;
	}

	private Link link(String id) {
		return new FakeLink(Id.createLinkId(id));
	}
}