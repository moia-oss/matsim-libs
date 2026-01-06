package org.matsim.contrib.dvrp.router.accessegress;

import org.matsim.facilities.Facility;

import java.util.Set;

public record AccessEgressFacilities(Set<Facility> access, Set<Facility> egress) {
}
