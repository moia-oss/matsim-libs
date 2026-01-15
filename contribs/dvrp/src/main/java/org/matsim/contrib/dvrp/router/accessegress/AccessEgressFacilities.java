package org.matsim.contrib.dvrp.router.accessegress;

import org.matsim.facilities.Facility;

import java.util.List;

public record AccessEgressFacilities(List<Facility> access, List<Facility> egress) {
}
