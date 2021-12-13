package org.matsim.contrib.drt.extension.shifts.shift;

import org.matsim.api.core.v01.Identifiable;

/**
 * @author nkuehnel, fzwick / MOIA
 */
public interface DrtShift extends Identifiable<DrtShift> {

	String DEFAULT_VEHICLE_TYPE = "default";

	double getStartTime();

	double getEndTime();

	DrtShiftBreak getBreak();

	boolean isStarted();

	boolean isEnded();

	void start();

	void end();

	String getVehicleType();
}
