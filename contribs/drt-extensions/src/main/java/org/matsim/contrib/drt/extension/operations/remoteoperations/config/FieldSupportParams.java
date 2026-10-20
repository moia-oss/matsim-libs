/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.remoteoperations.config;

import com.google.common.base.Verify;
import jakarta.annotation.Nullable;
import org.matsim.contrib.common.util.ReflectiveConfigGroupWithConfigurableParameterSets;
import org.matsim.core.config.Config;

import java.util.Optional;

/**
 * Field support, the labour pool L_F for incidents a remote operator cannot solve. An optional parameter set of
 * {@link RemoteGuidanceParams}: its agents are shifts of {@link #agentShiftType} in the same shift input file as the
 * remote operators, and its incidents are an own {@link IncidentParams} set, an independent Poisson process over the
 * driven distance, usually much rarer and much longer than the remote ones.
 * <p>
 * For now the process is the same as for remote guidance: the vehicle is held in place until a free field agent has
 * handled the incident, and it queues while none is free. The two pools run in parallel and never stand in for each
 * other. Field agents supervise no vehicles, so their shifts add no activation capacity. Driving to the vehicle is a
 * later extension.
 *
 * @author lpfeil / MOIA
 */
public class FieldSupportParams extends ReflectiveConfigGroupWithConfigurableParameterSets {

	public static final String SET_NAME = "fieldSupport";

	@Parameter
	@Comment("Shift type (in the shift input file) that marks a shift as a field support agent shift. Defaults to"
			+ " 'fieldSupport'.")
	private String agentShiftType = "fieldSupport";

	@Nullable
	private IncidentParams incidentParams;

	public FieldSupportParams() {
		super(SET_NAME);
		addDefinition(IncidentParams.SET_NAME, IncidentParams::new, () -> incidentParams,
				params -> incidentParams = (IncidentParams) params);
	}

	/** The field incidents; without them the field agents are rostered but have nothing to do. */
	public Optional<IncidentParams> getIncidentParams() {
		return Optional.ofNullable(incidentParams);
	}

	public String getAgentShiftType() {
		return agentShiftType;
	}

	public void setAgentShiftType(String agentShiftType) {
		this.agentShiftType = agentShiftType;
	}

	@Override
	protected void checkConsistency(Config config) {
		super.checkConsistency(config);
		Verify.verify(agentShiftType != null && !agentShiftType.isBlank(), "agentShiftType must be set.");
	}
}
