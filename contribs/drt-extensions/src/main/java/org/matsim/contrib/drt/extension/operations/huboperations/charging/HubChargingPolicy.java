/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.huboperations.charging;

import org.matsim.contrib.drt.extension.operations.huboperations.config.HubChargingSlackParams;

/**
 * Decides when hub charging is due and when it may be bundled into a hub visit. The plug-in, cleaning and unplug
 * mechanics are the same for every policy; a policy only answers <em>when</em>, like the
 * {@code MoiaChargingStrategy.chargingNotRequired} hook of the MOIA eDRT stack.
 *
 * @author lpfeil / MOIA
 */
public interface HubChargingPolicy {

	/** Whether a vehicle at {@code soc} must go to the hub to charge now. */
	boolean isDue(double soc);

	/** Whether a vehicle at {@code soc} that visits the hub for another reason should charge as well. */
	boolean isBundled(double soc);

	/** Whether charging vehicles may be unplugged early to give capacity back to the fleet. */
	default boolean allowsEarlyRelease() {
		return false;
	}

	/** Called once per sim step before the policy is asked. */
	default void update(double now) {
	}

	/**
	 * The plain policy: due at or below {@code threshold}, bundled from {@code bundlingShare} of the battery used.
	 * {@code threshold} 0 switches charging off.
	 */
	static HubChargingPolicy threshold(double threshold, double bundlingShare) {
		double dueUsed = 1. - threshold;
		return new HubChargingPolicy() {
			@Override
			public boolean isDue(double soc) {
				return threshold > 0 && soc <= threshold;
			}

			@Override
			public boolean isBundled(double soc) {
				return threshold > 0 && 1. - soc >= bundlingShare * dueUsed;
			}
		};
	}

	/**
	 * The fleet-slack aware policy: always due below {@code socCritical}, due below {@code socOpportunity} while the
	 * fleet is relaxed, and on another visit bundled below {@code socOpportunity} whatever the fleet does, since the
	 * vehicle is at the hub anyway. Early release is allowed.
	 */
	static HubChargingPolicy fleetSlack(HubChargingSlackParams params, FleetSlackMonitor monitor) {
		return new HubChargingPolicy() {
			@Override
			public boolean isDue(double soc) {
				return soc < params.getSocCritical() || (soc < params.getSocOpportunity() && monitor.isRelaxed());
			}

			@Override
			public boolean isBundled(double soc) {
				return soc < params.getSocOpportunity();
			}

			@Override
			public boolean allowsEarlyRelease() {
				return true;
			}

			@Override
			public void update(double now) {
				monitor.update(now);
			}
		};
	}
}
