package org.matsim.contrib.drt.extension.flexibleTransit;

import org.matsim.core.events.handler.EventHandler;

public interface FixedStopArrivalEventHandler extends EventHandler {
    public void handleEvent (FixedStopArrivalEvent event);
}