package org.matsim.contrib.dvrp.router.accessegress;

import org.matsim.facilities.Facility;
import org.matsim.utils.objectattributes.attributable.Attributes;

import java.util.Optional;

public interface AccessEgressFacilityFinder {
	Optional<AccessEgressFacilities> findFacilities(Facility fromFacility, Facility toFacility,
		Attributes tripAttributes);

}
