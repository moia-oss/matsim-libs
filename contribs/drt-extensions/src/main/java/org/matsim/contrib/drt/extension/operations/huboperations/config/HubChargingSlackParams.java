/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.huboperations.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.PositiveOrZero;
import org.matsim.core.config.ReflectiveConfigGroup;

/**
 * Optional fleet-slack aware charging policy for hub charging, after the slack-aware reactive charging controller of
 * the MOIA Lucerne study (FleetSlackParams). When present as a parameter set of {@link HubOperationsParams}, the fleet
 * situation rations charging and the state of charge only decides eligibility and urgency:
 * <ul>
 *     <li>below {@link #getSocCritical()} a vehicle is always due for charging, whatever the fleet does;</li>
 *     <li>below {@link #getSocOpportunity()} it is due only while the fleet has slack;</li>
 *     <li>while the fleet is tight, plugged-in vehicles above {@link #getSocRelease()} that have charged for at least
 *         {@link #getMinChargeSecondsBeforeRelease()} are unplugged early, fullest first and only as many as the
 *         fleet is short of {@link #getSlackLow()}. Each early unplug is a worker job like any other unplug.</li>
 * </ul>
 * The fleet is RELAXED or TIGHT with hysteresis between {@link #getSlackLow()} and {@link #getSlackHigh()} on the
 * smoothed share of free vehicles, and a minimum dwell per state. Without this set hub charging uses the plain
 * threshold {@link HubOperationsParams#getChargeSocThreshold()} with the bundling share.
 *
 * @author lpfeil / MOIA
 */
public class HubChargingSlackParams extends ReflectiveConfigGroup {

	public static final String SET_NAME = "chargingSlack";

	/** Minimum distance between the opportunistic threshold and the charging target, against oscillation. */
	public static final double MIN_BAND_BELOW_TARGET = 0.05;

	@Parameter
	@Comment("Below this SOC a vehicle always charges, regardless of the fleet. A range reserve. Defaults to 0.5.")
	@DecimalMin("0.0")
	@DecimalMax("1.0")
	private double socCritical = 0.50;

	@Parameter
	@Comment("Below this SOC a vehicle charges while the fleet has slack. Must be clearly below the charging target."
			+ " Defaults to 0.6.")
	@DecimalMin("0.0")
	@DecimalMax("1.0")
	private double socOpportunity = 0.60;

	@Parameter
	@Comment("From this SOC a charging vehicle may be unplugged early while the fleet is tight. Defaults to 0.7.")
	@DecimalMin("0.0")
	@DecimalMax("1.0")
	private double socRelease = 0.70;

	@Parameter
	@Comment("Minimum time on the plug before an early unplug, in seconds. Defaults to 600.")
	@PositiveOrZero
	private double minChargeSecondsBeforeRelease = 600;

	@Parameter
	@Comment("Free fleet share above which the fleet becomes RELAXED. Defaults to 0.3.")
	@DecimalMin("0.0")
	@DecimalMax("1.0")
	private double slackHigh = 0.30;

	@Parameter
	@Comment("Free fleet share below which the fleet becomes TIGHT. Defaults to 0.15.")
	@DecimalMin("0.0")
	@DecimalMax("1.0")
	private double slackLow = 0.15;

	@Parameter
	@Comment("Minimum dwell time per fleet state, in seconds, against flapping. Defaults to 900.")
	@PositiveOrZero
	private double minDwellSeconds = 900;

	@Parameter
	@Comment("How long a vehicle must have stood idle to count as free, in seconds. Defaults to 60.")
	@PositiveOrZero
	private double chargingMinIdleElapsed = 60;

	@Parameter
	@Comment("Time constant of the exponential smoothing of the free share, in seconds. Defaults to 300.")
	@PositiveOrZero
	private double smoothingSeconds = 300;

	@Parameter
	@Comment("Interval of the early release check, in seconds. Defaults to 60.")
	@PositiveOrZero
	private double releaseIntervalSeconds = 60;

	public HubChargingSlackParams() {
		super(SET_NAME);
	}

	/**
	 * Checks the invariants between the parameters, which only hold as a set.
	 *
	 * @param maxSoc the charging target
	 * @throws IllegalArgumentException if an invariant is violated
	 */
	public void validate(double maxSoc) {
		if (socOpportunity >= maxSoc - MIN_BAND_BELOW_TARGET) {
			throw new IllegalArgumentException(String.format("socOpportunity (%.2f) must be more than %.2f below the"
					+ " charging target (%.2f), otherwise vehicles oscillate between charging and service",
					socOpportunity, MIN_BAND_BELOW_TARGET, maxSoc));
		}
		if (socCritical > socOpportunity) {
			throw new IllegalArgumentException(String.format("socCritical (%.2f) must not be above socOpportunity"
					+ " (%.2f)", socCritical, socOpportunity));
		}
		if (socOpportunity > socRelease) {
			throw new IllegalArgumentException(String.format("socOpportunity (%.2f) must not be above socRelease"
					+ " (%.2f), otherwise a vehicle is released before it may start to charge", socOpportunity,
					socRelease));
		}
		if (slackLow >= slackHigh) {
			throw new IllegalArgumentException(String.format("slackLow (%.2f) must be below slackHigh (%.2f),"
					+ " otherwise there is no hysteresis", slackLow, slackHigh));
		}
	}

	public double getSocCritical() {
		return socCritical;
	}

	public void setSocCritical(double socCritical) {
		this.socCritical = socCritical;
	}

	public double getSocOpportunity() {
		return socOpportunity;
	}

	public void setSocOpportunity(double socOpportunity) {
		this.socOpportunity = socOpportunity;
	}

	public double getSocRelease() {
		return socRelease;
	}

	public void setSocRelease(double socRelease) {
		this.socRelease = socRelease;
	}

	public double getMinChargeSecondsBeforeRelease() {
		return minChargeSecondsBeforeRelease;
	}

	public void setMinChargeSecondsBeforeRelease(double minChargeSecondsBeforeRelease) {
		this.minChargeSecondsBeforeRelease = minChargeSecondsBeforeRelease;
	}

	public double getSlackHigh() {
		return slackHigh;
	}

	public void setSlackHigh(double slackHigh) {
		this.slackHigh = slackHigh;
	}

	public double getSlackLow() {
		return slackLow;
	}

	public void setSlackLow(double slackLow) {
		this.slackLow = slackLow;
	}

	public double getMinDwellSeconds() {
		return minDwellSeconds;
	}

	public void setMinDwellSeconds(double minDwellSeconds) {
		this.minDwellSeconds = minDwellSeconds;
	}

	public double getChargingMinIdleElapsed() {
		return chargingMinIdleElapsed;
	}

	public void setChargingMinIdleElapsed(double chargingMinIdleElapsed) {
		this.chargingMinIdleElapsed = chargingMinIdleElapsed;
	}

	public double getSmoothingSeconds() {
		return smoothingSeconds;
	}

	public void setSmoothingSeconds(double smoothingSeconds) {
		this.smoothingSeconds = smoothingSeconds;
	}

	public double getReleaseIntervalSeconds() {
		return releaseIntervalSeconds;
	}

	public void setReleaseIntervalSeconds(double releaseIntervalSeconds) {
		this.releaseIntervalSeconds = releaseIntervalSeconds;
	}
}
