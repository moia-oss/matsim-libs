/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.huboperations.analysis;

import com.google.common.collect.ImmutableMap;
import org.matsim.contrib.common.timeprofile.ProfileWriter;
import org.matsim.contrib.drt.extension.operations.huboperations.schedule.CleaningTask;
import org.matsim.contrib.drt.extension.operations.shifts.schedule.WaitForShiftTask;
import org.matsim.contrib.dvrp.analysis.VehicleOccupancyProfileCalculator;
import org.matsim.contrib.dvrp.schedule.Task;

import java.awt.Color;
import java.awt.Paint;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The occupancy time profile with the hub visits split into what the vehicles do there. The {@code CLEANING} column
 * is replaced by the {@link HubTimeProfileCalculator} columns, and visits of vehicles parked at the hub are moved out of
 * {@code WAIT_FOR_SHIFT} into them, so every vehicle still counts once and the stacked chart keeps its height. Parallel
 * activities, cleaning while charging, share a vehicle equally (1/2 or 1/3 each).
 * <p>
 * Colours follow the MOIA chart palette: interior blue, exterior gold, charging teal, maintenance red; waiting for a free
 * plug amber and for a worker grey, so the work, the charging and the waiting are told apart at a glance.
 *
 * @author lpfeil / MOIA
 */
public final class HubOccupancyProfileView implements ProfileWriter.ProfileView {

	private static final Map<String, Paint> HUB_PAINTS = Map.of(
			HubTimeProfileCalculator.INTERIOR, new Color(0x0532FF),
			HubTimeProfileCalculator.EXTERIOR, new Color(0xE6AA33),
			HubTimeProfileCalculator.CHARGING, new Color(0x00AA8B),
			HubTimeProfileCalculator.MAINTENANCE, new Color(0xD83232),
			HubTimeProfileCalculator.WAIT_FOR_WORKER, new Color(0x616467),
			HubTimeProfileCalculator.WAIT_FOR_PLUG, new Color(0xECBF66));

	private final VehicleOccupancyProfileCalculator occupancy;
	private final HubTimeProfileCalculator hub;
	private final Comparator<Task.TaskType> taskTypeComparator;
	private final Map<String, Paint> taskTypePaints;
	private final double analysisEndTime;

	public HubOccupancyProfileView(VehicleOccupancyProfileCalculator occupancy, HubTimeProfileCalculator hub,
								   Comparator<Task.TaskType> taskTypeComparator, Map<String, Paint> taskTypePaints,
								   double analysisEndTime) {
		this.occupancy = occupancy;
		this.hub = hub;
		this.taskTypeComparator = taskTypeComparator;
		this.taskTypePaints = taskTypePaints;
		this.analysisEndTime = analysisEndTime;
	}

	@Override
	public ImmutableMap<String, double[]> profiles() {
		hub.finish(analysisEndTime);
		Map<String, double[]> result = new LinkedHashMap<>();
		// hub columns first, so they sit at the bottom of the stacked chart next to the other non-passenger tasks
		hub.getProfiles().forEach(result::put);
		occupancy.getNonPassengerServingTaskProfiles().entrySet().stream()
				.filter(e -> !e.getKey().equals(CleaningTask.TYPE))
				.sorted(Map.Entry.comparingByKey(taskTypeComparator))
				.forEach(e -> {
					double[] profile = e.getValue().clone();
					if (e.getKey().equals(WaitForShiftTask.TYPE)) {
						double[] parked = hub.getParkedProfile();
						for (int i = 0; i < profile.length && i < parked.length; i++) {
							profile[i] = Math.max(0., profile[i] - parked[i]);
						}
					}
					result.put(e.getKey().name(), profile);
				});
		var occupancyProfiles = occupancy.getVehicleOccupancyProfiles();
		for (int i = 0; i < occupancyProfiles.size(); i++) {
			result.put(i + " pax", occupancyProfiles.get(i));
		}
		return ImmutableMap.copyOf(result);
	}

	@Override
	public Map<String, Paint> seriesPaints() {
		Map<String, Paint> paints = new HashMap<>(taskTypePaints);
		paints.putAll(HUB_PAINTS);
		return paints;
	}

	@Override
	public double[] times() {
		return occupancy.getTimeDiscretizer().getTimes();
	}
}
