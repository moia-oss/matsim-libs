/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.remoteoperations;

import org.matsim.api.core.v01.events.LinkLeaveEvent;
import org.matsim.api.core.v01.events.handler.LinkLeaveEventHandler;
import org.matsim.core.events.MobsimScopeEventHandler;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;
import org.matsim.core.mobsim.framework.listeners.MobsimBeforeSimStepListener;

/**
 * The field support incident dispatcher as a QSim component of its own. It delegates to an {@link IncidentDispatcher}
 * built by {@link IncidentDispatcher#forFieldSupport}; the separate type only gives it a binding key next to the
 * remote-guidance dispatcher of the same mode.
 *
 * @author lpfeil / MOIA
 */
public final class FieldSupportIncidentDispatcher implements MobsimBeforeSimStepListener, LinkLeaveEventHandler,
		MobsimScopeEventHandler {

	private final IncidentDispatcher delegate;

	public FieldSupportIncidentDispatcher(IncidentDispatcher delegate) {
		this.delegate = delegate;
	}

	@Override
	public void notifyMobsimBeforeSimStep(@SuppressWarnings("rawtypes") MobsimBeforeSimStepEvent e) {
		delegate.notifyMobsimBeforeSimStep(e);
	}

	@Override
	public void handleEvent(LinkLeaveEvent event) {
		delegate.handleEvent(event);
	}
}
