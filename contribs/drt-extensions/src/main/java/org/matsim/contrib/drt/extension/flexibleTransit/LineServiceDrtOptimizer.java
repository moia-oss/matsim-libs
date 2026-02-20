package org.matsim.contrib.drt.extension.flexibleTransit;

import org.matsim.contrib.drt.optimizer.DrtOptimizer;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.optimizer.Request;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;
import org.matsim.core.mobsim.framework.events.MobsimInitializedEvent;
import org.matsim.core.mobsim.framework.listeners.MobsimInitializedListener;

/**
 */
public class LineServiceDrtOptimizer implements DrtOptimizer, MobsimInitializedListener {

    private final DrtOptimizer optimizer;
    private final LineServiceManager lineServiceManager;


    public LineServiceDrtOptimizer(DrtOptimizer optimizer, LineServiceManager lineServiceManager) {
        this.optimizer = optimizer;
        this.lineServiceManager = lineServiceManager;
        //TODO:
        // add mobsim initialized handler to DrtOptimizer interface in matsim to allow wrapping/delegating shift optimizer
    }

    @Override
    public void nextTask(DvrpVehicle vehicle) {
        this.optimizer.nextTask(vehicle);
    }


    @Override
    public void notifyMobsimBeforeSimStep(MobsimBeforeSimStepEvent e) {
        this.optimizer.notifyMobsimBeforeSimStep(e);
        lineServiceManager.doSimStep(e.getSimulationTime());
    }

    @Override
    public void requestSubmitted(Request request) {
        this.optimizer.requestSubmitted(request);
    }

    @Override
    public void notifyMobsimInitialized(MobsimInitializedEvent e) {
      //  ((ShiftDrtOptimizer) optimizer).notifyMobsimInitialized(e);
    }

}
