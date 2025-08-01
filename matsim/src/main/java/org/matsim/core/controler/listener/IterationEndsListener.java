/* *********************************************************************** *
 * project: org.matsim.*
 * ControlerFinishIterationListener.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2007 by the members listed in the COPYING,        *
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

package org.matsim.core.controler.listener;

import org.matsim.core.controler.events.IterationEndsEvent;

/**
 * @author dgrether
 *
 */
public interface IterationEndsListener extends ControllerListener {
	/**
	 * Notifies all observers of the Controler that a iteration is finished
	 * @param event
	 */
	public void notifyIterationEnds(IterationEndsEvent event);
}
