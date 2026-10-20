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
import org.matsim.contrib.drt.extension.operations.huboperations.HubWorkers;
import org.matsim.contrib.drt.extension.operations.huboperations.config.HubOperationsParams;
import org.matsim.contrib.drt.extension.operations.remoteoperations.analysis.RemoteGuidanceAnalysisModeModule;
import org.matsim.contrib.drt.extension.operations.remoteoperations.analysis.RemoteGuidanceAnalysisTracker;
import org.matsim.contrib.drt.extension.operations.remoteoperations.config.RemoteGuidanceParams;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeModule;
import org.matsim.core.controler.MatsimServices;

import java.util.Optional;

/**
 * Installs {@link LaborStatsControlerListener}, the per-run labour summary {@code drt_labor_stats}, for modes with
 * remote guidance, hub operations or both. It reads the trackers the remote-guidance and hub-cleaning analysis modules
 * bind, so it must be installed after them.
 *
 * @author lpfeil / MOIA
 */
public class LaborStatsModeModule extends AbstractDvrpModeModule {

	private final DrtConfigGroup drtConfigGroup;

	public LaborStatsModeModule(DrtConfigGroup drtConfigGroup) {
		super(drtConfigGroup.getMode());
		this.drtConfigGroup = drtConfigGroup;
	}

	@Override
	public void install() {
		Optional<DrtOperationsParams> ops = ((DrtWithExtensionsConfigGroup) drtConfigGroup).getDrtOperationsParams();
		boolean remoteGuidance = ops.flatMap(DrtOperationsParams::getRemoteGuidanceParams).isPresent();
		Optional<HubOperationsParams> hubParams = ops.flatMap(DrtOperationsParams::getHubOperationsParams);
		if (!remoteGuidance && hubParams.isEmpty()) {
			return;
		}
		boolean fieldSupport = ops.flatMap(DrtOperationsParams::getRemoteGuidanceParams)
				.flatMap(RemoteGuidanceParams::getFieldSupportParams).isPresent();
		bindModal(LaborStatsControlerListener.class).toProvider(modalProvider(getter -> {
			LaborStatsControlerListener listener = new LaborStatsControlerListener(drtConfigGroup,
					remoteGuidance ? getter.getModal(RemoteGuidanceAnalysisTracker.class) : null,
					hubParams.isPresent() ? getter.getModal(HubCleaningAnalysisTracker.class) : null,
					hubParams.map(p -> HubWorkers.read(p, getConfig().getContext())).orElse(null),
					getter.get(MatsimServices.class));
			if (fieldSupport) {
				listener.withFieldSupport(getter.getModal(
						RemoteGuidanceAnalysisModeModule.FieldSupportAnalysisTracker.class).tracker());
			}
			return listener;
		})).asEagerSingleton();
		addControllerListenerBinding().to(modalKey(LaborStatsControlerListener.class));
	}
}
