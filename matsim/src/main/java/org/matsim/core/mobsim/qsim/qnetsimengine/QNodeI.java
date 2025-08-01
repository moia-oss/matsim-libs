
/* *********************************************************************** *
 * project: org.matsim.*
 * QNodeI.java
 *                                                                         *
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
 * *********************************************************************** */

package org.matsim.core.mobsim.qsim.qnetsimengine;

import org.matsim.core.mobsim.qsim.interfaces.NetsimNode;

/**
 * @author kainagel
 *
 */
interface QNodeI extends NetsimNode {

	boolean doSimStep(double now) ;

	void init( QNetwork qNetwork ) ;
	// this needs to have QNetwork as an argument, since when it is called, the QNetwork constructor has not yet finished, and in consequence the
	// QNetwork cannot be retrieved in an "official" way.  :-(  kai, jul'25

}
