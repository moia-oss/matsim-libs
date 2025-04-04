/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2010 by the members listed in the COPYING,        *
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

import java.util.Collection;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.QSimConfigGroup.VehicleBehavior;
import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.mobsim.framework.MobsimDriverAgent;
import org.matsim.vehicles.Vehicle;

public class NetworkModeDepartureHandlerDefaultImpl implements NetworkModeDepartureHandler {
	// needs to be public so it can be used as a delegate.  Constructor is package-private (and should remain so).  kai, jan'25

	private static final Logger log = LogManager.getLogger( NetworkModeDepartureHandlerDefaultImpl.class );

	private static int cntTeleportVehicle = 0;

	private final VehicleBehavior vehicleBehavior;

	private final QNetsimEngineI qNetsimEngine;

	private final Collection<String> networkModes;

	@Inject /* deliberately package-private */ NetworkModeDepartureHandlerDefaultImpl( QNetsimEngineI qNetsimEngine, QSimConfigGroup qsimConfig ) {
		this.qNetsimEngine = qNetsimEngine;
		this.vehicleBehavior = qsimConfig.getVehicleBehavior();
		this.networkModes =qsimConfig.getMainModes();
	}

	@Override public boolean handleDeparture( double now, MobsimAgent agent, Id<Link> linkId ) {
		if (this.networkModes.contains(agent.getMode() )) {
			if ( agent instanceof MobsimDriverAgent ) {
				handleNetworkModeDeparture(now, (MobsimDriverAgent)agent, linkId );
				return true;
			} else {
				throw new UnsupportedOperationException("wrong agent type to depart on a network mode");
			}
		}
		return false;
	}

	private void handleNetworkModeDeparture( double now, MobsimDriverAgent agent, Id<Link> linkId ) {
		// The situation where a leg starts and ends at the same link used to be handled specially, for all agents except
		// AbstractTransitDriverAgents. This however caused some problems in some cases, as apparently for taxicabs. Thus, such trips are now
		// simulated normally. See MATSIM-233 for details. td apr'14
		Id<Vehicle> vehicleId = agent.getPlannedVehicleId() ;
		QLinkI qlink = (QLinkI) qNetsimEngine.getNetsimNetwork().getNetsimLink(linkId);
		QVehicle vehicle = qlink.removeParkedVehicle(vehicleId);
		if (vehicle == null) {
			if (vehicleBehavior == VehicleBehavior.teleport) {
				vehicle = qNetsimEngine.getVehicles().get(vehicleId);
				if ( vehicle==null ) {
					// log a maximum of information, to help the user identifying the cause of the problem
					final String msg = "could not find requested vehicle "+vehicleId+" in simulation for agent "+agent+" with id "+agent.getId()+" on link "+agent.getCurrentLinkId()+" at time "+now+".";
					log.error( msg );
					log.error( "Note that, with AgentSource and if the agent starts on a leg, the vehicle needs to be inserted BEFORE the agent!") ;
					throw new RuntimeException( msg+" aborting ...") ;
				}
				teleportVehicleTo(vehicle, linkId, qNetsimEngine);

				vehicle.setDriver(agent);
				agent.setVehicle(vehicle) ;

				qlink.letVehicleDepart(vehicle);
				// (since the "teleportVehicle" does not physically move the vehicle, this is finally achieved in the departure
				// logic.  kai, nov'11)
			} else if (vehicleBehavior == VehicleBehavior.wait ) {
				// While we are waiting for our car
				qlink.registerDriverAgentWaitingForCar(agent);
			} else {
				throw new RuntimeException("vehicle " + vehicleId + " not available for agent " + agent.getId() + " on link " + linkId + " at time "+ now);
			}
		} else {
			vehicle.setDriver(agent);
			agent.setVehicle(vehicle) ;
			qlink.letVehicleDepart(vehicle);
		}
	}

	public static void teleportVehicleTo(QVehicle vehicle, Id<Link> linkId, QNetsimEngineI qNetsimEngine ) {
		if (vehicle.getCurrentLink() != null) {

			if (cntTeleportVehicle < 9) {
				cntTeleportVehicle++;
				log.info("teleport vehicle " + vehicle.getId() + " from link " + vehicle.getCurrentLink().getId() + " to link " + linkId);
				if (cntTeleportVehicle == 9) {
					log.info("No more occurrences of teleported vehicles will be reported.");
				}
			}
			QLinkI qlinkOld = (QLinkI) qNetsimEngine.getNetsimNetwork().getNetsimLink(vehicle.getCurrentLink().getId());
			QVehicle result = qlinkOld.removeParkedVehicle(vehicle.getId());
			if ( result==null ) {
				throw new RuntimeException( "Could not remove parked vehicle with id " + vehicle.getId() +" on the link id " 
						+ vehicle.getCurrentLink().getId()
						+ ".  Maybe it is currently used by someone else?"
						+ " (In which case ignoring this exception would lead to duplication of this vehicle.) "
						+ "Maybe was never placed onto a link?" );
			}
		}
	}

}
