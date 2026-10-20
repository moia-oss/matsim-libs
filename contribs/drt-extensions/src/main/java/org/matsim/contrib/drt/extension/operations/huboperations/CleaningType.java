/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.huboperations;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The tasks a hub worker performs on a vehicle. Each type has its own counter and threshold, and all of them share the
 * bundling rule of {@link SoilingTracker}. The name is kept from the first two types; {@link #charging} is a hub task
 * too, but not a cleaning one.
 *
 * @author lpfeil / MOIA
 */
public enum CleaningType {

	/** Interior cleaning, driven by passenger kilometres. */
	interior,

	/** Sensor and exterior cleaning, driven by vehicle kilometres, empty ones included. */
	exterior,

	/**
	 * Plugging in for charging, driven by the battery used, {@code 1 - SOC}. Only for electric fleets. The worker plugs
	 * in and out; the charging time itself comes from the ev module and can be used for the cleaning types.
	 */
	charging,

	/**
	 * Maintenance and sensor calibration in the hub workshop, driven by vehicle kilometres. Done by the maintenance pool
	 * L_M, never bundled with the other types and never while charging: a maintenance visit does maintenance only.
	 */
	maintenance;

	/** Whether a worker spends time on this type, as opposed to just plugging in and out. */
	public boolean isCleaning() {
		return this != charging;
	}

	/** The worker pool that does this type. */
	public HubWorkers.Pool pool() {
		return this == maintenance ? HubWorkers.Pool.maintenance : HubWorkers.Pool.cleaning;
	}

	/** Formats a set of types as {@code interior+exterior}, in declaration order, for events and CSV files. */
	public static String format(Set<CleaningType> types) {
		return types.stream().sorted().map(Enum::name).collect(Collectors.joining("+"));
	}

	/** Parses the output of {@link #format}. */
	public static Set<CleaningType> parse(String types) {
		Set<CleaningType> result = EnumSet.noneOf(CleaningType.class);
		if (types == null || types.isEmpty()) {
			return result;
		}
		Arrays.stream(types.split("\\+")).map(CleaningType::valueOf).forEach(result::add);
		return result;
	}
}
