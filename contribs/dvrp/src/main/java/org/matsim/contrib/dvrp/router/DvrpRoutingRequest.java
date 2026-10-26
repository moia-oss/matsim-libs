package org.matsim.contrib.dvrp.router;

import org.matsim.api.core.v01.population.Person;
import org.matsim.core.router.DefaultRoutingRequest;
import org.matsim.facilities.Facility;
import org.matsim.utils.objectattributes.attributable.Attributes;
import org.matsim.utils.objectattributes.attributable.AttributesImpl;

import java.util.List;

public class DvrpRoutingRequest extends DefaultRoutingRequest {

	private final List<Facility> fromFacilities;
	private final List<Facility> toFacilities;

	protected DvrpRoutingRequest(List<Facility> fromFacilities, List<Facility> toFacilities, double departureTime, Person person, Attributes attributes) {
		super(fromFacilities.getFirst(), toFacilities.getFirst(), departureTime, person, attributes);
		this.fromFacilities = fromFacilities;
		this.toFacilities = toFacilities;
	}

	static public DvrpRoutingRequest of(Facility fromFacility, Facility toFacility, double departureTime, Person person,
									Attributes attributes) {
		return new DvrpRoutingRequest(List.of(fromFacility), List.of(toFacility), departureTime, person, attributes);
	}

	static public DvrpRoutingRequest withoutAttributes(Facility fromFacility, Facility toFacility, double departureTime,
												   Person person) {
		return new DvrpRoutingRequest(List.of(fromFacility), List.of(toFacility), departureTime, person, new AttributesImpl());
	}

	static public DvrpRoutingRequest of(List<Facility> fromFacility, List<Facility> toFacility, double departureTime, Person person,
									Attributes attributes) {
		return new DvrpRoutingRequest(fromFacility, toFacility, departureTime, person, attributes);
	}

	static public DvrpRoutingRequest withoutAttributes(List<Facility> fromFacility, List<Facility> toFacility, double departureTime,
												   Person person) {
		return new DvrpRoutingRequest(fromFacility, toFacility, departureTime, person, new AttributesImpl());
	}

	public List<Facility> getFromFacilities() {
		return fromFacilities;
	}

	public List<Facility> getToFacilities() {
		return toFacilities;
	}

}
