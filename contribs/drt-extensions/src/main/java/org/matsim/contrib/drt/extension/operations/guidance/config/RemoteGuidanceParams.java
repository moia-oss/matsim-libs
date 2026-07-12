/*
 * Copyright (C) 2026 MOIA GmbH - All Rights Reserved
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.guidance.config;

import com.google.common.base.Verify;
import org.matsim.contrib.common.util.ReflectiveConfigGroupWithConfigurableParameterSets;
import org.matsim.core.config.Config;

/**
 * Configuration for remote guidance operator shifts. A remote guidance operator supervises up to
 * {@link #defaultOperatorCapacity} autonomous vehicles simultaneously. Operator shifts are defined in the
 * regular shift input file (see {@link org.matsim.contrib.drt.extension.operations.shifts.config.ShiftsParams})
 * and distinguished by their shift type, which must equal {@link #operatorShiftType}.
 * <p>
 * Vehicles operate hub-based: they wait at an operation facility until a remote operator with free capacity
 * activates them (by means of a virtual driver shift), and they return to the hub once no operator capacity
 * remains for them.
 *
 * @author nkuehnel / MOIA
 */
public class RemoteGuidanceParams extends ReflectiveConfigGroupWithConfigurableParameterSets {

	public static final String SET_NAME = "remoteGuidance";

	@Parameter
	@Comment("Shift type (in the shift input file) that marks a shift as a remote guidance operator shift. "
			+ "Shifts of this type are not assigned to vehicles directly but supervise up to 'defaultOperatorCapacity' "
			+ "vehicles each. Defaults to 'remoteGuidance'.")
	private String operatorShiftType = "remoteGuidance";

	@Parameter
	@Comment("Maximum number of vehicles a single remote guidance operator can supervise simultaneously. "
			+ "Defaults to 10.")
	private int defaultOperatorCapacity = 10;

	@Parameter
	@Comment("Minimum remaining operator shift time in [seconds] for a vehicle to (still) be activated under that "
			+ "operator. Prevents activating vehicles shortly before an operator shift ends. Defaults to 1800.")
	private double minRemainingShiftTimeForActivation = 1800;

	public RemoteGuidanceParams() {
		super(SET_NAME);
	}

	public String getOperatorShiftType() {
		return operatorShiftType;
	}

	public void setOperatorShiftType(String operatorShiftType) {
		this.operatorShiftType = operatorShiftType;
	}

	public int getDefaultOperatorCapacity() {
		return defaultOperatorCapacity;
	}

	public void setDefaultOperatorCapacity(int defaultOperatorCapacity) {
		this.defaultOperatorCapacity = defaultOperatorCapacity;
	}

	public double getMinRemainingShiftTimeForActivation() {
		return minRemainingShiftTimeForActivation;
	}

	public void setMinRemainingShiftTimeForActivation(double minRemainingShiftTimeForActivation) {
		this.minRemainingShiftTimeForActivation = minRemainingShiftTimeForActivation;
	}

	@Override
	protected void checkConsistency(Config config) {
		super.checkConsistency(config);
		Verify.verify(defaultOperatorCapacity > 0, "defaultOperatorCapacity must be a positive integer.");
		Verify.verify(minRemainingShiftTimeForActivation >= 0, "minRemainingShiftTimeForActivation must not be negative.");
	}
}