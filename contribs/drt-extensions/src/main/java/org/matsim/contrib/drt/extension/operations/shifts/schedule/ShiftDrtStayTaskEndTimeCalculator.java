package org.matsim.contrib.drt.extension.operations.shifts.schedule;

import com.google.common.base.Verify;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contrib.drt.extension.operations.guidance.schedule.IncidentHoldTask;
import org.matsim.contrib.drt.extension.operations.shifts.config.ShiftsParams;
import org.matsim.contrib.drt.extension.operations.shifts.shift.DrtShiftBreak;
import org.matsim.contrib.drt.schedule.DrtStayTaskEndTimeCalculator;
import org.matsim.contrib.drt.schedule.DrtTaskBaseType;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.schedule.ScheduleTimingUpdater;
import org.matsim.contrib.dvrp.schedule.StayTask;
import org.matsim.contrib.dvrp.schedule.Task;

import java.util.List;

/**
 * @author nkuehnel / MOIA
 */
public class ShiftDrtStayTaskEndTimeCalculator implements ScheduleTimingUpdater.StayTaskEndTimeCalculator {

    public final static Logger logger = LogManager.getLogger(ShiftDrtStayTaskEndTimeCalculator.class);

    private final ShiftsParams drtShiftParams;
    private final DrtStayTaskEndTimeCalculator delegate;

    public ShiftDrtStayTaskEndTimeCalculator(ShiftsParams drtShiftParams, DrtStayTaskEndTimeCalculator delegate) {
        this.drtShiftParams = drtShiftParams;
        this.delegate = delegate;
    }

    @Override
    public double calcNewEndTime(DvrpVehicle vehicle, StayTask task, double newBeginTime) {
        if (task instanceof WaitForShiftTask) {
            Verify.verify(newBeginTime <= task.getEndTime(), "WaitForShiftTasks should not be delayed");
            return task.getEndTime();
        }
        if (task instanceof IncidentHoldTask) {
            // The hold's end is owned by the IncidentDispatcher (it is τ once an operator is serving, and is pushed
            // forward step-by-step while the incident waits in the operator queue). Preserve it verbatim — the STOP
            // delegate below would otherwise collapse the hold to zero duration (it has no passengers to board), and
            // anchoring to anything else would fight the dispatcher's dynamic end. Cf. WaitForShiftTask above.
            return Math.max(newBeginTime, task.getEndTime());
        }
        if (task instanceof ShiftBreakTask) {
            final DrtShiftBreak shiftBreak = ((ShiftBreakTask) task).getShiftBreak();
            return newBeginTime + shiftBreak.getDuration();
        } else if (task instanceof ShiftChangeOverTask) {
            // The changeover's begin time was already anchored correctly when the task was (re)built: for a regular
            // end-of-shift changeover the vehicle is held idle at the hub until the scheduled shift end (the preceding
            // stay is stretched to shift.getEndTime() by the STAY branch below), so newBeginTime >= shift.getEndTime();
            // for an actively-terminated shift (e.g. a remote-guidance recall) the begin is pulled forward, before the
            // shift's (horizon) end. In both cases the changeover must simply last changeoverDuration from its own
            // begin — anchoring to shift.getEndTime() would wrongly stretch a pulled-forward changeover back to the
            // horizon and delay the trailing WaitForShiftTask past its end.
            return newBeginTime + drtShiftParams.getChangeoverDuration();
        } else if (DrtTaskBaseType.getBaseTypeOrElseThrow(task).equals(DrtTaskBaseType.STAY)) {
            final List<? extends Task> tasks = vehicle.getSchedule().getTasks();
            final int taskIdx = tasks.indexOf(task);
            if (tasks.size() > taskIdx + 1) {
                final Task nextTask = tasks.get(taskIdx + 1);
                if (nextTask instanceof ShiftChangeOverTask) {
                    return Math.max(newBeginTime, ((ShiftChangeOverTask) nextTask).getShift().getEndTime());
                }
            }
        }
        return delegate.calcNewEndTime(vehicle, task, newBeginTime);
    }
}
