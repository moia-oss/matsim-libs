/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.huboperations;

import org.matsim.api.core.v01.Id;
import org.matsim.contrib.drt.extension.operations.huboperations.charging.HubChargingPolicy;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Accrues the soiling of each fleet vehicle and marks it as due for cleaning once a counter crosses its threshold. There
 * is one counter per {@link CleaningType}:
 * <ul>
 *     <li>{@link CleaningType#interior} counts passenger kilometres. Every fully traversed link adds its length times the
 *         passengers on board, so empty kilometres add nothing.</li>
 *     <li>{@link CleaningType#exterior} counts vehicle kilometres. Every fully traversed link adds its length, empty
 *         or not, because sensors and body get dirty from driving.</li>
 *     <li>{@link CleaningType#charging} reads the state of charge. It is not accrued per link but set from the battery
 *         by {@link #updateBattery}, because the ev module owns the state of charge. When charging is due and when it
 *         is bundled is decided by a {@link HubChargingPolicy}.</li>
 * </ul>
 * <b>Bundling.</b> A hub visit is expensive: the vehicle finishes its riders, drives empty to the hub and may queue for
 * a worker. So when a vehicle is cleaned, {@link #typesToClean} also includes every type that is not yet due but has
 * reached {@code bundlingShare} of its threshold. With a share of 0.8 and an exterior threshold of 700 vkm, a vehicle
 * that comes in for its interior at 600 vkm gets its sensors cleaned as well, while one at 500 vkm does not. A share of
 * 1 switches bundling off.
 * <p>
 * The tracker is a plain state holder and knows nothing about events or schedules, so its accounting can be tested on
 * its own. The {@link CleaningDispatcher} feeds it from the event stream. A threshold crossing is reported exactly once
 * per cycle by {@link #addLink}. {@link #reset} clears the counters of the types that were cleaned.
 *
 * @author lpfeil / MOIA
 */
public final class SoilingTracker {

	private final Map<CleaningType, Double> thresholdMeters = new EnumMap<>(CleaningType.class);
	private final double bundlingShare;

	private final Map<Id<DvrpVehicle>, Integer> onBoard = new HashMap<>();
	private final Map<Id<DvrpVehicle>, Map<CleaningType, Double>> meters = new HashMap<>();
	private final Map<Id<DvrpVehicle>, Set<CleaningType>> due = new HashMap<>();
	// battery used, 1 - SOC, per electric vehicle; absent for vehicles without a battery
	private final Map<Id<DvrpVehicle>, Double> batteryUsed = new HashMap<>();
	// electric vehicles for which the policy said due at the last update, so a due event fires on the change only
	private final Set<Id<DvrpVehicle>> chargingWasDue = new HashSet<>();
	private final HubChargingPolicy chargingPolicy;

	/**
	 * @param interiorThresholdPkm passenger kilometres after which the interior is due; infinite disables it
	 * @param exteriorThresholdVkm vehicle kilometres after which sensors and exterior are due; infinite disables it
	 * @param bundlingShare        share of its threshold at which a type is cleaned along with a due one, in [0, 1]
	 */
	public SoilingTracker(double interiorThresholdPkm, double exteriorThresholdVkm, double bundlingShare) {
		this(interiorThresholdPkm, exteriorThresholdVkm, 0., bundlingShare);
	}

	/**
	 * @param chargeSocThreshold state of charge at or below which a vehicle is due for charging; 0 disables it
	 */
	public SoilingTracker(double interiorThresholdPkm, double exteriorThresholdVkm, double chargeSocThreshold,
						  double bundlingShare) {
		this(interiorThresholdPkm, exteriorThresholdVkm, checkedThreshold(chargeSocThreshold, bundlingShare),
				bundlingShare);
	}

	private static HubChargingPolicy checkedThreshold(double chargeSocThreshold, double bundlingShare) {
		if (!(chargeSocThreshold >= 0 && chargeSocThreshold < 1)) {
			throw new IllegalArgumentException("charge SOC threshold must be in [0, 1), got " + chargeSocThreshold);
		}
		return HubChargingPolicy.threshold(chargeSocThreshold, bundlingShare);
	}

	/**
	 * @param chargingPolicy decides when charging is due and when it is bundled, e.g. by a plain SOC threshold or by
	 *                       the slack of the fleet
	 */
	public SoilingTracker(double interiorThresholdPkm, double exteriorThresholdVkm, HubChargingPolicy chargingPolicy,
						  double bundlingShare) {
		this.chargingPolicy = chargingPolicy;
		if (!(interiorThresholdPkm > 0) || !(exteriorThresholdVkm > 0)) {
			throw new IllegalArgumentException("soiling thresholds must be positive, got " + interiorThresholdPkm
					+ " pkm and " + exteriorThresholdVkm + " vkm");
		}
		if (!(bundlingShare >= 0 && bundlingShare <= 1)) {
			throw new IllegalArgumentException("bundling share must be in [0, 1], got " + bundlingShare);
		}
		thresholdMeters.put(CleaningType.interior, interiorThresholdPkm * 1000.);
		thresholdMeters.put(CleaningType.exterior, exteriorThresholdVkm * 1000.);
		// off until withMaintenance switches it on
		thresholdMeters.put(CleaningType.maintenance, Double.POSITIVE_INFINITY);
		this.bundlingShare = bundlingShare;
	}

	/**
	 * Switches on maintenance, done by the maintenance pool L_M. It has its own vehicle-kilometre counter and is never
	 * bundled: a maintenance visit does maintenance only, see {@link #typesToClean}.
	 *
	 * @param maintenanceThresholdVkm vehicle kilometres after which maintenance is due; infinite keeps it off
	 */
	public SoilingTracker withMaintenance(double maintenanceThresholdVkm) {
		if (!(maintenanceThresholdVkm > 0)) {
			throw new IllegalArgumentException("maintenance threshold must be positive, got " + maintenanceThresholdVkm);
		}
		thresholdMeters.put(CleaningType.maintenance, maintenanceThresholdVkm * 1000.);
		return this;
	}

	public void pickUp(Id<DvrpVehicle> vehicleId) {
		onBoard.merge(vehicleId, 1, Integer::sum);
	}

	public void dropOff(Id<DvrpVehicle> vehicleId) {
		onBoard.merge(vehicleId, -1, Integer::sum);
		if (onBoard.get(vehicleId) < 0) {
			throw new IllegalStateException("negative passenger count for vehicle " + vehicleId);
		}
	}

	/**
	 * Adds one fully traversed link.
	 *
	 * @return the types this link pushed across their threshold, each reported once per cleaning cycle
	 */
	public Set<CleaningType> addLink(Id<DvrpVehicle> vehicleId, double linkLengthMeters) {
		Set<CleaningType> crossed = EnumSet.noneOf(CleaningType.class);
		int passengers = onBoard.getOrDefault(vehicleId, 0);
		accrue(vehicleId, CleaningType.exterior, linkLengthMeters, crossed);
		accrue(vehicleId, CleaningType.maintenance, linkLengthMeters, crossed);
		if (passengers > 0) {
			accrue(vehicleId, CleaningType.interior, linkLengthMeters * passengers, crossed);
		}
		return crossed;
	}

	private void accrue(Id<DvrpVehicle> vehicleId, CleaningType type, double addMeters, Set<CleaningType> crossed) {
		double total = meters.computeIfAbsent(vehicleId, v -> new EnumMap<>(CleaningType.class))
				.merge(type, addMeters, Double::sum);
		if (total >= thresholdMeters.get(type)
				&& due.computeIfAbsent(vehicleId, v -> EnumSet.noneOf(CleaningType.class)).add(type)) {
			crossed.add(type);
		}
	}

	/**
	 * Sets the battery state of an electric vehicle and reports whether it just became due for charging. Called once per
	 * step for every electric vehicle.
	 *
	 * @param soc state of charge in [0, 1]
	 * @return {@code true} when the state of charge falls to the threshold
	 */
	public boolean updateBattery(Id<DvrpVehicle> vehicleId, double soc) {
		batteryUsed.put(vehicleId, 1. - soc);
		// due on the change only, like the kilometre counters on their crossing: a vehicle that could not be charged (no
		// charger at its hub) is not sent back every step, and one charged above the threshold becomes due again when it
		// next falls. With the fleet-slack policy the change can also come from the fleet becoming relaxed.
		boolean dueNow = chargingPolicy.isDue(soc);
		boolean became = dueNow && chargingWasDue.add(vehicleId);
		if (!dueNow) {
			chargingWasDue.remove(vehicleId);
		}
		return became && due.computeIfAbsent(vehicleId, v -> EnumSet.noneOf(CleaningType.class))
				.add(CleaningType.charging);
	}

	/**
	 * Withdraws a charging due flag the policy no longer supports, e.g. an opportunistic charge after the fleet became
	 * tight. Only for a vehicle that has not started its hub visit yet; the caller checks that.
	 *
	 * @return whether the vehicle was due for charging only because of the policy and is no longer due at all
	 */
	public boolean withdrawCharging(Id<DvrpVehicle> vehicleId, double soc) {
		Set<CleaningType> dueTypes = due.get(vehicleId);
		if (dueTypes == null || !dueTypes.contains(CleaningType.charging) || chargingPolicy.isDue(soc)) {
			return false;
		}
		dueTypes.remove(CleaningType.charging);
		chargingWasDue.remove(vehicleId);
		return true;
	}

	public HubChargingPolicy getChargingPolicy() {
		return chargingPolicy;
	}

	/** Whether any type is due, which takes the vehicle out of request insertion. */
	public boolean isDue(Id<DvrpVehicle> vehicleId) {
		Set<CleaningType> types = due.get(vehicleId);
		return types != null && !types.isEmpty();
	}

	public Set<CleaningType> getDueTypes(Id<DvrpVehicle> vehicleId) {
		Set<CleaningType> types = due.get(vehicleId);
		return types == null ? EnumSet.noneOf(CleaningType.class) : EnumSet.copyOf(types);
	}

	/** Whether maintenance is due; its visit comes before any other, see {@link #typesToClean}. */
	public boolean isMaintenanceDue(Id<DvrpVehicle> vehicleId) {
		Set<CleaningType> types = due.get(vehicleId);
		return types != null && types.contains(CleaningType.maintenance);
	}

	/**
	 * The types of a maintenance visit: maintenance only. Nothing is bundled with it, and the other due types stay due
	 * for a visit of their own.
	 */
	public Set<CleaningType> typesToMaintain(Id<DvrpVehicle> vehicleId) {
		return isMaintenanceDue(vehicleId) ? EnumSet.of(CleaningType.maintenance) : EnumSet.noneOf(CleaningType.class);
	}

	/**
	 * The types to clean on a hub visit of the cleaning pool now: every due type plus every type at or above the
	 * bundling share. Maintenance is never part of it, see {@link #typesToMaintain}.
	 */
	public Set<CleaningType> typesToClean(Id<DvrpVehicle> vehicleId) {
		Set<CleaningType> types = getDueTypes(vehicleId);
		types.remove(CleaningType.maintenance);
		for (CleaningType type : List.of(CleaningType.interior, CleaningType.exterior)) {
			if (getMeters(vehicleId, type) >= bundlingShare * thresholdMeters.get(type)) {
				types.add(type);
			}
		}
		Double used = batteryUsed.get(vehicleId);
		if (used != null && chargingPolicy.isBundled(1. - used)) {
			types.add(CleaningType.charging);
		}
		return types;
	}

	public int getOnBoard(Id<DvrpVehicle> vehicleId) {
		return onBoard.getOrDefault(vehicleId, 0);
	}

	public double getPassengerKm(Id<DvrpVehicle> vehicleId) {
		return getMeters(vehicleId, CleaningType.interior) / 1000.;
	}

	/** Battery used, {@code 1 - SOC}, or NaN for a vehicle without a battery. */
	public double getBatteryUsed(Id<DvrpVehicle> vehicleId) {
		return batteryUsed.getOrDefault(vehicleId, Double.NaN);
	}

	public double getVehicleKm(Id<DvrpVehicle> vehicleId) {
		return getMeters(vehicleId, CleaningType.exterior) / 1000.;
	}

	/** Vehicle kilometres since the last maintenance. */
	public double getMaintenanceKm(Id<DvrpVehicle> vehicleId) {
		return getMeters(vehicleId, CleaningType.maintenance) / 1000.;
	}

	private double getMeters(Id<DvrpVehicle> vehicleId, CleaningType type) {
		Map<CleaningType, Double> counters = meters.get(vehicleId);
		return counters == null ? 0. : counters.getOrDefault(type, 0.);
	}

	/** Clears the counters and due flags of the cleaned types. Other types and the on-board count are untouched. */
	public void reset(Id<DvrpVehicle> vehicleId, Set<CleaningType> cleaned) {
		Map<CleaningType, Double> counters = meters.get(vehicleId);
		Set<CleaningType> dueTypes = due.get(vehicleId);
		for (CleaningType type : cleaned) {
			if (type == CleaningType.charging) {
				// the battery is not reset here: the ev module charges it, and updateBattery reads it again
				if (dueTypes != null) {
					dueTypes.remove(type);
				}
				continue;
			}
			if (counters != null) {
				counters.remove(type);
			}
			if (dueTypes != null) {
				dueTypes.remove(type);
			}
		}
	}

	/** Clears all state between iterations. */
	public void clear() {
		onBoard.clear();
		meters.clear();
		due.clear();
		batteryUsed.clear();
		chargingWasDue.clear();
	}
}
