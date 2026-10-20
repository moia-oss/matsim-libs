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
import org.matsim.contrib.drt.extension.operations.huboperations.HubWorkers;
import org.matsim.contrib.drt.extension.operations.huboperations.analysis.HubCleaningAnalysisTracker.CleaningRecord;
import org.matsim.contrib.drt.extension.operations.remoteoperations.analysis.RemoteGuidanceAnalysisTracker;
import org.matsim.contrib.drt.extension.operations.remoteoperations.analysis.RemoteGuidanceAnalysisTracker.IncidentRecord;
import org.matsim.contrib.drt.extension.operations.remoteoperations.analysis.RemoteGuidanceAnalysisTracker.OperatorRecord;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.core.controler.MatsimServices;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.controler.listener.ShutdownListener;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.core.utils.misc.Time;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The labour of a run at a glance, one row per labour type: how many people, how many shifts of which length, paid and
 * busy hours, when the first started and the last ended, and how many jobs they did. Two files per iteration, prefixed
 * {@code drt_labor_} and suffixed with the mode, and the last iteration's copied to the output root:
 * <ul>
 *     <li>{@code _stats}: one row per labour type, remote operators L_R, field support agents L_F, hub workers L_C
 *         and maintenance technicians L_M, and a {@code total} row.</li>
 *     <li>{@code _shifts}: one row per shift: type, id, hub, start, end, hours, jobs and busy hours.</li>
 * </ul>
 * Remote operators come from the operator started and ended events (actual hours, including a retention past the
 * planned end); their busy time is the time they spent handling incidents. Hub workers come from the roster (planned
 * window) and from the hub visits they served; their busy time is cleaning or maintenance, plugging takes none.
 *
 * @author lpfeil / MOIA
 */
public final class LaborStatsControlerListener implements IterationEndsListener, ShutdownListener {

	private static final String PREFIX = "drt_labor_";
	private static final String NA = "NA";

	/** One shift of one person, the unit both files are built from. */
	private record ShiftRow(String type, String id, String hub, double start, double end, int jobs, double busySeconds) {
		double hours() {
			return (end - start) / 3600.;
		}
	}

	private final DrtConfigGroup drtConfigGroup;
	@Nullable
	private final RemoteGuidanceAnalysisTracker operators;
	@Nullable
	private RemoteGuidanceAnalysisTracker fieldAgents;
	@Nullable
	private final HubCleaningAnalysisTracker hub;
	@Nullable
	private final HubWorkers workers;
	private final MatsimServices matsimServices;
	private final String delimiter;

	public LaborStatsControlerListener(DrtConfigGroup drtConfigGroup, @Nullable RemoteGuidanceAnalysisTracker operators,
									   @Nullable HubCleaningAnalysisTracker hub, @Nullable HubWorkers workers,
									   MatsimServices matsimServices) {
		this.drtConfigGroup = drtConfigGroup;
		this.operators = operators;
		this.hub = hub;
		this.workers = workers;
		this.matsimServices = matsimServices;
		this.delimiter = matsimServices.getConfig().global().getDefaultDelimiter();
	}

	/** Adds the field support agents L_F, from the tracker of their incident pool. */
	public LaborStatsControlerListener withFieldSupport(RemoteGuidanceAnalysisTracker fieldAgents) {
		this.fieldAgents = fieldAgents;
		return this;
	}

	@Override
	public void notifyIterationEnds(IterationEndsEvent event) {
		List<ShiftRow> rows = new ArrayList<>();
		rows.addAll(incidentRows(operators, "remoteOperator"));
		rows.addAll(incidentRows(fieldAgents, "fieldSupportAgent"));
		rows.addAll(hubWorkerRows());
		rows.sort(Comparator.comparing(ShiftRow::type).thenComparingDouble(ShiftRow::start).thenComparing(ShiftRow::id));
		writeStats(rows, iterationFile(event.getIteration(), "stats"));
		writeShifts(rows, iterationFile(event.getIteration(), "shifts"));
	}

	private static List<ShiftRow> incidentRows(@Nullable RemoteGuidanceAnalysisTracker operators, String laborType) {
		if (operators == null) {
			return List.of();
		}
		Map<String, List<IncidentRecord>> incidents = operators.getCompletedIncidents().stream()
				.filter(i -> i.operatorId() != null)
				.collect(Collectors.groupingBy(i -> i.operatorId().toString()));
		List<ShiftRow> rows = new ArrayList<>();
		for (OperatorRecord r : operators.getOperatorRecords()) {
			List<IncidentRecord> handled = incidents.getOrDefault(r.operatorId().toString(), List.of());
			double busy = handled.stream().mapToDouble(i -> i.resolveTime() - i.assignTime()).sum();
			rows.add(new ShiftRow(laborType, r.operatorId().toString(), NA, r.startTime(), r.actualEndTime(),
					handled.size(), busy));
		}
		return rows;
	}

	private List<ShiftRow> hubWorkerRows() {
		if (workers == null) {
			return List.of();
		}
		Map<String, List<CleaningRecord>> served = hub == null ? Map.of() : hub.getRecords().stream()
				.filter(r -> r.workerId() != null)
				.collect(Collectors.groupingBy(r -> r.workerId().toString()));
		List<ShiftRow> rows = new ArrayList<>();
		for (HubWorkers.Worker w : workers.getWorkers().values()) {
			List<CleaningRecord> visits = served.getOrDefault(w.id().toString(), List.of());
			double busy = visits.stream().mapToDouble(CleaningRecord::cleaningDuration)
					.filter(v -> !Double.isNaN(v)).sum();
			rows.add(new ShiftRow(laborType(w.pool()), w.id().toString(), w.hub().toString(), w.start(), w.end(), visits.size(),
					busy));
		}
		return rows;
	}

	/** Labour type of a hub pool, as in the {@code laborType} column. */
	static String laborType(HubWorkers.Pool pool) {
		return pool == HubWorkers.Pool.maintenance ? "maintenanceTechnician" : "hubWorker";
	}

	private void writeStats(List<ShiftRow> rows, String file) {
		Map<String, List<ShiftRow>> byType = rows.stream()
				.collect(Collectors.groupingBy(ShiftRow::type, java.util.LinkedHashMap::new, Collectors.toList()));
		try (BufferedWriter bw = IOUtils.getBufferedWriter(file)) {
			bw.append(line("laborType", "people", "shifts", "shiftLengths", "paidHours", "busyHours", "utilisation",
					"meanShiftHours", "firstStart", "lastEnd", "peakOnDuty", "peakTime", "jobs", "jobsPerPaidHour"));
			for (var entry : byType.entrySet()) {
				bw.append(summary(entry.getKey(), entry.getValue()));
			}
			if (byType.size() > 1) {
				bw.append(summary("total", rows));
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private String summary(String type, List<ShiftRow> rows) {
		if (rows.isEmpty()) {
			return line(type, 0, 0, NA, 0, 0, NA, NA, NA, NA, 0, NA, 0, NA);
		}
		double paid = rows.stream().mapToDouble(ShiftRow::hours).sum();
		double busy = rows.stream().mapToDouble(ShiftRow::busySeconds).sum() / 3600.;
		int jobs = rows.stream().mapToInt(ShiftRow::jobs).sum();
		double first = rows.stream().mapToDouble(ShiftRow::start).min().orElse(Double.NaN);
		double last = rows.stream().mapToDouble(ShiftRow::end).max().orElse(Double.NaN);
		// one shift per person in these pools, so people = shifts; kept apart for pools that may get split shifts
		long people = rows.stream().map(ShiftRow::id).distinct().count();
		double[] peak = peakOnDuty(rows);
		return line(type, people, rows.size(), lengths(rows), round(paid), round(busy),
				paid > 0 ? round(busy / paid) : NA, round(paid / rows.size()), Time.writeTime(first),
				Time.writeTime(last), (int) peak[0], Time.writeTime(peak[1]), jobs,
				paid > 0 ? round(jobs / paid) : NA);
	}

	/** Shift lengths as {@code 5x4h 3x8h}, rounded to the quarter hour. */
	private static String lengths(List<ShiftRow> rows) {
		Map<Double, Long> counts = rows.stream().collect(Collectors.groupingBy(
				r -> Math.round(r.hours() * 4) / 4., java.util.TreeMap::new, Collectors.counting()));
		return counts.entrySet().stream()
				.map(e -> e.getValue() + "x" + trim(e.getKey()) + "h")
				.collect(Collectors.joining(" "));
	}

	/** Most people on duty at once and the first time that happens. */
	private static double[] peakOnDuty(List<ShiftRow> rows) {
		List<double[]> changes = new ArrayList<>();
		for (ShiftRow r : rows) {
			changes.add(new double[]{r.start(), +1});
			changes.add(new double[]{r.end(), -1});
		}
		// ends before starts at the same time, so back-to-back shifts do not count twice
		changes.sort(Comparator.<double[]>comparingDouble(c -> c[0]).thenComparingDouble(c -> c[1]));
		int onDuty = 0;
		int peak = 0;
		double peakTime = Double.NaN;
		for (double[] c : changes) {
			onDuty += (int) c[1];
			if (onDuty > peak) {
				peak = onDuty;
				peakTime = c[0];
			}
		}
		return new double[]{peak, peakTime};
	}

	private void writeShifts(List<ShiftRow> rows, String file) {
		try (BufferedWriter bw = IOUtils.getBufferedWriter(file)) {
			bw.append(line("laborType", "id", "hub", "start", "end", "hours", "jobs", "busyHours", "utilisation"));
			for (ShiftRow r : rows) {
				double busy = r.busySeconds() / 3600.;
				bw.append(line(r.type(), r.id(), r.hub(), Time.writeTime(r.start()), Time.writeTime(r.end()),
						round(r.hours()), r.jobs(), round(busy), r.hours() > 0 ? round(busy / r.hours()) : NA));
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Copies the last iteration's files to the output root, as {@code output_drt_labor_*}. */
	@Override
	public void notifyShutdown(ShutdownEvent event) {
		int last = matsimServices.getConfig().controller().getLastIteration();
		for (String kind : List.of("stats", "shifts")) {
			try {
				IOUtils.copyFile(iterationFile(last, kind), matsimServices.getControllerIO()
						.getOutputFilenameWithOutputPrefix(name(kind)));
			} catch (Exception e) {
				// not written in the last iteration: nothing to copy
			}
		}
	}

	private String iterationFile(int iteration, String kind) {
		return matsimServices.getControllerIO().getIterationFilename(iteration, name(kind));
	}

	private String name(String kind) {
		return PREFIX + kind + "_" + drtConfigGroup.getMode() + ".csv";
	}

	private static String round(double value) {
		return String.format(Locale.ROOT, "%.2f", value);
	}

	private static String trim(double value) {
		return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
	}

	private String line(Object... cells) {
		return Arrays.stream(cells).map(String::valueOf).collect(Collectors.joining(delimiter, "", "\n"));
	}
}
