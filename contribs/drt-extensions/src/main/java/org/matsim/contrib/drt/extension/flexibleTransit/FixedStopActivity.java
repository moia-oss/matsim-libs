package org.matsim.contrib.drt.extension.flexibleTransit;

import org.matsim.contrib.drt.prebooking.PrebookingStopActivity;
import org.matsim.contrib.dynagent.DynActivity;

public class FixedStopActivity implements DynActivity {

    private final String TYPE = "FixedStopActivity";

    private final PrebookingStopActivity delegate;

    public FixedStopActivity(PrebookingStopActivity delegate) {
        this.delegate = delegate;
    }

    @Override
    public String getActivityType() {
        return TYPE;
    }

    @Override
    public double getEndTime() {
        return delegate.getEndTime();
    }

    @Override
    public void doSimStep(double now) {
        delegate.doSimStep(now);
    }
}
