/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.remoteoperations.analysis;

import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.drt.analysis.DrtEventSequenceCollector;
import org.matsim.contrib.drt.extension.DrtWithExtensionsConfigGroup;
import org.matsim.contrib.drt.extension.operations.DrtOperationsParams;
import org.matsim.contrib.drt.extension.operations.remoteoperations.FieldSupportAgents;
import org.matsim.contrib.drt.extension.operations.remoteoperations.IncidentPools;
import org.matsim.contrib.drt.extension.operations.remoteoperations.RemoteGuidanceOperators;
import org.matsim.contrib.drt.extension.operations.remoteoperations.config.RemoteGuidanceParams;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeModule;
import org.matsim.core.controler.MatsimServices;

import java.util.Optional;

/**
 * Installs the remote-guidance operator-utilisation analysis: a controller-scoped
 * {@link RemoteGuidanceAnalysisTracker} (event handler) feeding a {@link RemoteGuidanceAnalysisControlerListener}
 * (per-iteration file writer), bound modally in the same idiom as {@code DrtShiftEfficiencyModeModule}. Only installed
 * for modes that actually configure remote guidance; otherwise the module is inert.
 *
 * @author nkuehnel / MOIA
 */
public class RemoteGuidanceAnalysisModeModule extends AbstractDvrpModeModule {

	private final DrtConfigGroup drtConfigGroup;

	public RemoteGuidanceAnalysisModeModule(DrtConfigGroup drtConfigGroup) {
		super(drtConfigGroup.getMode());
		this.drtConfigGroup = drtConfigGroup;
	}

	@Override
	public void install() {
		Optional<RemoteGuidanceParams> remoteGuidance = ((DrtWithExtensionsConfigGroup) drtConfigGroup)
				.getDrtOperationsParams()
				.flatMap(DrtOperationsParams::getRemoteGuidanceParams);
		if (remoteGuidance.isEmpty()) {
			return;
		}

		bindModal(RemoteGuidanceAnalysisTracker.class)
				.toProvider(modalProvider(getter -> new RemoteGuidanceAnalysisTracker(getMode())))
				.asEagerSingleton();
		addEventHandlerBinding().to(modalKey(RemoteGuidanceAnalysisTracker.class));

		bindModal(RemoteGuidanceAnalysisControlerListener.class).toProvider(modalProvider(getter ->
				new RemoteGuidanceAnalysisControlerListener(drtConfigGroup,
						getter.getModal(RemoteGuidanceAnalysisTracker.class),
						getter.getModal(RemoteGuidanceOperators.class),
						getter.getModal(DrtEventSequenceCollector.class),
						getter.getModal(Network.class),
						getter.get(MatsimServices.class))));
		addControllerListenerBinding().to(modalKey(RemoteGuidanceAnalysisControlerListener.class));

		// field support (L_F): the same incident outputs as drt_fieldSupport_*, from a tracker of its own pool
		if (remoteGuidance.get().getFieldSupportParams().isPresent()) {
			bindModal(FieldSupportAnalysisTracker.class).toProvider(modalProvider(getter -> new FieldSupportAnalysisTracker(
					new RemoteGuidanceAnalysisTracker(getMode(), IncidentPools.FIELD_SUPPORT)))).asEagerSingleton();
			addEventHandlerBinding().toProvider(modalProvider(
					getter -> getter.getModal(FieldSupportAnalysisTracker.class).tracker()));
			addControllerListenerBinding().toProvider(modalProvider(getter ->
					new RemoteGuidanceAnalysisControlerListener(drtConfigGroup,
							getter.getModal(FieldSupportAnalysisTracker.class).tracker(),
							getter.getModal(FieldSupportAgents.class).registry(),
							getter.getModal(DrtEventSequenceCollector.class),
							getter.getModal(Network.class),
							getter.get(MatsimServices.class))));
		}
	}

	/** Binding key of the field support tracker, next to the remote-guidance one. */
	public record FieldSupportAnalysisTracker(RemoteGuidanceAnalysisTracker tracker) {
	}
}
