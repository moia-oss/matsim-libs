/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.huboperations.analysis;

import jakarta.annotation.Nullable;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.drt.extension.operations.huboperations.CleaningType;
import org.matsim.contrib.drt.extension.operations.huboperations.HubWorkers;
import org.matsim.contrib.drt.extension.operations.huboperations.analysis.HubCleaningAnalysisTracker.CleaningRecord;
import org.matsim.contrib.drt.extension.operations.huboperations.analysis.HubCleaningAnalysisTracker.HubChange;
import org.matsim.contrib.drt.extension.operations.huboperations.analysis.HubCleaningAnalysisTracker.Outcome;
import org.matsim.contrib.drt.extension.operations.operationFacilities.OperationFacility;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.core.controler.MatsimServices;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.utils.io.IOUtils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

/**
 * Writes the hub cleaning analysis per iteration, the counterpart of {@code RemoteGuidanceAnalysisControlerListener}.
 * All file names are prefixed with {@code drt_hubCleaning_} and suffixed with the mode:
 * <ul>
 *     <li>{@code _cleanings} (CSV) — one row per hub visit: vehicle, hub, the type that made it due, the types
 *         cleaned and whether that was bundled, passenger and vehicle km at the due event, the due, arrival, start and
 *         end times, the worker who cleaned it, the queue delay and the outcome.</li>
 *     <li>{@code _cleaningStats} (CSV) — per hub and combined: counts by outcome, mean and maximum queue delay, mean
 *         approach time and mean time out of service.</li>
 *     <li>{@code _workers} (CSV) — one row per hub worker: hub, planned window and hours, visits served, plug-ins
 *         and unplugs, busy hours (cleaning time; plugging takes none) and utilisation. Only written when a worker roster is configured.</li>
 *     <li>{@code _hubOccupancy} (CSV) — step function of the vehicles queued, the workers busy and the vehicles
 *         plugged in at each hub.</li>
 * </ul>
 * {@code _cleaningStats} also appends its combined row per iteration to a cross-iteration file.
 *
 * @author lpfeil / MOIA
 */
public final class HubCleaningAnalysisControlerListener implements IterationEndsListener {

	private static final String NA = "NA";
	private static final String COMBINED = "all";

	private final DrtConfigGroup drtConfigGroup;
	private final HubCleaningAnalysisTracker tracker;
	@Nullable
	private final HubWorkers workers;
	private final MatsimServices matsimServices;
	private final String delimiter;
	private final String runId;
	private boolean statsHeaderWritten = false;

	public HubCleaningAnalysisControlerListener(DrtConfigGroup drtConfigGroup, HubCleaningAnalysisTracker tracker,
												@Nullable HubWorkers workers, MatsimServices matsimServices) {
		this.drtConfigGroup = drtConfigGroup;
		this.tracker = tracker;
		this.workers = workers;
		this.matsimServices = matsimServices;
		this.delimiter = matsimServices.getConfig().global().getDefaultDelimiter();
		this.runId = Optional.ofNullable(matsimServices.getConfig().controller().getRunId()).orElse(NA);
	}

	@Override
	public void notifyIterationEnds(IterationEndsEvent event) {
		List<CleaningRecord> records = tracker.getRecords();
		writeCleanings(records, filename(event, "cleanings"));
		writeStats(records, event.getIteration(), filename(event, "cleaningStats"));
		if (workers != null) {
			writeWorkers(records, filename(event, "workers"));
		}
		writeOccupancy(tracker.getHubChanges(), filename(event, "hubOccupancy"));
	}

	// ------------------------------------------------------------------------------------------------- cleaning log

	private void writeCleanings(List<CleaningRecord> records, String csvFile) {
		try (BufferedWriter bw = IOUtils.getBufferedWriter(csvFile)) {
			bw.append(line("vehicle", "hub", "trigger", "cleaned", "bundled", "passengerKm", "vehicleKm", "dueTime",
					"arrivalTime", "startTime", "endTime", "worker", "queueDelay", "approachTime", "cleaningDuration",
					"charger", "plugTime", "socAtPlug", "unplugTime", "socAtUnplug", "unplugWorker", "waitForUnplug",
					"energyKWh", "earlyUnplug", "dwellTime", "outOfServiceTime", "outcome"));
			for (CleaningRecord r : records) {
				CleaningRecord.Charging c = r.charging();
				bw.append(line(r.vehicleId(), orNa(r.hub()), orNa(r.trigger()),
						r.cleaned().isEmpty() ? NA : CleaningType.format(r.cleaned()), r.bundled(), r.passengerKm(),
						r.vehicleKm(), num(r.dueTime()), num(r.arrivalTime()),
						num(r.startTime()), num(r.endTime()), orNa(r.workerId()), num(r.queueDelay()),
						num(r.approachTime()), num(r.cleaningDuration()), orNa(c.chargerId()), num(c.plugTime()),
						num(c.socAtPlug()), num(c.unplugTime()), num(c.socAtUnplug()), orNa(c.unplugWorker()),
						num(c.waitForUnplug()), num(c.energyKWh()), c.early(), num(r.dwellTime()), num(r.outOfServiceTime()),
						r.outcome()));
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	// ------------------------------------------------------------------------------------------------- statistics

	private void writeStats(List<CleaningRecord> records, int iteration, String perIterationCsv) {
		Map<String, List<CleaningRecord>> byHub = new LinkedHashMap<>();
		records.stream()
				.sorted(Comparator.comparing(r -> String.valueOf(r.hub())))
				.forEach(r -> byHub.computeIfAbsent(String.valueOf(orNa(r.hub())), h -> new ArrayList<>()).add(r));
		byHub.put(COMBINED, records);

		try (BufferedWriter bw = IOUtils.getBufferedWriter(perIterationCsv)) {
			bw.append(line(statsHeader().toArray()));
			for (var entry : byHub.entrySet()) {
				bw.append(line(prepend(entry.getKey(), summarize(entry.getValue()))));
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		try (BufferedWriter bw = getAppendingBufferedWriter("cleaningStats")) {
			if (!statsHeaderWritten) {
				List<Object> header = new ArrayList<>(List.of("runId", "iteration"));
				header.addAll(statsHeader());
				bw.write(line(header.toArray()));
				statsHeaderWritten = true;
			}
			bw.write(line(prepend(runId, prepend(Integer.toString(iteration), prepend(COMBINED, summarize(records))))));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static List<Object> statsHeader() {
		return List.of("hub", "due", "cleaned", "abandoned", "open", "interiorCleanings", "exteriorCleanings",
				"chargings", "maintenances", "bundledVisits", "queued", "meanQueueDelay", "maxQueueDelay", "meanApproachTime",
				"meanDwellTime", "meanOutOfServiceTime", "meanWaitForUnplug", "earlyUnplugs", "energyKWh",
				"meanPassengerKmAtDue",
				"meanVehicleKmAtDue");
	}

	private static Object[] summarize(List<CleaningRecord> records) {
		List<CleaningRecord> cleaned = records.stream().filter(r -> r.outcome() == Outcome.cleaned).toList();
		long abandoned = records.stream().filter(r -> r.outcome() == Outcome.abandoned).count();
		long open = records.stream().filter(r -> r.outcome() == Outcome.open).count();
		List<CleaningRecord> withDelay = records.stream().filter(r -> !Double.isNaN(r.queueDelay())).toList();
		long queued = withDelay.stream().filter(r -> r.queueDelay() > 0).count();
		long interior = cleaned.stream().filter(r -> r.cleaned().contains(CleaningType.interior)).count();
		long exterior = cleaned.stream().filter(r -> r.cleaned().contains(CleaningType.exterior)).count();
		long chargings = cleaned.stream().filter(r -> r.cleaned().contains(CleaningType.charging)).count();
		long maintenances = cleaned.stream().filter(r -> r.cleaned().contains(CleaningType.maintenance)).count();
		long bundled = cleaned.stream().filter(CleaningRecord::bundled).count();
		double energy = records.stream().mapToDouble(r -> r.charging().energyKWh()).filter(v -> !Double.isNaN(v)).sum();
		long early = records.stream().filter(r -> r.charging().early()).count();
		return new Object[]{records.size(), cleaned.size(), abandoned, open, interior, exterior, chargings, maintenances,
				bundled,
				queued, mean(withDelay, CleaningRecord::queueDelay), max(withDelay, CleaningRecord::queueDelay),
				mean(records, CleaningRecord::approachTime), mean(cleaned, CleaningRecord::dwellTime),
				mean(cleaned, CleaningRecord::outOfServiceTime), mean(records, r -> r.charging().waitForUnplug()),
				early, energy, mean(records, CleaningRecord::passengerKm), mean(records, CleaningRecord::vehicleKm)};
	}

	// ------------------------------------------------------------------------------------------------- workers

	private void writeWorkers(List<CleaningRecord> records, String csvFile) {
		Map<Id<HubWorkers.HubWorker>, List<CleaningRecord>> byWorker = records.stream()
				.filter(r -> r.workerId() != null)
				.collect(Collectors.groupingBy(CleaningRecord::workerId));
		try (BufferedWriter bw = IOUtils.getBufferedWriter(csvFile)) {
			Map<Id<HubWorkers.HubWorker>, Long> unplugs = records.stream()
					.filter(r -> r.charging().unplugWorker() != null)
					.collect(Collectors.groupingBy(r -> r.charging().unplugWorker(), Collectors.counting()));
			bw.append(line("worker", "pool", "hub", "start", "end", "plannedHours", "visits", "plugIns", "unplugs",
					"busyHours", "utilisation", "vehicles"));
			for (HubWorkers.Worker worker : workers.getWorkers().values()) {
				List<CleaningRecord> done = byWorker.getOrDefault(worker.id(), List.of());
				long plugIns = done.stream().filter(r -> r.charging().plugged()).count();
				// a cleaning still running when the iteration ends has no end time and is not counted as busy time
				double busySeconds = done.stream().mapToDouble(CleaningRecord::cleaningDuration)
						.filter(v -> !Double.isNaN(v)).sum();
				double busyHours = busySeconds / 3600.;
				String vehicles = done.stream().map(r -> r.vehicleId().toString()).collect(Collectors.joining(" "));
				bw.append(line(worker.id(), worker.pool(), worker.hub(), worker.start(), worker.end(), worker.plannedHours(),
						done.size(), plugIns, unplugs.getOrDefault(worker.id(), 0L), busyHours,
						busyHours / worker.plannedHours(), vehicles));
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	// ------------------------------------------------------------------------------------------------- occupancy

	private void writeOccupancy(List<HubChange> changes, String csvFile) {
		List<HubChange> sorted = new ArrayList<>(changes);
		sorted.sort(Comparator.comparingDouble(HubChange::time));
		Set<Id<OperationFacility>> hubs = new LinkedHashSet<>();
		sorted.forEach(c -> hubs.add(c.hub()));
		Map<Id<OperationFacility>, int[]> state = new LinkedHashMap<>();
		hubs.forEach(h -> state.put(h, new int[5]));

		try (BufferedWriter bw = IOUtils.getBufferedWriter(csvFile)) {
			bw.append(line("time", "hub", "queued", "busyWorkers", "plugged", "queuedMaintenance", "busyTechnicians"));
			for (HubChange change : sorted) {
				int[] s = state.get(change.hub());
				int offset = change.maintenance() ? 3 : 0;
				s[offset] += change.queueDelta();
				s[offset + 1] += change.busyDelta();
				s[2] += change.pluggedDelta();
				bw.append(line(change.time(), change.hub(), s[0], s[1], s[2], s[3], s[4]));
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	// ------------------------------------------------------------------------------------------------- helpers

	private static double mean(List<CleaningRecord> records, ToDoubleFunction<CleaningRecord> f) {
		return records.stream().mapToDouble(f).filter(v -> !Double.isNaN(v)).average().orElse(Double.NaN);
	}

	private static double max(List<CleaningRecord> records, ToDoubleFunction<CleaningRecord> f) {
		return records.stream().mapToDouble(f).filter(v -> !Double.isNaN(v)).max().orElse(Double.NaN);
	}

	private static Object num(double value) {
		return Double.isNaN(value) ? NA : value;
	}

	private static Object orNa(Object value) {
		return value == null ? NA : value;
	}

	private static Object[] prepend(Object first, Object[] rest) {
		Object[] all = new Object[rest.length + 1];
		all[0] = first;
		System.arraycopy(rest, 0, all, 1, rest.length);
		return all;
	}

	private String filename(IterationEndsEvent event, String prefix) {
		return matsimServices.getControllerIO().getIterationFilename(event.getIteration(),
				"drt_hubCleaning_" + prefix + "_" + drtConfigGroup.getMode() + ".csv");
	}

	private BufferedWriter getAppendingBufferedWriter(String prefix) {
		return IOUtils.getAppendingBufferedWriter(matsimServices.getControllerIO()
				.getOutputFilename("drt_hubCleaning_" + prefix + "_" + drtConfigGroup.getMode() + ".csv"));
	}

	private String line(Object... cells) {
		return Arrays.stream(cells).map(String::valueOf).collect(Collectors.joining(delimiter, "", "\n"));
	}
}
