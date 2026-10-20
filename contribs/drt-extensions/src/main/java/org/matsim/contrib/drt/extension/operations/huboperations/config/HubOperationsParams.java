/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.huboperations.config;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import org.matsim.contrib.drt.extension.operations.huboperations.CleaningType;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.contrib.common.util.ReflectiveConfigGroupWithConfigurableParameterSets;

import java.net.URL;
import java.util.Optional;
import java.util.Set;

/**
 * Configuration for activity-driven hub tasks. When present (as an optional parameter set of
 * {@code DrtOperationsParams}), vehicles accrue soiling and return to the nearest hub for cleaning once a threshold is
 * crossed. There are two cleaning types with their own counter, threshold and duration:
 * <ul>
 *     <li><b>interior</b>, per passenger kilometre: every link adds its length times the passengers on board, so
 *         interior soiling scales with passenger exposure.</li>
 *     <li><b>exterior</b> (sensors and body), per vehicle kilometre: every link adds its length, empty or not.</li>
 * </ul>
 * One hub visit can serve both. Each due type is cleaned, and so is every other type that has reached
 * {@link #getBundlingShare()} of its threshold, so a vehicle that comes in for its interior at 600 of 700 vehicle
 * kilometres gets its sensors done in the same visit. One worker cleans the types of a vehicle one after the other, so
 * a bundled visit takes the sum of both durations.
 * <p>
 * <b>Charging</b> is a third type for electric fleets, due at {@link #getChargeSocThreshold()} and bundled by the same
 * rule on the battery used. A free worker plugs the vehicle in, which takes no time, and cleans it while it charges.
 * When the ev module has charged it to its target the vehicle stays on the plug until a free worker unplugs it. It
 * leaves when it is unplugged and cleaned. The optional {@link HubChargingSlackParams} set replaces the plain charging
 * threshold by a fleet-slack aware policy with early unplugs.
 * <p>
 * Crossing the threshold does not stop the vehicle at once. It marks the vehicle as due for cleaning. From then on the
 * vehicle accepts no new requests, finishes the passengers already assigned to it, and once it is empty drives to the
 * nearest hub, where it stays for {@link #getCleaningDuration()} before it re-enters service with its counter reset.
 * <p>
 * <b>Maintenance</b> and sensor calibration is a fourth type, per vehicle kilometre with its own counter
 * ({@link #getMaintenanceThresholdVkm()}, off by default). It is done by a separate pool, the technicians of the
 * worker file with {@code pool="maintenance"}, in the workshop: a maintenance visit does maintenance only, with no
 * bundling and no charging, and the other due types wait for a visit of their own.
 * <p>
 * Hub labour is optional. Without {@link #getHubWorkerInputFile()} every hub has unlimited capacity and a cleaning starts
 * on arrival. With a worker file, each cleaning needs one free service worker of that hub who is on duty, and vehicles
 * queue first come, first served until one is available.
 *
 * @author lpfeil / MOIA
 */
public class HubOperationsParams extends ReflectiveConfigGroupWithConfigurableParameterSets {

	public static final String SET_NAME = "hubOperations";

	@Parameter
	@Comment("Passenger kilometres a vehicle may carry before it is due for interior cleaning. Defaults to 500.")
	@Positive
	private double soilingThresholdPkm = 500.;

	@Parameter
	@Comment("Duration of one interior cleaning, in seconds. Defaults to 1200 (20 minutes).")
	@Positive
	private double cleaningDuration = 1200.;

	@Parameter
	@Comment("Vehicle kilometres a vehicle may drive before its sensors and exterior are due for cleaning. Defaults to"
			+ " 700. Set to Infinity to switch exterior cleaning off.")
	@Positive
	private double exteriorSoilingThresholdVkm = 700.;

	@Parameter
	@Comment("Duration of one sensor and exterior cleaning, in seconds. Defaults to 900 (15 minutes).")
	@Positive
	private double exteriorCleaningDuration = 900.;

	@Parameter
	@Comment("State of charge at or below which an electric vehicle is due for charging at the hub. A hub worker plugs it"
			+ " in and out; the charging itself follows the ev module. Defaults to 0.3. 0 switches hub charging off."
			+ " Ignored for fleets without batteries. Requires the shift charging (chargeAtHubThreshold,"
			+ " chargeDuringBreakThreshold) to be off.")
	@DecimalMin("0.0")
	@DecimalMax("1.0")
	private double chargeSocThreshold = 0.3;

	@Parameter
	@Comment("Charger type used for hub charging, e.g. fast or slow. If a hub has no charger of this type any of its"
			+ " chargers is used. Defaults to fast.")
	private String chargerType = "fast";

	@Parameter
	@Comment("Share of its threshold at which a cleaning type that is not yet due is done anyway on a hub visit for"
			+ " another type. 0.8 cleans the exterior from 560 of 700 vkm on. 1 switches bundling off, 0 always"
			+ " cleans everything. Defaults to 0.8.")
	@DecimalMin("0.0")
	@DecimalMax("1.0")
	private double bundlingShare = 0.8;

	@Parameter
	@Comment("Vehicle kilometres a vehicle may drive before it is due for maintenance and sensor calibration by the"
			+ " maintenance pool. Never bundled with the other types and never combined with charging. Defaults to"
			+ " Infinity, off.")
	@Positive
	private double maintenanceThresholdVkm = Double.POSITIVE_INFINITY;

	@Parameter
	@Comment("Duration of one maintenance and sensor calibration, in seconds. Defaults to 3600 (one hour).")
	@Positive
	private double maintenanceDuration = 3600.;

	@Parameter
	@Comment("Optional roster of hub service workers (id, hub, start, end and optional pool, cleaning or"
			+ " maintenance, per worker). If not set, hubs have unlimited cleaning and maintenance capacity.")
	@Nullable
	private String hubWorkerInputFile;

	@Parameter
	@Comment("State of charge the ev charging strategy charges up to. The hub charging thresholds are checked against"
			+ " it. Defaults to 0.9.")
	@DecimalMin("0.0")
	@DecimalMax("1.0")
	private double chargingTargetSoc = 0.9;

	@Parameter
	@Comment("Predicted SOC below which an electric vehicle accepts no new requests, the dispatch gate. Must be below"
			+ " the charging trigger, otherwise a vehicle drops out of dispatch without being sent to charge."
			+ " Defaults to 0, no gate, as before.")
	@DecimalMin("0.0")
	@DecimalMax("1.0")
	private double minSocForDispatch = 0.;

	@Nullable
	private HubChargingSlackParams chargingSlackParams;

	public HubOperationsParams() {
		super(SET_NAME);
		addDefinition(HubChargingSlackParams.SET_NAME, HubChargingSlackParams::new, () -> chargingSlackParams,
				params -> chargingSlackParams = (HubChargingSlackParams) params);
	}

	/** The optional fleet-slack aware charging policy; without it hub charging uses the plain SOC threshold. */
	public Optional<HubChargingSlackParams> getChargingSlackParams() {
		return Optional.ofNullable(chargingSlackParams);
	}

	public double getMinSocForDispatch() {
		return minSocForDispatch;
	}

	public void setMinSocForDispatch(double minSocForDispatch) {
		this.minSocForDispatch = minSocForDispatch;
	}

	/** The SOC at which charging is due at the latest: the plain threshold, or socCritical with the slack policy. */
	public double getChargingTrigger() {
		return getChargingSlackParams().map(HubChargingSlackParams::getSocCritical).orElse(chargeSocThreshold);
	}

	public double getChargingTargetSoc() {
		return chargingTargetSoc;
	}

	public void setChargingTargetSoc(double chargingTargetSoc) {
		this.chargingTargetSoc = chargingTargetSoc;
	}

	@Override
	protected void checkConsistency(Config config) {
		super.checkConsistency(config);
		validate();
	}

	/**
	 * Checks the invariants between the charging parameters, the same as MoiaConfig and the Lucerne FleetSlackParams:
	 * dispatch gate below the charging trigger below the charging target.
	 *
	 * @throws IllegalArgumentException if an invariant is violated
	 */
	public void validate() {
		// same invariant as MoiaConfig: a trigger at or above the target would plug vehicles that are already done
		if (chargeSocThreshold > 0 && chargeSocThreshold >= chargingTargetSoc) {
			throw new IllegalArgumentException(String.format("chargeSocThreshold (%.2f) must be below"
					+ " chargingTargetSoc (%.2f)", chargeSocThreshold, chargingTargetSoc));
		}
		getChargingSlackParams().ifPresent(slack -> slack.validate(chargingTargetSoc));
		// same invariant as MoiaConfig: a gate at or above the trigger takes vehicles out of dispatch before they go
		if (minSocForDispatch > 0 && chargeSocThreshold > 0 && minSocForDispatch >= getChargingTrigger()) {
			throw new IllegalArgumentException(String.format("minSocForDispatch (%.2f) must be below the charging"
					+ " trigger (%.2f)", minSocForDispatch, getChargingTrigger()));
		}
	}

	public double getSoilingThresholdPkm() {
		return soilingThresholdPkm;
	}

	public void setSoilingThresholdPkm(double soilingThresholdPkm) {
		this.soilingThresholdPkm = soilingThresholdPkm;
	}

	public double getCleaningDuration() {
		return cleaningDuration;
	}

	public void setCleaningDuration(double cleaningDuration) {
		this.cleaningDuration = cleaningDuration;
	}

	public double getExteriorSoilingThresholdVkm() {
		return exteriorSoilingThresholdVkm;
	}

	public void setExteriorSoilingThresholdVkm(double exteriorSoilingThresholdVkm) {
		this.exteriorSoilingThresholdVkm = exteriorSoilingThresholdVkm;
	}

	public double getExteriorCleaningDuration() {
		return exteriorCleaningDuration;
	}

	public void setExteriorCleaningDuration(double exteriorCleaningDuration) {
		this.exteriorCleaningDuration = exteriorCleaningDuration;
	}

	public double getBundlingShare() {
		return bundlingShare;
	}

	public void setBundlingShare(double bundlingShare) {
		this.bundlingShare = bundlingShare;
	}

	public double getMaintenanceThresholdVkm() {
		return maintenanceThresholdVkm;
	}

	public void setMaintenanceThresholdVkm(double maintenanceThresholdVkm) {
		this.maintenanceThresholdVkm = maintenanceThresholdVkm;
	}

	public double getMaintenanceDuration() {
		return maintenanceDuration;
	}

	public void setMaintenanceDuration(double maintenanceDuration) {
		this.maintenanceDuration = maintenanceDuration;
	}

	public double getChargeSocThreshold() {
		return chargeSocThreshold;
	}

	public void setChargeSocThreshold(double chargeSocThreshold) {
		this.chargeSocThreshold = chargeSocThreshold;
	}

	public String getChargerType() {
		return chargerType;
	}

	public void setChargerType(String chargerType) {
		this.chargerType = chargerType;
	}

	/** Worker time of one job of {@code type}. Plugging in and out takes no worker time. */
	public double getDuration(CleaningType type) {
		return switch (type) {
			case interior -> cleaningDuration;
			case exterior -> exteriorCleaningDuration;
			case charging -> 0.;
			case maintenance -> maintenanceDuration;
		};
	}

	/** Worker time of a hub visit that does {@code types} one after the other. */
	public double getDuration(Set<CleaningType> types) {
		return types.stream().mapToDouble(this::getDuration).sum();
	}

	@Nullable
	public String getHubWorkerInputFile() {
		return hubWorkerInputFile;
	}

	public void setHubWorkerInputFile(@Nullable String hubWorkerInputFile) {
		this.hubWorkerInputFile = hubWorkerInputFile;
	}

	@Nullable
	public URL getHubWorkerInputUrl(URL context) {
		return hubWorkerInputFile == null ? null : ConfigGroup.getInputFileURL(context, hubWorkerInputFile);
	}
}
