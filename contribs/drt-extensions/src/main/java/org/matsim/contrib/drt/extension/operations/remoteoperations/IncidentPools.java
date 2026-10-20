/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.remoteoperations;

/**
 * Names of the incident-handling labour pools. Both pools run the same process (an incident holds the vehicle in place
 * until a free agent of the pool has handled it) and differ only in their rates, durations and staff:
 * <ul>
 *     <li>{@link #REMOTE_GUIDANCE}: remote operators L_R, frequent and short incidents.</li>
 *     <li>{@link #FIELD_SUPPORT}: field support agents L_F, rare incidents that cannot be solved remotely and take much
 *         longer.</li>
 * </ul>
 * The pool name is carried on every incident and agent lifecycle event, so analyses can tell the pools apart.
 *
 * @author lpfeil / MOIA
 */
public final class IncidentPools {

	public static final String REMOTE_GUIDANCE = "remoteGuidance";
	public static final String FIELD_SUPPORT = "fieldSupport";

	private IncidentPools() {
	}
}
