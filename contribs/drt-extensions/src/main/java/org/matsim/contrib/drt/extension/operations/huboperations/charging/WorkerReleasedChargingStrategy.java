/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.huboperations.charging;

import org.matsim.contrib.ev.charging.ChargingStrategy;

/**
 * Keeps a vehicle on the plug after it has reached its target until a hub worker unplugs it.
 * <p>
 * The ev charging logic ends a charge and frees the plug as soon as the strategy reports it completed. With hub workers
 * the plug is freed only when a worker unplugs the vehicle, which the {@code CleaningDispatcher} does with
 * {@code ChargingLogic.removeVehicle}. This wrapper makes that possible without touching the ev module: it never
 * reports the charge as completed, and once the wrapped strategy has reached its target it asks for no more energy, so
 * the vehicle stays on the plug and charges nothing. {@link #isTargetReached} tells the dispatcher when the vehicle
 * waits for its unplug, and {@link #cancel} hands the ending back to the ev logic.
 *
 * @author lpfeil / MOIA
 */
public final class WorkerReleasedChargingStrategy implements ChargingStrategy {

	private final ChargingStrategy delegate;
	private boolean cancelled = false;

	public WorkerReleasedChargingStrategy(ChargingStrategy delegate) {
		this.delegate = delegate;
	}

	/** Whether the wrapped strategy would have ended the charge, i.e. the vehicle now only waits to be unplugged. */
	public boolean isTargetReached() {
		return delegate.isChargingCompleted();
	}

	/**
	 * Lets the ev logic end the charge by itself in its next charging step. Used when the vehicle cannot be removed
	 * directly because the ev logic has not yet plugged or queued it.
	 */
	public void cancel() {
		cancelled = true;
	}

	@Override
	public boolean isChargingCompleted() {
		return cancelled;
	}

	@Override
	public double calcRemainingEnergyToCharge() {
		return delegate.isChargingCompleted() ? 0. : Math.max(0., delegate.calcRemainingEnergyToCharge());
	}

	@Override
	public double calcRemainingTimeToCharge() {
		return delegate.isChargingCompleted() ? 0. : delegate.calcRemainingTimeToCharge();
	}
}
