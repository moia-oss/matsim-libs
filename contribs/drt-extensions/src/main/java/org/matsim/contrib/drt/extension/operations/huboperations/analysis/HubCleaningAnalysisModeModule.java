/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.huboperations.analysis;

import org.matsim.contrib.drt.extension.DrtWithExtensionsConfigGroup;
import org.matsim.contrib.drt.extension.operations.DrtOperationsParams;
import org.matsim.contrib.drt.extension.operations.huboperations.CleaningDispatcher;
import org.matsim.contrib.drt.extension.operations.huboperations.HubWorkers;
import org.matsim.contrib.drt.extension.operations.huboperations.config.HubOperationsParams;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeModule;
import org.matsim.contrib.common.timeprofile.ProfileWriter;
import org.matsim.contrib.drt.extension.operations.remoteoperations.schedule.IncidentHoldTask;
import org.matsim.contrib.drt.extension.operations.shifts.schedule.WaitForShiftTask;
import org.matsim.contrib.drt.schedule.DrtStayTask;
import org.matsim.contrib.dvrp.analysis.VehicleOccupancyProfileCalculator;
import org.matsim.contrib.dvrp.fleet.DvrpVehicleSpecification;
import org.matsim.contrib.dvrp.fleet.FleetSpecification;
import org.matsim.contrib.dvrp.schedule.Task;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.contrib.common.timeprofile.TimeProfileCharts;
import org.matsim.core.controler.MatsimServices;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.listener.ShutdownListener;
import org.matsim.core.utils.io.IOUtils;

import java.awt.Color;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import java.util.Optional;

/**
 * Installs the hub cleaning analysis: a controller-scoped {@link HubCleaningAnalysisTracker} (event handler) feeding a
 * {@link HubCleaningAnalysisControlerListener} (per-iteration CSV writer), in the same idiom as
 * {@code RemoteGuidanceAnalysisModeModule}. Only installed for modes that configure hub operations.
 *
 * @author lpfeil / MOIA
 */
public class HubCleaningAnalysisModeModule extends AbstractDvrpModeModule {

	private final DrtConfigGroup drtConfigGroup;

	public HubCleaningAnalysisModeModule(DrtConfigGroup drtConfigGroup) {
		super(drtConfigGroup.getMode());
		this.drtConfigGroup = drtConfigGroup;
	}

	@Override
	public void install() {
		Optional<HubOperationsParams> hubParams = ((DrtWithExtensionsConfigGroup) drtConfigGroup)
				.getDrtOperationsParams()
				.flatMap(DrtOperationsParams::getHubOperationsParams);
		if (hubParams.isEmpty()) {
			return;
		}

		bindModal(HubCleaningAnalysisTracker.class)
				.toProvider(modalProvider(getter -> new HubCleaningAnalysisTracker(getMode())))
				.asEagerSingleton();
		addEventHandlerBinding().to(modalKey(HubCleaningAnalysisTracker.class));

		bindModal(HubCleaningAnalysisControlerListener.class).toProvider(modalProvider(getter ->
				new HubCleaningAnalysisControlerListener(drtConfigGroup,
						getter.getModal(HubCleaningAnalysisTracker.class),
						HubWorkers.read(hubParams.get(), getConfig().getContext()),
						getter.get(MatsimServices.class))));
		addControllerListenerBinding().to(modalKey(HubCleaningAnalysisControlerListener.class));

		// occupancy profile with the hub visits split into interior, exterior, charging and the waiting states; same
		// bins and horizon as occupancy_time_profiles, so the two files line up row by row
		HubOperationsParams params = hubParams.get();
		bindModal(HubTimeProfileCalculator.class).toProvider(modalProvider(getter -> new HubTimeProfileCalculator(
				getMode(), TIME_BIN, analysisEndTime(getter.getModal(FleetSpecification.class),
				getter.get(QSimConfigGroup.class)), params.getCleaningDuration(), params.getExteriorCleaningDuration(),
				params.getMaintenanceDuration())))
				.asEagerSingleton();
		addEventHandlerBinding().to(modalKey(HubTimeProfileCalculator.class));
		addControllerListenerBinding().toProvider(modalProvider(getter -> new ProfileWriter(
				getter.get(MatsimServices.class), getMode(),
				new HubOccupancyProfileView(getter.getModal(VehicleOccupancyProfileCalculator.class),
						getter.getModal(HubTimeProfileCalculator.class), TASK_TYPE_ORDER, Map.of(
						DrtStayTask.TYPE.name(), Color.LIGHT_GRAY, WaitForShiftTask.TYPE.name(), Color.WHITE,
						// the chart's default reds would read as maintenance: the drive to the hub in MOIA black, the
						// remote-guidance hold in warm grey
						CleaningDispatcher.DRIVE_TO_CLEANING_TYPE.name(), new Color(0x000C15),
						IncidentHoldTask.TYPE.name(), new Color(0xDDDBD3),
						// field support holds in MOIA dark gold, set apart from the remote hold's grey
						IncidentHoldTask.FIELD_SUPPORT_TYPE.name(), new Color(0xDB9F22)),
						analysisEndTime(getter.getModal(FleetSpecification.class), getter.get(QSimConfigGroup.class))),
				PROFILE)));
		// as DrtAnalysisControlerListener does for occupancy_time_profiles: copy the last iteration's files to the output
		// root, so output_hub_occupancy_time_profiles_<mode> sits next to output_occupancy_time_profiles_<mode>
		String mode = getMode();
		addControllerListenerBinding().toInstance((ShutdownListener) event -> {
			OutputDirectoryHierarchy io = event.getServices().getControllerIO();
			int last = event.getServices().getConfig().controller().getLastIteration();
			for (String name : List.of(PROFILE + "_" + mode + ".txt",
					PROFILE + "_" + TimeProfileCharts.ChartType.Line + "_" + mode + ".png",
					PROFILE + "_" + TimeProfileCharts.ChartType.StackedArea + "_" + mode + ".png")) {
				try {
					IOUtils.copyFile(io.getIterationFilename(last, name), io.getOutputFilenameWithOutputPrefix(name));
				} catch (Exception e) {
					// not written in the last iteration, e.g. graphs switched off: nothing to copy
				}
			}
		});
	}

	private static final String PROFILE = "hub_occupancy_time_profiles";

	/** The occupancy profile's bin size, see {@code DrtModeAnalysisModule}. */
	private static final int TIME_BIN = 300;

	/** Non-passenger tasks after the hub columns: changeover, waiting for a shift, stay last. */
	private static final Comparator<Task.TaskType> TASK_TYPE_ORDER = Comparator.comparing(type ->
			type.equals(DrtStayTask.TYPE) ? "C" : type.equals(WaitForShiftTask.TYPE) ? "B" : "A" + type.name());

	/** The horizon of {@code VehicleOccupancyProfileCalculator}: the later of the QSim end and the last service end. */
	private static double analysisEndTime(FleetSpecification fleet, QSimConfigGroup qsim) {
		double serviceEnd = fleet.getVehicleSpecifications().values().stream()
				.mapToDouble(DvrpVehicleSpecification::getServiceEndTime).max().orElse(0);
		return Math.max(qsim.getEndTime().orElse(0), serviceEnd);
	}
}
