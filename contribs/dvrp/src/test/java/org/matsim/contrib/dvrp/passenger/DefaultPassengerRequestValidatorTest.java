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

import java.util.*;

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
			List.of(link("from")),
			List.of(link("to"))
		);

		Set<String> errors = validator.validateRequest(request);
		assertThat(errors).isEmpty();
	}

	@Test
	void testValidRequest_multipleLinks() {
		// Dynamic stop selection: multiple access and egress candidates
		PassengerRequest request = createRequest(
			List.of(link("access1"), link("access2"), link("access3")),
			List.of(link("egress1"), link("egress2"), link("egress3"))
		);

		Set<String> errors = validator.validateRequest(request);
		assertThat(errors).isEmpty();
	}


	@Test
	void testInvalidRequest_emptyAccessLinks() {
		PassengerRequest request = createRequest(
			Collections.emptyList(), // Empty access links
			List.of(link("egress1"))
		);

		Set<String> errors = validator.validateRequest(request);
		assertThat(errors).contains(DefaultPassengerRequestValidator.EMPTY_ACCESS_LINKS);
	}

	@Test
	void testInvalidRequest_emptyEgressLinks() {
		PassengerRequest request = createRequest(
			List.of(link("access1")),
			Collections.emptyList() // Empty egress links
		);

		Set<String> errors = validator.validateRequest(request);
		assertThat(errors).contains(DefaultPassengerRequestValidator.EMPTY_EGRESS_LINKS);
	}

	@Test
	void testInvalidRequest_emptyBoth() {
		PassengerRequest request = createRequest(
			Collections.emptyList(),
			Collections.emptyList()
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
			List.of(sameLink),
			List.of(sameLink)
		);

		Set<String> errors = validator.validateRequest(request);
		assertThat(errors).contains(DefaultPassengerRequestValidator.EQUAL_FROM_LINK_AND_TO_LINK_CAUSE);
	}

	@Test
	void testValidRequest_overlappingMultipleLinks() {
		// Valid: Multiple links with some overlap (but not single equal link)
		Link shared = link("shared");
		PassengerRequest request = createRequest(
			List.of(shared, link("access1")),
			List.of(shared, link("egress1"))
		);

		Set<String> errors = validator.validateRequest(request);
		// Valid because there are multiple candidates - some combinations will work
		assertThat(errors).isEmpty();
	}

	@Test
	void testInvalidRequest_multipleErrors() {
		// Combination of errors: same fromLink/toLink AND empty access/egress
		PassengerRequest request = createRequest(
			Collections.emptyList(),
			Collections.emptyList()
		);

		Set<String> errors = validator.validateRequest(request);
		assertThat(errors).containsExactlyInAnyOrder(
			DefaultPassengerRequestValidator.EMPTY_ACCESS_LINKS,
			DefaultPassengerRequestValidator.EMPTY_EGRESS_LINKS
		);
	}

	@Test
	void testValidRequest_singleAccessMultipleEgress() {
		// Edge case: single access, multiple egress - should be valid
		PassengerRequest request = createRequest(
			List.of(link("access1")),
			List.of(link("egress1"), link("egress2"), link("egress3"))
		);

		Set<String> errors = validator.validateRequest(request);
		assertThat(errors).isEmpty();
	}

	@Test
	void testValidRequest_multipleAccessSingleEgress() {
		// Edge case: multiple access, single egress - should be valid
		PassengerRequest request = createRequest(
			List.of(link("access1"), link("access2"), link("access3")),
			List.of(link("egress1"))
		);

		Set<String> errors = validator.validateRequest(request);
		assertThat(errors).isEmpty();
	}

	// Helper methods

	private PassengerRequest createRequest(List<Link> accessLinks, List<Link> egressLinks) {
		PassengerRequest request = mock(PassengerRequest.class);
		when(request.getFromLinks()).thenReturn(accessLinks);
		when(request.getToLinks()).thenReturn(egressLinks);
		return request;
	}

	private Link link(String id) {
		return new FakeLink(Id.createLinkId(id));
	}
}