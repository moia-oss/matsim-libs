/* *********************************************************************** *
 * project: org.matsim.*
 * BasicHouseholds
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2009 by the members listed in the COPYING,        *
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
 * *********************************************************************** */
package org.matsim.households;

import java.util.Map;

import org.matsim.api.core.v01.Id;
import org.matsim.core.api.internal.MatsimToplevelContainer;


/**
 * @author dgrether
 *
 */
public interface Households extends MatsimToplevelContainer {

	Map<Id<Household>, Household> getHouseholds();

	@Override
	HouseholdsFactory getFactory();

}
