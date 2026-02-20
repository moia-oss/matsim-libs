package org.matsim.contrib.drt.extension.flexibleTransit;

import jakarta.validation.constraints.NotNull;
import org.matsim.contrib.drt.prebooking.PrebookingActionCreator;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.schedule.Task;
import org.matsim.contrib.dvrp.vrpagent.VrpAgentLogic;
import org.matsim.contrib.dynagent.DynAction;
import org.matsim.contrib.dynagent.DynAgent;
import org.matsim.contrib.evrp.EvDvrpVehicle;
import org.matsim.contrib.evrp.tracker.OfflineETaskTracker;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.mobsim.framework.MobsimTimer;

public class FixedStopActivityCreator implements VrpAgentLogic.DynActionCreator {

    private final PrebookingActionCreator delegate;
    private final EventsManager eventsManager;

    private final MobsimTimer timer;
    private final String mode;

    private final LineServiceManager lineServiceManager;

    /**
     * Constructor for both standard and electric vehicles
     */
    public FixedStopActivityCreator(PrebookingActionCreator delegate, EventsManager eventsManager, @NotNull MobsimTimer timer, String mode, LineServiceManager lineServiceManager) {
        this.delegate = delegate;
        this.eventsManager = eventsManager;
        this.timer = timer;
        this.mode = mode;
        this.lineServiceManager = lineServiceManager;
    }

    @Override
    public DynAction createAction(DynAgent dynAgent, DvrpVehicle vehicle, double now) {
        Task task = vehicle.getSchedule().getCurrentTask();

        if (task instanceof FixedStopTask fixedStopTask) {
            LineService lineService = fixedStopTask.getLineService();
            FixedStopArrivalEvent fixedStopArrivalEvent = new FixedStopArrivalEvent(now, mode,
                    lineService.getLine().getId(),
                    lineService.getRoute().getId(),
                    lineService.getDeparture().getId(),
                    fixedStopTask.getStop().getStopFacility().getId(),
                    vehicle.getId(),
                    fixedStopTask.getLink().getId());
            eventsManager.processEvent(fixedStopArrivalEvent);
            lineServiceManager.arrival(vehicle, fixedStopTask);
        }

        // For all other task types, delegate to the provided delegate
        DynAction action = delegate.createAction(dynAgent, vehicle, now);

        return action;
    }

    /**
     * Initialize the task tracker for an electric vehicle task
     */
    private void initEvTaskTracker(Task task, EvDvrpVehicle vehicle) {
        if (task.getTaskTracker() == null) {
            task.initTaskTracker(new OfflineETaskTracker(vehicle, timer));
        }
    }
}

