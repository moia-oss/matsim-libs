package org.matsim.contrib.drt.extension.shifts.shift;

import org.matsim.api.core.v01.Id;

/**
 * @author nkuehnel, fzwick / MOIA
 */
public class DrtShiftImpl implements DrtShift {

	private final Id<DrtShift> id;

	private double start;
	private double end;

	private DrtShiftBreak shiftBreak;
	private final String vehicleType;

	private boolean started = false;
	private boolean ended = false;

	public DrtShiftImpl(Id<DrtShift> id, double start, double end, DrtShiftBreak shiftBreak, String vehicleType) {
		this.id = id;
		this.start = start;
		this.end = end;
		this.shiftBreak = shiftBreak;
		this.vehicleType = vehicleType;
	}

	@Override
	public double getStartTime() {
		return start;
	}

	@Override
	public double getEndTime() {
		return end;
	}

	@Override
	public DrtShiftBreak getBreak() {
		return shiftBreak;
	}

	@Override
	public boolean isStarted() {
		return started;
	}

	@Override
	public boolean isEnded() {
		return ended;
	}

	@Override
	public void start() {
		if(!started) {
			started = true;
		} else {
			throw new IllegalStateException("Shift already started!");
		}
	}

	@Override
	public void end() {
		if(!ended) {
			ended = true;
		} else {
			throw new IllegalStateException("Shift already ended!");
		}
	}

	@Override
	public String getVehicleType() {
		return vehicleType;
	}

	@Override
	public Id<DrtShift> getId() {
		return id;
	}

	@Override
	public String toString() {
		return "Shift " + id.toString() + " ["+start+"-"+end+"]";
	}
}
