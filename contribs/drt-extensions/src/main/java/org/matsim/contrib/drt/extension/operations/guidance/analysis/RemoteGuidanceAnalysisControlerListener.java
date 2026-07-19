/*
 * Copyright (C) 2026 MOIA GmbH - All Rights Reserved
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.guidance.analysis;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.matsim.contrib.drt.extension.operations.guidance.RemoteGuidanceOperators;
import org.matsim.contrib.drt.extension.operations.guidance.analysis.RemoteGuidanceAnalysisTracker.ActivationChange;
import org.matsim.contrib.drt.extension.operations.guidance.analysis.RemoteGuidanceAnalysisTracker.IncidentRecord;
import org.matsim.contrib.drt.extension.operations.guidance.events.VehicleDeactivatedForRemoteGuidanceEvent.DeactivationReason;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.core.controler.MatsimServices;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.utils.io.IOUtils;

import java.awt.BasicStroke;
import java.awt.Color;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/**
 * Writes the remote-guidance operator-utilisation analysis outputs (track B1–B5) per iteration, from the raw data
 * accumulated by {@link RemoteGuidanceAnalysisTracker}. Files, all prefixed with {@code drt_remoteGuidance_} and
 * suffixed with the mode:
 * <ul>
 *     <li><b>B1</b> {@code _incidents} (CSV, per iteration) — one row per completed incident.</li>
 *     <li><b>B2</b> {@code _incidentStats} (CSV, per iteration) — per-severity + combined summary of counts, queueing
 *         and durations; also appended one row per iteration to the cross-iteration {@code _incidentStats} output
 *         file.</li>
 *     <li><b>B3</b> {@code _operatorUtilisation} (CSV + PNG, per iteration) — the concurrent-busy-operator step function
 *         against the operator pool (the incident server pool is one operator per incident regardless of κ, D14, so the
 *         denominator is the schedule-based {@link RemoteGuidanceOperators#plannedOnDutyCount(double)}).</li>
 *     <li><b>B4</b> {@code _activeVehicles} (CSV + PNG, per iteration) — the supervised-active count step function
 *         against the activation ceiling {@link RemoteGuidanceOperators#activationCapacityAt(double)}.</li>
 *     <li><b>B5</b> {@code _deactivationReasons} (CSV, per iteration) — the deactivation-reason breakdown plus the
 *         activation-churn totals.</li>
 * </ul>
 * The cross-iteration {@code _incidentStats} summary row lets a run be tracked over its iterations without post-
 * processing per-iteration files.
 * <p>
 * <b>Not covered here</b> (deferred — the events do not yet carry the needed causal link / position): per-trip
 * delay-budget decomposition, incident blast radius, coverage maps, and causal rejection attribution.
 *
 * @author nkuehnel / MOIA
 */
public final class RemoteGuidanceAnalysisControlerListener implements IterationEndsListener {

	private final DrtConfigGroup drtConfigGroup;
	private final RemoteGuidanceAnalysisTracker tracker;
	private final RemoteGuidanceOperators operators;
	private final MatsimServices matsimServices;

	private final String delimiter;
	private final String runId;
	private boolean incidentStatsHeaderWritten = false;

	private static final String NA = "NA";
	private static final String COMBINED = "all";

	public RemoteGuidanceAnalysisControlerListener(DrtConfigGroup drtConfigGroup,
												   RemoteGuidanceAnalysisTracker tracker,
												   RemoteGuidanceOperators operators, MatsimServices matsimServices) {
		this.drtConfigGroup = drtConfigGroup;
		this.tracker = tracker;
		this.operators = operators;
		this.matsimServices = matsimServices;
		this.delimiter = matsimServices.getConfig().global().getDefaultDelimiter();
		this.runId = Optional.ofNullable(matsimServices.getConfig().controller().getRunId()).orElse(NA);
	}

	@Override
	public void notifyIterationEnds(IterationEndsEvent event) {
		int createGraphsInterval = matsimServices.getConfig().controller().getCreateGraphsInterval();
		boolean createGraphs = createGraphsInterval > 0 && event.getIteration() % createGraphsInterval == 0;

		List<IncidentRecord> incidents = tracker.getCompletedIncidents();

		writeIncidentLog(incidents, filename(event, "incidents", ".csv"));                       // B1
		writeIncidentStats(incidents, event.getIteration(), filename(event, "incidentStats", ".csv")); // B2
		writeOperatorUtilisation(incidents, filename(event, "operatorUtilisation", ".csv"),
				createGraphs ? filename(event, "operatorUtilisation", ".png") : null);           // B3
		writeActiveVehicles(tracker.getActivationChanges(), filename(event, "activeVehicles", ".csv"),
				createGraphs ? filename(event, "activeVehicles", ".png") : null);                // B4
		writeDeactivationReasons(filename(event, "deactivationReasons", ".csv"));                // B5
	}

	// ---------------------------------------------------------------------------------------- B1: incident log

	private void writeIncidentLog(List<IncidentRecord> incidents, String csvFile) {
		try (BufferedWriter bw = IOUtils.getBufferedWriter(csvFile)) {
			bw.append(line("vehicle", "operatorId", "severity", "link", "startTime", "assignTime", "resolveTime",
					"expectedDuration", "actualDuration", "queued", "queueDelay"));
			for (IncidentRecord i : incidents) {
				bw.append(line(i.vehicleId(), i.operatorId(), i.severity(), i.linkId(), i.startTime(), i.assignTime(),
						i.resolveTime(), i.expectedDuration(), i.actualDuration(), i.queued(), i.queueDelay()));
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	// ---------------------------------------------------------------------------------------- B2: incident summary

	private void writeIncidentStats(List<IncidentRecord> incidents, int iteration, String perIterationCsv) {
		// severity classes present this iteration, in ascending order, plus the combined aggregate.
		List<Integer> severities = incidents.stream().map(IncidentRecord::severity).distinct().sorted().toList();

		try (BufferedWriter bw = IOUtils.getBufferedWriter(perIterationCsv)) {
			bw.append(line(statsHeaderCells().toArray()));
			bw.append(line(prepend(COMBINED, summarizeIncidents(incidents))));
			for (int severity : severities) {
				List<IncidentRecord> subset = incidents.stream().filter(i -> i.severity() == severity).toList();
				bw.append(line(prepend(Integer.toString(severity), summarizeIncidents(subset))));
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		// cross-iteration appended file: one combined row per iteration.
		try (BufferedWriter bw = getAppendingBufferedWriter("incidentStats", ".csv")) {
			if (!incidentStatsHeaderWritten) {
				incidentStatsHeaderWritten = true;
				List<Object> header = new ArrayList<>();
				header.add("runId");
				header.add("iteration");
				header.addAll(statsHeaderCells());
				bw.write(line(header.toArray()));
			}
			bw.write(line(prepend(runId, prepend(Integer.toString(iteration),
					prepend(COMBINED, summarizeIncidents(incidents))))));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private List<Object> statsHeaderCells() {
		return new ArrayList<>(List.of("severity", "count", "queuedCount", "queuedShare", "meanQueueDelay",
				"p50QueueDelay", "p90QueueDelay", "p95QueueDelay", "maxQueueDelay", "meanActualDuration",
				"p90ActualDuration", "meanActualToExpectedRatio", "meanInterArrival"));
	}

	private Object[] summarizeIncidents(List<IncidentRecord> incidents) {
		int count = incidents.size();
		long queuedCount = incidents.stream().filter(IncidentRecord::queued).count();
		double queuedShare = count == 0 ? Double.NaN : (double) queuedCount / count;

		double[] queueDelays = incidents.stream().mapToDouble(IncidentRecord::queueDelay).sorted().toArray();
		double[] durations = incidents.stream().mapToDouble(IncidentRecord::actualDuration).sorted().toArray();
		double meanRatio = incidents.stream()
				.filter(i -> i.expectedDuration() > 0)
				.mapToDouble(i -> i.actualDuration() / i.expectedDuration())
				.average().orElse(Double.NaN);

		// mean inter-arrival over incident start times (needs ≥ 2 to define a gap).
		double[] starts = incidents.stream().mapToDouble(IncidentRecord::startTime).sorted().toArray();
		double meanInterArrival = starts.length < 2 ? Double.NaN
				: (starts[starts.length - 1] - starts[0]) / (starts.length - 1);

		return new Object[] {
				count,
				queuedCount,
				queuedShare,
				mean(queueDelays),
				percentile(queueDelays, 50),
				percentile(queueDelays, 90),
				percentile(queueDelays, 95),
				max(queueDelays),
				mean(durations),
				percentile(durations, 90),
				meanRatio,
				meanInterArrival
		};
	}

	// ---------------------------------------------------------------------------------------- B3: operator utilisation

	private void writeOperatorUtilisation(List<IncidentRecord> incidents, String csvFile, String pngFile) {
		// build the concurrent-busy step function from operator-busy intervals [assignTime, resolveTime].
		List<double[]> changes = new ArrayList<>(); // {time, delta}
		for (IncidentRecord i : incidents) {
			if (!Double.isNaN(i.assignTime())) {
				changes.add(new double[] {i.assignTime(), +1});
				changes.add(new double[] {i.resolveTime(), -1});
			}
		}
		changes.sort(Comparator.comparingDouble((double[] c) -> c[0]).thenComparingDouble(c -> c[1]));

		XYSeries busySeries = new XYSeries("busy operators", false, true);
		XYSeries onDutySeries = new XYSeries("on-duty operators", false, true);

		try (BufferedWriter bw = IOUtils.getBufferedWriter(csvFile)) {
			bw.append(line("time", "busyOperators", "onDutyOperators", "utilisation"));
			int busy = 0;
			for (double[] c : changes) {
				busy += (int) c[1];
				double time = c[0];
				// schedule-based (planned-window) count, NOT the runtime onDutyCount: the latter reads the mutable
				// released flag which reflects end-of-iteration state, so querying it for a historical time is wrong.
				int onDuty = operators.plannedOnDutyCount(time);
				double utilisation = onDuty == 0 ? Double.NaN : (double) busy / onDuty;
				bw.append(line(time, busy, onDuty, utilisation));
				busySeries.add(time / 3600.0, busy);
				onDutySeries.add(time / 3600.0, onDuty);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		if (pngFile != null) {
			writeStepChart(pngFile, "Remote guidance operator utilisation", "# operators", busySeries, onDutySeries);
		}
	}

	// ---------------------------------------------------------------------------------------- B4: active vehicles

	private void writeActiveVehicles(List<ActivationChange> changes, String csvFile, String pngFile) {
		List<ActivationChange> sorted = new ArrayList<>(changes);
		sorted.sort(Comparator.comparingDouble(ActivationChange::time).thenComparingInt(ActivationChange::delta));

		XYSeries activeSeries = new XYSeries("active vehicles", false, true);
		XYSeries ceilingSeries = new XYSeries("activation ceiling", false, true);

		try (BufferedWriter bw = IOUtils.getBufferedWriter(csvFile)) {
			bw.append(line("time", "activeVehicles", "activationCapacity"));
			int active = 0;
			for (ActivationChange c : sorted) {
				active += c.delta();
				int ceiling = operators.activationCapacityAt(c.time());
				bw.append(line(c.time(), active, ceiling));
				activeSeries.add(c.time() / 3600.0, active);
				ceilingSeries.add(c.time() / 3600.0, ceiling);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		if (pngFile != null) {
			writeStepChart(pngFile, "Remote guidance active vehicles", "# vehicles", activeSeries, ceilingSeries);
		}
	}

	// ---------------------------------------------------------------------------------------- B5: deactivation reasons

	private void writeDeactivationReasons(String csvFile) {
		var counts = tracker.getDeactivationReasonCounts();
		int totalDeactivations = counts.values().stream().mapToInt(Integer::intValue).sum();
		try (BufferedWriter bw = IOUtils.getBufferedWriter(csvFile)) {
			bw.append(line("reason", "count", "share"));
			for (DeactivationReason reason : DeactivationReason.values()) {
				int count = counts.getOrDefault(reason, 0);
				double share = totalDeactivations == 0 ? Double.NaN : (double) count / totalDeactivations;
				bw.append(line(reason, count, share));
			}
			// activation-churn totals: each activation-deactivation pair is one supervision cycle.
			bw.append(line("totalActivations", tracker.getActivationCount(), NA));
			bw.append(line("totalDeactivations", totalDeactivations, NA));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	// ---------------------------------------------------------------------------------------- helpers

	private void writeStepChart(String pngFile, String title, String rangeLabel, XYSeries... series) {
		XYSeriesCollection dataset = new XYSeriesCollection();
		for (XYSeries s : series) {
			dataset.addSeries(s);
		}
		JFreeChart chart = ChartFactory.createXYStepChart(title, "time [h]", rangeLabel, dataset,
				PlotOrientation.VERTICAL, true, false, false);
		XYPlot plot = chart.getXYPlot();
		for (int i = 0; i < series.length; i++) {
			plot.getRenderer().setSeriesStroke(i, new BasicStroke(2.0f));
		}
		plot.setBackgroundPaint(Color.white);
		plot.setRangeGridlinePaint(Color.gray);
		plot.setDomainGridlinePaint(Color.gray);
		try {
			ChartUtils.saveChartAsPNG(new File(pngFile), chart, 1024, 768);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static double mean(double[] sorted) {
		if (sorted.length == 0) {
			return Double.NaN;
		}
		return Arrays.stream(sorted).average().orElse(Double.NaN);
	}

	private static double max(double[] sorted) {
		return sorted.length == 0 ? Double.NaN : sorted[sorted.length - 1];
	}

	/** Nearest-rank percentile over an already-sorted array; {@code p} in (0, 100]. */
	private static double percentile(double[] sorted, double p) {
		if (sorted.length == 0) {
			return Double.NaN;
		}
		int rank = (int) Math.ceil(p / 100.0 * sorted.length);
		return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
	}

	private Object[] prepend(Object head, Object[] tail) {
		Object[] out = new Object[tail.length + 1];
		out[0] = head;
		System.arraycopy(tail, 0, out, 1, tail.length);
		return out;
	}

	private String filename(IterationEndsEvent event, String prefix, String extension) {
		return matsimServices.getControllerIO().getIterationFilename(event.getIteration(),
				"drt_remoteGuidance_" + prefix + "_" + drtConfigGroup.getMode() + extension);
	}

	private BufferedWriter getAppendingBufferedWriter(String prefix, String extension) {
		return IOUtils.getAppendingBufferedWriter(matsimServices.getControllerIO()
				.getOutputFilename("drt_remoteGuidance_" + prefix + "_" + drtConfigGroup.getMode() + extension));
	}

	private String line(Object... cells) {
		return Arrays.stream(cells).map(String::valueOf).collect(Collectors.joining(delimiter, "", "\n"));
	}
}
